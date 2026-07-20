@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.libpag.PAGFile
import org.libpag.PAGImageView
import org.libpag.PAGScaleMode
import org.libpag.PAGView
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil

/**
 * Monotonic present counter for PAG benches (PAGView/PAGImageView `onAnimationUpdate`).
 * Playback path mirrors hanna-pag [PAGAnimation]: LetterBox · setMaxFrameRate · repeat=0 · lifecycle play/pause.
 *
 * PAG 基准用单调 present 计数器,挂在 PAGView/PAGImageView 的 `onAnimationUpdate`
 * 上;播放路径对齐 hanna-pag 的 [PAGAnimation]:LetterBox · setMaxFrameRate · repeat=0 ·
 * lifecycle play/pause。
 */
internal class PagPresentCounter {
    private val count = AtomicLong(0L)
    val recorder = PresentIntervalRecorder()
    private var lastFrameIndex = -1
    fun get(): Long = count.load()

    /**
     * Counts only when the content frame index advances.
     * PAG renders flush at vsync rate even when `setMaxFrameRate` keeps content on a coarser
     * grid; duplicate re-presents of the same content frame are collapsed here so the
     * measured rate is the content frame rate — directly comparable to VAP's present rate.
     *
     * 仅在内容帧索引推进时计数。PAG 即便设了 `setMaxFrameRate` 把内容限制在较粗的网格上,
     * 渲染仍按 vsync 节奏 flush,这里把同一内容帧的重复 present 折叠掉,使测量到的
     * 速率就是内容帧速率,可与 VAP 的 present 速率直接对比。
     */
    fun bumpFrame(frameIndex: Int) {
        if (frameIndex == lastFrameIndex) return
        lastFrameIndex = frameIndex
        count.addAndFetch(1L)
        recorder.onPresent()
    }
}

/**
 * Memoized loader for [PAGFile] keyed on `assetPath`; failures (missing/corrupt asset)
 * collapse to `null` so the calling Composable can render an empty state instead of
 * crashing the whole bench run.
 *
 * 按 `assetPath` 记忆化加载 [PAGFile];加载失败(资源缺失或损坏)统一收敛为 `null`,
 * 由上层 Composable 显示空态而不是让整轮基准崩溃。
 */
@Composable
internal fun rememberPagFile(assetPath: String): PAGFile? {
    val context = LocalContext.current
    return remember(assetPath) {
        runCatching { PAGFile.Load(context.assets, assetPath) }.getOrNull()
    }
}

/**
 * PAGView (GPU/Surface) renderer used by the present benches. Composition is bound
 * once at view creation; the update block only flips play/pause on lifecycle changes
 * to avoid rebinding `composition` every frame (which would invalidate the GPU cache).
 *
 * 基准使用的 PAGView(GPU/Surface)渲染器。composition 仅在 view 创建时绑定一次;
 * update 块只根据生命周期切换 play/pause,避免每帧重新绑定 composition 导致
 * GPU 缓存失效。
 */
@Composable
internal fun PagAnimation(
    assetPath: String,
    targetFps: Int,
    modifier: Modifier = Modifier,
    presentCounter: PagPresentCounter? = null,
    scaleMode: Int = PAGScaleMode.LetterBox,
) {
    val pagFile = rememberPagFile(assetPath)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val maxFps = if (targetFps > 0) targetFps.toFloat() else 30f
    // Content-frame grid mirroring PAGPlayer's setMaxFrameRate quantization:
    // numFrames = ceil(duration * min(maxFps, assetRate)); a vsync tick only counts
    // as a new frame when floor(progress * numFrames) advances.
    // 内容帧网格,与 PAGPlayer setMaxFrameRate 的量化方式一致:
    // numFrames = ceil(duration * min(maxFps, assetRate));
    // 只有 floor(progress * numFrames) 推进时,vsync tick 才计为新帧。
    val contentFrames = remember(pagFile, maxFps) {
        if (pagFile == null) {
            1
        } else {
            val assetRate = pagFile.frameRate().takeIf { it > 0f } ?: maxFps
            ceil(pagFile.duration() / 1_000_000.0 * minOf(maxFps, assetRate))
                .toInt().coerceAtLeast(1)
        }
    }
    val listener = remember(presentCounter, contentFrames) {
        object : PAGView.PAGViewListener {
            override fun onAnimationStart(view: PAGView?) = Unit
            override fun onAnimationEnd(view: PAGView?) = Unit
            override fun onAnimationCancel(view: PAGView?) = Unit
            override fun onAnimationRepeat(view: PAGView?) = Unit
            override fun onAnimationUpdate(view: PAGView?) {
                val progress = view?.progress ?: return
                presentCounter?.bumpFrame((progress * contentFrames).toInt())
            }
        }
    }

    if (pagFile == null) return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PAGView(ctx).apply {
                composition = pagFile
                setMaxFrameRate(maxFps)
                setScaleMode(scaleMode)
                setRepeatCount(0)
                if (presentCounter != null) addListener(listener)
            }
        },
        update = { view ->
            // Match hanna-pag: lifecycle gates play/pause; avoid rebinding composition every frame.
            // 与 hanna-pag 对齐:由 lifecycle 门控 play/pause,避免每帧重绑 composition。
            when (lifecycleState) {
                Lifecycle.State.STARTED, Lifecycle.State.RESUMED -> view.play()
                else -> view.pause()
            }
        },
    )
}

/**
 * PAGImageView renderer variant — uses [PAGImageView] (software-decoded Bitmap rasterizer)
 * instead of [PAGView] (GPU/Surface). Same playback semantics: LetterBox · setMaxFrameRate ·
 * repeat=0 · lifecycle play/pause. Present is counted from [PAGImageView.currentFrame],
 * which is the quantized content-frame index reported by libpag — same collapsing as
 * PAGView's progress quantization, no extra math required.
 *
 * PAGImageView 渲染变体:使用 [PAGImageView](软件解码 Bitmap 栅格化)替代
 * [PAGView](GPU/Surface)。播放语义一致:LetterBox · setMaxFrameRate · repeat=0 ·
 * lifecycle play/pause。present 直接取自 [PAGImageView.currentFrame],即 libpag
 * 报告的量化后内容帧索引,与 PAGView 进度量化的折叠语义相同,无需额外计算。
 */
@Composable
internal fun PagImageViewAnimation(
    assetPath: String,
    targetFps: Int,
    modifier: Modifier = Modifier,
    presentCounter: PagPresentCounter? = null,
    scaleMode: Int = PAGScaleMode.LetterBox,
) {
    val pagFile = rememberPagFile(assetPath)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val maxFps = if (targetFps > 0) targetFps.toFloat() else 30f
    val listener = remember(presentCounter) {
        object : PAGImageView.PAGImageViewListener {
            override fun onAnimationStart(view: PAGImageView?) = Unit
            override fun onAnimationEnd(view: PAGImageView?) = Unit
            override fun onAnimationCancel(view: PAGImageView?) = Unit
            override fun onAnimationRepeat(view: PAGImageView?) = Unit
            override fun onAnimationUpdate(view: PAGImageView?) {
                val frame = view?.currentFrame() ?: return
                presentCounter?.bumpFrame(frame)
            }
        }
    }

    if (pagFile == null) return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PAGImageView(ctx).apply {
                setComposition(pagFile, maxFps)
                setScaleMode(scaleMode)
                setRepeatCount(0)
                // PAGImageView 默认 cacheAllFramesInMemory=false。
                // 开启后 PSS 爆炸(D1 800-1040 MB,D2 1500-1800 MB),fps 反降、janky 反升,
                // 因为 libpag 实现只是缓存解码后的 bitmap(适合随机 seek),连续播放时无效。
                // PAGImageView defaults cacheAllFramesInMemory=false. Enabling it explodes
                // PSS (D1 800-1040 MB, D2 1500-1800 MB) and actually drops fps while
                // raising jank, because libpag's cache only stores decoded bitmaps
                // (useful for random seek), not for sequential playback.
                if (presentCounter != null) addListener(listener)
            }
        },
        update = { view ->
            when (lifecycleState) {
                Lifecycle.State.STARTED, Lifecycle.State.RESUMED -> view.play()
                else -> view.pause()
            }
        },
    )
}

// Convenience mapping from bench asset label to its bundled `.pag` resource path.
// 将基准 asset 名映射到打包的 `.pag` 资源路径。
internal fun BenchAsset.pagAssetPath(): String = "$clipLabel.pag"
