package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

public expect class VapFrameDecoder() {
    public suspend fun open(
        source: VapSource,
        loop: Boolean = false,
        fpsOverride: Int? = null
    ): VapConfig

    public suspend fun nextFrame(): VapPlatformFrame?
    public fun setDisplaySize(widthPx: Int, heightPx: Int)
    public fun setPlaying(playing: Boolean)
    public fun close()
}
