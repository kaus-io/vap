package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.createAndroidVapFrameDecoder

internal actual fun createPlatformVapFrameDecoder(): VapFrameDecoder =
    createAndroidVapFrameDecoder()
