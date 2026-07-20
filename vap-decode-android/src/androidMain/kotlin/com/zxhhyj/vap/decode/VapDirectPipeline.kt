@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.view.Surface
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Opaque-content direct playback:
 *   MediaCodec → user's Surface via [MediaCodec.setOutputSurface] (API 23+).
 * 不透明内容的直播放放：MediaCodec → 用户 Surface，通过 [MediaCodec.setOutputSurface]
 * （API 23+）直送。
 *
 * Skips the Vulkan / GL composite path entirely — frames go straight from the
 * hardware decoder block to BufferQueue and SF/HWC composites them on vsync.
 * 完全跳过 Vulkan / GL 合成路径 —— 帧从硬件解码器块直送 BufferQueue，
 * 由 SF/HWC 在 vsync 上完成合成。
 *
 * The pipeline only owns a tiny placeholder [Surface] so [MediaCodec.configure]
 * has somewhere to write before the user attaches a real window surface.
 * 本管线仅持有一个最小占位 [Surface]，目的是在用户接入真实窗口 Surface 之前，
 * 给 [MediaCodec.configure] 一个可写入的目标。
 */
internal class VapDirectPipeline {

    private val released = AtomicBoolean(false)

    private val surfaceTexture = SurfaceTexture(0).apply {
        setDefaultBufferSize(1, 1)
    }

    private val placeholderSurface: Surface = Surface(surfaceTexture)

    /** Surface handed to [MediaCodec.configure] in [AndroidVapFrameDecoder.startPipelineLocked]. */
    val codecSurface: Surface get() = placeholderSurface

    /** Swap the codec's output to the user's window surface. Safe to call multiple times. */
    fun attachOutputSurface(codec: MediaCodec, surface: Surface) {
        if (released.load()) return
        codec.setOutputSurface(surface)
    }

    /** Send codec output back to the placeholder so it keeps running while hidden. */
    fun detachOutputSurface(codec: MediaCodec) {
        if (released.load()) return
        codec.setOutputSurface(placeholderSurface)
    }

    /** No-op for direct playback: SurfaceView sizes itself. */
    fun resizeOutput(@Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int) {
    }

    /**
     * No-op for direct playback: codec plays at the source container fps and
     * the BufferQueue paces display at vsync. App-side rate gating is meaningless.
     */
    fun setTargetFrameRate(@Suppress("UNUSED_PARAMETER") fps: Int) {
    }

    fun setSwapEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
    }

    fun discardPendingPresent() {
    }

    fun resetPresentClock() {
    }

    fun release() {
        if (!released.compareAndSet(expectedValue = false, newValue = true)) return
        runCatching { placeholderSurface.release() }
        runCatching { surfaceTexture.release() }
    }
}