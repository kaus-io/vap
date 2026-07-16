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
import androidx.compose.runtime.setValue
import com.zxhhyj.vap.decode.VapFrameDecoder
import com.zxhhyj.vap.decode.VapPlatformFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Stable
public class VapAnimationState internal constructor(
    internal val decoder: VapFrameDecoder,
    internal val presentMode: VapPresentMode,
) {
    private var heldFrame: VapPlatformFrame? = null


    private var drawInvalidationEnabled: Boolean = true

    internal var drawGeneration by mutableIntStateOf(0)
        private set

    public var progress: Float by mutableFloatStateOf(0f)
        private set

    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    private var lastProgressMark = TimeSource.Monotonic.markNow()


    internal val usesSurfaceHost: Boolean
        get() = presentMode == VapPresentMode.Surface

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
        if (enabled && heldFrame != null) {
            drawGeneration++
        }
    }

    public fun syncPlaying(playing: Boolean) {
        isPlaying = playing
        decoder.setPlaying(playing)
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
        if (!force && lastProgressMark.elapsedNow() < PROGRESS_MIN_INTERVAL) return
        if (clamped == progress) return
        progress = clamped
        lastProgressMark = TimeSource.Monotonic.markNow()
    }

    internal fun close() {
        clearFrame()
        decoder.close()
        publishProgress(0f, force = true)
    }

    private companion object {
        val PROGRESS_MIN_INTERVAL = 100.milliseconds
    }
}

@Composable
public fun animateVapCompositionAsState(
    composition: VapComposition?,
    isPlaying: Boolean = true,
    iterations: Int = 1,
    fps: Int? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): VapAnimationState = animateVapCompositionAsState(
    composition = composition,
    isPlaying = isPlaying,
    iterations = iterations,
    fps = fps,
    onCompleted = onCompleted,
    onError = onError,
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
    presentModeOverride: VapPresentMode?,
): VapAnimationState {
    val presentMode = currentVapPresentMode(override = presentModeOverride)
    val state = remember(presentMode) {
        val decoder = VapFrameDecoder().also { it.configurePresentMode(presentMode) }
        VapAnimationState(decoder = decoder, presentMode = presentMode)
    }
    val loopForever = iterations == VapConstants.IterateForever
    val decoderLoop = loopForever || iterations > 1
    val surfaceMode = state.usesSurfaceHost

    DisposableEffect(state) {
        onDispose { state.close() }
    }

    LaunchedEffect(state, composition, iterations, fps) {
        if (composition == null) {
            state.clearFrame()
            state.publishProgress(0f, force = true)
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) {
                state.decoder.open(composition.source, loop = decoderLoop, fpsOverride = fps)
            }
            state.syncPlaying(isPlaying)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onError?.invoke(t) ?: throw t
            return@LaunchedEffect
        }

        val total = composition.config.totalFrames.coerceAtLeast(1)
        var index = 0
        var playCount = 0
        while (isActive) {
            try {
                when (val advance = state.decoder.advancePresentedFrame(surfaceMode)) {
                    VapFrameAdvance.Ended -> {
                        state.publishProgress(1f, force = true)
                        onCompleted?.invoke()
                        break
                    }

                    is VapFrameAdvance.Bitmap -> state.present(advance.frame)

                    VapFrameAdvance.SurfacePresented -> Unit
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
    }

    LaunchedEffect(state, isPlaying) {
        state.syncPlaying(isPlaying)
    }

    return state
}
