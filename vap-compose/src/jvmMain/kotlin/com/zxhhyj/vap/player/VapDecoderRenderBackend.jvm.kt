package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder

internal actual fun VapFrameDecoder.configurePresentMode(mode: VapPresentMode) {

}

internal actual suspend fun VapFrameDecoder.advancePresentedFrame(
    surfaceMode: Boolean,
): VapFrameAdvance {
    val frame = nextFrame() ?: return VapFrameAdvance.Ended
    return VapFrameAdvance.Bitmap(frame)
}
