package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun VapSurfaceRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
)
