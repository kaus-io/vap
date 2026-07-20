package com.zxhhyj.vap.player

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zxhhyj.vap.decode.VapPresentMode
import com.zxhhyj.vap.decode.VapSurfaceFrameDecoder
import com.zxhhyj.vap.decode.createAndroidVapFrameDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Session for `VapGlOutputMode.WindowSurface`: decode + GLES composite + eglSwapBuffers
 * into a Compose Embedded External Surface.
 *
 * `VapGlOutputMode.WindowSurface` 的会话：解码 + GLES 合成 + eglSwapBuffers，
 * 全部输出到 Compose Embedded External Surface。
 */
@Stable
public class VapSurfaceSession internal constructor(
    internal val decoder: VapSurfaceFrameDecoder,
) {
    /**
     * Normalized playback progress in `[0f, 1f]`. Throttled; first/last frame always force a
     * publish.
     *
     * 归一化播放进度，范围 `[0f, 1f]`。已节流，首末帧强制发布一次。
     */
    public var progress: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Whether the underlying decoder clock is currently running.
     *
     * 底层解码器时钟当前是否在跑。
     */
    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    private var lastProgressMark = TimeSource.Monotonic.markNow()

    /**
     * Binds an output [Surface] of [width]x[height] to the decoder. Safe to call multiple
     * times (e.g. on layout changes).
     *
     * 将 [width]x[height] 的输出 [Surface] 绑定到解码器。可重复调用（例如布局变化时）。
     */
    public fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        decoder.attachOutputSurface(surface, width, height)
    }

    /**
     * Resize an already-attached output without rebinding the Surface object.
     *
     * 在已绑定 Surface 的情况下仅调整输出尺寸，不重新绑定 Surface 对象。
     */
    public fun resizeOutput(width: Int, height: Int) {
        decoder.resizeOutput(width, height)
    }

    /**
     * Detach and release the currently bound output Surface, if any.
     *
     * 解绑并释放当前已绑定的输出 Surface（若有）。
     */
    public fun detachOutputSurface() {
        decoder.detachOutputSurface()
    }

    /**
     * Alias of [setVisible] for API symmetry with the Canvas path.
     *
     * [setVisible] 的别名，用于与 Canvas 路径保持 API 对称。
     */
    public fun setSwapEnabled(enabled: Boolean) {
        setVisible(enabled)
    }

    /**
     * Invisible stops decode clock (not only EGL swap).
     *
     * 不可见时不仅停止 EGL swap，还会停止解码时钟。
     */
    public fun setVisible(visible: Boolean) {
        decoder.setVisible(visible)
    }

    /**
     * Push play/pause state to both the Compose-readable flag and the decoder clock.
     *
     * 将播放/暂停状态同步到 Compose 侧标志位与底层解码器时钟。
     */
    public fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
    }

    /**
     * Target present FPS (`0` follows media timestamps).
     *
     * 目标呈现帧率（`0` 跟随媒体时间戳）。
     */
    public fun setTargetFrameRate(fps: Int) {
        decoder.setTargetFrameRate(fps)
    }

    internal fun publishProgress(value: Float, force: Boolean = false) {
        val clamped = value.coerceIn(0f, 1f)
        if (!force && lastProgressMark.elapsedNow() < PROGRESS_MIN_INTERVAL) return
        if (clamped == progress) return
        progress = clamped
        lastProgressMark = TimeSource.Monotonic.markNow()
    }

    /**
     * Tear down the codec/GL session; session object itself stays alive.
     *
     * 拆除 codec/GL 会话；session 对象本身仍然保留。
     */
    internal suspend fun releaseDecodeSession() {
        decoder.releaseDecodeSession()
        publishProgress(0f, force = true)
    }

    /**
     * Full teardown: closes the decoder and resets progress. Idempotent via the decoder.
     *
     * 完整拆除：关闭解码器并重置进度；底层解码器保证幂等。
     */
    public fun close() {
        decoder.close()
        publishProgress(0f, force = true)
    }

    private companion object {
        val PROGRESS_MIN_INTERVAL = 100.milliseconds
    }
}

/**
 * Composable that drives a [VapSurfaceSession] for the parallel
 * [VapSurfaceAnimation] entry point. Pause releases the session; composition changes
 * re-open it.
 *
 * 为并行的 [VapSurfaceAnimation] 入口驱动 [VapSurfaceSession] 的 Composable。
 * 暂停会释放会话；composition 变化会重新打开会话。
 */
@Composable
internal fun animateVapSurfaceSessionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): VapSurfaceSession {
    val session = remember {
        val decoder = createAndroidVapFrameDecoder()
        decoder.configurePresentMode(VapPresentMode.Surface)
        VapSurfaceSession(decoder)
    }
    val loopForever = iterations == VapConstants.IterateForever
    val decoderLoop = loopForever || iterations > 1

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session, composition, iterations, fps, isPlaying) {
        if (composition == null) {
            session.releaseDecodeSession()
            return@LaunchedEffect
        }
        if (!isPlaying) {
            session.syncPlaying(false)
            session.releaseDecodeSession()
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) {
                session.decoder.open(composition.source, loop = decoderLoop, fpsOverride = fps)
            }
            session.syncPlaying(true)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onError?.invoke(t) ?: throw t
            return@LaunchedEffect
        }

        val total = composition.config.totalFrames.coerceAtLeast(1)
        var index = 0
        var playCount = 0
        try {
            while (isActive) {
                try {
                    // awaitFramePresented() parks the loop on the decoder's present latch;
                    // returning `false` signals natural end-of-stream.
                    // awaitFramePresented() 在解码器的 present 门闩上挂起循环；返回 `false`
                    // 表示自然结束。
                    val presented = session.decoder.awaitFramePresented()
                    if (!presented) {
                        session.publishProgress(1f, force = true)
                        onCompleted?.invoke()
                        break
                    }
                    val atBoundary = index + 1 >= total
                    session.publishProgress(
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
            session.releaseDecodeSession()
        }
    }

    return session
}
