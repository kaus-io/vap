package com.zxhhyj.vap.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Records per-instance intervals between successful presents for benchmark statistics.
 * A single writer and reader may race benignly; a torn slot perturbs at most one sample,
 * which percentile aggregation absorbs.
 *
 * 记录每个实例成功呈现之间的间隔，用于基准统计。
 * 单写者与单读者之间允许良性竞态；撕裂槽位至多影响一个样本，
 * 百分位聚合会吸收该误差。
 */
internal class PresentIntervalRecorder(capacity: Int = 4096) {
    // Per-present interval in nanoseconds.
    // 每次呈现的间隔，单位为纳秒。
    private val ring = LongArray(capacity)
    private var writeIndex = 0L
    private var readIndex = 0L
    private var lastMark: TimeMark? = null

    /**
     * Allocation-free hot path wired to `VapAnimationState.onPresented` or a PAG counter bump.
     *
     * 连接到 `VapAnimationState.onPresented` 或 PAG 计数递增的无分配热路径。
     */
    var onPresent: () -> Unit = {
        val now = TimeSource.Monotonic.markNow()
        val prev = lastMark
        lastMark = now
        if (prev != null) {
            ring[(writeIndex % ring.size).toInt()] = prev.elapsedNow().inWholeNanoseconds
            writeIndex++
        }
    }

    /**
     * Drops every interval before the sampling window on the reader thread.
     *
     * 在读取线程上丢弃采样窗口之前的全部间隔。
     */
    fun reset() {
        drain()
    }

    /**
     * Returns chronological present intervals in nanoseconds since the previous drain or reset.
     *
     * 返回上次 drain 或 reset 之后按时间排序的呈现间隔，单位为纳秒。
     */
    fun drain(): LongArray {
        val end = writeIndex
        val start = maxOf(readIndex, end - ring.size)
        readIndex = end
        val n = (end - start).toInt()
        return LongArray(n) { i -> ring[((start + i) % ring.size).toInt()] }
    }
}

/**
 * Steady-state presentation metrics emitted by one benchmark sampling window.
 *
 * 单个基准采样窗口输出的稳态呈现指标模型。
 */
internal data class BenchSampleResult(
    val avgFps: Double,
    /**
     * Per-instance whole-window FPS extrema across instances.
     *
     * 各实例全窗口 FPS 的最小值与最大值。
     */
    val minFps: Double,
    val maxFps: Double,
    /**
     * 1% low FPS from the worst 1% present intervals across all instances; `null` when unwired.
     *
     * 根据全部实例最差 1% 呈现间隔计算的 1% low FPS；未接入记录器时为 `null`。
     */
    val low1Fps: Double?,
    val presents: Long,
    val durationMs: Long,
    val targetFps: Int,
    val assetKey: String,
    val assetLabel: String,
    val instanceCount: Int,
    val gpuPrefer: String = "default",
    val gpuResolved: String = "default",
) {
    /**
     * Aggregate presentation rate across all instances.
     *
     * 所有实例的总呈现速率。
     */
    val totalFps: Double
        get() = if (instanceCount <= 1) avgFps else presents * 1000.0 / durationMs

    fun toLogLine(kind: String): String {
        val low1 = low1Fps?.let { "low1_fps=${it.round1()} " } ?: ""
        val base =
            "RESULT kind=$kind count=$instanceCount avg_fps=${avgFps.round1()} " +
                "min_fps=${minFps.round1()} max_fps=${maxFps.round1()} " + low1 +
                "presents=$presents duration_ms=$durationMs target_fps=$targetFps " +
                "asset=$assetKey clip=$assetLabel " +
                "gpu=$gpuPrefer resolved=$gpuResolved"
        return if (instanceCount > 1) {
            "$base total_fps=${totalFps.round1()}"
        } else {
            base
        }
    }
}

/**
 * Samples successful present counters over one steady-state window.
 *
 * 在单个稳态窗口内采样成功呈现计数器。
 *
 * @param readPresents Monotonic present counter, aggregated across instances for concurrent runs.
 * @param instanceCount Divisor used to report per-instance average when greater than one.
 * @param readInstancePresents Per-instance monotonic counters in instance order; enables min/max FPS.
 *   When absent, min/max fall back to the average.
 * @param intervalRecorders Per-instance interval recorders reset at window start and drained at window end
 *   to compute [BenchSampleResult.low1Fps].
 *
 * @param readPresents 单调递增的呈现计数器；并发测试时为所有实例之和。
 * @param instanceCount 大于一时用于计算单实例平均值的除数。
 * @param readInstancePresents 按实例顺序排列的单调计数器，用于计算最小/最大 FPS；缺省时回退到平均值。
 * @param intervalRecorders 每实例的间隔记录器；窗口开始时重置、结束时读取，以计算 [BenchSampleResult.low1Fps]。
 */
internal suspend fun runPresentSample(
    readPresents: () -> Long,
    targetFps: Int,
    assetKey: String,
    assetLabel: String,
    durationMs: Long,
    instanceCount: Int = 1,
    gpuPrefer: String = "default",
    gpuResolved: String = "default",
    readInstancePresents: (() -> LongArray)? = null,
    intervalRecorders: List<PresentIntervalRecorder>? = null,
    onLiveFps: (Double?) -> Unit = {},
): BenchSampleResult? {
    val mark = TimeSource.Monotonic.markNow()
    var lastCount = readPresents()
    var lastElapsedMs = 0L
    val startCount = lastCount
    val startInstanceCounts = readInstancePresents?.invoke()
    intervalRecorders?.forEach { it.reset() }
    val windowMs = 250L
    val instances = instanceCount.coerceAtLeast(1)

    logVapBench(
        "START kind=${if (instances > 1) "multi" else "single"} count=$instances " +
            "asset=$assetKey clip=$assetLabel target_fps=$targetFps " +
            "duration_ms=$durationMs start_presents=$startCount " +
            "gpu=$gpuPrefer resolved=$gpuResolved",
    )

    while (coroutineContext.isActive && mark.elapsedNow().inWholeMilliseconds < durationMs) {
        delay(windowMs)
        val elapsedMsNow = mark.elapsedNow().inWholeMilliseconds
        val count = readPresents()
        val dtMs = (elapsedMsNow - lastElapsedMs).coerceAtLeast(1L)
        val delta = (count - lastCount).coerceAtLeast(0L)
        onLiveFps(delta * 1000.0 / dtMs / instances)
        lastCount = count
        lastElapsedMs = elapsedMsNow
    }

    if (!coroutineContext.isActive) {
        logVapBench("ERROR cancelled asset=$assetKey count=$instances")
        return null
    }

    val elapsedMs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
    val totalPresents = (readPresents() - startCount).coerceAtLeast(0L)
    val avg = totalPresents * 1000.0 / elapsedMs / instances

    var minFps = avg
    var maxFps = avg
    val endInstanceCounts = readInstancePresents?.invoke()
    if (startInstanceCounts != null && endInstanceCounts != null) {
        val n = minOf(startInstanceCounts.size, endInstanceCounts.size)
        for (i in 0 until n) {
            val fps = (endInstanceCounts[i] - startInstanceCounts[i]).coerceAtLeast(0L) *
                1000.0 / elapsedMs
            minFps = minOf(minFps, fps)
            maxFps = maxOf(maxFps, fps)
        }
    }

    val low1Fps = intervalRecorders
        ?.flatMap { it.drain().asIterable() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { intervals ->
            val worst = intervals.sortedDescending()
            val k = maxOf(1, ceil(worst.size / 100.0).toInt())
            val meanNs = worst.take(k).average()
            if (meanNs > 0) 1_000_000_000.0 / meanNs else null
        }

    return BenchSampleResult(
        avgFps = avg,
        minFps = minFps,
        maxFps = maxFps,
        low1Fps = low1Fps,
        presents = totalPresents,
        durationMs = elapsedMs,
        targetFps = targetFps,
        assetKey = assetKey,
        assetLabel = assetLabel,
        instanceCount = instances,
        gpuPrefer = gpuPrefer,
        gpuResolved = gpuResolved,
    )
}

internal fun Double.round1(): String =
    ((this * 10.0).roundToInt() / 10.0).toString()
