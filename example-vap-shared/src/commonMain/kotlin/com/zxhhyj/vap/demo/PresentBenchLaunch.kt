package com.zxhhyj.vap.demo

import com.zxhhyj.vap.decode.VapGpuBackend

/**
 * Parsed launch contract for adb/script-driven Present and Concurrent benchmarks.
 *
 * 由 adb/脚本驱动的单实例与并发呈现基准启动约定。
 *
 * Single:
 * 单实例：
 * ```
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es asset idle --ei fps 30 --ei count 1 --ez auto true \
 *   --ei duration_ms 10000 --ei warmup_ms 2000 --ez finish true \
 *   --es gpu vulkan
 * ```
 *
 * Multi (count > 1 opens Concurrent Benchmark):
 * 多实例（count > 1 时打开并发基准）：
 * ```
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es asset about --ei fps 30 --ei count 4 --ez auto true
 * ```
 *
 * PAG engines (Android only; matching `.pag` assets):
 * PAG 引擎（仅 Android，需匹配的 `.pag` 资源）：
 * ```
 * # org.libpag.PAGView
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es engine pagview --es asset idle --ei fps 30 --ei count 8 --ez auto true
 *
 * # org.libpag.PAGImageView
 * adb shell am start -n com.zxhhyj.vap/.MainActivity \
 *   -a com.zxhhyj.vap.action.PRESENT_BENCHMARK \
 *   --es engine pagimageview --es asset idle --ei fps 30 --ei count 8 --ez auto true
 * ```
 */
data class PresentBenchLaunch(
    val assetKey: String = "idle",
    val targetFps: Int = 30,
    val auto: Boolean = true,
    val durationMs: Long = 10_000L,
    val warmUpMs: Long = 2_000L,
    val finishAfter: Boolean = true,
    /**
     * `1` selects Present Benchmark; values above `1` select Concurrent Benchmark.
     *
     * `1` 选择单实例呈现基准；大于 `1` 时选择并发基准。
     */
    val count: Int = 1,
    /**
     * `vulkan` or `null` for the process default; ignored when [isPag] is true.
     *
     * `vulkan`，或以 `null` 使用进程默认值；[isPag] 为 true 时忽略。
     */
    val gpuBackendKey: String? = null,
    /**
     * Engine selector parsed as [Engine].
     *
     * 按 [Engine] 解析的引擎选择键。
     */
    val engineKey: String? = null,
    /**
     * Secondary asset key for a two-instance mixed-asset coexistence render.
     * Ignored unless [count] is `2`.
     *
     * 双实例混合资源共存渲染使用的第二资源键。
     * 仅当 [count] 为 `2` 时生效。
     */
    val asset2Key: String? = null,
    /**
     * Ordered asset keys for StateSwap, advanced every [stateSwapCycleMs].
     * An empty list disables swapping; a non-empty list selects [StateSwapBenchmarkCase].
     *
     * StateSwap 按序使用的资源键，每隔 [stateSwapCycleMs] 推进一次。
     * 空列表禁用切换；非空列表选择 [StateSwapBenchmarkCase]。
     */
    val stateSwapSeq: List<String> = emptyList(),
    /**
     * Per-asset dwell time in StateSwap, in milliseconds.
     *
     * StateSwap 中每个资源的停留时间，单位为毫秒。
     */
    val stateSwapCycleMs: Long = 3000L,
    /**
     * Number of complete StateSwap cycles.
     *
     * StateSwap 的完整循环次数。
     */
    val stateSwapCycles: Int = 10,
) {
    enum class Engine { VAP, PAG_VIEW, PAG_IMAGE_VIEW }

    val isMulti: Boolean get() = count > 1

    val engine: Engine get() = parseEngineKey(engineKey)

    val isPag: Boolean get() = engine != Engine.VAP
    val isPagView: Boolean get() = engine == Engine.PAG_VIEW
    val isPagImageView: Boolean get() = engine == Engine.PAG_IMAGE_VIEW

    val gpuBackend: VapGpuBackend? get() = parseGpuBackendKey(gpuBackendKey)

    companion object {
        const val ACTION: String = "com.zxhhyj.vap.action.PRESENT_BENCHMARK"
        const val EXTRA_ASSET: String = "asset"
        const val EXTRA_FPS: String = "fps"
        const val EXTRA_AUTO: String = "auto"
        const val EXTRA_DURATION_MS: String = "duration_ms"
        const val EXTRA_WARMUP_MS: String = "warmup_ms"
        const val EXTRA_FINISH: String = "finish"
        const val EXTRA_COUNT: String = "count"
        const val EXTRA_GPU: String = "gpu"
        const val EXTRA_ENGINE: String = "engine"
        const val EXTRA_ASSET2: String = "asset2"
        const val EXTRA_STATE_SWAP_SEQ: String = "state_swap_seq"
        const val EXTRA_STATE_SWAP_CYCLE_MS: String = "state_swap_cycle_ms"
        const val EXTRA_STATE_SWAP_CYCLES: String = "state_swap_cycles"

        const val LOG_TAG: String = "VapBench"
    }
}

internal fun parseEngineKey(key: String?): PresentBenchLaunch.Engine =
    when (key?.trim()?.lowercase()) {
        null, "", "vap", "default" -> PresentBenchLaunch.Engine.VAP
        "pag", "pagview", "pag_view", "pag-view" -> PresentBenchLaunch.Engine.PAG_VIEW
        "pagimageview", "pag_image_view", "pag-image-view", "pagiv" ->
            PresentBenchLaunch.Engine.PAG_IMAGE_VIEW
        else -> PresentBenchLaunch.Engine.VAP
    }

internal fun PresentBenchLaunch.Engine.benchLabel(): String = when (this) {
    PresentBenchLaunch.Engine.VAP -> "vap"
    PresentBenchLaunch.Engine.PAG_VIEW -> "pagview"
    PresentBenchLaunch.Engine.PAG_IMAGE_VIEW -> "pagimageview"
}

internal fun parseGpuBackendKey(key: String?): VapGpuBackend? = when (key?.trim()?.lowercase()) {
    null, "", "default", "auto" -> null
    "vulkan", "vk" -> VapGpuBackend.Vulkan
    else -> null
}

internal fun VapGpuBackend.benchLabel(): String = when (this) {
    VapGpuBackend.Vulkan -> "vulkan"
}

internal expect fun logVapBench(message: String)
