package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/** Platform present host: Surface on Android, Canvas on JVM. */
@Composable
internal expect fun VapPlatformRenderHost(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
)
