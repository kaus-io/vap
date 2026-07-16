@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
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
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

public actual class VapFrameDecoder actual constructor() {
    private var config: VapConfig? = null
    private var tempFile: File? = null
    private var loop: Boolean = false

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var glPipeline: VapOffscreenGlPipeline? = null

    private var frameChannel =
        Channel<VapGlFrame>(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
    private val inputDone = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var decodeJob: Job? = null

    private var glSlots = Semaphore(permits = 1)
    private val speedControl = VapSpeedControl()
    private val playbackGate = VapPlaybackGate()
    private var displayW = 0
    private var displayH = 0

    public actual suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        withContext(Dispatchers.IO) {
            closeInternal()
            this@VapFrameDecoder.loop = loop
            playbackGate.setPlaying(true)
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
            speedControl.setFixedPlaybackRate(fpsOverride ?: 0)
            startPipeline(filePath, parsed)
            applyDisplaySize(parsed, displayW, displayH)
            parsed
        }

    public actual suspend fun nextFrame(): VapPlatformFrame? {
        if (config == null || stopped.load()) return null
        playbackGate.awaitPlaying { stopped.load() }
        if (config == null || stopped.load()) return null
        val frame = withTimeoutOrNull(3_000L) { frameChannel.receive() } ?: return null
        return VapPlatformFrame.fromGlFrame(frame)
    }

    public actual fun close() {
        closeInternal()
    }

    public actual fun setDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx == displayW && heightPx == displayH) return
        displayW = widthPx
        displayH = heightPx
        val cfg = config ?: return
        applyDisplaySize(cfg, widthPx, heightPx)
    }

    public actual fun setPlaying(playing: Boolean) {
        val resumed = playbackGate.setPlaying(playing)
        if (!playing) {
            drainFrameChannel()
        }
        if (resumed) {
            speedControl.reset()
        }
    }

    private fun applyDisplaySize(cfg: VapConfig, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val fitted = VapOutputSize.fit(cfg.width, cfg.height, widthPx, heightPx)
        glPipeline?.setTargetOutputSize(fitted.width, fitted.height)
    }

    private suspend fun startPipeline(filePath: String, cfg: VapConfig) {
        stopPipeline()
        stopped.store(false)
        inputDone.store(false)
        glSlots = Semaphore(permits = 1)
        frameChannel = Channel(capacity = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY)
        speedControl.reset()

        val pipeline = VapOffscreenGlPipeline(
            config = cfg,
            frameChannel = frameChannel,
            stopped = stopped,
            onFrameComposited = { glSlots.release() },
            maxBufferedFrames = VapOffscreenGlPipeline.FRAME_CHANNEL_CAPACITY,
        )
        check(pipeline.start()) { "Failed to start offscreen GL pipeline" }
        glPipeline = pipeline
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
                if (!playbackGate.isPlaying()) {
                    playbackGate.awaitPlaying { stopped.load() }
                    if (stopped.load() || !coroutineContext.isActive) break
                    speedControl.reset()
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
                        if (!playbackGate.isPlaying()) {
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
                            if (!playbackGate.isPlaying()) {
                                codec.releaseOutputBuffer(outIndex, false)
                                continue
                            }
                            speedControl.preRender(info.presentationTimeUs)
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
        drainFrameChannel()
    }

    private fun drainFrameChannel() {
        while (true) {
            val frame = frameChannel.tryReceive().getOrNull() ?: break
            frame.release()
        }
    }

    private fun stopPipeline() {
        stopped.store(true)
        runBlocking {
            decodeJob?.cancelAndJoin()
            decodeJob = null
        }
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

        val pipeline = glPipeline
        glPipeline = null
        if (pipeline != null) {
            Handler(Looper.getMainLooper()).post { pipeline.release() }
        }
    }

    private fun closeInternal() {
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
