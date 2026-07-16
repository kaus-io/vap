package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

public enum class VapRenderBackend {

    HardwareBuffer,


    @ExperimentalVapSurfaceApi
    Surface,
}


public val LocalVapRenderBackend: ProvidableCompositionLocal<VapRenderBackend> =
    staticCompositionLocalOf { VapRenderBackend.HardwareBuffer }

@OptIn(ExperimentalVapSurfaceApi::class)
@Composable
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode {
    if (override != null) return override
    return when (LocalVapRenderBackend.current) {
        VapRenderBackend.HardwareBuffer -> VapPresentMode.Canvas
        VapRenderBackend.Surface -> VapPresentMode.Surface
    }
}
