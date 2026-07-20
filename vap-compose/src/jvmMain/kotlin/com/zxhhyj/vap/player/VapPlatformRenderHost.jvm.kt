package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * JVM `actual`: always delegates to [VapCanvasAnimation] — there is no platform Surface
 * abstraction in the desktop Compose runtime.
 *
 * JVM `actual`：始终委托给 [VapCanvasAnimation] —— 桌面 Compose 运行时没有平台 Surface 抽象。
 */
@Composable
internal actual fun VapPlatformRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    VapCanvasAnimation(
        animationState = animationState,
        modifier = modifier,
        contentScale = contentScale,
    )
}
