package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.createJvmVapFrameDecoder

internal actual fun createPlatformVapFrameDecoder(): VapFrameDecoder =
    createJvmVapFrameDecoder()
