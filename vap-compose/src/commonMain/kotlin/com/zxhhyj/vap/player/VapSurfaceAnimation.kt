package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Opaque playback handle for the Surface (Android) / fallback (Desktop) render path.
 * Create with [animateVapSurfaceAsState], draw with [VapSurfaceAnimation].
 *
 * Surface（Android）/ 兜底（桌面）渲染路径上的不透明播放句柄。使用 [animateVapSurfaceAsState]
 * 创建，配合 [VapSurfaceAnimation] 绘制。
 */
@Stable
public expect class VapSurfacePlaybackState {
    public val progress: Float
    public val isPlaying: Boolean
}

/**
 * Builds a [VapSurfacePlaybackState] driven by [composition].
 *
 * 创建由 [composition] 驱动的 [VapSurfacePlaybackState]。
 *
 * @param composition Loaded VAP, or `null` to idle.
 * @param isPlaying Whether playback runs immediately.
 * @param iterations Play count; [VapConstants.IterateForever] loops indefinitely.
 * @param fps Target present FPS; `null` follows media PTS.
 * @param onCompleted Invoked on natural end of all iterations.
 * @param onError Invoked when decoding/presenting fails; non-fatal so the loop continues.
 */
@Composable
public expect fun animateVapSurfaceAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): VapSurfacePlaybackState

/**
 * Parallel render host: Android uses Compose Surface + EGL swap;
 * Desktop falls back to [VapAnimation].
 *
 * 并行的渲染宿主：Android 使用 Compose Surface + EGL swap；桌面回退到 [VapAnimation]。
 */
@Composable
public expect fun VapSurfaceAnimation(
    state: VapSurfacePlaybackState,
    modifier: Modifier = Modifier,
)

/**
 * Common convenience overload that builds the [VapSurfacePlaybackState] internally
 * and forwards to the platform-specific [VapSurfaceAnimation] Composable.
 *
 * 通用便捷重载：内部构建 [VapSurfacePlaybackState] 后转发到平台特定的 [VapSurfaceAnimation]
 * Composable。
 */
@Composable
public fun VapSurfaceAnimation(
    composition: VapComposition?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
) {
    val state = animateVapSurfaceAsState(
        composition = composition,
        isPlaying = isPlaying,
        iterations = iterations,
        fps = fps,
        onCompleted = onCompleted,
        onError = onError,
    )
    VapSurfaceAnimation(state = state, modifier = modifier)
}
