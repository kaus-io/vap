package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform `expect` for the dedicated Surface render host. Only reached when
 * [VapAnimationState.usesSurfaceHost] is `true`; Android binds a Compose
 * Embedded External Surface, JVM falls back to a Canvas.
 *
 * 专用 Surface 渲染宿主的平台 `expect` 定义。仅在 [VapAnimationState.usesSurfaceHost]
 * 为 `true` 时被调用；Android 绑定 Compose Embedded External Surface，JVM 退化为 Canvas。
 */
@Composable
internal expect fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
)
