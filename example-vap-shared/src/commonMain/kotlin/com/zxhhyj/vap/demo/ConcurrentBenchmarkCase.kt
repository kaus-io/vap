package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_0
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_30
import com.zxhhyj.example_vap_shared.generated.resources.bench_fps_60
import com.zxhhyj.example_vap_shared.generated.resources.bench_result
import com.zxhhyj.example_vap_shared.generated.resources.bench_running
import com.zxhhyj.example_vap_shared.generated.resources.bench_start_10s
import com.zxhhyj.example_vap_shared.generated.resources.status_bench_missing
import com.zxhhyj.example_vap_shared.generated.resources.status_concurrent_bench
import com.zxhhyj.example_vap_shared.generated.resources.stress_cell_label
import com.zxhhyj.example_vap_shared.generated.resources.stress_clear
import com.zxhhyj.example_vap_shared.generated.resources.stress_empty_hint
import com.zxhhyj.example_vap_shared.generated.resources.stress_minus_one
import com.zxhhyj.example_vap_shared.generated.resources.stress_plus_four
import com.zxhhyj.example_vap_shared.generated.resources.stress_plus_one
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.sqrt

private const val MAX_INSTANCES = 16

@Composable
internal fun ConcurrentBenchmarkCase(
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
    var count by remember(launch?.count) {
        mutableIntStateOf((launch?.count ?: 4).coerceIn(0, MAX_INSTANCES))
    }
    var running by remember { mutableStateOf(false) }
    var liveFps by remember { mutableStateOf<Double?>(null) }
    var result by remember { mutableStateOf<BenchSampleResult?>(null) }
    var autoStarted by remember { mutableStateOf(false) }
    val presentHolders = remember { mutableStateListOf<VapAnimationState?>() }

    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_500L
    val clip = clips.byLabel(asset.clipLabel)
    val ids = remember(count) { List(count) { it } }
    val columns = remember(count) {
        when {
            count <= 1 -> 1
            count <= 4 -> 2
            count <= 9 -> 3
            else -> ceil(sqrt(count.toFloat())).toInt().coerceAtLeast(1)
        }
    }
    val rows = remember(count, columns) {
        if (count == 0) 0 else (count + columns - 1) / columns
    }

    DemoCaseScaffold(
        case = DemoCase.ConcurrentBenchmark,
        onBack = onBack,
        modifier = modifier,
        status = if (clip == null) {
            stringResource(Res.string.status_bench_missing)
        } else {
            stringResource(
                Res.string.status_concurrent_bench,
                stringResource(asset.labelRes),
                count,
                if (targetFps <= 0) "PTS" else "${targetFps}fps",
                if (running) "run" else "idle",
            )
        },
    ) {
        if (clip == null) {
            LaunchedEffect(Unit) {
                val msg = "missing asset key=${asset.key}"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.auto == true && launch.finishAfter) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        LaunchedEffect(count) {
            while (presentHolders.size < count) presentHolders.add(null)
            while (presentHolders.size > count) presentHolders.removeAt(presentHolders.lastIndex)
        }

        LaunchedEffect(launch?.auto, clip.label, targetFps, asset, count) {
            if (launch?.auto != true || autoStarted) return@LaunchedEffect
            if (count <= 0) {
                logVapBench("ERROR count=0")
                if (launch.finishAfter) onAutoFinished?.invoke()
                return@LaunchedEffect
            }
            autoStarted = true
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP kind=multi count=$count asset=${asset.key} target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            var spins = 0
            while (presentHolders.count { it != null } < count && spins < 50) {
                kotlinx.coroutines.delay(100)
                spins++
            }
            running = true
        }

        LaunchedEffect(running, targetFps, asset, count, durationMs) {
            if (!running) {
                liveFps = null
                return@LaunchedEffect
            }
            result = null
            val snap = runPresentSample(
                readPresents = { presentHolders.sumOf { it?.presentedCount ?: 0L } },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = clip.label,
                durationMs = durationMs,
                instanceCount = count.coerceAtLeast(1),
                onLiveFps = { liveFps = it },
            )
            if (snap != null) {
                result = snap
                logVapBench(snap.toLogLine("multi"))
            }
            running = false
            liveFps = null
            if (launch?.auto == true && launch.finishAfter) {
                onAutoFinished?.invoke()
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DemoActionChip(
                    label = stringResource(Res.string.stress_minus_one),
                    enabled = !running && count > 0,
                    onClick = {
                        count = (count - 1).coerceAtLeast(0)
                        result = null
                    },
                    modifier = Modifier.weight(1f),
                )
                DemoActionChip(
                    label = stringResource(Res.string.stress_plus_one),
                    enabled = !running && count < MAX_INSTANCES,
                    onClick = {
                        count = (count + 1).coerceAtMost(MAX_INSTANCES)
                        result = null
                    },
                    modifier = Modifier.weight(1f),
                )
                DemoActionChip(
                    label = stringResource(Res.string.stress_plus_four),
                    enabled = !running && count < MAX_INSTANCES,
                    onClick = {
                        count = (count + 4).coerceAtMost(MAX_INSTANCES)
                        result = null
                    },
                    modifier = Modifier.weight(1f),
                )
                DemoActionChip(
                    label = stringResource(Res.string.stress_clear),
                    enabled = !running && count > 0,
                    onClick = {
                        count = 0
                        result = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            DemoActionChip(
                label = stringResource(Res.string.bench_start_10s),
                enabled = !running && count > 0,
                onClick = {
                    result = null
                    running = true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ConcurrentGrid(
                count = count,
                rows = rows,
                columns = columns,
                ids = ids,
                clip = clip,
                assetKey = asset.key,
                targetFps = targetFps,
                onError = onError,
                onState = { index, state ->
                    if (index in presentHolders.indices) {
                        presentHolders[index] = state
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface),
            )

            val metrics = buildString {
                if (running) {
                    append(stringResource(Res.string.bench_running))
                    liveFps?.let { append(" · per ").append(it.round1()).append(" fps") }
                    append(" · total_presents=")
                    append(presentHolders.sumOf { it?.presentedCount ?: 0L })
                    append(" · n=").append(count)
                } else {
                    val snap = result
                    if (snap == null) {
                        append("n=").append(count)
                        append(" · target=")
                        append(if (targetFps <= 0) "PTS" else "${targetFps}fps")
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
                        append(" · total_fps=").append(snap.totalFps.round1())
                        append(" · n=").append(snap.instanceCount)
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

@Composable
private fun ConcurrentGrid(
    count: Int,
    rows: Int,
    columns: Int,
    ids: List<Int>,
    clip: VapDemoClip,
    assetKey: String,
    targetFps: Int,
    onError: (String) -> Unit,
    onState: (Int, VapAnimationState) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count == 0) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.stress_empty_hint),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
        }
        return
    }
    Column(modifier = modifier) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    if (index < ids.size) {
                        key(ids[index], assetKey, targetFps) {
                            ConcurrentCell(
                                clip = clip,
                                index = index,
                                targetFps = targetFps,
                                onError = onError,
                                onState = { state -> onState(index, state) },
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

@Composable
private fun ConcurrentCell(
    clip: VapDemoClip,
    index: Int,
    targetFps: Int,
    onError: (String) -> Unit,
    onState: (VapAnimationState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val composition = rememberSyncDemoComposition(clip, onError)
    val anim = animateVapCompositionAsState(
        composition = composition,
        iterations = VapConstants.IterateForever,
        isPlaying = true,
        fps = targetFps.takeIf { it > 0 },
        onError = { onError(it.message ?: it.toString()) },
    )
    LaunchedEffect(anim) {
        onState(anim)
    }
    Box(modifier = modifier) {
        VapAnimation(
            animationState = anim,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = stringResource(Res.string.stress_cell_label, index + 1),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
