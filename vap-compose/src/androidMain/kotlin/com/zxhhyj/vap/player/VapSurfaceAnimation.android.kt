package com.zxhhyj.vap.player

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.awaitCancellation

/**
 * Android `actual`: thin wrapper over a [VapSurfaceSession] exposing Compose-readable
 * progress / playing flags.
 *
 * Android `actual`：[VapSurfaceSession] 的轻量包装，对外暴露 Compose 可读的
 * progress / playing 标志。
 */
@Stable
public actual class VapSurfacePlaybackState internal constructor(
    internal val session: VapSurfaceSession,
) {
    public actual val progress: Float get() = session.progress
    public actual val isPlaying: Boolean get() = session.isPlaying
}

/**
 * Android `actual`: builds a [VapSurfacePlaybackState] backed by [animateVapSurfaceSessionAsState].
 *
 * Android `actual`：基于 [animateVapSurfaceSessionAsState] 创建 [VapSurfacePlaybackState]。
 */
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

/**
 * Android `actual`: hosts a [VapSurfaceSession] inside a Compose
 * [AndroidEmbeddedExternalSurface]. Visibility changes propagate to the session so the
 * decode clock stops when off-screen.
 *
 * Android `actual`：在 Compose [AndroidEmbeddedExternalSurface] 中承载 [VapSurfaceSession]。
 * 节点可见性变化会通知 session，从而在离屏时停止解码时钟。
 */
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
            // 首次回调可能在缓冲区尚未处于 consumer-ready 状态时到达；先按当前尺寸绑定一次，
            // 再在 onChanged 时随 Pager / 布局稳定后重新绑定（修复首次进入的黑屏）。
            if (width > 0 && height > 0) {
                session.attachOutputSurface(surface, width, height)
            }
            surface.onChanged { w, h ->
                if (w > 0 && h > 0) {
                    session.attachOutputSurface(surface, w, h)
                }
            }
            surface.onDestroyed {
                session.detachOutputSurface()
            }
            // Park here until the surface itself is torn down; awaitCancellation keeps the
            // Embedded External Surface alive for the lifetime of this composition.
            // 挂起至 surface 自身被销毁；awaitCancellation 用于在本次组合生命周期内维持
            // Embedded External Surface 的存活。
            try {
                awaitCancellation()
            } finally {
                session.detachOutputSurface()
            }
        }
    }
}
