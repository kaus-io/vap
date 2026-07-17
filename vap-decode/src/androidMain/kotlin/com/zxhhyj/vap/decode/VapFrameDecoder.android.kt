@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

public actual class VapFrameDecoder actual constructor() {
    private data class PendingWindowSurface(
        val surface: Surface,
        val width: Int,
        val height: Int,
    )

    private var config: VapConfig? = null
    private var tempFile: File? = null
    private var loop: Boolean = false

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var codecAsync: VapMediaCodecAsync? = null
    private var glPipeline: VapOffscreenGlPipeline? = null

    private var presentedTicks =
        Channel<Unit>(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
    private val inputDone = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val pendingWindowSurface = AtomicReference<PendingWindowSurface?>(null)
    private val pendingSwapEnabled = AtomicReference<Boolean?>(null)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var decodeJob: Job? = null

    private var glSlots = Semaphore(permits = 1)
    private val playbackGate = VapPlaybackGate()
    private var displayW = 0
    private var displayH = 0
    private var targetFrameRate: Int = 0

    public fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        val pending = PendingWindowSurface(
            surface = surface,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
        pendingWindowSurface.store(pending)
        glPipeline?.attachOutputSurface(pending.surface, pending.width, pending.height)
    }

    public fun resizeOutput(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        pendingWindowSurface.load()?.let { current ->
            pendingWindowSurface.store(current.copy(width = w, height = h))
        }
        glPipeline?.resizeOutput(w, h)
    }

    /** Coroutine-friendly detach (uses [VapSharedGlRuntime.withGl]). */
    public suspend fun detachOutputSurface() {
        pendingWindowSurface.store(null)
        glPipeline?.detachOutputSurface()
    }

    /** For synchronous Surface callbacks such as [android.view.Surface] destroy. */
    public fun detachOutputSurfaceBlocking() {
        pendingWindowSurface.store(null)
        glPipeline?.detachOutputSurfaceBlocking()
    }

    public fun setSwapEnabled(enabled: Boolean) {
        setVisible(enabled)
    }

    public actual suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        withContext(Dispatchers.IO) {
            closeInternal()
            this@VapFrameDecoder.loop = loop
            playbackGate.setPlaying(true)
            playbackGate.setVisible(true)
            val filePath = when (source) {
                is VapSource.AbsolutePath -> source.path
                is VapSource.Bytes -> {
                    val file = File.createTempFile("vap_", ".mp4")
                    file.writeBytes(source.data)
                    tempFile = file
                    file.absolutePath
                }
            }
            val parsed = parseMp4File(filePath)
            config = parsed
            setTargetFrameRate(fpsOverride ?: 0)
            startPipeline(filePath, parsed)
            parsed
        }

    public actual suspend fun nextFrame(): VapPlatformFrame? {
        error("Android VAP uses WindowSurface; call awaitFramePresented()")
    }

    public suspend fun awaitFramePresented(): Boolean {
        if (config == null || stopped.load()) return false
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return false
        return withTimeoutOrNull(3_000L) {
            presentedTicks.receive()
            true
        } ?: false
    }

    public actual suspend fun releaseDecodeSession() {
        stopPipeline()
        config = null
        loop = false
    }

    public actual fun close() {
        runBlocking { closeInternal() }
    }

    public actual fun setDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx == displayW && heightPx == displayH) return
        displayW = widthPx
        displayH = heightPx
    }

    public actual fun setPlaying(playing: Boolean) {
        applyGateChange(playbackGate.setPlaying(playing))
    }

    public actual fun setTargetFrameRate(fps: Int) {
        targetFrameRate = fps.coerceAtLeast(0)
        glPipeline?.setTargetFrameRate(targetFrameRate)
        glPipeline?.resetPresentClock()
    }

    public actual fun setVisible(visible: Boolean) {
        pendingSwapEnabled.store(visible)
        glPipeline?.setSwapEnabled(visible)
        applyGateChange(playbackGate.setVisible(visible))
    }

    private fun applyGateChange(resumed: Boolean) {
        if (!playbackGate.isActive()) {
            glPipeline?.discardPendingPresent()
            drainPresentedTicks()
        }
        if (resumed) {
            glPipeline?.resetPresentClock()
        }
    }

    private suspend fun startPipeline(filePath: String, cfg: VapConfig) {
        stopPipeline()
        stopped.store(false)
        inputDone.store(false)
        glSlots = Semaphore(permits = 1)
        presentedTicks = Channel(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)

        val pipeline = VapOffscreenGlPipeline(
            config = cfg,
            presentedTicks = presentedTicks,
            stopped = stopped,
            onFrameComposited = { glSlots.release() },
        )
        check(pipeline.start()) { "Failed to start offscreen GL pipeline" }
        pipeline.setTargetFrameRate(targetFrameRate)
        glPipeline = pipeline
        applyPendingWindowOutput(pipeline)
        val surface = pipeline.codecSurface ?: error("Missing codec surface")

        val ext = MediaExtractor()
        ext.setDataSource(filePath)
        val track = selectVideoTrack(ext)
        require(track >= 0) { "No video track" }
        ext.selectTrack(track)
        val format = ext.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing mime")

        val async = VapMediaCodecAsync()
        val decoder = MediaCodec.createDecoderByType(mime)
        // Async callback must be set before configure.
        async.attach(decoder)
        decoder.configure(format, surface, null, 0)
        async.start(decoder)

        codecAsync = async
        codec = decoder
        extractor = ext

        decodeJob = scope.launch {
            pumpCodecAsync(ext, decoder, async, cfg.totalFrames)
        }
    }

    private suspend fun pumpCodecAsync(
        extractor: MediaExtractor,
        codec: MediaCodec,
        async: VapMediaCodecAsync,
        totalFrames: Int,
    ) {
        var renderedInLoop = 0
        try {
            while (coroutineContext.isActive && !stopped.load()) {
                if (!playbackGate.isActive()) {
                    playbackGate.awaitActive { stopped.load() }
                    if (stopped.load() || !coroutineContext.isActive) break
                    glPipeline?.resetPresentClock()
                    continue
                }

                val endLoop = select {
                    if (!inputDone.load()) {
                        async.onInputBuffer { index ->
                            feedInputBuffer(extractor, codec, index)
                            false
                        }
                    }
                    async.onOutputBuffer { packet ->
                        val result = handleOutputBuffer(codec, packet, renderedInLoop, totalFrames)
                        renderedInLoop = result.renderedInLoop
                        result.endLoop
                    }
                }
                if (endLoop) {
                    if (loop && !stopped.load() && coroutineContext.isActive) {
                        softRewind(extractor, codec, async)
                        renderedInLoop = 0
                        continue
                    }
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            if (!stopped.load()) throw e
        }
    }

    private fun feedInputBuffer(extractor: MediaExtractor, codec: MediaCodec, index: Int) {
        val buffer = codec.getInputBuffer(index) ?: run {
            codec.queueInputBuffer(index, 0, 0, 0L, 0)
            return
        }
        val sample = extractor.readSampleData(buffer, 0)
        if (sample < 0) {
            codec.queueInputBuffer(
                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone.store(true)
        } else {
            codec.queueInputBuffer(index, 0, sample, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private data class OutputHandleResult(
        val renderedInLoop: Int,
        val endLoop: Boolean,
    )

    private suspend fun handleOutputBuffer(
        codec: MediaCodec,
        packet: VapMediaCodecAsync.OutputBuffer,
        renderedInLoop: Int,
        totalFrames: Int,
    ): OutputHandleResult {
        val eos = packet.isEos
        val render = packet.size > 0 && !eos
        var rendered = renderedInLoop
        if (render) {
            if (!playbackGate.isActive()) {
                codec.releaseOutputBuffer(packet.index, false)
                return OutputHandleResult(rendered, endLoop = false)
            }
            var submittedToGl = false
            glSlots.acquire()
            try {
                if (stopped.load() || !coroutineContext.isActive) {
                    codec.releaseOutputBuffer(packet.index, false)
                    return OutputHandleResult(rendered, endLoop = true)
                }
                if (!playbackGate.isActive()) {
                    codec.releaseOutputBuffer(packet.index, false)
                    return OutputHandleResult(rendered, endLoop = false)
                }
                // Vsync + PTS / target FPS gate paces present (no sleep).
                codec.releaseOutputBuffer(packet.index, true)
                submittedToGl = true
                rendered++
            } finally {
                if (!submittedToGl) glSlots.release()
            }
        } else {
            codec.releaseOutputBuffer(packet.index, false)
        }

        return OutputHandleResult(rendered, endLoop = eos || rendered >= totalFrames)
    }

    private fun softRewind(
        extractor: MediaExtractor,
        codec: MediaCodec,
        async: VapMediaCodecAsync,
    ) {
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // Async mode: flush invalidates outstanding buffer indices; must start() again.
        async.clearPending()
        codec.flush()
        async.clearPending()
        async.start(codec)
        inputDone.store(false)
        glPipeline?.discardPendingPresent()
        glPipeline?.resetPresentClock()
        drainPresentedTicks()
    }

    private fun drainPresentedTicks() {
        while (presentedTicks.tryReceive().isSuccess) {
        }
    }

    private suspend fun stopPipeline() {
        stopped.store(true)
        decodeJob?.cancelAndJoin()
        decodeJob = null
        codecAsync?.close()
        codecAsync = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: IllegalStateException) {
        }
        codec = null
        extractor?.release()
        extractor = null

        drainPresentedTicks()
        presentedTicks.close()
        drainPresentedTicks()

        val pipeline = glPipeline
        glPipeline = null
        pipeline?.release()
    }

    private fun applyPendingWindowOutput(pipeline: VapOffscreenGlPipeline) {
        pendingSwapEnabled.load()?.let(pipeline::setSwapEnabled)
        pendingWindowSurface.load()?.let { pending ->
            pipeline.attachOutputSurface(pending.surface, pending.width, pending.height)
        }
    }

    private suspend fun closeInternal() {
        stopPipeline()
        config = null
        loop = false

        tempFile?.delete()
        tempFile = null
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }
}
