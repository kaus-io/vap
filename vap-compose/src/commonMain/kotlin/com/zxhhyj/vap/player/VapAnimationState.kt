package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.zxhhyj.vap.decode.VapFrameAdvance
import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapGpu
import com.zxhhyj.vap.decode.VapGpuBackend
import com.zxhhyj.vap.decode.VapPlatformFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Single source of truth for one VAP playback: holds the most recent decoded frame,
 * exposes Compose-readable progress/playing flags, and forwards lifecycle commands
 * (visibility, play/pause, target FPS) to the underlying `VapFrameDecoder`.
 *
 * 一次 VAP 播放的唯一状态源：持有最近解码的帧，对外暴露 Compose 可读的 progress / playing
 * 标志，并把可见性、播放/暂停、目标帧率等生命周期命令转发到底层 `VapFrameDecoder`。
 */
@Stable
public class VapAnimationState internal constructor(
    internal val decoder: VapFrameDecoder,
    internal val presentMode: VapPresentMode,
) {
    private var heldFrame: VapPlatformFrame? = null


    private var drawInvalidationEnabled: Boolean = true

    /**
     * Monotonic counter incremented every time a new frame is published or the surface
     * is invalidated. Read inside `drawBehind` to subscribe to redraws.
     *
     * 每次发布新帧或 surface 失效时递增的计数器。在 `drawBehind` 中读取以订阅重绘。
     */
    internal var drawGeneration by mutableIntStateOf(0)
        private set

    /**
     * Normalized playback progress in `[0f, 1f]`. Updated at most every
     * [PROGRESS_MIN_INTERVAL] unless forced (first frame / last frame).
     *
     * 归一化播放进度，范围 `[0f, 1f]`。默认节流到 [PROGRESS_MIN_INTERVAL]，首末帧强制刷新。
     */
    public var progress: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Whether the underlying decoder is currently clocked (i.e. producing frames).
     *
     * 解码器当前是否在跑时钟（即持续产出帧）。
     */
    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    /**
     * Cumulative presents since last [resetPresentedCount]. Not a Compose snapshot field —
     * benchmarks / demos should poll it.
     *
     * 自上次 [resetPresentedCount] 起的累计 present 次数。**不是** Compose snapshot 字段，
     * 基准测试 / 演示应主动轮询。
     */
    public var presentedCount: Long = 0L
        private set

    /**
     * Bench/diagnostics hook: invoked once per present right after [presentedCount] bumps.
     * Called on the present loop thread; keep it allocation-free. Not a snapshot field.
     *
     * 基准 / 诊断回调：在 [presentedCount] 增加后立即触发一次。运行于 present 循环线程，
     * 实现应避免分配。**不是** snapshot 字段。
     */
    public var onPresented: (() -> Unit)? = null

    /**
     * Logical RGB content size from `VapConfig` — used to letterbox Surface host (PAG LetterBox).
     *
     * `VapConfig` 中的逻辑 RGB 内容尺寸 —— 用于 Surface host 的 letterbox（类似 PAG LetterBox）。
     */
    internal var contentWidth: Int by mutableIntStateOf(0)
        private set
    internal var contentHeight: Int by mutableIntStateOf(0)
        private set

    private var lastProgressMark = TimeSource.Monotonic.markNow()


    /**
     * True when this state is wired to the Surface render host (Android).
     *
     * 当前 state 是否走 Surface 渲染宿主（Android）。
     */
    internal val usesSurfaceHost: Boolean
        get() = presentMode == VapPresentMode.Surface

    /**
     * Maps the public Compose present mode to the decoder-side present mode.
     *
     * 将 Compose 侧的 present 模式映射到底层解码器侧的 present 模式。
     */
    internal fun decodePresentMode(): com.zxhhyj.vap.decode.VapPresentMode = when (presentMode) {
        VapPresentMode.Canvas -> com.zxhhyj.vap.decode.VapPresentMode.Bitmap
        VapPresentMode.Surface -> com.zxhhyj.vap.decode.VapPresentMode.Surface
    }

    internal fun setContentSize(width: Int, height: Int) {
        contentWidth = width.coerceAtLeast(0)
        contentHeight = height.coerceAtLeast(0)
    }

    internal fun markPresented() {
        presentedCount++
        onPresented?.invoke()
    }

    internal fun resetPresentedCount() {
        presentedCount = 0L
    }

    /**
     * Returns the most recently published frame, or `null` if none yet / cleared.
     *
     * 返回最近一次发布的帧；若尚无帧或已被清理则返回 `null`。
     */
    internal fun currentFrame(): VapPlatformFrame? = heldFrame

    internal fun setDisplaySize(widthPx: Int, heightPx: Int) {
        decoder.setDisplaySize(widthPx, heightPx)
        if (widthPx <= 0 || heightPx <= 0) {
            setDrawInvalidationEnabled(false)
        }
    }


    internal fun setDrawInvalidationEnabled(enabled: Boolean) {
        if (drawInvalidationEnabled == enabled) return
        drawInvalidationEnabled = enabled
        // Canvas path: invisible also stops the decode clock.
        // Canvas 路径下：不可见时同步停止解码时钟，避免离屏继续解码浪费功耗。
        decoder.setVisible(enabled)
        if (enabled && heldFrame != null) {
            drawGeneration++
        }
    }

    /**
     * Push play/pause state to both the Compose-readable flag and the decoder clock.
     *
     * 将播放/暂停状态同步到 Compose 侧的标志位与底层解码器时钟。
     */
    public fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
    }

    /**
     * Target present FPS (PAG-style max/target frame rate).
     * `0` follows media timestamps; typical UI animation uses `30`.
     *
     * 目标呈现帧率（类似 PAG 的 max/target frame rate）。`0` 跟随媒体时间戳；
     * 典型 UI 动画可传 `30`。
     */
    public fun setTargetFrameRate(fps: Int) {
        decoder.setTargetFrameRate(fps)
    }

    /**
     * Drop codec/GL session; composition + this state remain for a later `open` via animate*.
     *
     * 销毁 codec / GL 会话；composition 与本 state 保留，可稍后通过 animate* 重新 `open`。
     */
    internal suspend fun releaseDecodeSession() {
        clearFrame()
        decoder.releaseDecodeSession()
    }

    /**
     * Publish a new frame: swap in [platformFrame], bump the draw generation so any
     * `drawBehind` reading it redraws, and release the previously held frame.
     *
     * 发布新帧：换入 [platformFrame]，递增 drawGeneration 以触发正在订阅的 `drawBehind` 重绘，
     * 并释放此前持有的帧。
     */
    internal fun present(platformFrame: VapPlatformFrame) {
        val previous = heldFrame
        heldFrame = platformFrame
        if (drawInvalidationEnabled) {
            drawGeneration++
        }
        previous?.release()
    }

    /**
     * Release the held frame and bump generation so consumers redraw a blank.
     *
     * 释放当前持有的帧并递增 generation，使消费者重绘为空白。
     */
    internal fun clearFrame() {
        heldFrame?.release()
        heldFrame = null
        drawGeneration++
    }

    /**
     * Throttled progress publisher — first/last frame always force an update.
     *
     * 节流的进度发布器；首末帧总是强制更新一次。
     */
    internal fun publishProgress(value: Float, force: Boolean = false) {
        val clamped = value.coerceIn(0f, 1f)
        if (!force && lastProgressMark.elapsedNow() < PROGRESS_MIN_INTERVAL) return
        if (clamped == progress) return
        progress = clamped
        lastProgressMark = TimeSource.Monotonic.markNow()
    }

    /**
     * Final teardown: drop the held frame, close the decoder, reset progress.
     *
     * 最终拆除：释放持有的帧、关闭解码器、将进度归零。
     */
    internal fun close() {
        clearFrame()
        decoder.close()
        publishProgress(0f, force = true)
    }

    private companion object {
        val PROGRESS_MIN_INTERVAL = 100.milliseconds
    }
}

/**
 * Composable that drives a [VapComposition] and exposes a [VapAnimationState] for use
 * with [VapAnimation].
 *
 * 驱动 [VapComposition] 并返回可与 [VapAnimation] 配合使用的 [VapAnimationState] 的 Composable。
 *
 * @param composition Loaded VAP, or `null` to idle.
 * @param isPlaying Whether playback runs immediately.
 * @param iterations Play count; [VapConstants.IterateForever] loops indefinitely.
 * @param fps Target present frame rate. `null` follows media PTS; e.g. `30` matches PAG maxFrameRate.
 *           目标呈现帧率。`null` 跟随媒体时间戳；如 `30` 与 PAG maxFrameRate 一致。
 * @param onCompleted Invoked on natural end of all iterations (not on every loop).
 * @param onError Invoked when decoding/presenting fails; non-fatal so the loop can continue.
 * @param releaseDecodeSessionOnPause When `true` (default), `isPlaying=false` tears down MediaCodec/GL
 *   (low memory; next play pays cold `open`). When `false`, pause only gates the clock and keeps the
 *   session warm for fast resume (HannaWindow-style hide/show).
 *   为 `true`（默认）时，`isPlaying=false` 会拆除 MediaCodec/GL（低内存，下次播放需冷启动 `open`）；
 *   为 `false` 时，仅暂停时钟并保留会话，以便快速恢复（类似 HannaWindow 的隐藏/显示）。
 * @param gpuBackend Optional override for the GPU backend the decoder should target.
 */
@Composable
public fun animateVapCompositionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
    releaseDecodeSessionOnPause: Boolean = true,
    gpuBackend: VapGpuBackend? = null,
): VapAnimationState = animateVapCompositionAsState(
    composition = composition,
    isPlaying = isPlaying,
    iterations = iterations,
    fps = fps,
    onCompleted = onCompleted,
    onError = onError,
    releaseDecodeSessionOnPause = releaseDecodeSessionOnPause,
    gpuBackend = gpuBackend,
    presentModeOverride = null,
)

@Composable
internal fun animateVapCompositionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
    releaseDecodeSessionOnPause: Boolean = true,
    gpuBackend: VapGpuBackend? = null,
    presentModeOverride: VapPresentMode?,
): VapAnimationState {
    val presentMode = currentVapPresentMode(override = presentModeOverride)
    val resolvedGpu = VapGpu.resolve(gpuBackend)
    val state = remember(presentMode, resolvedGpu) {
        val decodePresentMode = when (presentMode) {
            VapPresentMode.Canvas -> com.zxhhyj.vap.decode.VapPresentMode.Bitmap
            VapPresentMode.Surface -> com.zxhhyj.vap.decode.VapPresentMode.Surface
        }
        val decoder = createPlatformVapFrameDecoder().also {
            it.configurePresentMode(decodePresentMode)
            it.configureGpuBackend(resolvedGpu)
        }
        VapAnimationState(decoder = decoder, presentMode = presentMode)
    }
    // Invalidates in-flight LaunchedEffect finally blocks so a cancelled effect cannot
    // releaseDecodeSession() after a newer effect has already opened the next session.
    // 会话纪元：用于让旧 effect 的 finally 块失效，避免被取消的 effect 在新 effect 已经
    // 打开新会话之后再次调用 releaseDecodeSession()。
    val sessionEpoch = remember(state) { intArrayOf(0) }
    val loopForever = iterations == VapConstants.IterateForever
    val decoderLoop = loopForever || iterations > 1
    val surfaceMode = state.usesSurfaceHost
    val playingLatest by rememberUpdatedState(isPlaying)

    DisposableEffect(state) {
        onDispose {
            sessionEpoch[0]++
            state.close()
        }
    }

    // Sync content size before first Surface host layout (letterbox like PAG LetterBox).
    // 在 Surface host 首次布局前同步内容尺寸（letterbox 行为参考 PAG LetterBox）。
    SideEffect {
        if (composition != null) {
            state.setContentSize(composition.config.width, composition.config.height)
        } else {
            state.setContentSize(0, 0)
        }
    }

    if (releaseDecodeSessionOnPause) {
        // Default: only the playing instance holds a decode session.
        // 默认：仅处于播放状态的实例持有解码会话。
        LaunchedEffect(state, composition, iterations, fps, isPlaying) {
            val epoch = ++sessionEpoch[0]
            if (composition == null) {
                if (sessionEpoch[0] == epoch) {
                    state.setContentSize(0, 0)
                    state.releaseDecodeSession()
                    state.publishProgress(0f, force = true)
                }
                return@LaunchedEffect
            }
            state.setContentSize(composition.config.width, composition.config.height)
            if (!isPlaying) {
                state.syncPlaying(false)
                if (sessionEpoch[0] == epoch) {
                    state.releaseDecodeSession()
                }
                return@LaunchedEffect
            }
            try {
                withContext(Dispatchers.IO) {
                    state.decoder.open(composition.source, loop = decoderLoop, fpsOverride = fps)
                }
                if (sessionEpoch[0] != epoch) return@LaunchedEffect
                state.resetPresentedCount()
                state.syncPlaying(true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError?.invoke(t) ?: throw t
                return@LaunchedEffect
            }
            runPresentLoop(
                state = state,
                composition = composition,
                loopForever = loopForever,
                iterations = iterations,
                surfaceMode = surfaceMode,
                onCompleted = onCompleted,
                onError = onError,
                shouldReleaseOnExit = { sessionEpoch[0] == epoch },
            )
        }
    } else {
        // Warm standby: open once (lazily on first play), pause via playback gate only.
        // 热待机：首次播放时惰性打开会话，暂停仅关闭播放门、不拆除解码会话。
        LaunchedEffect(state, isPlaying) {
            state.syncPlaying(isPlaying)
        }
        LaunchedEffect(state, composition, iterations, fps) {
            val epoch = ++sessionEpoch[0]
            if (composition == null) {
                if (sessionEpoch[0] == epoch) {
                    state.setContentSize(0, 0)
                    state.releaseDecodeSession()
                    state.publishProgress(0f, force = true)
                }
                return@LaunchedEffect
            }
            state.setContentSize(composition.config.width, composition.config.height)
            snapshotFlow { playingLatest }.first { it }
            if (sessionEpoch[0] != epoch) return@LaunchedEffect
            try {
                withContext(Dispatchers.IO) {
                    state.decoder.open(composition.source, loop = decoderLoop, fpsOverride = fps)
                }
                if (sessionEpoch[0] != epoch) return@LaunchedEffect
                state.resetPresentedCount()
                state.syncPlaying(playingLatest)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError?.invoke(t) ?: throw t
                return@LaunchedEffect
            }
            runPresentLoop(
                state = state,
                composition = composition,
                loopForever = loopForever,
                iterations = iterations,
                surfaceMode = surfaceMode,
                onCompleted = onCompleted,
                onError = onError,
                shouldReleaseOnExit = { sessionEpoch[0] == epoch },
            )
        }
    }

    return state
}

/**
 * Pull-based present loop. For Canvas mode the decoder advances and yields a `VapFrameAdvance.Bitmap`
 * frame; for Surface mode it just signals `VapFrameAdvance.SurfacePresented` because the swap happens
 * inside the decoder. Loops according to [loopForever] / [iterations].
 *
 * 拉取式 present 循环。Canvas 模式下解码器推进后产出 `VapFrameAdvance.Bitmap` 帧；
 * Surface 模式下 swap 在解码器内部完成，这里仅收到 `VapFrameAdvance.SurfacePresented` 信号。
 * 按 [loopForever] / [iterations] 控制循环次数。
 *
 * @param shouldReleaseOnExit Returns `true` only when this loop's epoch is still current, so the
 *   `finally` does not release a session that has already been replaced.
 */
private suspend fun runPresentLoop(
    state: VapAnimationState,
    composition: VapComposition,
    loopForever: Boolean,
    iterations: Int,
    surfaceMode: Boolean,
    onCompleted: (() -> Unit)?,
    onError: ((Throwable) -> Unit)?,
    shouldReleaseOnExit: () -> Boolean,
) {
    val total = composition.config.totalFrames.coerceAtLeast(1)
    var index = 0
    var playCount = 0
    try {
        while (currentCoroutineContext().isActive) {
            try {
                when (val advance = state.decoder.advancePresentedFrame(surfaceMode)) {
                    VapFrameAdvance.Ended -> {
                        state.publishProgress(1f, force = true)
                        onCompleted?.invoke()
                        break
                    }

                    is VapFrameAdvance.Bitmap -> {
                        state.present(advance.frame)
                        state.markPresented()
                    }

                    VapFrameAdvance.SurfacePresented -> state.markPresented()
                }
                val atBoundary = index + 1 >= total
                state.publishProgress(
                    value = (index + 1).toFloat() / total,
                    force = atBoundary || index == 0,
                )
                if (++index >= total) {
                    onCompleted?.invoke()
                    playCount++
                    index = when {
                        loopForever -> 0
                        playCount >= iterations.coerceAtLeast(1) -> break
                        else -> 0
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError?.invoke(t) ?: throw t
                break
            }
        }
    } finally {
        if (shouldReleaseOnExit()) {
            state.releaseDecodeSession()
        }
    }
}
