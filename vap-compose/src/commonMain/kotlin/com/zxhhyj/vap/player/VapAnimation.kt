package com.zxhhyj.vap.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Composable
public fun VapAnimation(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
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

@Composable
internal fun VapCanvasAnimation(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Spacer(
        modifier = modifier
            .onSizeChanged { size ->
                animationState.setDisplaySize(size.width, size.height)
            }
            .onGloballyPositioned { coordinates ->
                animationState.setDrawInvalidationEnabled(
                    isComposeNodeVisibleInWindow(coordinates),
                )
            }
            .drawBehind {
                animationState.drawGeneration
                val frame = animationState.currentFrame() ?: return@drawBehind
                val dst: IntSize
                val origin: IntOffset
                when (contentScale) {
                    ContentScale.Fit, ContentScale.Inside -> {
                        val scale = minOf(
                            size.width / frame.width.coerceAtLeast(1),
                            size.height / frame.height.coerceAtLeast(1),
                        )
                        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
                        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
                        dst = IntSize(w, h)
                        origin = IntOffset(
                            ((size.width - w) / 2f).roundToInt(),
                            ((size.height - h) / 2f).roundToInt(),
                        )
                    }

                    else -> {
                        dst = IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        )
                        origin = IntOffset.Zero
                    }
                }
                drawVapPlatformFrame(
                    frame = frame,
                    dstOffset = origin,
                    dstSize = dst,
                )
            },
    )
}

@Composable
public fun VapAnimation(
    composition: VapComposition?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    contentScale: ContentScale = ContentScale.Fit,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
) {
    val animationState = animateVapCompositionAsState(
        composition = composition,
        isPlaying = isPlaying,
        iterations = iterations,
        fps = fps,
        onCompleted = onCompleted,
        onError = onError,
    )
    VapAnimation(
        animationState = animationState,
        modifier = modifier,
        contentScale = contentScale,
    )
}


internal fun isComposeNodeVisibleInWindow(
    coordinates: androidx.compose.ui.layout.LayoutCoordinates,
): Boolean {
    if (!coordinates.isAttached) return true
    val bounds = coordinates.boundsInWindow()
    if (bounds.width <= 0f || bounds.height <= 0f) return true

    val root = coordinates.findRootCoordinates().size
    if (root.width <= 0 || root.height <= 0) return true
    val window = Rect(0f, 0f, root.width.toFloat(), root.height.toFloat())
    return bounds.overlaps(window)
}
