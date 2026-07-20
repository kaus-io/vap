@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.os.Handler
import android.os.HandlerThread
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Process-wide HandlerThread for [android.media.MediaCodec.Callback].
 * 全进程共享的 [android.media.MediaCodec.Callback] HandlerThread。
 *
 * Without a shared [Handler], each `MediaCodec.setCallback` spawns its own
 * dedicated callback thread. N-way playback then pays N× scheduling / binder fan-in
 * before the decode coroutines even run on `Dispatchers.IO`.
 * 若不共享 [Handler]，每次 `MediaCodec.setCallback` 都会生成专用回调线程。N 路播放
 * 会在解码协程进入 `Dispatchers.IO` 之前，先承担 N 倍调度 / binder 扇入开销。
 *
 * Refcount semantics: first [acquire] starts the thread, last [release] quits it
 * (quitSafely). Callbacks from stale codecs after [release] must be ignored at the
 * owner layer (see [VapMediaCodecAsync.attach] generation token).
 * 引用计数语义：首次 [acquire] 启动线程，最后一次 [release] 通过 quitSafely 退出。
 * [release] 之后来自已失效 codec 的回调必须在所有者层忽略（参见
 * [VapMediaCodecAsync.attach] 中的 generation 令牌）。
 */
internal object VapSharedCodecCallbackRuntime {
    private data class Session(
        val thread: HandlerThread,
        val handler: Handler,
    )

    private val lock = Any()
    private val refCount = AtomicInt(0)
    private val session = AtomicReference<Session?>(null)

    fun handler(): Handler? = session.load()?.handler

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

    private fun startLocked() {
        val thread = HandlerThread("vap-codec-cb").also { it.start() }
        session.store(Session(thread, Handler(thread.looper)))
    }

    private fun stopLocked() {
        val s = session.exchange(null) ?: return
        s.thread.quitSafely()
    }
}
