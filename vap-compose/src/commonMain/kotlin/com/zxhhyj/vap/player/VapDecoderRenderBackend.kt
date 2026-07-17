package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapPlatformFrame

internal expect suspend fun VapFrameDecoder.advancePresentedFrame(): VapFrameAdvance

internal sealed class VapFrameAdvance {
    data class Bitmap(val frame: VapPlatformFrame) : VapFrameAdvance()
    data object SurfacePresented : VapFrameAdvance()
    data object Ended : VapFrameAdvance()
}
