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
import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapGlOutputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Session for [VapGlOutputMode.WindowSurface]: decode + GLES composite + eglSwapBuffers
 * into a Compose Embedded External Surface.
 */
@Stable
public class VapSurfaceSession internal constructor(
    internal val decoder: VapFrameDecoder,
) {
    public var progress: Float by mutableFloatStateOf(0f)
        private set

    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    private var lastProgressMark = TimeSource.Monotonic.markNow()

    public fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        decoder.attachOutputSurface(surface, width, height)
    }

    public fun resizeOutput(width: Int, height: Int) {
        decoder.resizeOutput(width, height)
    }

    public fun detachOutputSurface() {
        decoder.detachOutputSurface()
    }

    public fun setSwapEnabled(enabled: Boolean) {
        setVisible(enabled)
    }

    /** Invisible stops decode clock (not only EGL swap). */
    public fun setVisible(visible: Boolean) {
        decoder.setVisible(visible)
    }

    public fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
    }

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

    internal fun releaseDecodeSession() {
        decoder.releaseDecodeSession()
        publishProgress(0f, force = true)
    }

    public fun close() {
        decoder.close()
        publishProgress(0f, force = true)
    }

    private companion object {
        val PROGRESS_MIN_INTERVAL = 100.milliseconds
    }
}

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
        VapSurfaceSession(
            VapFrameDecoder().also { it.setOutputMode(VapGlOutputMode.WindowSurface) },
        )
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
