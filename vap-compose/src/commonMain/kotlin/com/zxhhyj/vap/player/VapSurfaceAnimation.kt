package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Opaque playback handle for the Surface (Android) / fallback (Desktop) render path.
 * Create with [animateVapSurfaceAsState], draw with [VapSurfaceAnimation].
 */
@Stable
public expect class VapSurfacePlaybackState {
    public val progress: Float
    public val isPlaying: Boolean
}

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
 */
@Composable
public expect fun VapSurfaceAnimation(
    state: VapSurfacePlaybackState,
    modifier: Modifier = Modifier,
)

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
