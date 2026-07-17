package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

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
