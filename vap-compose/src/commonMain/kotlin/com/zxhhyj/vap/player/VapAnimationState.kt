@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapPlatformFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Stable
public class VapAnimationState internal constructor(
    internal val decoder: VapFrameDecoder,
) {
    private var heldFrame: VapPlatformFrame? = null


    private var drawInvalidationEnabled: Boolean = true

    internal var drawGeneration by mutableIntStateOf(0)
        private set

    /**
     * Playback progress in `0f..1f`.
     *
     * Snapshot writes only when the integer percent (`0..100`) changes (or [force]),
     * so UI that displays percent does not recompose on every present.
     */
    public var progress: Float by mutableFloatStateOf(0f)
        private set

    /** Last published `0..100` percent; `-1` means never published. */
    private var publishedProgressPercent: Int = -1

    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    /**
     * Successful presents since the last decode [open] (demo / benchmark).
     *
     * Not a Compose Snapshot state — updated on the present path without forcing
     * recomposition. Readers (bench) should poll; the sampling loop already does.
     */
    public val presentedCount: Long
        get() = presentedCountAtomic.load()

    private val presentedCountAtomic = AtomicLong(0L)


    internal fun currentFrame(): VapPlatformFrame? = heldFrame

    internal fun markPresented() {
        presentedCountAtomic.addAndFetch(1L)
    }

    internal fun resetPresentedCount() {
        presentedCountAtomic.store(0L)
    }

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
        decoder.setVisible(enabled)
        if (enabled && heldFrame != null) {
            drawGeneration++
        }
    }

    public fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
    }

    /**
     * Target present FPS (PAG [maxFrameRate] analogue).
     * `> 0`: wall-clock logical frame grid (`floor(t * fps)`); `0` follows media PTS.
     */
    public fun setTargetFrameRate(fps: Int) {
        decoder.setTargetFrameRate(fps)
    }

    /** Drop codec/GL session; composition + this state remain for a later [open] via animate*. */
    internal suspend fun releaseDecodeSession() {
        clearFrame()
        decoder.releaseDecodeSession()
    }

    internal fun present(platformFrame: VapPlatformFrame) {
        val previous = heldFrame
        heldFrame = platformFrame
        if (drawInvalidationEnabled) {
            drawGeneration++
        }
        previous?.release()
    }

    internal fun clearFrame() {
        heldFrame?.release()
        heldFrame = null
        drawGeneration++
    }

    internal fun publishProgress(value: Float, force: Boolean = false) {
        val clamped = value.coerceIn(0f, 1f)
        val percent = (clamped * 100f).toInt().coerceIn(0, 100)
        if (!force && percent == publishedProgressPercent) return
        publishedProgressPercent = percent
        progress = clamped
    }

    internal fun close() {
        clearFrame()
        decoder.close()
        publishProgress(0f, force = true)
    }
}

@Composable
/**
 * @param fps Target present FPS. `null` follows media PTS; e.g. `30` = PAG-style progress grid.
 */
public fun animateVapCompositionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): VapAnimationState {
    val state = remember {
        VapAnimationState(decoder = VapFrameDecoder())
    }
    val loopForever = iterations == VapConstants.IterateForever
    val decoderLoop = loopForever || iterations > 1
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val currentOnError by rememberUpdatedState(onError)

    DisposableEffect(state) {
        onDispose { state.close() }
    }

    // Only the playing instance holds a decode session; standby instances keep composition only.
    // Callbacks use rememberUpdatedState so lambda identity changes do not restart decode.
    LaunchedEffect(state, composition, iterations, fps, isPlaying) {
        if (composition == null) {
            state.releaseDecodeSession()
            state.publishProgress(0f, force = true)
            return@LaunchedEffect
        }
        if (!isPlaying) {
            state.syncPlaying(false)
            state.releaseDecodeSession()
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) {
                state.decoder.open(composition.source, loop = decoderLoop, fpsOverride = fps)
            }
            state.resetPresentedCount()
            state.syncPlaying(true)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            currentOnError?.invoke(t) ?: throw t
            return@LaunchedEffect
        }

        val total = composition.config.totalFrames.coerceAtLeast(1)
        var index = 0
        var playCount = 0
        try {
            while (isActive) {
                try {
                    when (val advance = state.decoder.advancePresentedFrame()) {
                        VapFrameAdvance.Ended -> {
                            state.publishProgress(1f, force = true)
                            currentOnCompleted?.invoke()
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
                        currentOnCompleted?.invoke()
                        playCount++
                        index = when {
                            loopForever -> 0
                            playCount >= iterations.coerceAtLeast(1) -> break
                            else -> 0
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    currentOnError?.invoke(t) ?: throw t
                    break
                }
            }
        } finally {
            // Leaving this effect (pause / switch / dispose restart) always frees the codec.
            state.releaseDecodeSession()
        }
    }

    return state
}
