package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource

/**
 * Platform-agnostic VAP decoder session.
 *
 * 与平台无关的 VAP 解码会话。
 *
 * Implementations are provided by platform-specific modules such as
 * `:vap-decode-android` and `:vap-decode-jvm`.
 *
 * 由平台特定模块（如 `:vap-decode-android` 与 `:vap-decode-jvm`）提供实现。
 */
public interface VapFrameDecoder {
    public suspend fun open(
        source: VapSource,
        loop: Boolean = false,
        /**
         * Target present FPS. `null` / `<= 0` follows media PTS.
         * Android: PAG-style wall-clock frame grid when `> 0`; JVM: [VapSpeedControl] pacing.
         *
         * 目标呈现 FPS。`null` 或 `<= 0` 时跟随媒体 PTS。
         * Android：`> 0` 时使用 PAG 风格挂钟帧网格；JVM：使用 [VapSpeedControl] 节奏控制。
         */
        fpsOverride: Int? = null,
    ): VapConfig

    public suspend fun nextFrame(): VapPlatformFrame?
    public fun setDisplaySize(widthPx: Int, heightPx: Int)
    public fun setPlaying(playing: Boolean)

    /**
     * Target present FPS. `0` = follow media PTS.
     * Android `> 0`: PAG-style wall-clock grid (`floor(t * fps)`); can change while open.
     *
     * 目标呈现 FPS。`0` 表示跟随媒体 PTS。
     * Android 上 `> 0`：PAG 风格的挂钟网格（`floor(t * fps)`），会话期间可变更。
     */
    public fun setTargetFrameRate(fps: Int)

    /**
     * When false, decode/present clock stops (PAG invisible -> duration=0).
     * Independent from [setPlaying]; both must be true for the pipeline to advance.
     *
     * 为 false 时，解码/呈现时钟停止（PAG 不可见 -> 时长为 0）。与 [setPlaying] 相互独立；
     * 仅当两者同时为 true 时管线才会推进。
     */
    public fun setVisible(visible: Boolean)

    /**
     * Tear down codec/GL session while keeping this instance reusable.
     * Call [open] again to resume. Does not delete temp files from `VapSource.Bytes`.
     *
     * Prefer calling from a coroutine (e.g. Compose `LaunchedEffect`); use [close] from
     * non-coroutine dispose paths.
     *
     * 销毁编解码/GL 会话但保留本实例以便复用，再次调用 [open] 即可恢复。不会删除
     * `VapSource.Bytes` 产生的临时文件。建议在协程中调用（例如 Compose 的 `LaunchedEffect`）；
     * 非协程销毁路径请使用 [close]。
     */
    public suspend fun releaseDecodeSession()

    /** Blocking teardown for non-coroutine dispose (e.g. Compose `DisposableEffect`). */
    public fun close()

    /**
     * Android-only: choose whether decoder frames are emitted as bitmaps or
     * presented directly to a window surface. Default is no-op for platforms
     * without this concept.
     *
     * 仅 Android：选择解码帧以位图形式输出，或直接呈现到窗口 Surface。对没有此概念的平台
     * 默认实现为空操作。
     */
    public fun configurePresentMode(mode: VapPresentMode): Unit = Unit

    /**
     * Android-only: hint the GPU backend used for surface composition.
     * Default is no-op for platforms without this concept.
     *
     * 仅 Android：提示 Surface 合成所用的 GPU 后端。对没有此概念的平台默认实现为空操作。
     */
    public fun configureGpuBackend(backend: VapGpuBackend): Unit = Unit

    /**
     * Advance one frame in the presentation loop.
     *
     * In bitmap mode this returns the next decoded/composited frame.
     * In surface mode the implementation waits for the frame to be presented
     * by the platform compositor.
     *
     * 在呈现循环中推进一帧。位图模式下返回下一帧解码/合成结果；Surface 模式下挂起
     * 直至平台合成器呈现该帧。
     */
    public suspend fun advancePresentedFrame(surfaceMode: Boolean): VapFrameAdvance
}

/**
 * Result of [VapFrameDecoder.advancePresentedFrame].
 *
 * [VapFrameDecoder.advancePresentedFrame] 的返回结果。
 */
public sealed class VapFrameAdvance {
    public data class Bitmap(val frame: VapPlatformFrame) : VapFrameAdvance()
    public data object SurfacePresented : VapFrameAdvance()
    public data object Ended : VapFrameAdvance()
}

/**
 * Presentation mode for the Android decoder.
 *
 * Android 解码器的呈现模式。
 */
public enum class VapPresentMode {
    /** Decode to offscreen bitmaps; used for Canvas-based Compose rendering. */
    Bitmap,

    /** Decode to a SurfaceTexture-backed window surface; used for SurfaceView rendering. */
    Surface,
}
