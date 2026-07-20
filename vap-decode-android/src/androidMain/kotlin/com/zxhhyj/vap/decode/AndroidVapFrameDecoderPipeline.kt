package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.zxhhyj.vap.player.VapConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. / 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。 */
internal suspend fun AndroidVapFrameDecoder.startPipelineLocked(filePath: String, cfg: VapConfig) {
    stopPipelineLocked()
    stopped.store(false)
    inputDone.store(false)
    queuedOutputs.store(0)
    glSlots = kotlinx.coroutines.sync.Semaphore(permits = 1)
    frameChannel = kotlinx.coroutines.channels.Channel(capacity = 2)
    presentedTicks = kotlinx.coroutines.channels.Channel(capacity = 2)
    speedControl.reset()

    val resolvedGpu = VapGpu.resolve(gpuBackend)
    gpuBackend = resolvedGpu

    if (!cfg.hasAlpha) {
        // Opaque content: skip Vulkan/GL entirely. MediaCodec writes straight to the
        // user's Surface via BufferQueue; SF/HWC composites on vsync.
        // MediaCodec is NOT created here — some drivers (e.g. qti HEVC) reject
        // setOutputSurface() when configured with a placeholder Surface. Codec
        // creation is deferred to [AndroidVapFrameDecoder.ensureDirectCodecLocked]
        // which runs from [attachOutputSurface] with the real user Surface.
        // 不透明内容：完全跳过 Vulkan/GL。MediaCodec 直接写到用户 Surface，
        // 经 BufferQueue 由 SF/HWC 在 vsync 上合成。
        // 此处不创建 MediaCodec —— 某些驱动（如 qti HEVC）在配置占位 Surface 后
        // 会拒绝 setOutputSurface()。codec 的创建被推迟到
        // [AndroidVapFrameDecoder.ensureDirectCodecLocked]，由 [attachOutputSurface]
        // 在拿到真实用户 Surface 时调用。
        val direct = VapDirectPipeline()
        directPipeline = direct

    val ext = MediaExtractor()
    ext.setDataSource(filePath)
    val track = selectVideoTrack(ext)
    require(track >= 0) { "No video track" }
    ext.selectTrack(track)
    val fmt = ext.getTrackFormat(track)
    pendingFormat = fmt
    pendingMime = fmt.getString(MediaFormat.KEY_MIME) ?: error("missing mime")
    pendingTotalFrames = cfg.totalFrames
    extractor = ext
    return
}

    val surface: Surface = if (resolvedGpu == VapGpuBackend.Vulkan) {
        val session = VapVulkanSession(
            config = cfg,
            presentedTicks = presentedTicks.takeIf { outputMode == VapGlOutputMode.WindowSurface },
            stopped = stopped,
            onFrameComposited = { glSlots.release() },
        )
        check(session.start()) { "Failed to start Vulkan 1.1 pipeline" }
        session.setTargetFrameRate(targetFrameRate)
        vkSession = session
        applyPendingWindowOutputVk(session)
        session.codecSurface ?: error("Missing Vulkan codec ImageReader surface")
    } else {
        error("Unsupported GPU backend $resolvedGpu")
    }
    check(surface.isValid) { "Codec surface released before MediaCodec.configure" }

    val ext = MediaExtractor()
    ext.setDataSource(filePath)
    val track = selectVideoTrack(ext)
    require(track >= 0) { "No video track" }
    ext.selectTrack(track)
    val format = ext.getTrackFormat(track)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing mime")

    val async = VapMediaCodecAsync()
    val decoder = MediaCodec.createDecoderByType(mime)
    // Input feeding runs inline on the shared codec-callback thread (no pump dispatch);
    // outputs try an inline fast path first and only fall back to the pump queue.
    // Capture `async` directly: a stale codec callback must never enqueue into a
    // successor pipeline's queue during open->close->open races.
    // Input 喂入在共享 codec 回调线程上内联运行（不经泵分派）；output 先尝试内联
    // 快速路径，只有失败才回退到泵队列。直接捕获 `async`：在 open->close->open 竞争中，
    // 陈旧的 codec 回调绝不能入队到后继管线的队列里。
    async.inputFeeder = { c, index -> feedInputInline(ext, c, index) }
    async.outputHandler = { c, packet -> routeOutputBuffer(async, c, packet) }
    // Async callback must be set before configure.
    // 异步回调必须在 configure 之前设置。
    async.attach(decoder)
    inputGeneration.addAndFetch(1)
    applyLowLatency(format)
    decoder.configure(format, surface, null, 0)
    async.start(decoder)

    codecAsync = async
    codec = decoder
    extractor = ext

    decodeJob = scope.launch {
        try {
            pumpCodecAsync(ext, decoder, async, cfg.totalFrames)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never let decode-job failures crash the process (Compose remount / surface races).
            // 永远不要让 decode-job 失败搞挂进程（Compose 重组 / surface 竞争场景）。
            android.util.Log.w("VapDecode", "decode pump terminated", e)
        }
    }
}

/**
 * Direct mode only: create the MediaCodec now that the real user Surface is attached.
 * Some drivers reject `setOutputSurface` after `configure`, so we configure once with
 * the destination surface and skip the swap entirely.
 * 仅 Direct 模式：在真实用户 Surface 已挂上时再创建 MediaCodec。某些驱动在
 * `configure` 之后会拒绝 `setOutputSurface`，因此直接用目标 surface 一次配置，
 * 完全跳过 swap。
 *
 * Caller must hold [AndroidVapFrameDecoder.sessionMutex]. No-op when the codec is
 * already up or when not in direct mode.
 * 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。codec 已经在运行或非
 * Direct 模式时为 no-op。
 */
internal fun AndroidVapFrameDecoder.ensureDirectCodecLocked(userSurface: Surface) {
    if (codec != null) return
    val direct = directPipeline ?: return
    val ext = extractor ?: return
    val format = pendingFormat ?: return
    val mime = pendingMime ?: return
    val totalFrames = pendingTotalFrames
    if (!userSurface.isValid) return
    if (stopped.load()) return

    val async = VapMediaCodecAsync()
    val decoder = MediaCodec.createDecoderByType(mime)
    async.inputFeeder = { c, index -> feedInputInline(ext, c, index) }
    async.outputHandler = { c, packet -> routeOutputBuffer(async, c, packet) }
    async.attach(decoder)
    inputGeneration.addAndFetch(1)
    applyLowLatency(format)
    decoder.configure(format, userSurface, null, 0)
    async.start(decoder)

    codecAsync = async
    codec = decoder

    decodeJob = scope.launch {
        try {
            pumpCodecAsync(ext, decoder, async, totalFrames)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never let decode-job failures crash the process (Compose remount / surface races).
            // 永远不要让 decode-job 失败搞挂进程（Compose 重组 / surface 竞争场景）。
            android.util.Log.w("VapDecode", "decode pump terminated", e)
        }
    }
}

/** Caller must hold [AndroidVapFrameDecoder.sessionMutex]. / 调用方必须持有 [AndroidVapFrameDecoder.sessionMutex]。 */
internal suspend fun AndroidVapFrameDecoder.stopPipelineLocked() {
    stopped.store(true)
    // Drop ST listener before joining the pump so in-flight frames cannot updateTexImage
    // after MediaCodec disconnects (BufferQueue "no connected producer").
    // 在 join 泵之前先取消 SurfaceTexture 监听器，避免 MediaCodec 断开后还有
    // 在飞行的帧去 updateTexImage（BufferQueue "no connected producer"）。
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

    drainFrameChannel()
    frameChannel.close()
    drainFrameChannel()
    drainPresentedTicks()
    presentedTicks.close()
    drainPresentedTicks()

    val vk = vkSession
    vkSession = null
    vk?.release()
    val direct = directPipeline
    directPipeline = null
    direct?.release()

    pendingFormat = null
    pendingMime = null
    pendingTotalFrames = 0
}

internal fun AndroidVapFrameDecoder.applyPendingWindowOutputVk(session: VapVulkanSession) {
    if (outputMode != VapGlOutputMode.WindowSurface) return
    pendingSwapEnabled.load()?.let(session::setSwapEnabled)
    pendingWindowSurface.load()?.let { pending ->
        session.attachOutputSurface(pending.surface, pending.width, pending.height)
    }
}

/** Try to reduce decode-to-output latency. API 30+; harmless if unsupported. / 尝试降低解码到输出的延迟，API 30+；不支持时无害。 */
private fun applyLowLatency(format: MediaFormat) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
}
