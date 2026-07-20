package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Android `actual`: dispatches to the Surface host when [VapAnimationState.usesSurfaceHost]
 * is `true`, otherwise to the Canvas draw path.
 *
 * Android `actual` 实现：当 [VapAnimationState.usesSurfaceHost] 为 `true` 时走 Surface
 * 宿主，否则走 Canvas 绘制路径。
 */
@Composable
internal actual fun VapPlatformRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    if (animationState.usesSurfaceHost) {
        VapSurfaceRenderHost(
            animationState = animationState,
            modifier = modifier,
        )
    } else {
        VapCanvasAnimation(
            animationState = animationState,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}
