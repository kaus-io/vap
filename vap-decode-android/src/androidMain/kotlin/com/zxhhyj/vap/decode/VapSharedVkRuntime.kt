@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Process-wide Vulkan worker thread (ImageReader callbacks + native present).
 * 全进程共享的 Vulkan 工作线程（负责 ImageReader 回调 + native present）。
 *
 * Mirrors the (now-removed) `VapSharedGlRuntime`: one HandlerThread backs all [VapVulkanSession]s
 * so 8-way playback does not spawn 8 `vap-vk-image` threads or block `Dispatchers.Default` on submit.
 * 与（已移除的）`VapSharedGlRuntime` 对称：单个 HandlerThread 承载所有 [VapVulkanSession]，
 * 避免 8 路播放各自生成 8 个 `vap-vk-image` 线程，或在 submit 时阻塞 `Dispatchers.Default`。
 *
 * The owning [CoroutineScope] is cancelled before the HandlerThread is quitSafely so
 * coroutines launched via [launchVk] see cancellation before the looper tears down.
 * 拥有 [CoroutineScope] 在 HandlerThread quitSafely 之前被取消，这样 [launchVk] 启动的
 * 协程会在 looper 拆除之前收到取消信号。
 */
internal object VapSharedVkRuntime {
    private data class Session(
        val thread: HandlerThread,
        val handler: Handler,
        val dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
    )

    private val lock = Any()
    private val refCount = AtomicInt(0)
    private val session = AtomicReference<Session?>(null)

    /** Shared looper for all ImageReader listeners. Null when no session holds a ref. */
    fun imageHandler(): Handler? = session.load()?.handler

    fun acquire() {
        synchronized(lock) {
            if (refCount.fetchAndAdd(1) == 0) {
                startLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (refCount.addAndFetch(-1) <= 0) {
                refCount.store(0)
                stopLocked()
            }
        }
    }

    /** Fire-and-forget work on the shared Vulkan worker (present / vsync fan-out). */
    fun launchVk(block: suspend CoroutineScope.() -> Unit): Job? {
        val s = session.load() ?: return null
        return s.scope.launch(s.dispatcher, block = block)
    }

    private fun startLocked() {
        val thread = HandlerThread("vap-vk-shared").also { it.start() }
        val handler = Handler(thread.looper)
        val dispatcher = handler.asCoroutineDispatcher("vap-vk-shared")
        val scope = CoroutineScope(dispatcher)
        session.store(Session(thread, handler, dispatcher, scope))
    }

    private fun stopLocked() {
        val s = session.exchange(null) ?: return
        s.scope.cancel()
        s.thread.quitSafely()
    }
}
