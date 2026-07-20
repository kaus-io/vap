@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.runBlocking
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class PendingWindowSurface(
    val surface: Surface,
    val width: Int,
    val height: Int,
)

internal class AndroidVapFrameDecoder : VapSurfaceFrameDecoder {
    internal var config: VapConfig? = null
    internal var tempFile: File? = null
    internal var loop: Boolean = false
    internal var outputMode: VapGlOutputMode = VapGlOutputMode.WindowSurface
    internal var gpuBackend: VapGpuBackend = VapGpu.defaultBackend

    internal var extractor: MediaExtractor? = null
    internal var codec: MediaCodec? = null
    internal var codecAsync: VapMediaCodecAsync? = null
    internal var vkSession: VapVulkanSession? = null
    internal var directPipeline: VapDirectPipeline? = null

    // Direct mode: stashed at startPipelineLocked so the MediaCodec can be created
    // later with the real user Surface (avoids qti-hevc BAD_INDEX on setOutputSurface).
    // Direct 模式：在 startPipelineLocked 暂存，以便稍后用真实用户 Surface 创建 MediaCodec
    // （避免在 setOutputSurface 上出现 qti-hevc BAD_INDEX）。
    internal var pendingFormat: MediaFormat? = null
    internal var pendingMime: String? = null
    internal var pendingTotalFrames: Int = 0

    /**
     * True when the active pipeline is the MediaCodec-direct path (no GPU composite).
     * Callers in this module branch on it to skip GPU-specific gating.
     * 当活跃管线为 MediaCodec 直送路径（无 GPU 合成）时为 true。本模块调用方据此
     * 跳过 GPU 相关的门控逻辑。
     */
    internal val isDirectMode: Boolean
        get() = directPipeline != null

    internal var frameChannel =
        Channel<VapPlatformFrame>(capacity = 2)
    internal var presentedTicks =
        Channel<Unit>(capacity = 2)
    internal val inputDone = AtomicBoolean(false)
    internal val stopped = AtomicBoolean(false)
    /**
     * Incremented every time the codec is (re-)started or flushed. The inline input feeder
     * discards callbacks whose captured generation is stale, preventing use of buffer indices
     * invalidated by flush()/start() on the shared callback thread.
     * 在 codec 每次 (重)启动或 flush 时递增。内联 input 喂入器会丢弃捕获到陈旧 generation
     * 的回调，避免在共享回调线程上使用已被 flush()/start() 失效的缓冲索引。
     */
    internal val inputGeneration = AtomicInt(0)

    /** Serializes inline input feeding (codec callback thread) against softRewind seek/flush.
     * 将 codec 回调线程上的内联 input 喂入与 softRewind 的 seek/flush 操作串行化。 */
    internal val inputLock = Any()

    /**
     * Output packets queued to the decode pump (incremented on the callback thread before
     * enqueue, decremented by the pump after handling). The inline fast path only runs
     * when this reads 0, which guarantees it can never overtake a queued earlier frame.
     * 已入队到解码泵的 output 包数量（在回调线程入队前递增，泵处理完后递减）。内联
     * 快速路径仅当读到 0 时才运行，以此保证它永远不可能抢先于已入队的更早帧。
     */
    internal val queuedOutputs = AtomicInt(0)

    internal val pendingWindowSurface = AtomicReference<PendingWindowSurface?>(null)
    internal val pendingSwapEnabled = AtomicReference<Boolean?>(null)

    internal val scope = CoroutineScope(Dispatchers.IO)
    internal var decodeJob: Job? = null
    /** Serializes open / releaseDecodeSession / close against Compose effect races.
     * 将 open / releaseDecodeSession / close 与 Compose effect 的竞争串行化。 */
    internal val sessionMutex = Mutex()

    internal var glSlots = Semaphore(permits = 1)
    internal val speedControl = VapSpeedControl()
    internal val playbackGate = VapPlaybackGate()
    internal var displayW = 0
    internal var displayH = 0
    internal var targetFrameRate: Int = 0

    // Direct mode: translate source PTS (us, 0 at stream start) to wall-clock
    // System.nanoTime() base for MediaCodec.releaseOutputBuffer(index, ts).
    // Captured on the very first release; reset on loop/seek.
    // Direct 模式：把源 PTS（微秒，流起始为 0）平移到挂钟时间 System.nanoTime() 基线，
    // 供 MediaCodec.releaseOutputBuffer(index, ts) 使用。在首次 release 时捕获；
    // 在循环 / seek 时重置。
    private val directPlayStartNs = AtomicLong(-1L)
    private val directFirstPtsUs = AtomicLong(-1L)

    /** Direct mode only: source PTS (us) → wall-clock ns suitable for releaseOutputBuffer. / 仅 Direct 模式：源 PTS（us）→ 适合 releaseOutputBuffer 的挂钟时间纳秒。 */
    internal fun computeDirectReleaseTimestampNs(ptsUs: Long): Long {
        val firstPts = directFirstPtsUs.compareAndExchange(-1L, ptsUs)
        val effectiveFirstPts = if (firstPts == -1L) ptsUs else firstPts
        val startNs = directPlayStartNs.compareAndExchange(-1L, System.nanoTime())
        val effectiveStartNs = if (startNs == -1L) System.nanoTime() else startNs
        return effectiveStartNs + (ptsUs - effectiveFirstPts) * 1000L
    }

    /** Reset the direct-mode PTS clock — call after loop seek / pause-resume. / 重置 Direct 模式的 PTS 时钟 —— 在循环 seek / 暂停恢复后调用。 */
    internal fun resetDirectClock() {
        directPlayStartNs.store(-1L)
        directFirstPtsUs.store(-1L)
    }

    fun setOutputMode(mode: VapGlOutputMode) {
        outputMode = mode
    }

    /** Selects the preferred backend for the next decode session. / 为下一次解码会话选择首选后端。 */
    fun setGpuBackend(backend: VapGpuBackend) {
        gpuBackend = backend
    }

    fun gpuBackend(): VapGpuBackend = gpuBackend

    override fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        val pending = PendingWindowSurface(
            surface = surface,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
        pendingWindowSurface.store(pending)
        // Direct mode: codec isn't created yet — do it now with the real surface.
        // Direct 模式：codec 尚未创建 —— 用真实 surface 立即创建它。
        if (directPipeline != null && codec == null) {
            runBlocking { sessionMutex.withLock { ensureDirectCodecLocked(pending.surface) } }
        }
        directPipeline?.attachOutputSurface(codec ?: return, pending.surface)
        vkSession?.attachOutputSurface(pending.surface, pending.width, pending.height)
    }

    override fun resizeOutput(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        pendingWindowSurface.load()?.let { current ->
            pendingWindowSurface.store(current.copy(width = w, height = h))
        }
        directPipeline?.resizeOutput(w, h)
        vkSession?.resizeOutput(w, h)
    }

    override fun detachOutputSurface() {
        pendingWindowSurface.store(null)
        // Direct mode: if codec is up, stop it so we can recreate against a future
        // surface. releaseDecodeSession() / open() will also handle this via the
        // stop/start pipeline, so this is only needed for the live detach case.
        // Direct 模式：若 codec 还在运行则停掉，以便后续可针对新 surface 重建。
        // releaseDecodeSession() / open() 通过 stop/start 管线也会处理相同情况，
        // 因此这里只为「运行时分离」这一场景服务。
        if (directPipeline != null && codec != null) {
            runBlocking {
                sessionMutex.withLock {
                    stopDirectCodecLocked()
                }
            }
        }
        vkSession?.detachOutputSurface()
    }

    override fun configurePresentMode(mode: VapPresentMode) {
        outputMode = when (mode) {
            VapPresentMode.Bitmap -> VapGlOutputMode.HardwareBuffer
            VapPresentMode.Surface -> VapGlOutputMode.WindowSurface
        }
    }

    override fun configureGpuBackend(backend: VapGpuBackend) {
        gpuBackend = backend
    }

    override suspend fun advancePresentedFrame(surfaceMode: Boolean): VapFrameAdvance {
        return if (surfaceMode) {
            if (awaitFramePresented()) VapFrameAdvance.SurfacePresented else VapFrameAdvance.Ended
        } else {
            val frame = nextFrame() ?: return VapFrameAdvance.Ended
            VapFrameAdvance.Bitmap(frame)
        }
    }

    override suspend fun awaitFramePresented(): Boolean {
        if (outputMode != VapGlOutputMode.WindowSurface) {
            error("awaitFramePresented() requires WindowSurface output mode")
        }
        if (config == null || stopped.load()) return false
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return false
        return kotlinx.coroutines.withTimeoutOrNull(3_000L) {
            presentedTicks.receive()
            true
        } ?: false
    }

    override suspend fun open(source: VapSource, loop: Boolean, fpsOverride: Int?): VapConfig =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                closeInternalLocked()
                this@AndroidVapFrameDecoder.loop = loop
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
                startPipelineLocked(filePath, parsed)
                applyDisplaySize(parsed, displayW, displayH)
                parsed
            }
        }

    override suspend fun nextFrame(): VapPlatformFrame? {
        if (outputMode == VapGlOutputMode.WindowSurface) {
            error("nextFrame() is not used in WindowSurface mode; call awaitFramePresented()")
        }
        if (config == null || stopped.load()) return null
        playbackGate.awaitActive { stopped.load() }
        if (config == null || stopped.load()) return null
        val frame = kotlinx.coroutines.withTimeoutOrNull(3_000L) { frameChannel.receive() } ?: return null
        return frame
    }

    override suspend fun releaseDecodeSession() {
        sessionMutex.withLock {
            stopPipelineLocked()
            config = null
            loop = false
        }
    }

    override fun close() {
        runBlocking {
            sessionMutex.withLock { closeInternalLocked() }
        }
    }

    override fun setDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx == displayW && heightPx == displayH) return
        displayW = widthPx
        displayH = heightPx
        val cfg = config ?: return
        applyDisplaySize(cfg, widthPx, heightPx)
    }

    override fun setPlaying(playing: Boolean) {
        applyGateChange(playbackGate.setPlaying(playing))
    }

    override fun setTargetFrameRate(fps: Int) {
        targetFrameRate = fps.coerceAtLeast(0)
        speedControl.setFixedPlaybackRate(targetFrameRate)
        directPipeline?.setTargetFrameRate(targetFrameRate)
        vkSession?.setTargetFrameRate(targetFrameRate)
        vkSession?.resetPresentClock()
    }

    override fun setVisible(visible: Boolean) {
        pendingSwapEnabled.store(visible)
        directPipeline?.setSwapEnabled(visible)
        vkSession?.setSwapEnabled(visible)
        applyGateChange(playbackGate.setVisible(visible))
    }

    internal fun applyGateChange(resumed: Boolean) {
        if (!playbackGate.isActive()) {
            vkSession?.discardPendingPresent()
            directPipeline?.discardPendingPresent()
            drainFrameChannel()
            drainPresentedTicks()
        }
        if (resumed) {
            speedControl.reset()
            resetDirectClock()
            directPipeline?.resetPresentClock()
            vkSession?.resetPresentClock()
        }
    }

    internal fun applyDisplaySize(cfg: VapConfig, widthPx: Int, heightPx: Int) {
        // WindowSurface / Direct: output is sized by the Surface itself.
        // HardwareBuffer (GL-only) used to fit the offscreen target here; removed with ES backends.
        // WindowSurface / Direct：输出尺寸由 Surface 自身决定。
        // HardwareBuffer（仅 GL）曾经在此适配离屏目标；ES 后端被移除后该路径不再需要。
    }

    internal fun drainFrameChannel() {
        // Drop any pending Bitmaps so the pool's onRelease fires; close()/detach must not
        // leak native pixels when consumers stop awaiting.
        // 丢弃所有待处理 Bitmap 以触发池的 onRelease；close()/detach 时不能让原生像素泄漏。
        while (true) {
            val frame = frameChannel.tryReceive().getOrNull() ?: break
            frame.release()
        }
    }

    internal fun drainPresentedTicks() {
        while (presentedTicks.tryReceive().isSuccess) {
        }
    }

    /** Caller must hold [sessionMutex]. / 调用方必须持有 [sessionMutex]。 */
    internal suspend fun closeInternalLocked() {
        stopPipelineLocked()
        config = null
        loop = false

        tempFile?.delete()
        tempFile = null
    }

    /** Caller must hold [sessionMutex]. Direct mode only. / 调用方必须持有 [sessionMutex]，仅 Direct 模式使用。 */
    internal fun stopDirectCodecLocked() {
        decodeJob?.cancel()
        decodeJob = null
        codecAsync?.close()
        codecAsync = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: IllegalStateException) {
        }
        codec = null
        // Extractor stays alive — it has no surface dependency and will be reused
        // on the next ensureDirectCodecLocked.
        // Extractor 保持存活 —— 它不依赖 surface，会在下一次 ensureDirectCodecLocked 中复用。
    }

    internal fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }
}
