package com.zxhhyj.vap.decode

/**
 * Platform-specific decoded/composited frame.
 *
 * 平台相关的解码/合成结果帧。
 *
 * On JVM this wraps a Skia `Image`; on Android it wraps a `Bitmap` or
 * hardware-buffer-backed bitmap. Callers must [release] when done.
 *
 * JVM 平台包装 Skia `Image`；Android 平台包装 `Bitmap` 或基于硬件缓冲区的位图。
 * 使用方在消费完毕后必须调用 [release]。
 */
public interface VapPlatformFrame {
    public val width: Int
    public val height: Int
    public fun release()
}
