package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal actual fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier,
) {
    VapCanvasAnimation(
        animationState = animationState,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
