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
    private var outputMode: VapGlOutputMode = VapGlOutputMode.HardwareBuffer

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var glPipeline: VapOffscreenGlPipeline? = null

    private var frameChannel =
        Channel<VapGlFrame>(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
    private var presentedTicks =
        Channel<Unit>(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
    private val inputDone = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)


    private val pendingWindowSurface = AtomicReference<PendingWindowSurface?>(null)
    private val pendingSwapEnabled = AtomicReference<Boolean?>(null)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var decodeJob: Job? = null

    private var glSlots = Semaphore(permits = 1)
    private val speedControl = VapSpeedControl()
    private val playbackGate = VapPlaybackGate()
    private var displayW = 0
    private var displayH = 0
    private var targetFrameRate: Int = 0


    public fun setOutputMode(mode: VapGlOutputMode) {
        outputMode = mode
    }

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

    public fun detachOutputSurface() {
        pendingWindowSurface.store(null)
        glPipeline?.detachOutputSurface()
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
            applyDisplaySize(parsed, displayW, displayH)
            parsed
        }

    public actual suspend fun nextFrame(): VapPlatformFrame? {
        if (outputMode == VapGlOutputMode.WindowSurface) {
            error("nextFrame() is not used in WindowSurface mode; call awaitFramePresented()")
        }
        if (config == null || stopped.load()) return null
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return null
        val frame = withTimeoutOrNull(3_000L) { frameChannel.receive() } ?: return null
        return VapPlatformFrame.fromGlFrame(frame)
    }


    public suspend fun awaitFramePresented(): Boolean {
        if (outputMode != VapGlOutputMode.WindowSurface) {
            error("awaitFramePresented() requires WindowSurface output mode")
        }
        if (config == null || stopped.load()) return false
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return false
        return withTimeoutOrNull(3_000L) {
            presentedTicks.receive()
            true
        } ?: false
    }

    public actual fun releaseDecodeSession() {
        runBlocking {
            stopPipeline()
            config = null
            loop = false
        }
    }

    public actual fun close() {
        runBlocking { closeInternal() }
    }

    public actual fun setDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx == displayW && heightPx == displayH) return
        displayW = widthPx
        displayH = heightPx
        val cfg = config ?: return
        applyDisplaySize(cfg, widthPx, heightPx)
    }

    public actual fun setPlaying(playing: Boolean) {
        applyGateChange(playbackGate.setPlaying(playing))
    }

    public actual fun setTargetFrameRate(fps: Int) {
        targetFrameRate = fps.coerceAtLeast(0)
        speedControl.setFixedPlaybackRate(targetFrameRate)
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
            drainFrameChannel()
            drainPresentedTicks()
        }
        if (resumed) {
            speedControl.reset()
            glPipeline?.resetPresentClock()
        }
    }

    private fun applyDisplaySize(cfg: VapConfig, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (outputMode == VapGlOutputMode.WindowSurface) {

            return
        }
        val fitted = VapOutputSize.fit(cfg.width, cfg.height, widthPx, heightPx)
        glPipeline?.setTargetOutputSize(fitted.width, fitted.height)
    }

    private suspend fun startPipeline(filePath: String, cfg: VapConfig) {
        stopPipeline()
        stopped.store(false)
        inputDone.store(false)
        glSlots = Semaphore(permits = 1)
        frameChannel = Channel(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
        presentedTicks = Channel(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
        speedControl.reset()

        val pipeline = VapOffscreenGlPipeline(
            config = cfg,
            outputMode = outputMode,
            frameChannel = frameChannel.takeIf { outputMode == VapGlOutputMode.HardwareBuffer },
            presentedTicks = presentedTicks.takeIf { outputMode == VapGlOutputMode.WindowSurface },
            stopped = stopped,
            onFrameComposited = { glSlots.release() },
            maxBufferedFrames = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY,
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

        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        extractor = ext
        decoder.configure(format, surface, null, 0)
        decoder.start()

        decodeJob = scope.launch {
            pumpCodec(ext, decoder, cfg.totalFrames)
        }
    }

    private suspend fun pumpCodec(extractor: MediaExtractor, codec: MediaCodec, totalFrames: Int) {
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L
        var renderedInLoop = 0
        try {
            while (coroutineContext.isActive && !stopped.load()) {
                if (!playbackGate.isActive()) {
                    playbackGate.awaitActive { stopped.load() }
                    if (stopped.load() || !coroutineContext.isActive) break
                    speedControl.reset()
                    glPipeline?.resetPresentClock()
                    continue
                }

                if (!inputDone.load()) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val sample = extractor.readSampleData(buffer, 0)
                        if (sample < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone.store(true)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sample, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0 && !eos
                    if (render) {
                        if (!playbackGate.isActive()) {
                            codec.releaseOutputBuffer(outIndex, false)
                            continue
                        }
                        var submittedToGl = false
                        glSlots.acquire()
                        try {
                            if (stopped.load() || !coroutineContext.isActive) {
                                codec.releaseOutputBuffer(outIndex, false)
                                break
                            }
                            if (!playbackGate.isActive()) {
                                codec.releaseOutputBuffer(outIndex, false)
                                continue
                            }
                            // WindowSurface: vsync + PTS gate paces present (no sleep).
                            // HardwareBuffer: keep sleep-based realtime control.
                            if (outputMode != VapGlOutputMode.WindowSurface) {
                                speedControl.preRender(info.presentationTimeUs)
                            }
                            codec.releaseOutputBuffer(outIndex, true)
                            submittedToGl = true
                            renderedInLoop++
                        } finally {
                            if (!submittedToGl) glSlots.release()
                        }
                    } else {
                        codec.releaseOutputBuffer(outIndex, false)
                    }

                    if (eos || renderedInLoop >= totalFrames) {
                        if (loop && !stopped.load() && coroutineContext.isActive) {
                            softRewind(extractor, codec)
                            renderedInLoop = 0
                            continue
                        }
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            if (!stopped.load()) throw e
        }
    }

    private fun softRewind(extractor: MediaExtractor, codec: MediaCodec) {
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone.store(false)
        speedControl.reset()
        glPipeline?.discardPendingPresent()
        glPipeline?.resetPresentClock()
        drainFrameChannel()
        drainPresentedTicks()
    }

    private fun drainFrameChannel() {
        while (true) {
            val frame = frameChannel.tryReceive().getOrNull() ?: break
            frame.release()
        }
    }

    private fun drainPresentedTicks() {
        while (presentedTicks.tryReceive().isSuccess) {

        }
    }

    private suspend fun stopPipeline() {
        stopped.store(true)
        decodeJob?.cancelAndJoin()
        decodeJob = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: IllegalStateException) {
        }
        codec = null
        extractor?.release()
        extractor = null

        drainFrameChannel()
        frameChannel.close()
        drainFrameChannel()
        drainPresentedTicks()
        presentedTicks.close()
        drainPresentedTicks()

        val pipeline = glPipeline
        glPipeline = null
        pipeline?.release()
    }

    private fun applyPendingWindowOutput(pipeline: VapOffscreenGlPipeline) {
        if (outputMode != VapGlOutputMode.WindowSurface) return
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
