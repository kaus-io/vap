package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * JVM `actual`: desktop has no platform Surface, so the host falls back to
 * [VapCanvasAnimation] with [ContentScale.Fit] — the `modifier` already letterboxes the
 * composable via [VapSurfaceHostScaled], so `Fit` here preserves the letterbox.
 *
 * JVM `actual`：桌面没有平台 Surface，因此宿主退回到 [VapCanvasAnimation] 并使用
 * [ContentScale.Fit] —— 实际 letterbox 已由外层 [VapSurfaceHostScaled] 通过 `modifier`
 * 完成，此处的 `Fit` 保持 letterbox 不被二次缩放覆盖。
 */
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
