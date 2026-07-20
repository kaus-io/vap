package com.zxhhyj.vap.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Top-level Composable that drives a VAP animation from a pre-built [VapAnimationState].
 * Dispatches to the Surface (Android Embedded External Surface) or Canvas draw path
 * based on the state's present mode.
 *
 * 使用已构建的 [VapAnimationState] 驱动 VAP 动画的顶层 Composable。
 * 根据 state 的 present 模式分发到 Surface（Android Embedded External Surface）
 * 或 Canvas 绘制路径。
 *
 * @param animationState Shared state returned by [animateVapCompositionAsState].
 * @param modifier Standard Compose modifier applied to the host.
 * @param contentScale How the VAP content is scaled within the host bounds.
 */
@Composable
public fun VapAnimation(
    animationState: VapAnimationState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (animationState.usesSurfaceHost) {
        VapSurfaceHostScaled(
            animationState = animationState,
            contentScale = contentScale,
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

/**
 * Surface path previously stretched to fill the Compose box (unlike Canvas `ContentScale.Fit`
 * and PAG LetterBox). Letterbox / fill here so WindowSurface matches.
 *
 * Surface 路径此前会被拉伸填满 Compose 盒子（与 Canvas 的 `ContentScale.Fit` 和 PAG LetterBox 不同）。
 * 这里统一做 letterbox / fill，使 WindowSurface 与其他路径行为一致。
 */
@Composable
private fun VapSurfaceHostScaled(
    animationState: VapAnimationState,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    val contentW = animationState.contentWidth
    val contentH = animationState.contentHeight
    // Wait for logical content size so we don't create a fill-parent Surface and then
    // tear it down for letterbox (Vulkan swapchain sees SURFACE_LOST on the stale window).
    // 等待逻辑内容尺寸就绪，避免先创建填满父容器的 Surface、再为 letterbox 拆除它
    // （旧窗口上的 Vulkan swapchain 会因此报 SURFACE_LOST）。
    if (contentW <= 0 || contentH <= 0) {
        Box(modifier)
        return
    }
    val letterbox = contentScale == ContentScale.Fit ||
        contentScale == ContentScale.Inside
    if (!letterbox) {
        VapSurfaceRenderHost(
            animationState = animationState,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.coerceAtLeast(1)
        val boxH = constraints.maxHeight.coerceAtLeast(1)
        val scale = minOf(
            boxW.toFloat() / contentW.toFloat(),
            boxH.toFloat() / contentH.toFloat(),
        )
        val hostW = (contentW * scale).roundToInt().coerceAtLeast(1)
        val hostH = (contentH * scale).roundToInt().coerceAtLeast(1)
        VapSurfaceRenderHost(
            animationState = animationState,
            modifier = Modifier.size(
                width = with(density) { hostW.toDp() },
                height = with(density) { hostH.toDp() },
            ),
        )
    }
}

/**
 * Canvas draw path: a `Spacer` that draws the latest decoded frame via [drawVapPlatformFrame]
 * inside `drawBehind`. Forwards size + window-visibility changes to the state.
 *
 * Canvas 绘制路径：在 `Spacer` 的 `drawBehind` 中通过 [drawVapPlatformFrame] 绘制最新解码帧，
 * 并把尺寸、窗口可见性变化转发给 state。
 */
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
                // Reading drawGeneration snapshots the state so recomposition/repaint
                // is triggered each time a new frame is published.
                // 读取 drawGeneration 使 state 被快照订阅，从而在新帧发布时触发重绘。
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

/**
 * Convenience overload that builds a [VapAnimationState] from a [VapComposition] and then
 * delegates to the state-based [VapAnimation].
 *
 * 便捷重载：从 [VapComposition] 构建 [VapAnimationState]，再委托给基于 state 的 [VapAnimation]。
 *
 * @param composition Loaded VAP, or `null` to idle.
 * @param isPlaying Whether playback runs immediately.
 * @param iterations Play count; [VapConstants.IterateForever] loops indefinitely.
 * @param fps Target present frame rate. `null` follows media PTS; e.g. `30` for UI loops.
 *           目标呈现帧率。`null` 跟随媒体时间戳；UI 循环可传 `30`。
 * @param contentScale Content scaling strategy within the host.
 * @param onCompleted Invoked on natural end of all iterations (not on every loop).
 * @param onError Invoked when decoding/presenting fails; non-fatal so the loop can continue.
 */
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


/**
 * Returns `true` when this node's window-space bounds overlap the root window.
 * Used to gate drawing / decoding when the node is scrolled or clipped off-screen.
 *
 * 当节点在窗口坐标系中的边界与根窗口存在重叠时返回 `true`。用于在节点被滚动或裁剪到屏外时
 * 暂停绘制 / 解码以节省资源。
 */
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
