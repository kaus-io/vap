package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapFrameDecoder

/**
 * Creates the decoder supplied by the current platform source set.
 *
 * 创建当前平台 source set 提供的解码器。
 */
internal expect fun createPlatformVapFrameDecoder(): VapFrameDecoder
