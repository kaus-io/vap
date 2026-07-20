@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import com.zxhhyj.vap.vk.VapVkNative
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Process-wide vsync ticker (PAG AnimationTicker analogue).
 * 全进程共享的 vsync ticker（与 PAG AnimationTicker 类似）。
 *
 * One Choreographer callback on Main posts coalesced ticks (DROP_OLDEST) to
 * Vulkan [VapVulkanSession] on [VapSharedVkRuntime].
 * Main 上的一个 Choreographer 回调将合并后的 tick（DROP_OLDEST）投递到
 * [VapSharedVkRuntime] 上的 Vulkan [VapVulkanSession]。
 *
 * No SharedFlow / collect hop on the present path.
 * present 路径上没有 SharedFlow / collect 的额外跳转。
 *
 * The `vap-vk-shared` handler coalesces multiple Choreographer ticks per frame via
 * `removeCallbacks` + single `post`; on 120 Hz displays that halves tick fan-out vs.
 * one callback per session.
 * `vap-vk-shared` Handler 通过 `removeCallbacks` + 单次 `post` 将同帧内的多次 Choreographer
 * tick 合并；在 120 Hz 屏幕上，tick 扇出比每个会话一次回调减少一半。
 */
internal object VapVsyncClock {
    private val listenersLock = Any()
    private val vkSessions = ArrayList<VapVulkanSession>()
    private val pumping = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pendingVkNanos = AtomicLong(0L)

    /** Hot-path tick logs; leave off for CPU benches (re-enable when profiling).
     * 热路径 tick 日志；进行 CPU 基准测试时请关闭（做性能剖析时再打开）。 */
    private const val LOG_VK_TICK = false
    private var vkTickN = 0L
    private var vkTickUsSum = 0L
    private var vkPresentNSum = 0L

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!hasListeners()) {
                pumping.store(false)
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
            scheduleVkTick(frameTimeNanos)
        }
    }

    private val batchHandles = ArrayList<Long>(16)

    private val vkTickRunnable = Runnable {
        val frameTimeNanos = pendingVkNanos.load()
        val sessions = snapshotVk()
        if (VapVulkanPipeline.BATCH_PRESENT) {
            // Prepare every due session, then a single native submit+present covers all.
            // 准备好所有到期的会话，再通过一次原生 submit+present 覆盖全部。
            synchronized(VapVulkanPipeline.tickLock) {
                // Sweep first: skipping sessions otherwise pin stale batch slots.
                // 先扫描释放：否则被跳过的会话会持续占用陈旧批次槽位。
                for (s in sessions) s.retireSignaled()
                batchHandles.clear()
                for (s in sessions) {
                    if (s.onVsyncTick(frameTimeNanos)) {
                        val h = s.nativeHandle
                        if (h != 0L) batchHandles.add(h)
                    }
                }
                if (batchHandles.isNotEmpty()) {
                    VapVkNative.nativeSubmitBatch(batchHandles.toLongArray())
                }
            }
            return@Runnable
        }
        if (!LOG_VK_TICK) {
            for (s in sessions) s.onVsyncTick(frameTimeNanos)
            return@Runnable
        }
        val t0 = System.nanoTime()
        var presented = 0
        for (s in sessions) {
            if (s.onVsyncTick(frameTimeNanos)) presented++
        }
        val tickUs = (System.nanoTime() - t0) / 1_000L
        vkTickN++
        vkTickUsSum += tickUs
        vkPresentNSum += presented.toLong()
        if (vkTickN == 1L || vkTickN % 60L == 0L) {
            val n = vkTickN
            Log.i(
                "VapVk",
                "vk-tick n=$n avg_tick_us=${vkTickUsSum / n} " +
                    "avg_presents=${"%.2f".format(vkPresentNSum.toDouble() / n)} " +
                    "sessions=${sessions.size} last_tick_us=$tickUs last_presents=$presented",
            )
        }
    }

    fun registerVulkan(session: VapVulkanSession): Boolean {
        synchronized(listenersLock) {
            if (!vkSessions.contains(session)) vkSessions.add(session)
        }
        if (VapSharedVkRuntime.imageHandler() == null) {
            synchronized(listenersLock) {
                vkSessions.remove(session)
                if (isEmptyLocked()) stopAll()
            }
            return false
        }
        ensurePump()
        return true
    }

    fun unregisterVulkan(session: VapVulkanSession) {
        val empty = synchronized(listenersLock) {
            vkSessions.remove(session)
            isEmptyLocked()
        }
        if (empty) stopAll()
    }

    private fun isEmptyLocked(): Boolean = vkSessions.isEmpty()

    private fun hasListeners(): Boolean =
        synchronized(listenersLock) { !isEmptyLocked() }

    private fun snapshotVk(): Array<VapVulkanSession> =
        synchronized(listenersLock) { vkSessions.toTypedArray() }

    private fun ensurePump() {
        if (!pumping.compareAndSet(false, true)) return
        mainHandler.post {
            if (!hasListeners()) {
                pumping.store(false)
                return@post
            }
            Choreographer.getInstance().postFrameCallback(choreographerCallback)
        }
    }

    private fun scheduleVkTick(frameTimeNanos: Long) {
        val handler = VapSharedVkRuntime.imageHandler() ?: return
        pendingVkNanos.store(frameTimeNanos)
        handler.removeCallbacks(vkTickRunnable)
        handler.post(vkTickRunnable)
    }

    private fun stopAll() {
        pumping.store(false)
        mainHandler.post {
            Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        }
        VapSharedVkRuntime.imageHandler()?.removeCallbacks(vkTickRunnable)
        vkTickN = 0L
        vkTickUsSum = 0L
        vkPresentNSum = 0L
    }
}
