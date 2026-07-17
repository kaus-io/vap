package com.zxhhyj.vap.demo

/**
 * adb / script launch for Present / Concurrent Benchmark.
 *
 * Single:
 * ```
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es asset idle --ei fps 30 --ei count 1 --ez auto true \
 *   --ei duration_ms 10000 --ei warmup_ms 2000 --ez finish true
 * ```
 *
 * Multi (count > 1 opens Concurrent Benchmark):
 * ```
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es asset about --ei fps 30 --ei count 4 --ez auto true
 * ```
 */
data class PresentBenchLaunch(
    val assetKey: String = "idle",
    val targetFps: Int = 30,
    val auto: Boolean = true,
    val durationMs: Long = 10_000L,
    val warmUpMs: Long = 2_000L,
    val finishAfter: Boolean = true,
    /** `1` = Present Benchmark; `>1` = Concurrent Benchmark. */
    val count: Int = 1,
) {
    val isMulti: Boolean get() = count > 1

    companion object {
        const val ACTION: String = "com.zxhhyj.vap.action.PRESENT_BENCHMARK"
        const val EXTRA_ASSET: String = "asset"
        const val EXTRA_FPS: String = "fps"
        const val EXTRA_AUTO: String = "auto"
        const val EXTRA_DURATION_MS: String = "duration_ms"
        const val EXTRA_WARMUP_MS: String = "warmup_ms"
        const val EXTRA_FINISH: String = "finish"
        const val EXTRA_COUNT: String = "count"

        const val LOG_TAG: String = "VapBench"
    }
}

internal expect fun logVapBench(message: String)
