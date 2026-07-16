package com.zxhhyj.vap.player

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.awaitCancellation

@Composable
internal actual fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
) {
    val decoder = animationState.decoder
    AndroidEmbeddedExternalSurface(
        modifier = modifier.onGloballyPositioned { coordinates ->
            decoder.setVisible(isComposeNodeVisibleInWindow(coordinates))
        },
        isOpaque = false,
    ) {
        onSurface { surface, width, height ->
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
