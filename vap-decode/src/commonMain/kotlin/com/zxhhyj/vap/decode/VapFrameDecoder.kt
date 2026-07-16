package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

public expect class VapFrameDecoder() {
    public suspend fun open(
        source: VapSource,
        loop: Boolean = false,
        /**
         * Target present frame rate. `null` / `<= 0` follows media PTS (or container fps pacing).
         * On Android WindowSurface this caps/paces vsync presents; elsewhere drives [VapSpeedControl].
         */
        fpsOverride: Int? = null
    ): VapConfig

    public suspend fun nextFrame(): VapPlatformFrame?
    public fun setDisplaySize(widthPx: Int, heightPx: Int)
    public fun setPlaying(playing: Boolean)

    /**
     * Target present FPS. `0` disables the override (follow media timestamps).
     * Can be changed while a session is open.
     */
    public fun setTargetFrameRate(fps: Int)

    /**
     * When false, decode/present clock stops (PAG invisible → duration=0).
     * Independent from [setPlaying]; both must be true for the pipeline to advance.
     */
    public fun setVisible(visible: Boolean)

    /**
     * Tear down MediaCodec / GL (or FFmpeg) session while keeping this instance reusable.
     * Call [open] again to resume. Does not delete temp files from [VapSource.Bytes].
     */
    public fun releaseDecodeSession()

    public fun close()
}
