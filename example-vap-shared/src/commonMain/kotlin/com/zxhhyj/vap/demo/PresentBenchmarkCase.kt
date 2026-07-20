package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.bench_about
import com.zxhhyj.example_vap_shared.generated.resources.bench_home
import com.zxhhyj.example_vap_shared.generated.resources.bench_idle
import com.zxhhyj.example_vap_shared.generated.resources.bench_speech
import com.zxhhyj.example_vap_shared.generated.resources.bench_think
import com.zxhhyj.example_vap_shared.generated.resources.bench_touch_wake
import com.zxhhyj.vap.decode.VapGpu
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.StringResource

internal enum class BenchAsset(val labelRes: StringResource, val clipLabel: String, val key: String) {
    Idle(Res.string.bench_idle, "hanna_window_idle", "idle"),
    Home(Res.string.bench_home, "hanna_home", "home"),
    About(Res.string.bench_about, "hanna_about_card", "about"),
    Speech(Res.string.bench_speech, "hanna_window_speech", "speech"),
    Think(Res.string.bench_think, "hanna_window_think", "think"),
    TouchWake(Res.string.bench_touch_wake, "hanna_touch_wake", "touch_wake"),
}

internal fun parseBenchAsset(key: String?): BenchAsset =
    BenchAsset.entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BenchAsset.Idle

/**
 * Script-only single-instance benchmark measuring successful presentation cadence after warm-up.
 *
 * 仅供脚本启动的单实例基准测试，在预热后测量成功呈现的节奏。
 */
@Composable
internal fun PresentBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
) {
    val asset = remember(launch?.assetKey) { parseBenchAsset(launch?.assetKey) }
    val targetFps = launch?.targetFps ?: 30
    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_000L
    val gpuPrefer = remember(launch?.gpuBackendKey) { launch?.gpuBackend }
    val gpuResolved = remember(gpuPrefer) { VapGpu.resolve(gpuPrefer) }
    val gpuPreferLabel = gpuPrefer?.benchLabel() ?: "default"
    val gpuResolvedLabel = gpuResolved.benchLabel()
    val clip = clips.byLabel(asset.clipLabel)
    var finished by remember { mutableStateOf(false) }

    DemoCaseScaffold(
        case = DemoCase.PresentBenchmark,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (clip == null) {
            LaunchedEffect(Unit) {
                val msg = "missing asset key=${asset.key} label=${asset.clipLabel}"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.finishAfter == true) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        val composition = rememberSyncDemoComposition(clip, onError)
        val presentRecorder = remember { PresentIntervalRecorder() }
        val firstPresentMark = remember { TimeSource.Monotonic.markNow() }
        val firstPresentEmitted = remember { mutableStateOf(false) }
        val anim = animateVapCompositionAsState(
            composition = composition,
            iterations = VapConstants.IterateForever,
            isPlaying = true,
            fps = targetFps.takeIf { it > 0 },
            gpuBackend = gpuPrefer,
            onError = { onError(it.message ?: it.toString()) },
        )
        // Install the callback from an effect to avoid mutating the state object during composition.
        // 通过副作用安装回调，避免在组合期间修改状态对象。
        LaunchedEffect(anim) {
            anim.onPresented = {
                presentRecorder.onPresent()
                if (!firstPresentEmitted.value) {
                    firstPresentEmitted.value = true
                    logVapBench(
                        "FIRST_PRESENT asset=${asset.key} clip=${clip.label} " +
                            "elapsed_ms=${firstPresentMark.elapsedNow().inWholeMilliseconds}",
                    )
                }
            }
        }

        // Exclude warm-up; the sample window counts only successful presentation deltas.
        // 预热阶段不计入采样；测量窗口仅统计成功呈现计数器的增量。
        LaunchedEffect(clip.label, targetFps, asset, gpuPreferLabel) {
            if (finished) return@LaunchedEffect
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP asset=${asset.key} clip=${clip.label} target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs " +
                        "gpu=$gpuPreferLabel resolved=$gpuResolvedLabel",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            val snap = runPresentSample(
                readPresents = { anim.presentedCount },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = clip.label,
                durationMs = durationMs,
                gpuPrefer = gpuPreferLabel,
                gpuResolved = gpuResolvedLabel,
                readInstancePresents = { longArrayOf(anim.presentedCount) },
                intervalRecorders = listOf(presentRecorder),
            )
            if (snap != null) logVapBench(snap.toLogLine("single"))
            finished = true
            if (launch?.finishAfter != false) onAutoFinished?.invoke()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            VapAnimation(
                animationState = anim,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
        }
    }
}
