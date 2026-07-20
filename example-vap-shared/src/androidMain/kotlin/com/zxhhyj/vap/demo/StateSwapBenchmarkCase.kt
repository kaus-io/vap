package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zxhhyj.vap.decode.VapGpuBackend
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * StateSwap benchmark for hanna's HannaBall AnimatedContent transitions.
 * Cycles [PresentBenchLaunch.stateSwapSeq] every [PresentBenchLaunch.stateSwapCycleMs] ms
 * for [PresentBenchLaunch.stateSwapCycles] cycles. Logs `SWAP_RESULT` per cycle with
 * `first_present_ms` (time from swap-instruction to first present of the new asset).
 *
 * Supports both VAP (`engine=vap`) and PAG/PAGImageView (`engine=pag*`).
 *
 * StateSwap 基准(对应 hanna 的 HannaBall AnimatedContent 转场):按
 * [PresentBenchLaunch.stateSwapCycleMs] 的周期遍历 [PresentBenchLaunch.stateSwapSeq]
 * 共 [PresentBenchLaunch.stateSwapCycles] 轮,每轮输出 `SWAP_RESULT`,其中
 * `first_present_ms` 是从发出 swap 指令到新资源首次呈现的耗时。
 * 同时支持 VAP(`engine=vap`)与 PAG/PAGImageView(`engine=pag*`)。
 */
@Composable
internal actual fun StateSwapBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    StateSwapBenchmarkCaseImpl(
        clips = clips,
        onBack = onBack,
        launch = launch,
        onError = onError,
        modifier = modifier,
        onAutoFinished = onAutoFinished,
    )
}

@Composable
private fun StateSwapBenchmarkCaseImpl(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    launch: PresentBenchLaunch?,
    onError: (String) -> Unit,
    modifier: Modifier,
    onAutoFinished: (() -> Unit)?,
) {
    val context = LocalContext.current
    val seq = launch?.stateSwapSeq.orEmpty().map { parseBenchAsset(it) }
    val cycleMs = launch?.stateSwapCycleMs ?: 3000L
    val cycles = launch?.stateSwapCycles ?: 10
    val targetFps = launch?.targetFps ?: 30
    val isPag = launch?.isPag == true

    DemoCaseScaffold(
        case = DemoCase.StateSwapBenchmark,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (seq.isEmpty()) {
            LaunchedEffect(Unit) {
                val msg = "state_swap_seq empty"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.finishAfter != false) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        // PAG mode requires all swap targets to exist as bundled assets; short-circuit
        // to an empty state and log ERROR so the script knows the run was a no-op.
        // PAG 模式下要求所有 swap 目标资源都存在,否则短路显示空状态并输出 ERROR。
        if (isPag) {
            val missing = seq.firstOrNull { asset ->
                runCatching { context.assets.open(asset.pagAssetPath()).close(); false }
                    .getOrDefault(true)
            }
            if (missing != null) {
                LaunchedEffect(Unit) {
                    val msg = "missing PAG asset path=${missing.pagAssetPath()}"
                    logVapBench("ERROR $msg")
                    onError(msg)
                    if (launch?.finishAfter != false) onAutoFinished?.invoke()
                }
                DemoEmptyState(modifier = Modifier.fillMaxSize())
                return@DemoCaseScaffold
            }
        }

        var currentIndex by remember { mutableIntStateOf(0) }
        var cycleCount by remember { mutableIntStateOf(0) }
        var swapStartMark by remember { mutableStateOf<TimeMark?>(null) }
        var finished by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            logVapBench(
                "STATE_SWAP_START seq=${seq.joinToString(",") { it.key }} " +
                    "cycle_ms=$cycleMs cycles=$cycles " +
                    "engine=${launch?.engine?.benchLabel() ?: "vap"}",
            )
        }

        // Main swap driver: stamp the swap-in time, mutate `currentIndex` to swap the
        // keyed subtree, then sleep `cycleMs` before the next slot. `finished` is a
        // cooperative cancellation so dispose can stop the run without leaking frames.
        // 主驱动:记录 swap 起始时刻,改 `currentIndex` 让 keyed 子树切换,
        // 然后等待 `cycleMs` 后切到下一个槽位;`finished` 用于协程退出。
        LaunchedEffect(seq, cycleMs, cycles) {
            if (finished) return@LaunchedEffect
            for (c in 1..cycles) {
                cycleCount = c
                for (i in seq.indices) {
                    if (finished) return@LaunchedEffect
                    val asset = seq[i]
                    swapStartMark = TimeSource.Monotonic.markNow()
                    currentIndex = i
                    logVapBench(
                        "SWAP_IN cycle=$c pos=$i asset=${asset.key} " +
                            "t_swap_in_ms=${swapStartMark!!.elapsedNow().inWholeMilliseconds}",
                    )
                    kotlinx.coroutines.delay(cycleMs)
                }
            }
            finished = true
            if (launch?.finishAfter != false) onAutoFinished?.invoke()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            // `key` forces a fresh composer for each swap slot so the previous animation
            // tears down its decoder before the next one mounts — mirrors the production
            // AnimatedContent transition semantics.
            // `key` 强制每个 swap 槽位使用全新 composer,使前一个动画在挂载下一
            // 个之前完成解码器拆卸,与生产 AnimatedContent 转场语义一致。
            key(currentIndex, cycleCount) {
                if (isPag) {
                    val asset = seq[currentIndex]
                    PagSwapCell(
                        asset = asset,
                        targetFps = targetFps,
                        swapStartMark = swapStartMark,
                        cycleCount = cycleCount,
                        engineIsImageView = launch?.isPagImageView == true,
                        onError = onError,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                } else {
                    val asset = seq[currentIndex]
                    VapSwapCell(
                        clips = clips,
                        asset = asset,
                        targetFps = targetFps,
                        swapStartMark = swapStartMark,
                        cycleCount = cycleCount,
                        gpuPrefer = launch?.gpuBackend,
                        onError = onError,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VapSwapCell(
    clips: List<VapDemoClip>,
    asset: BenchAsset,
    targetFps: Int,
    swapStartMark: TimeMark?,
    cycleCount: Int,
    gpuPrefer: VapGpuBackend?,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val clip = clips.byLabel(asset.clipLabel)
    if (clip == null) {
        LaunchedEffect(Unit) {
            val msg = "missing VAP asset label=${asset.clipLabel}"
            logVapBench("ERROR $msg")
            onError(msg)
        }
        DemoEmptyState(modifier = modifier)
        return
    }
    val composition = rememberSyncDemoComposition(clip, onError)
    val anim = animateVapCompositionAsState(
        composition = composition,
        iterations = VapConstants.IterateForever,
        isPlaying = true,
        fps = targetFps.takeIf { it > 0 },
        gpuBackend = gpuPrefer,
        onError = { onError(it.message ?: it.toString()) },
    )
    val firstPresentEmitted = remember { mutableStateOf(false) }
    // Hook the VAP animation's first-present callback to stamp SWAP_RESULT once per swap.
    // Reset is keyed on (cycle, asset) so a fresh swap triggers a fresh measurement.
    // 挂钩 VAP 动画的 onPresented 回调,在每次 swap 时仅记录首帧 SWAP_RESULT;
    // 重置条件为 (cycle, asset) 以保证新一轮 swap 触发新的测量。
    LaunchedEffect(anim, cycleCount, asset.key) {
        firstPresentEmitted.value = false
        anim.onPresented = {
            if (!firstPresentEmitted.value) {
                firstPresentEmitted.value = true
                val startMark = swapStartMark
                if (startMark != null) {
                    logVapBench(
                        "SWAP_RESULT cycle=$cycleCount asset=${asset.key} " +
                            "engine=vap first_present_ms=${startMark.elapsedNow().inWholeMilliseconds}",
                    )
                }
            }
        }
    }
    VapAnimation(animationState = anim, modifier = modifier)
}

@Composable
private fun PagSwapCell(
    asset: BenchAsset,
    targetFps: Int,
    swapStartMark: TimeMark?,
    cycleCount: Int,
    engineIsImageView: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val presentCounter = remember { PagPresentCounter() }
    val firstPresentEmitted = remember { mutableStateOf(false) }
    LaunchedEffect(asset.key, cycleCount) {
        firstPresentEmitted.value = false
    }
    // DisposableEffect lets us splice in a temporary onPresent hook and restore the
    // original handler on dispose, so successive swap slots get a clean measurement
    // without leaking the previous one.
    // DisposableEffect 用于临时插入 onPresent 钩子,dispose 时恢复原始 handler,
    // 使后续 swap 槽位获得干净测量而不受前次影响。
    DisposableEffect(presentCounter, asset.key, cycleCount) {
        val orig = presentCounter.recorder.onPresent
        presentCounter.recorder.onPresent = {
            orig()
            if (!firstPresentEmitted.value) {
                firstPresentEmitted.value = true
                val startMark = swapStartMark
                if (startMark != null) {
                    logVapBench(
                        "SWAP_RESULT cycle=$cycleCount asset=${asset.key} " +
                            "engine=${if (engineIsImageView) "pagimageview" else "pagview"} " +
                            "first_present_ms=${startMark.elapsedNow().inWholeMilliseconds}",
                    )
                }
            }
        }
        onDispose {
            presentCounter.recorder.onPresent = orig
        }
    }
    if (engineIsImageView) {
        PagImageViewAnimation(
            assetPath = asset.pagAssetPath(),
            targetFps = targetFps,
            presentCounter = presentCounter,
            modifier = modifier,
        )
    } else {
        PagAnimation(
            assetPath = asset.pagAssetPath(),
            targetFps = targetFps,
            presentCounter = presentCounter,
            modifier = modifier,
        )
    }
}