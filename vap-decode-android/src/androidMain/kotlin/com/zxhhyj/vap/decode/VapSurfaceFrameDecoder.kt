package com.zxhhyj.vap.decode

import android.view.Surface

/**
 * Android-specific extension of [VapFrameDecoder] for Surface output mode.
 * Android 平台专属的 [VapFrameDecoder] 扩展接口，用于 Surface 输出模式。
 *
 * Instances are created directly by [createAndroidVapFrameDecoder] when the Android
 * source set is selected; no global registry or startup initialization is required.
 * Android source set 通过 [createAndroidVapFrameDecoder] 直接创建实例，
 * 不需要全局注册表或启动初始化。
 *
 * Implementations must be safe to call [attachOutputSurface] / [detachOutputSurface]
 * repeatedly across the lifetime of a single decoder (Compose remount / surface loss).
 * 实现必须保证在单个解码器生命周期内，可重复调用 [attachOutputSurface] 与
 * [detachOutputSurface]（Compose 重组 / Surface 丢失场景）。
 */
public interface VapSurfaceFrameDecoder : VapFrameDecoder {
    public fun attachOutputSurface(surface: Surface, width: Int, height: Int)
    public fun resizeOutput(width: Int, height: Int)
    public fun detachOutputSurface()
    public suspend fun awaitFramePresented(): Boolean
}
