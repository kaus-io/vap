package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder

internal actual suspend fun VapFrameDecoder.advancePresentedFrame(): VapFrameAdvance {
    val frame = nextFrame() ?: return VapFrameAdvance.Ended
    return VapFrameAdvance.Bitmap(frame)
}
