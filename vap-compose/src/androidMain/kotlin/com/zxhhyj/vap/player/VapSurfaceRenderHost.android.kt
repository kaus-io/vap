package com.zxhhyj.vap.player

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import com.zxhhyj.vap.decode.VapSurfaceFrameDecoder
import kotlinx.coroutines.awaitCancellation

/**
 * Android `actual` for [VapSurfaceRenderHost]: binds the surface-mode decoder
 * ([VapSurfaceFrameDecoder]) to a Compose [AndroidEmbeddedExternalSurface]. The decoder
 * performs decode + GLES composite + `eglSwapBuffers` internally.
 *
 * [VapSurfaceRenderHost] 的 Android `actual`：将 surface 模式解码器（[VapSurfaceFrameDecoder]）
 * 绑定到 Compose [AndroidEmbeddedExternalSurface]。解码 + GLES 合成 + `eglSwapBuffers`
 * 全部在解码器内部完成。
 */
@Composable
internal actual fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
) {
    val decoder = animationState.decoder as VapSurfaceFrameDecoder
    AndroidEmbeddedExternalSurface(
        modifier = modifier.onGloballyPositioned { coordinates ->
            decoder.setVisible(isComposeNodeVisibleInWindow(coordinates))
        },
        isOpaque = false,
    ) {
        onSurface { surface, width, height ->
            // Bind only on a valid size — a zero-sized buffer would create a Surface that the
            // Vulkan swapchain rejects with SURFACE_LOST.
            // 仅当尺寸合法时绑定；零尺寸缓冲区会生成被 Vulkan swapchain 拒绝的 Surface
            // （SURFACE_LOST）。
            if (width > 0 && height > 0) {
                decoder.attachOutputSurface(surface, width, height)
            }
            surface.onChanged { w, h ->
                if (w > 0 && h > 0) {
                    decoder.attachOutputSurface(surface, w, h)
                }
            }
            surface.onDestroyed {
                decoder.detachOutputSurface()
            }
            try {
                awaitCancellation()
            } finally {
                decoder.detachOutputSurface()
            }
        }
    }
}
