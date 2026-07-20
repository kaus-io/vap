package com.zxhhyj.vap.decode

/**
 * Strategy for getting composited frames out of the GPU pipeline.
 * 选择 GPU 管线如何将合成后的帧呈现出去。
 *
 * - [HardwareBuffer]: produce an [android.graphics.Bitmap] per frame for callers
 *   that render offscreen (e.g. Compose drawing into an ImageBitmap). 输出离屏位图。
 * - [WindowSurface]: present frames directly into a user-supplied [android.view.Surface]
 *   (e.g. SurfaceView / TextureView) so SurfaceFlinger composites on vsync. 投递到用户 Surface，由 SF 在 vsync 合成。
 */
public enum class VapGlOutputMode {
    HardwareBuffer,
    WindowSurface,
}
