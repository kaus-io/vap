package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.bench_about
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_0
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_30
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_60
import com.zxhhyj.example_vap_shared.generated.resources.bench_home
import com.zxhhyj.example_vap_shared.generated.resources.bench_idle
import com.zxhhyj.example_vap_shared.generated.resources.bench_result
import com.zxhhyj.example_vap_shared.generated.resources.bench_running
import com.zxhhyj.example_vap_shared.generated.resources.bench_start_10s
import com.zxhhyj.example_vap_shared.generated.resources.status_bench
import com.zxhhyj.example_vap_shared.generated.resources.status_bench_missing
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class BenchAsset(val labelRes: StringResource, val clipLabel: String, val key: String) {
    Idle(Res.string.bench_idle, "hanna_window_idle", "idle"),
    Home(Res.string.bench_home, "hanna_home", "home"),
    About(Res.string.bench_about, "hanna_about_card", "about"),
}

internal fun parseBenchAsset(key: String?): BenchAsset =
    BenchAsset.entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BenchAsset.Idle

@Composable
internal fun PresentBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
) {
    val initialAsset = remember(launch?.assetKey) { parseBenchAsset(launch?.assetKey) }
    var asset by remember(initialAsset) { mutableStateOf(initialAsset) }
    var targetFps by remember(launch?.targetFps) {
        mutableIntStateOf(launch?.targetFps ?: 30)
    }
    var running by remember { mutableStateOf(false) }
    var liveFps by remember { mutableStateOf<Double?>(null) }
    var result by remember { mutableStateOf<BenchSampleResult?>(null) }
    var autoStarted by remember { mutableStateOf(false) }

    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_000L
    val clip = clips.byLabel(asset.clipLabel)

    DemoCaseScaffold(
        case = DemoCase.PresentBenchmark,
        onBack = onBack,
        modifier = modifier,
        status = if (clip == null) {
            stringResource(Res.string.status_bench_missing)
        } else {
            stringResource(
                Res.string.status_bench,
                stringResource(asset.labelRes),
                if (targetFps <= 0) "PTS" else "${targetFps}fps",
                if (running) "run" else "idle",
            )
        },
    ) {
        if (clip == null) {
            LaunchedEffect(Unit) {
                val msg = "missing asset key=${asset.key} label=${asset.clipLabel}"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.auto == true && launch.finishAfter) {
                    onAutoFinished?.invoke()
                }
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        val composition = rememberSyncDemoComposition(clip, onError)
        val anim = animateVapCompositionAsState(
            composition = composition,
            iterations = VapConstants.IterateForever,
            isPlaying = true,
            fps = targetFps.takeIf { it > 0 },
            onError = { onError(it.message ?: it.toString()) },
        )

        LaunchedEffect(launch?.auto, clip.label, targetFps, asset) {
            if (launch?.auto != true || autoStarted) return@LaunchedEffect
            autoStarted = true
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP asset=${asset.key} clip=${clip.label} target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            running = true
        }

        LaunchedEffect(running, anim, targetFps, asset, durationMs) {
            if (!running) {
                liveFps = null
                return@LaunchedEffect
            }
            result = null
            val snap = runPresentSample(
                readPresents = { anim.presentedCount },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = clip.label,
                durationMs = durationMs,
                onLiveFps = { liveFps = it },
            )
            if (snap != null) {
                result = snap
                logVapBench(snap.toLogLine("single"))
            }
            running = false
            liveFps = null
            if (launch?.auto == true && launch.finishAfter) {
                onAutoFinished?.invoke()
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BenchAsset.entries.forEach { item ->
                    DemoChoiceChip(
                        label = stringResource(item.labelRes),
                        selected = asset == item,
                        enabled = !running,
                        onClick = {
                            asset = item
                            result = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0 to Res.string.bench_fps_0, 30 to Res.string.bench_fps_30, 60 to Res.string.bench_fps_60)
                    .forEach { (fps, res) ->
                        DemoChoiceChip(
                            label = stringResource(res),
                            selected = targetFps == fps,
                            enabled = !running,
                            onClick = {
                                targetFps = fps
                                result = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
            }
            DemoActionChip(
                label = stringResource(Res.string.bench_start_10s),
                enabled = !running,
                onClick = {
                    result = null
                    running = true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
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

            val metrics = buildString {
                if (running) {
                    append(stringResource(Res.string.bench_running))
                    liveFps?.let { append(" · live ").append(it.round1()).append(" fps") }
                    append(" · presents=").append(anim.presentedCount)
                } else {
                    val snap = result
                    if (snap == null) {
                        append("target=")
                        append(if (targetFps <= 0) "PTS" else "${targetFps}fps")
                        append(" · tap Start / adb PRESENT_BENCHMARK")
                    } else {
                        append(
                            stringResource(
                                Res.string.bench_result,
                                snap.avgFps.round1(),
                                snap.minWindowFps.round1(),
                                snap.maxWindowFps.round1(),
                                snap.presents.toString(),
                                snap.durationMs.toString(),
                                if (snap.targetFps <= 0) "PTS" else "${snap.targetFps}",
                            ),
                        )
                    }
                }
            }
            Text(
                text = metrics,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
