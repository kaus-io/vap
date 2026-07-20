package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zxhhyj.vap.decode.VapGpu
import com.zxhhyj.vap.decode.VapGpuBackend
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import kotlin.math.ceil
import kotlin.math.sqrt

private const val MAX_INSTANCES = 16

/**
 * Script-only benchmark measuring aggregate and per-instance presentation cadence under concurrent load.
 *
 * 仅供脚本启动的并发负载基准测试，测量总体及各实例的呈现节奏。
 */
@Composable
internal fun ConcurrentBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
) {
    val asset = remember(launch?.assetKey) { parseBenchAsset(launch?.assetKey) }
    val asset2 = remember(launch?.asset2Key) { launch?.asset2Key?.let { parseBenchAsset(it) } }
    val targetFps = launch?.targetFps ?: 30
    val count = (launch?.count ?: 4).coerceIn(1, MAX_INSTANCES)
    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_500L
    val gpuPrefer = remember(launch?.gpuBackendKey) { launch?.gpuBackend }
    val gpuResolved = remember(gpuPrefer) { VapGpu.resolve(gpuPrefer) }
    val gpuPreferLabel = gpuPrefer?.benchLabel() ?: "default"
    val gpuResolvedLabel = gpuResolved.benchLabel()
    val clip = clips.byLabel(asset.clipLabel)
    val clip2 = asset2?.let { clips.byLabel(it.clipLabel) }
    val presentHolders = remember { mutableStateListOf<VapAnimationState?>() }
    val presentRecorders = remember { mutableStateListOf<PresentIntervalRecorder?>() }
    var finished by remember { mutableStateOf(false) }
    val ids = remember(count) { List(count) { it } }
    val columns = remember(count) {
        when {
            count <= 1 -> 1
            count <= 4 -> 2
            count <= 9 -> 3
            else -> ceil(sqrt(count.toFloat())).toInt().coerceAtLeast(1)
        }
    }
    val rows = remember(count, columns) { (count + columns - 1) / columns }

    DemoCaseScaffold(
        case = DemoCase.ConcurrentBenchmark,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (clip == null || (count == 2 && asset2 != null && clip2 == null)) {
            LaunchedEffect(Unit) {
                val msg = "missing asset key=${asset.key}" +
                    if (count == 2 && asset2 != null && clip2 == null) " or asset2=${asset2.key}" else ""
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.finishAfter != false) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        // Resize registration slots after composition; child effects publish states asynchronously.
        // 在组合后调整注册槽位；子项副作用会异步发布状态。
        LaunchedEffect(count) {
            while (presentHolders.size < count) presentHolders.add(null)
            while (presentHolders.size > count) presentHolders.removeAt(presentHolders.lastIndex)
            while (presentRecorders.size < count) presentRecorders.add(null)
            while (presentRecorders.size > count) presentRecorders.removeAt(presentRecorders.lastIndex)
        }

        LaunchedEffect(clip.label, targetFps, asset, count, gpuPreferLabel, asset2?.key) {
            if (finished) return@LaunchedEffect
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP kind=multi count=$count asset=${asset.key}" +
                        if (count == 2 && asset2 != null) " asset2=${asset2.key}" else "" +
                        " target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs " +
                        "gpu=$gpuPreferLabel resolved=$gpuResolvedLabel",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            // Bound readiness polling so a failed child cannot hang an automated benchmark run.
            // 限制就绪轮询次数，避免失败的子项挂起自动化基准流程。
            var spins = 0
            while (presentHolders.count { it != null } < count && spins < 50) {
                kotlinx.coroutines.delay(100)
                spins++
            }
            val assetKeyLog = if (count == 2 && asset2 != null) "${asset.key}+${asset2.key}" else asset.key
            val assetLabelLog = if (count == 2 && clip2 != null) "${clip.label}+${clip2.label}" else clip.label
            val snap = runPresentSample(
                readPresents = { presentHolders.sumOf { it?.presentedCount ?: 0L } },
                targetFps = targetFps,
                assetKey = assetKeyLog,
                assetLabel = assetLabelLog,
                durationMs = durationMs,
                instanceCount = count,
                gpuPrefer = gpuPreferLabel,
                gpuResolved = gpuResolvedLabel,
                readInstancePresents = {
                    LongArray(count) { i -> presentHolders.getOrNull(i)?.presentedCount ?: 0L }
                },
                intervalRecorders = presentRecorders.filterNotNull(),
            )
            if (snap != null) logVapBench(snap.toLogLine("multi"))
            finished = true
            if (launch?.finishAfter != false) onAutoFinished?.invoke()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colors.surface)
                .padding(4.dp),
        ) {
            repeat(rows) { row ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    repeat(columns) { col ->
                        val index = row * columns + col
                        if (index < ids.size) {
                            val cellClip = if (count == 2 && asset2 != null && index == 1) clip2 ?: clip else clip
                            key(ids[index], cellClip.label, targetFps, gpuPrefer) {
                                ConcurrentCell(
                                    clip = cellClip,
                                    targetFps = targetFps,
                                    gpuBackend = gpuPrefer,
                                    onError = onError,
                                    onState = { state, recorder ->
                                        if (index in presentHolders.indices) {
                                            presentHolders[index] = state
                                            presentRecorders[index] = recorder
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(2.dp),
                                )
                            }
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConcurrentCell(
    clip: VapDemoClip,
    targetFps: Int,
    gpuBackend: VapGpuBackend?,
    onError: (String) -> Unit,
    onState: (VapAnimationState, PresentIntervalRecorder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val composition = rememberSyncDemoComposition(clip, onError)
    val recorder = remember { PresentIntervalRecorder() }
    val anim = animateVapCompositionAsState(
        composition = composition,
        iterations = VapConstants.IterateForever,
        isPlaying = true,
        fps = targetFps.takeIf { it > 0 },
        gpuBackend = gpuBackend,
        onError = { onError(it.message ?: it.toString()) },
    )
    LaunchedEffect(anim) {
        anim.onPresented = recorder.onPresent
        onState(anim, recorder)
    }
    VapAnimation(
        animationState = anim,
        modifier = modifier.fillMaxSize(),
    )
}
