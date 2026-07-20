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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.sqrt

// Hard cap mirroring the present bench sampler; higher counts would just produce
// 0-sized present samples and clutter the bench log without adding signal.
// 与 present 采样器一致的硬上限,过高实例数只会产生零样本并污染日志。
private const val MAX_INSTANCES = 16

/**
 * Multi-instance PAGImageView present benchmark. Lays out [count] instances in a near-
 * square grid, sums their presents via [runPresentSample], and tags the result with
 * `kind=multi` so the report can split per-instance vs aggregated numbers.
 *
 * 多实例 PAGImageView present 基准:将 [count] 个实例排成近似正方形网格,通过
 * [runPresentSample] 累加它们的 present 计数,结果以 `kind=multi` 标记,便于
 * 报告中区分逐实例与聚合数据。
 *
 * adb / script only — no interactive controls.
 */
@Composable
internal actual fun PagImageViewConcurrentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    val context = LocalContext.current
    val asset = remember(launch?.assetKey) { parseBenchAsset(launch?.assetKey) }
    val targetFps = launch?.targetFps ?: 30
    val count = (launch?.count ?: 4).coerceIn(1, MAX_INSTANCES)
    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_500L
    val assetPath = asset.pagAssetPath()
    val assetExists = remember(assetPath) {
        runCatching { context.assets.open(assetPath).close(); true }.getOrDefault(false)
    }
    val counters = remember { mutableStateListOf<PagPresentCounter>() }
    var finished by remember { mutableStateOf(false) }
    val ids = remember(count) { List(count) { it } }
    // Pick a near-square column count so the grid stays balanced for any `count`.
    // The square-root heuristic avoids manual casework for >9 instances.
    // 选取接近正方形的列数,使任意 `count` 下网格保持平衡;>9 实例时使用
    // 平方根启发式,避免手工列举。
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
        case = DemoCase.PagImageViewConcurrentBenchmark,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (!assetExists) {
            LaunchedEffect(Unit) {
                val msg = "missing PAG asset path=$assetPath"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.finishAfter != false) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        // Keep `counters` size in sync with `count` so each grid cell has its own
        // counter to report per-instance present rates without index shuffling.
        // 让 `counters` 数量与 `count` 保持一致,每个格子拥有独立计数器,
        // 便于在不重排索引的前提下报告逐实例 present 速率。
        LaunchedEffect(count) {
            while (counters.size < count) counters.add(PagPresentCounter())
            while (counters.size > count) counters.removeAt(counters.lastIndex)
        }

        LaunchedEffect(assetPath, targetFps, asset, count) {
            if (finished) return@LaunchedEffect
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP kind=multi engine=pagimageview count=$count asset=${asset.key} " +
                        "target_fps=$targetFps warmup_ms=$warmUpMs duration_ms=$durationMs " +
                        "gpu=pagimageview resolved=pagimageview",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            val snap = runPresentSample(
                readPresents = { counters.sumOf { it.get() } },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = assetPath.removeSuffix(".pag"),
                durationMs = durationMs,
                instanceCount = count,
                gpuPrefer = "pagimageview",
                gpuResolved = "pagimageview",
                readInstancePresents = {
                    LongArray(count) { i -> counters.getOrNull(i)?.get() ?: 0L }
                },
                intervalRecorders = counters.map { it.recorder },
            )
            if (snap != null) logVapBench(snap.toLogLine("multi") + " engine=pagimageview")
            finished = true
            if (launch?.finishAfter != false) onAutoFinished?.invoke()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
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
                        if (index < ids.size && index < counters.size) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(2.dp),
                            ) {
                                key(assetPath, targetFps, index) {
                                    PagImageViewAnimation(
                                        assetPath = assetPath,
                                        targetFps = targetFps,
                                        presentCounter = counters[index],
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
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