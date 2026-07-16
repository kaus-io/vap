package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

@Stable
public actual class VapSurfacePlaybackState internal constructor(
    internal val animationState: VapAnimationState,
) {
    public actual val progress: Float get() = animationState.progress
    public actual val isPlaying: Boolean get() = animationState.isPlaying
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
    val animationState = animateVapCompositionAsState(
        composition = composition,
        isPlaying = isPlaying,
        iterations = iterations,
        fps = fps,
        onCompleted = onCompleted,
        onError = onError,
    )
    return VapSurfacePlaybackState(animationState)
}

@Composable
public actual fun VapSurfaceAnimation(
    state: VapSurfacePlaybackState,
    modifier: Modifier,
) {
    VapAnimation(
        animationState = state.animationState,
        modifier = modifier,
    )
}
