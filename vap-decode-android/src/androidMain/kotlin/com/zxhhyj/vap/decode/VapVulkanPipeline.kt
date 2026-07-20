@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.view.Surface
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vk.VapVkNative
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Vulkan 1.1 WindowSurface path:
 * MediaCodec → [ImageReader] AHB → native VkImage import → VAP alpha composite → swapchain.
 * Vulkan 1.1 WindowSurface 路径：MediaCodec → [ImageReader] AHB → 原生 VkImage 导入 →
 * VAP alpha 合成 → 交换链。
 *
 * Timing mirrors the (now-removed) `VapOffscreenGlPipeline`: decode fills a pending slot; Choreographer vsync presents.
 * 时序与（已移除的）`VapOffscreenGlPipeline` 对齐：解码填入 pending 槽位；Choreographer vsync 触发 present。
 *
 * [Image] is kept open for [kInflightImages] presents so BufferQueue sees the consumer hold
 * (closing Image immediately while GPU still samples was incompatible with AHB reuse).
 * [Image] 在 [kInflightImages] 次 present 期间保持打开，使 BufferQueue 看到消费者持有；
 * 若 GPU 仍在采样的同时立刻关闭 Image，会与 AHB 复用不兼容。
 */
internal object VapVulkanPipeline {
    const val IMPLEMENTED: Boolean = true

    /** Must stay in sync with native `kFramesInFlight`. / 必须与原生 `kFramesInFlight` 保持同步。 */
    const val kInflightImages: Int = 2

    /**
     * Batch path: each vsync tick prepares every due session, then one native
     * submit+present covers all of them (single vkQueueSubmit / vkQueuePresentKHR).
     * 批处理路径：每个 vsync tick 准备好所有到期的会话，然后一次原生 submit+present
     * 覆盖全部（单次 vkQueueSubmit / vkQueuePresentKHR）。
     */
    const val BATCH_PRESENT: Boolean = true

    /**
     * Serializes a whole vsync tick (prepare loop + [VapVkNative.nativeSubmitBatch])
     * against [VapVulkanSession.release], so a batch never touches a destroyed engine.
     * 将整个 vsync tick（prepare 循环 + [VapVkNative.nativeSubmitBatch]）与
     * [VapVulkanSession.release] 串行化，确保批处理不会触达已被销毁的引擎。
     */
    internal val tickLock = Any()
}

internal class VapVulkanSession(
    private val config: VapConfig,
    private val presentedTicks: Channel<Unit>?,
    private val stopped: AtomicBoolean,
    private val onFrameComposited: () -> Unit,
) {
    private data class PendingWindow(val surface: Surface, val width: Int, val height: Int)

    private val acquired = AtomicBoolean(false)
    private val swapEnabled = AtomicBoolean(true)
    private val pendingPresent = AtomicBoolean(false)
    private val decodeSlotHeld = AtomicBoolean(false)
    private val vsyncRegistered = AtomicBoolean(false)
    private val surfaceEpoch = AtomicInt(0)
    private val targetFps = AtomicInt(0)
    private val playStartMonoNs = AtomicLong(0L)
    private val playStartPtsNs = AtomicLong(0L)
    private val lastPresentedLogicalFrame = AtomicLong(-1L)

    private val pendingWindow = AtomicReference<PendingWindow?>(null)
    private val appliedWindow = AtomicReference<PendingWindow?>(null)
    /** Latest decoded frame; closed only when superseded or after inflight window.
     * 最新解码帧；仅在被覆盖或经过 in-flight 窗口后才关闭。 */
    private val pendingImage = AtomicReference<Image?>(null)

    /** Images still referenced by in-flight GPU work. Touched only on vap-vk-shared.
     * 仍被 in-flight GPU 工作引用的 Image。仅在 vap-vk-shared 线程上访问。 */
    private val inflightImages = ArrayDeque<Image>(VapVulkanPipeline.kInflightImages + 1)

    private var handle: Long = 0L
    private var imageReader: ImageReader? = null
    private var sharedAcquired = false

    /** Native engine handle for the batched submit step (0 = destroyed). / 批处理 submit 步骤使用的原生引擎句柄（0 = 已销毁）。 */
    internal val nativeHandle: Long
        get() = handle

    /**
     * Free batch-slot holds for frames the GPU already finished. Called every tick for
     * every registered session — even ones skipping present — so holds never pin stale batches.
     * 释放 GPU 已完成帧的批处理槽位持有。每个 tick 都会为所有已注册的会话调用 —— 即便
     * 该会话本 tick 跳过 present —— 以防持有陈旧的批次。
     */
    internal fun retireSignaled() {
        if (!VapVulkanPipeline.BATCH_PRESENT) return
        val h = handle
        if (h != 0L && acquired.load()) VapVkNative.nativeRetireSignaled(h)
    }

    val codecSurface: Surface?
        get() = imageReader?.surface

    fun setTargetFrameRate(fps: Int) {
        targetFps.store(fps.coerceAtLeast(0))
    }

    fun resetPresentClock() {
        playStartMonoNs.store(0L)
        playStartPtsNs.store(0L)
        lastPresentedLogicalFrame.store(-1L)
    }

    fun setSwapEnabled(enabled: Boolean) {
        swapEnabled.store(enabled)
        if (!enabled) {
            unregisterVsync()
            discardPending(notify = false)
        } else if (pendingPresent.load()) {
            registerVsync()
        }
    }

    fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        pendingWindow.store(PendingWindow(surface, width.coerceAtLeast(1), height.coerceAtLeast(1)))
        applyPendingWindow()
    }

    fun resizeOutput(width: Int, height: Int) {
        val cur = pendingWindow.load() ?: return
        pendingWindow.store(cur.copy(width = width.coerceAtLeast(1), height = height.coerceAtLeast(1)))
        applyPendingWindow()
    }

    fun detachOutputSurface() {
        pendingWindow.store(null)
        appliedWindow.store(null)
        if (handle != 0L) VapVkNative.nativeClearOutputSurface(handle)
        surfaceEpoch.addAndFetch(1)
    }

    fun discardPendingPresent() {
        discardPending(notify = false)
    }

    fun start(): Boolean {
        if (!VapVulkanPipeline.IMPLEMENTED) return false
        VapSharedVkRuntime.acquire()
        sharedAcquired = true
        try {
            handle = VapVkNative.nativeCreate(
                videoW = config.videoWidth,
                videoH = config.videoHeight,
                alphaX = config.alphaFrame.x,
                alphaY = config.alphaFrame.y,
                alphaW = config.alphaFrame.w,
                alphaH = config.alphaFrame.h,
                rgbX = config.rgbFrame.x,
                rgbY = config.rgbFrame.y,
                rgbW = config.rgbFrame.w,
                rgbH = config.rgbFrame.h,
            )
        } catch (t: Throwable) {
            Log.e("VapVk", "nativeCreate failed", t)
            handle = 0L
            releaseShared()
            return false
        }
        if (handle == 0L) {
            releaseShared()
            return false
        }

        val handler = VapSharedVkRuntime.imageHandler()
        if (handler == null) {
            Log.e("VapVk", "Shared Vulkan runtime has no image handler")
            if (handle != 0L) {
                VapVkNative.nativeDestroy(handle)
                handle = 0L
            }
            releaseShared()
            return false
        }

        val w = config.videoWidth.coerceAtLeast(1)
        val h = config.videoHeight.coerceAtLeast(1)
        // in-flight held open + pending + one free slot for MediaCodec producer.
        // 容量 = 在飞行（in-flight）持有 + pending + 一格空闲槽位供 MediaCodec 生产者使用。
        val maxImages = VapVulkanPipeline.kInflightImages + 2
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        imageReader = if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, maxImages, usage)
        } else {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, maxImages)
        }
        imageReader?.setOnImageAvailableListener({ reader ->
            if (stopped.load()) return@setOnImageAvailableListener
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            val previous = pendingImage.exchange(image)
            previous?.close()
            pendingPresent.store(true)
            decodeSlotHeld.store(true)
            registerVsync()
        }, handler)

        acquired.store(true)
        applyPendingWindow()
        return true
    }

    fun release() {
        if (!acquired.exchange(false)) {
            releaseShared()
            return
        }
        unregisterVsync()
        discardPending(notify = false)
        drainInflightImages()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        if (handle != 0L) {
            // Batch ticks hold tickLock across prepare+submit; destroy must not interleave.
            // 批处理 tick 在 prepare+submit 期间持有 tickLock，destroy 不能与之交错。
            if (VapVulkanPipeline.BATCH_PRESENT) {
                synchronized(VapVulkanPipeline.tickLock) { VapVkNative.nativeDestroy(handle) }
            } else {
                VapVkNative.nativeDestroy(handle)
            }
            handle = 0L
        }
        releaseShared()
    }

    private fun releaseShared() {
        if (!sharedAcquired) return
        sharedAcquired = false
        VapSharedVkRuntime.release()
    }

    /** @return true if a native present was attempted this tick. / 若本 tick 尝试了一次原生 present 则返回 true。 */
    internal fun onVsyncTick(frameTimeNanos: Long): Boolean {
        if (stopped.load() || !acquired.load()) return false
        if (!pendingPresent.load()) return false
        if (!swapEnabled.load()) {
            discardPending(notify = false)
            return false
        }
        // Fewer presents: no valid output yet → skip GPU but keep pending Image.
        // 减少 present 次数：尚无可用输出 → 跳过 GPU，但保留 pending Image。
        val out = appliedWindow.load() ?: pendingWindow.load()
        if (out == null || !out.surface.isValid) {
            return false
        }
        applyPendingWindow()
        val image = pendingImage.load() ?: run {
            discardPending(notify = false)
            return false
        }
        val fps = targetFps.load()
        // PTS unknown for AHB path; wall-clock grid when targetFps > 0, else present every vsync.
        // AHB 路径无 PTS 可用：targetFps > 0 时使用挂钟时间网格，否则每个 vsync 都 present。
        if (fps > 0) {
            val start = playStartMonoNs.load()
            if (start == 0L) {
                playStartMonoNs.store(frameTimeNanos)
            }
            val elapsed = (frameTimeNanos - playStartMonoNs.load()).coerceAtLeast(0L)
            val logical = elapsed * fps.toLong() / 1_000_000_000L
            val last = lastPresentedLogicalFrame.load()
            if (logical <= last) {
                // Keep pending Image + decode slot until a due cell.
                // Releasing the slot here lets MediaCodec race ahead at vsync rate (~60)
                // while we only present at targetFps (~30) → wasted decode / ImageReader CPU.
                // 保持 pending Image 和解码槽位直到一个到期格。若此刻释放槽位，
                // MediaCodec 会按 vsync 速率（约 60）抢跑，而我们只按 targetFps（约 30）present，
                // 会造成解码 / ImageReader CPU 的浪费。
                return false
            }
            lastPresentedLogicalFrame.store(logical)
        }
        if (!pendingPresent.compareAndSet(expectedValue = true, newValue = false)) return false
        val buf = try {
            image.hardwareBuffer
        } catch (_: Throwable) {
            null
        }
        if (buf == null) {
            pendingImage.compareAndSet(image, null)
            image.close()
            releaseDecodeSlot()
            return false
        }
        val ok = handle != 0L && if (VapVulkanPipeline.BATCH_PRESENT) {
            VapVkNative.preparePresent(handle, buf)
        } else {
            VapVkNative.presentHardwareBuffer(handle, buf)
        }
        pendingImage.compareAndSet(image, null)
        buf.close()
        // Keep Image open until GPU in-flight window advances (BufferQueue consumer hold).
        // 保持 Image 打开直到 GPU in-flight 窗口前移（BufferQueue 消费者持有）。
        inflightImages.addLast(image)
        while (inflightImages.size > VapVulkanPipeline.kInflightImages) {
            inflightImages.removeFirst().close()
        }
        releaseDecodeSlot()
        if (ok) {
            presentedTicks?.trySend(Unit)
        }
        return true
    }

    private fun applyPendingWindow() {
        val handler = VapSharedVkRuntime.imageHandler()
        if (handler != null && handler.looper.thread != Thread.currentThread()) {
            handler.post { applyPendingWindow() }
            return
        }
        val pending = pendingWindow.load() ?: return
        if (handle == 0L) return
        if (!pending.surface.isValid) {
            Log.w("VapVk", "skip setOutputSurface: surface invalid ${pending.width}x${pending.height}")
            return
        }
        val applied = appliedWindow.load()
        if (applied != null &&
            applied.surface === pending.surface &&
            applied.width == pending.width &&
            applied.height == pending.height
        ) {
            return
        }
        if (!VapVkNative.nativeSetOutputSurface(handle, pending.surface, pending.width, pending.height)) {
            Log.e("VapVk", "nativeSetOutputSurface failed ${pending.width}x${pending.height}")
            return
        }
        appliedWindow.store(pending)
        surfaceEpoch.addAndFetch(1)
    }

    private fun discardPending(notify: Boolean) {
        if (!pendingPresent.exchange(false)) {
            pendingImage.exchange(null)?.close()
            return
        }
        pendingImage.exchange(null)?.close()
        releaseDecodeSlot()
        if (notify) presentedTicks?.trySend(Unit)
    }

    private fun drainInflightImages() {
        while (inflightImages.isNotEmpty()) {
            inflightImages.removeFirst().close()
        }
    }

    private fun releaseDecodeSlot() {
        if (decodeSlotHeld.compareAndSet(expectedValue = true, newValue = false)) {
            onFrameComposited()
        }
    }

    private fun registerVsync() {
        if (vsyncRegistered.load()) return
        if (!VapVsyncClock.registerVulkan(this)) return
        vsyncRegistered.store(true)
    }

    private fun unregisterVsync() {
        if (!vsyncRegistered.compareAndSet(expectedValue = true, newValue = false)) return
        VapVsyncClock.unregisterVulkan(this)
    }
}
