package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * JVM `actual`: thin wrapper over a [VapAnimationState] — there is no real Surface on
 * desktop, so progress / playing flags are read from the Canvas-path state.
 *
 * JVM `actual`：[VapAnimationState] 的轻量包装 —— 桌面没有真正的 Surface，因此
 * progress / playing 标志来自 Canvas 路径的 state。
 */
@Stable
public actual class VapSurfacePlaybackState internal constructor(
    internal val animationState: VapAnimationState,
) {
    public actual val progress: Float get() = animationState.progress
    public actual val isPlaying: Boolean get() = animationState.isPlaying
}

/**
 * JVM `actual`: builds the playback state via [animateVapCompositionAsState] (the shared
 * Canvas-mode state machine).
 *
 * JVM `actual`：通过 [animateVapCompositionAsState]（共享的 Canvas 模式状态机）
 * 构建播放 state。
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

/**
 * JVM `actual`: renders via the standard [VapAnimation] Composable. The name is kept for
 * cross-source API parity with Android.
 *
 * JVM `actual`：使用标准 [VapAnimation] Composable 渲染。保留该名称以与 Android 端
 * 保持 API 一致。
 */
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
