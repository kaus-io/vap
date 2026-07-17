package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

public expect class VapFrameDecoder() {
    public suspend fun open(
        source: VapSource,
        loop: Boolean = false,
        /**
         * Target present FPS. `null` / `<= 0` follows media PTS.
         * Android: PAG-style wall-clock frame grid when `> 0`; JVM: [VapSpeedControl] pacing.
         */
        fpsOverride: Int? = null
    ): VapConfig

    public suspend fun nextFrame(): VapPlatformFrame?
    public fun setDisplaySize(widthPx: Int, heightPx: Int)
    public fun setPlaying(playing: Boolean)

    /**
     * Target present FPS. `0` = follow media PTS.
     * Android `> 0`: PAG-style wall-clock grid (`floor(t * fps)`); can change while open.
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
     *
     * Prefer calling from a coroutine (e.g. Compose [LaunchedEffect]); use [close] from
     * non-coroutine dispose paths.
     */
    public suspend fun releaseDecodeSession()

    /** Blocking teardown for non-coroutine dispose (e.g. Compose [DisposableEffect]). */
    public fun close()
}
