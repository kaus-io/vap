package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

@Deprecated(
    message = "Use animateVapCompositionAsState with " +
            "CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface)",
    replaceWith = ReplaceWith(
        "animateVapCompositionAsState(composition, isPlaying, iterations, fps, onCompleted, onError)",
        "com.zxhhyj.vap.player.animateVapCompositionAsState",
    ),
)
@ExperimentalVapSurfaceApi
@Stable
public class VapSurfacePlaybackState internal constructor(
    internal val animationState: VapAnimationState,
) {
    public val progress: Float get() = animationState.progress
    public val isPlaying: Boolean get() = animationState.isPlaying
}

@Deprecated(
    message = "Use animateVapCompositionAsState with " +
            "CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface)",
)
@ExperimentalVapSurfaceApi
@Composable
public fun animateVapSurfaceAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): VapSurfacePlaybackState {
    val animationState = animateVapCompositionAsState(
        composition = composition,
        isPlaying = isPlaying,
        iterations = iterations,
        fps = fps,
        onCompleted = onCompleted,
        onError = onError,
        presentModeOverride = VapPresentMode.Surface,
    )
    return VapSurfacePlaybackState(animationState)
}

@Deprecated(
    message = "Use VapAnimation with " +
            "CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface)",
)
@ExperimentalVapSurfaceApi
@Composable
public fun VapSurfaceAnimation(
    state: VapSurfacePlaybackState,
    modifier: Modifier = Modifier,
) {
    VapAnimation(
        animationState = state.animationState,
        modifier = modifier,
    )
}

@Deprecated(
    message = "Use VapAnimation with " +
            "CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface)",
    replaceWith = ReplaceWith(
        "CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface) { " +
                "VapAnimation(composition, modifier, isPlaying, iterations, fps, onCompleted = onCompleted, onError = onError) }",
        "androidx.compose.runtime.CompositionLocalProvider",
        "com.zxhhyj.vap.player.LocalVapRenderBackend",
        "com.zxhhyj.vap.player.VapAnimation",
        "com.zxhhyj.vap.player.VapRenderBackend",
    ),
)
@ExperimentalVapSurfaceApi
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
    CompositionLocalProvider(LocalVapRenderBackend provides VapRenderBackend.Surface) {
        VapAnimation(
            composition = composition,
            modifier = modifier,
            isPlaying = isPlaying,
            iterations = iterations,
            fps = fps,
            onCompleted = onCompleted,
            onError = onError,
        )
    }
}
