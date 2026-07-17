package com.zxhhyj.vap.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource

internal data class BenchSampleResult(
    val avgFps: Double,
    val minWindowFps: Double,
    val maxWindowFps: Double,
    val presents: Long,
    val durationMs: Long,
    val targetFps: Int,
    val assetKey: String,
    val assetLabel: String,
    val instanceCount: Int,
) {
    /** Aggregate present rate across all instances. */
    val totalFps: Double
        get() = if (instanceCount <= 1) avgFps else presents * 1000.0 / durationMs

    fun toLogLine(kind: String): String {
        val base =
            "RESULT kind=$kind count=$instanceCount avg_fps=${avgFps.round1()} " +
                "min_fps=${minWindowFps.round1()} max_fps=${maxWindowFps.round1()} " +
                "presents=$presents duration_ms=$durationMs target_fps=$targetFps " +
                "asset=$assetKey clip=$assetLabel"
        return if (instanceCount > 1) {
            "$base total_fps=${totalFps.round1()}"
        } else {
            base
        }
    }
}

/**
 * @param readPresents monotonic present counter (sum across instances for multi).
 * @param instanceCount used to report per-instance avg when > 1.
 */
internal suspend fun runPresentSample(
    readPresents: () -> Long,
    targetFps: Int,
    assetKey: String,
    assetLabel: String,
    durationMs: Long,
    instanceCount: Int = 1,
    onLiveFps: (Double?) -> Unit = {},
): BenchSampleResult? {
    val mark = TimeSource.Monotonic.markNow()
    var lastCount = readPresents()
    var lastElapsedMs = 0L
    var minWin = Double.POSITIVE_INFINITY
    var maxWin = 0.0
    val startCount = lastCount
    val windowMs = 250L
    val instances = instanceCount.coerceAtLeast(1)

    logVapBench(
        "START kind=${if (instances > 1) "multi" else "single"} count=$instances " +
            "asset=$assetKey clip=$assetLabel target_fps=$targetFps " +
            "duration_ms=$durationMs start_presents=$startCount",
    )

    while (coroutineContext.isActive && mark.elapsedNow().inWholeMilliseconds < durationMs) {
        delay(windowMs)
        val elapsedMsNow = mark.elapsedNow().inWholeMilliseconds
        val count = readPresents()
        val dtMs = (elapsedMsNow - lastElapsedMs).coerceAtLeast(1L)
        val delta = (count - lastCount).coerceAtLeast(0L)
        val winFps = delta * 1000.0 / dtMs / instances
        onLiveFps(winFps)
        if (delta > 0 || lastCount > startCount) {
            minWin = minOf(minWin, winFps)
            maxWin = maxOf(maxWin, winFps)
        }
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
    return BenchSampleResult(
        avgFps = avg,
        minWindowFps = if (minWin.isFinite()) minWin else 0.0,
        maxWindowFps = maxWin,
        presents = totalPresents,
        durationMs = elapsedMs,
        targetFps = targetFps,
        assetKey = assetKey,
        assetLabel = assetLabel,
        instanceCount = instances,
    )
}

internal fun Double.round1(): String =
    ((this * 10.0).roundToInt() / 10.0).toString()
