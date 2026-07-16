package com.zxhhyj.vap.decode

import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Process-wide vsync ticker (PAG AnimationTicker analogue).
 *
 * Choreographer is reached only through a Main coroutine; consumers collect [ticks]
 * on their own dispatcher (typically the shared GL scope).
 */
internal object VapVsyncClock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscribers = AtomicInteger(0)
    private var pumpJob: Job? = null

    private val _ticks = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: SharedFlow<Long> = _ticks.asSharedFlow()

    fun addSubscriber() {
        if (subscribers.getAndIncrement() == 0) {
            startPump()
        }
    }

    fun removeSubscriber() {
        if (subscribers.decrementAndGet() <= 0) {
            subscribers.set(0)
            stopPump()
        }
    }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (subscribers.get() > 0) {
                val frameTimeNanos = awaitFrameNanos()
                _ticks.emit(frameTimeNanos)
            }
        }
    }

    private fun stopPump() {
        pumpJob?.cancel()
        pumpJob = null
    }

    private suspend fun awaitFrameNanos(): Long =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { frameTimeNanos ->
                    if (cont.isActive) cont.resume(frameTimeNanos)
                }
                choreographer.postFrameCallback(callback)
                cont.invokeOnCancellation {
                    choreographer.removeFrameCallback(callback)
                }
            }
        }
}
