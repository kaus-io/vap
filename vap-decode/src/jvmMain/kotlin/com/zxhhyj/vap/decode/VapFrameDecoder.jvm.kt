@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

public actual class VapFrameDecoder actual constructor() {
    private var config: VapConfig? = null
    private var tempFile: File? = null
    private var loop: Boolean = false

    private var formatCtx: AVFormatContext? = null
    private var codecCtx: AVCodecContext? = null
    private var swsCtx: SwsContext? = null
    private var rawFrame: AVFrame? = null
    private var rgbFrame: AVFrame? = null
    private var packet: AVPacket? = null
    private var rgbBuffer: BytePointer? = null
    private var videoStreamIndex: Int = -1
    private var timeBaseSec: Double = 0.0

    private var frameChannel = Channel<VapPlatformFrame>(capacity = 2)
    private val stopped = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var decodeJob: Job? = null
    private val speedControl = VapSpeedControl()
    private val playbackGate = VapPlaybackGate()
    private var videoW = 0
    private var videoH = 0
    private var rgbLineSize = 0
    private val outW = AtomicInt(1)
    private val outH = AtomicInt(1)

    public actual suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        withContext(Dispatchers.IO) {
            closeInternal()
            this@VapFrameDecoder.loop = loop
            playbackGate.setPlaying(true)
            val path = when (source) {
                is VapSource.AbsolutePath -> source.path
                is VapSource.Bytes -> {
                    val file = File.createTempFile("vap_", ".mp4")
                    file.writeBytes(source.data)
                    tempFile = file
                    file.absolutePath
                }
            }
            val parsed = parseMp4File(path)
            config = parsed
            outW.store(parsed.width.coerceAtLeast(1))
            outH.store(parsed.height.coerceAtLeast(1))
            speedControl.setFixedPlaybackRate(fpsOverride ?: 0)
            openFfmpeg(path)
            startDecodeLoop(parsed)
            parsed
        }

    public actual suspend fun nextFrame(): VapPlatformFrame? {
        if (config == null || stopped.load()) return null
        playbackGate.awaitPlaying { stopped.load() }
        if (config == null || stopped.load()) return null
        return withTimeoutOrNull(3_000L) { frameChannel.receive() }
    }

    public actual fun setDisplaySize(widthPx: Int, heightPx: Int) {
        val cfg = config ?: return
        if (widthPx <= 0 || heightPx <= 0) return
        val fitted = VapOutputSize.fit(cfg.width, cfg.height, widthPx, heightPx)
        if (fitted.width == outW.load() && fitted.height == outH.load()) return
        outW.store(fitted.width)
        outH.store(fitted.height)
    }

    public actual fun setPlaying(playing: Boolean) {
        val resumed = playbackGate.setPlaying(playing)
        if (!playing) {
            while (true) {
                frameChannel.tryReceive().getOrNull()?.release() ?: break
            }
        }
        if (resumed) {
            speedControl.reset()
        }
    }

    public actual fun close() {
        closeInternal()
    }

    private fun openFfmpeg(path: String) {
        val fmt = AVFormatContext(null)
        if (avformat_open_input(fmt, path, null, null) < 0) {
            error("avformat_open_input failed: $path")
        }
        if (avformat_find_stream_info(fmt, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            avformat_close_input(fmt)
            error("avformat_find_stream_info failed")
        }
        val streamIndex = av_find_best_stream(
            fmt,
            AVMEDIA_TYPE_VIDEO,
            -1,
            -1,
            null as org.bytedeco.ffmpeg.avcodec.AVCodec?,
            0
        )
        if (streamIndex < 0) {
            avformat_close_input(fmt)
            error("no video stream")
        }
        val stream = fmt.streams(streamIndex)
        val codec = avcodec_find_decoder(stream.codecpar().codec_id())
            ?: run {
                avformat_close_input(fmt)
                error("decoder not found")
            }
        val ctx = avcodec_alloc_context3(codec)
        avcodec_parameters_to_context(ctx, stream.codecpar())
        if (avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            avformat_close_input(fmt)
            error("avcodec_open2 failed")
        }

        videoW = ctx.width()
        videoH = ctx.height()
        val sws = sws_getContext(
            videoW, videoH, ctx.pix_fmt(),
            videoW, videoH, AV_PIX_FMT_RGB24,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: run {
            avcodec_free_context(ctx)
            avformat_close_input(fmt)
            error("sws_getContext failed")
        }

        val numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGB24, videoW, videoH, 1)
        val buffer = BytePointer(av_malloc(numBytes.toLong()))
        val rgb = av_frame_alloc()
        av_image_fill_arrays(
            rgb.data(),
            rgb.linesize(),
            buffer,
            AV_PIX_FMT_RGB24,
            videoW,
            videoH,
            1
        )

        formatCtx = fmt
        codecCtx = ctx
        swsCtx = sws
        rawFrame = av_frame_alloc()
        rgbFrame = rgb
        rgbBuffer = buffer
        packet = av_packet_alloc()
        videoStreamIndex = streamIndex
        timeBaseSec = av_q2d(stream.time_base())
        rgbLineSize = rgb.linesize(0)
    }

    private fun startDecodeLoop(cfg: VapConfig) {
        stopped.store(false)
        speedControl.reset()
        frameChannel = Channel(capacity = 2)
        decodeJob = scope.launch {
            pump(cfg)
        }
    }

    private suspend fun pump(cfg: VapConfig) {
        val fmt = formatCtx ?: return
        val ctx = codecCtx ?: return
        val sws = swsCtx ?: return
        val raw = rawFrame ?: return
        val rgb = rgbFrame ?: return
        val pkt = packet ?: return
        var renderedInLoop = 0

        while (coroutineContext.isActive && !stopped.load()) {
            val read = av_read_frame(fmt, pkt)
            if (read < 0) {
                if (loop && !stopped.load() && coroutineContext.isActive) {
                    softRewind()
                    renderedInLoop = 0
                    continue
                }
                break
            }
            try {
                if (pkt.stream_index() != videoStreamIndex) continue
                if (avcodec_send_packet(ctx, pkt) < 0) continue
                while (coroutineContext.isActive && !stopped.load()) {
                    val rec = avcodec_receive_frame(ctx, raw)
                    if (rec == AVERROR_EAGAIN() || rec == AVERROR_EOF()) break
                    if (rec < 0) break

                    sws_scale(
                        sws,
                        raw.data(),
                        raw.linesize(),
                        0,
                        videoH,
                        rgb.data(),
                        rgb.linesize(),
                    )

                    val ptsUs = if (raw.best_effort_timestamp() != AV_NOPTS_VALUE) {
                        (raw.best_effort_timestamp() * timeBaseSec * 1_000_000.0).toLong()
                    } else {
                        renderedInLoop * 33_333L
                    }
                    playbackGate.awaitPlaying { stopped.load() }
                    if (stopped.load() || !coroutineContext.isActive) return
                    speedControl.preRender(ptsUs)

                    val image = compositeToPlatformFrame(cfg, rgb.data(0), rgbLineSize)
                    while (
                        !frameChannel.trySend(image).isSuccess &&
                        coroutineContext.isActive &&
                        !stopped.load()
                    ) {
                        playbackGate.awaitPlaying { stopped.load() }
                        if (stopped.load() || !coroutineContext.isActive) return
                        frameChannel.tryReceive()
                    }
                    renderedInLoop++
                    if (!loop && renderedInLoop >= cfg.totalFrames) {
                        stopped.store(true)
                        return
                    }
                }
            } finally {
                av_packet_unref(pkt)
            }
        }
    }

    private fun softRewind() {
        val fmt = formatCtx ?: return
        val ctx = codecCtx ?: return
        av_seek_frame(fmt, videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(ctx)
        speedControl.reset()
        while (true) {
            frameChannel.tryReceive().getOrNull()?.release() ?: break
        }
    }

    private fun compositeToPlatformFrame(
        cfg: VapConfig,
        rgbPtr: BytePointer,
        srcStride: Int
    ): VapPlatformFrame {
        val destW = outW.load().coerceAtLeast(1)
        val destH = outH.load().coerceAtLeast(1)
        val rgb = cfg.rgbFrame
        val alpha = cfg.alphaFrame
        val bytes = ByteArray(destW * destH * 4)
        var di = 0
        for (y in 0 until destH) {
            val syRgb = rgb.y + y * rgb.h / destH
            val syA = alpha.y + y * alpha.h / destH
            for (x in 0 until destW) {
                val sxRgb = rgb.x + x * rgb.w / destW
                val sxA = alpha.x + x * alpha.w / destW
                val rgbOff = syRgb * srcStride + sxRgb * 3
                val aOff = syA * srcStride + sxA * 3
                val r = rgbPtr.get(rgbOff.toLong()).toInt() and 0xff
                val g = rgbPtr.get(rgbOff + 1L).toInt() and 0xff
                val b = rgbPtr.get(rgbOff + 2L).toInt() and 0xff
                val a = rgbPtr.get(aOff.toLong()).toInt() and 0xff
                bytes[di++] = r.toByte()
                bytes[di++] = g.toByte()
                bytes[di++] = b.toByte()
                bytes[di++] = a.toByte()
            }
        }
        val image = Image.makeRaster(
            ImageInfo(destW, destH, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
            bytes,
            destW * 4,
        )
        return VapPlatformFrame(image)
    }

    private fun closeInternal() {
        stopped.store(true)
        runBlocking {
            decodeJob?.cancelAndJoin()
            decodeJob = null
        }
        frameChannel.close()
        while (true) {
            frameChannel.tryReceive().getOrNull()?.release() ?: break
        }

        swsCtx?.let { sws_freeContext(it) }
        swsCtx = null
        packet?.let { av_packet_free(it) }
        packet = null
        rawFrame?.let { av_frame_free(it) }
        rawFrame = null
        rgbFrame?.let { av_frame_free(it) }
        rgbFrame = null
        rgbBuffer?.let { av_free(it) }
        rgbBuffer = null
        codecCtx?.let { avcodec_free_context(it) }
        codecCtx = null
        formatCtx?.let { avformat_close_input(it) }
        formatCtx = null

        config = null
        loop = false
        tempFile?.delete()
        tempFile = null
    }
}
