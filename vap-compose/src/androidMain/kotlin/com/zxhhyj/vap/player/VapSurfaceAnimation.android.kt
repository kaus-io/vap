package com.zxhhyj.vap.player

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.awaitCancellation

@Stable
public actual class VapSurfacePlaybackState internal constructor(
    internal val session: VapSurfaceSession,
) {
    public actual val progress: Float get() = session.progress
    public actual val isPlaying: Boolean get() = session.isPlaying
}

@Composable
public actual fun animateVapSurfaceAsState(
    composition: VapComposition?,
    isPlaying: Boolean,
    iterations: Int,
    fps: Int?,
    onCompleted: (() -> Unit)?,
    onError: ((Throwable) -> Unit)?,
): VapSurfacePlaybackState {
    val session = animateVapSurfaceSessionAsState(
        composition = composition,
        isPlaying = isPlaying,
        iterations = iterations,
        fps = fps,
        onCompleted = onCompleted,
        onError = onError,
    )
    return VapSurfacePlaybackState(session)
}

@Composable
public actual fun VapSurfaceAnimation(
    state: VapSurfacePlaybackState,
    modifier: Modifier,
) {
    val session = state.session
    AndroidEmbeddedExternalSurface(
        modifier = modifier.onGloballyPositioned { coordinates ->
            session.setVisible(isComposeNodeVisibleInWindow(coordinates))
        },
        isOpaque = false,
    ) {
        onSurface { surface, width, height ->
            // First callback can arrive before the buffer is consumer-ready; bind once,
            // then re-bind on onChanged when Pager/layout settles (fixes first-entry black).
            if (width > 0 && height > 0) {
                session.attachOutputSurface(surface, width, height)
            }
            surface.onChanged { w, h ->
                if (w > 0 && h > 0) {
                    session.attachOutputSurface(surface, w, h)
                }
            }
            surface.onDestroyed {
                session.detachOutputSurfaceBlocking()
            }
            try {
                awaitCancellation()
            } finally {
                session.detachOutputSurface()
            }
        }
    }
}
