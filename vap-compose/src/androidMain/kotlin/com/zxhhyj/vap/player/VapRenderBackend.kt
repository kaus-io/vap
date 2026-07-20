package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * User-selectable present backend for [VapAnimation] on Android.
 *
 * Android 上 [VapAnimation] 的可选呈现后端。
 */
public enum class VapRenderBackend {
    /**
     * Compose Canvas path; useful as an A/B baseline or when Surface fails.
     *
     * 走 Compose Canvas 路径，可作为对照基线或 Surface 出错时的兜底。
     */
    HardwareBuffer,

    /**
     * Compose Embedded External Surface + EGL swap. Default.
     *
     * 使用 Compose Embedded External Surface + EGL swap，为默认值。
     */
    @ExperimentalVapSurfaceApi
    Surface,
}

/**
 * Present backend for [VapAnimation].
 * Default [VapRenderBackend.Surface] (1.0.3 behavior). Provide [VapRenderBackend.HardwareBuffer]
 * for Canvas A/B tests.
 *
 * [VapAnimation] 的呈现后端。默认 [VapRenderBackend.Surface]（与 1.0.3 行为一致），可显式提供
 * [VapRenderBackend.HardwareBuffer] 用于 Canvas A/B 测试。
 */
@OptIn(ExperimentalVapSurfaceApi::class)
public val LocalVapRenderBackend: ProvidableCompositionLocal<VapRenderBackend> =
    staticCompositionLocalOf { VapRenderBackend.Surface }

/**
 * Android `actual`: explicit [override] wins, otherwise translate [LocalVapRenderBackend]
 * into the internal [VapPresentMode].
 *
 * Android `actual`：显式 [override] 优先，否则按 [LocalVapRenderBackend] 映射到内部
 * [VapPresentMode]。
 */
@OptIn(ExperimentalVapSurfaceApi::class)
@Composable
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode {
    if (override != null) return override
    return when (LocalVapRenderBackend.current) {
        VapRenderBackend.HardwareBuffer -> VapPresentMode.Canvas
        VapRenderBackend.Surface -> VapPresentMode.Surface
    }
}
