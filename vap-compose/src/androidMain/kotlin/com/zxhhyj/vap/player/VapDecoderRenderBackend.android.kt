package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder

internal actual suspend fun VapFrameDecoder.advancePresentedFrame(): VapFrameAdvance {
    return if (awaitFramePresented()) {
        VapFrameAdvance.SurfacePresented
    } else {
        VapFrameAdvance.Ended
    }
}
