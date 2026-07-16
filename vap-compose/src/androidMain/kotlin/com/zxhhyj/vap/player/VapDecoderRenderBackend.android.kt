package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapGlOutputMode

internal actual fun VapFrameDecoder.configurePresentMode(mode: VapPresentMode) {
    setOutputMode(
        when (mode) {
            VapPresentMode.Canvas -> VapGlOutputMode.HardwareBuffer
            VapPresentMode.Surface -> VapGlOutputMode.WindowSurface
        },
    )
}

internal actual suspend fun VapFrameDecoder.advancePresentedFrame(
    surfaceMode: Boolean,
): VapFrameAdvance {
    if (surfaceMode) {
        return if (awaitFramePresented()) {
            VapFrameAdvance.SurfacePresented
        } else {
            VapFrameAdvance.Ended
        }
    }
    val frame = nextFrame() ?: return VapFrameAdvance.Ended
    return VapFrameAdvance.Bitmap(frame)
}
