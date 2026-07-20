@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

public class JvmVapFrameDecoder : VapFrameDecoder {
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
    /** Serializes [open] / [releaseDecodeSession] / [close] (same race class as Android). / 将 [open] / [releaseDecodeSession] / [close] 串行化（与 Android 同一类竞争）。 */
    private val sessionMutex = Mutex()
    private val speedControl = VapSpeedControl()
    private val playbackGate = VapPlaybackGate()
    private var videoW = 0
    private var videoH = 0
    private var rgbLineSize = 0
    private val outW = AtomicInt(1)
    private val outH = AtomicInt(1)

    override suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                closeInternalLocked()
                this@JvmVapFrameDecoder.loop = loop
                playbackGate.setPlaying(true)
                playbackGate.setVisible(true)
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
                setTargetFrameRate(fpsOverride ?: 0)
                openFfmpeg(path)
                startDecodeLoop(parsed)
                parsed
            }
        }

    override suspend fun nextFrame(): VapPlatformFrame? {
        if (config == null || stopped.load()) return null
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return null
        return withTimeoutOrNull(3_000L) { frameChannel.receive() }
    }

    override fun configurePresentMode(mode: VapPresentMode) {
        // JVM decoder only supports bitmap output; mode is ignored.
        // JVM 解码器仅支持 bitmap 输出；该参数被忽略。
    }

    override fun configureGpuBackend(backend: VapGpuBackend) {
        // JVM decoder has no GPU backend selection; ignored.
        // JVM 解码器无 GPU 后端选择；忽略。
    }

    override suspend fun advancePresentedFrame(surfaceMode: Boolean): VapFrameAdvance {
        val frame = nextFrame() ?: return VapFrameAdvance.Ended
        return VapFrameAdvance.Bitmap(frame)
    }

    override fun setDisplaySize(widthPx: Int, heightPx: Int) {
        val cfg = config ?: return
        if (widthPx <= 0 || heightPx <= 0) return
        val fitted = VapOutputSize.fit(cfg.width, cfg.height, widthPx, heightPx)
        if (fitted.width == outW.load() && fitted.height == outH.load()) return
        outW.store(fitted.width)
        outH.store(fitted.height)
    }

    override fun setPlaying(playing: Boolean) {
        applyGateChange(playbackGate.setPlaying(playing))
    }

    override fun setTargetFrameRate(fps: Int) {
        speedControl.setFixedPlaybackRate(fps.coerceAtLeast(0))
        if (fps > 0) speedControl.reset()
    }

    override fun setVisible(visible: Boolean) {
        applyGateChange(playbackGate.setVisible(visible))
    }

    private fun applyGateChange(resumed: Boolean) {
        if (!playbackGate.isActive()) {
            while (true) {
                frameChannel.tryReceive().getOrNull()?.release() ?: break
            }
        }
        if (resumed) {
            speedControl.reset()
        }
    }

    override suspend fun releaseDecodeSession() {
        sessionMutex.withLock { releaseDecodeSessionLocked() }
    }

    override fun close() {
        runBlocking {
            sessionMutex.withLock {
                releaseDecodeSessionLocked()
                tempFile?.delete()
                tempFile = null
            }
        }
    }

    private fun openFfmpeg(path: String) {
        val fmt = AVFormatContext(null)
        // Each failure path must release what it acquired: avformat_close_input also
        // frees the AVFormatContext, but codec-context errors below need an explicit
        // avcodec_free_context because the context is independent of format lifetime.
        // 每个失败分支都必须释放已申请的资源：avformat_close_input 同时会释放
        // AVFormatContext，但 codec context 的错误需要显式 avcodec_free_context，
        // 因为其生命周期与 format 相互独立。
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
        // av_image_fill_arrays binds the externally malloc'd buffer to rgb's planes
        // without copying; the buffer must outlive rgb (released in releaseDecodeSessionLocked).
        // av_image_fill_arrays 将外部 malloc 的 buffer 绑定到 rgb 的 planes，并不拷贝；
        // 该 buffer 的生命周期必须长于 rgb（在 releaseDecodeSessionLocked 中释放）。
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
                    // EAGAIN: needs more input; EOF: codec fully drained. Both are normal
                    // backpressure signals, not errors.
                    // EAGAIN：需要更多输入；EOF：codec 完全排空。两者都是正常背压信号，不是错误。
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

                    // PTS fallback: some streams lack proper timestamps; approximate via
                    // 30 fps so speedControl / preRender stays bounded.
                    // PTS 回退：部分流缺少正确时间戳，按 30 fps 近似以便 speedControl / preRender
                    // 保持在合理范围。
                    val ptsUs = if (raw.best_effort_timestamp() != AV_NOPTS_VALUE) {
                        (raw.best_effort_timestamp() * timeBaseSec * 1_000_000.0).toLong()
                    } else {
                        renderedInLoop * 33_333L
                    }
                    playbackGate.awaitActive { stopped.load() }
                    if (stopped.load() || !coroutineContext.isActive) return
                    speedControl.preRender(ptsUs)

                    val image = compositeToPlatformFrame(cfg, rgb.data(0), rgbLineSize)
                    // Backpressure: drop the OLDEST queued frame to keep latency bounded.
                    // tryReceive returns null on empty, so the while exits via trySend success.
                    // 背压处理：丢弃队列中最早的帧以将延迟控制在有界范围。
                    // tryReceive 在队列为空时返回 null，因此循环最终会通过 trySend 成功而退出。
                    while (
                        !frameChannel.trySend(image).isSuccess &&
                        coroutineContext.isActive &&
                        !stopped.load()
                    ) {
                        playbackGate.awaitActive { stopped.load() }
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
                // Always unref the packet even on continue / error so the next av_read_frame
                // gets a fresh AVPacket; otherwise the same packet ref leaks per packet.
                // 即使 continue / 出错也要 unref，以便下一次 av_read_frame 拿到干净的 AVPacket；
                // 否则同一个包的引用会按包数量累积泄漏。
                av_packet_unref(pkt)
            }
        }
    }

    private fun softRewind() {
        val fmt = formatCtx ?: return
        val ctx = codecCtx ?: return
        // BACKWARD seek flag: let ffmpeg snap to the previous sync sample so we don't
        // decode a partial GOP; flush_buffers drops any frames already buffered.
        // BACKWARD 标志：让 ffmpeg 对齐到上一个 sync sample，避免解码不完整的 GOP；
        // flush_buffers 会丢弃已经缓冲的所有帧。
        av_seek_frame(fmt, videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(ctx)
        speedControl.reset()
        // Drop queued frames so their Skia images close before the next decode loop reads.
        // 丢弃已入队的帧，使 Skia image 在下一次解码循环读取前就被关闭。
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
        // Nearest-neighbor sub-frame sampling: the VAP container already encodes the
        // sprite's logical position/size; for the desktop decoder (preview / tooling) the
        // visual fidelity gain of bilinear resampling is not worth the per-pixel loop cost.
        // 最近邻子帧采样：VAP 容器已编码 sprite 的逻辑位置/大小；对桌面解码器（预览 / 工具链）
        // 而言，双线性重采样的视觉收益不值得按像素循环的开销。
        var di = 0
        for (y in 0 until destH) {
            val syRgb = rgb.y + y * rgb.h / destH
            val syA = alpha.y + y * alpha.h / destH
            for (x in 0 until destW) {
                val sxRgb = rgb.x + x * rgb.w / destW
                val sxA = alpha.x + x * alpha.w / destW
                // rgb pointer carries RGB24 (3 bytes/sample); the alpha channel lives at the
                // same x/y in the alpha sub-frame, not interleaved. Opaque VAP clips encode no
                // alpha sub-frame at all (aFrame == [0,0,0,0]), so any read from that region
                // would sample unrelated RGB data and produce a fully-transparent image — force
                // alpha=255 instead.
                // rgb 指针承载 RGB24（每像素 3 字节）；alpha 通道位于 alpha 子帧的同坐标，
                // 不与 RGB 交错。不透明 VAP 完全不编码 alpha 子帧（aFrame == [0,0,0,0]），
                // 此时读取该区域会采到无关的 RGB 数据，导致整张图变透明 —— 强制写 255。
                val rgbOff = syRgb * srcStride + sxRgb * 3
                val r = rgbPtr.get(rgbOff.toLong()).toInt() and 0xff
                val g = rgbPtr.get(rgbOff + 1L).toInt() and 0xff
                val b = rgbPtr.get(rgbOff + 2L).toInt() and 0xff
                val a = if (cfg.hasAlpha) {
                    val aOff = syA * srcStride + sxA * 3
                    rgbPtr.get(aOff.toLong()).toInt() and 0xff
                } else {
                    0xff
                }
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
        return JvmVapPlatformFrame(image)
    }

    /** Caller must hold [sessionMutex]. / 调用方必须持有 [sessionMutex]。 */
    private suspend fun releaseDecodeSessionLocked() {
        stopped.store(true)
        decodeJob?.cancelAndJoin()
        decodeJob = null
        frameChannel.close()
        // Drain remaining frames after close: their Skia images must release before the
        // buffer / frame owners below are freed, otherwise Skia holds dangling pointers.
        // close 之后再排空剩余帧：必须在释放 buffer / frame 持有者之前释放其 Skia image，
        // 否则 Skia 会持有悬空指针。
        while (true) {
            frameChannel.tryReceive().getOrNull()?.release() ?: break
        }

        // Release order: dependents first (sws_ctx, packet, frames, buffer) then codec context,
        // then format context (which closes the input file). Reversing this leaks native state.
        // 释放顺序：先释放被依赖者（sws_ctx / packet / frame / buffer），再 codec context，
        // 最后 format context（关闭输入文件）。反过来会导致原生状态泄漏。
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
    }

    /** Caller must hold [sessionMutex]. / 调用方必须持有 [sessionMutex]。 */
    private suspend fun closeInternalLocked() {
        releaseDecodeSessionLocked()
        tempFile?.delete()
        tempFile = null
    }
}
