package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Platform present host: Surface on Android, Canvas on JVM.
 *
 * 平台呈现宿主：Android 使用 Surface，JVM 使用 Canvas。
 *
 * Platform `expect` for the Composable that actually paints the latest frame.
 *
 * 用于实际绘制最新帧的 Composable 的平台 `expect` 定义。
 *
 * Implementations:
 * - Android: routes to [VapSurfaceRenderHost] when present mode is Surface,
 *   otherwise to [VapCanvasAnimation].
 * - JVM: always routes to [VapCanvasAnimation].
 */
@Composable
internal expect fun VapPlatformRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
)
