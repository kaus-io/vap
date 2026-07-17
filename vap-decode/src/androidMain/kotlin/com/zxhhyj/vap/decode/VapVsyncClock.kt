package com.zxhhyj.vap.decode

import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Process-wide vsync ticker (PAG AnimationTicker analogue).
 *
 * One Choreographer pump on Main + **one** SharedFlow collector on the shared GL
 * thread that fans out to every registered [VapOffscreenGlPipeline]. Pipelines no
 * longer each `collect` (avoids N×vsync wakeups on the GL dispatcher).
 */
internal object VapVsyncClock {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val pipelinesLock = Any()
    private val pipelines = ArrayList<VapOffscreenGlPipeline>()
    private val pumpJob = AtomicReference<Job?>(null)
    private val dispatchJob = AtomicReference<Job?>(null)

    private val ticks = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** @return false if the shared GL session is not ready (caller should retry). */
    fun register(pipeline: VapOffscreenGlPipeline): Boolean {
        synchronized(pipelinesLock) {
            if (!pipelines.contains(pipeline)) {
                pipelines.add(pipeline)
            }
        }
        ensurePump()
        if (!ensureDispatch()) {
            synchronized(pipelinesLock) {
                pipelines.remove(pipeline)
                if (pipelines.isEmpty()) stopAll()
            }
            return false
        }
        return true
    }

    fun unregister(pipeline: VapOffscreenGlPipeline) {
        val empty = synchronized(pipelinesLock) {
            pipelines.remove(pipeline)
            pipelines.isEmpty()
        }
        if (empty) stopAll()
    }

    private fun hasPipelines(): Boolean =
        synchronized(pipelinesLock) { pipelines.isNotEmpty() }

    private fun snapshotPipelines(): Array<VapOffscreenGlPipeline> =
        synchronized(pipelinesLock) { pipelines.toTypedArray() }

    private fun ensurePump() {
        while (true) {
            val current = pumpJob.get()
            if (current?.isActive == true) return
            if (!hasPipelines()) return
            val job = scope.launch {
                while (hasPipelines()) {
                    val frameTimeNanos = awaitFrameNanos()
                    ticks.emit(frameTimeNanos)
                }
            }
            if (pumpJob.compareAndSet(current, job)) {
                current?.cancel()
                return
            }
            job.cancel()
        }
    }

    /** @return false when GL runtime cannot start a dispatch job. */
    private fun ensureDispatch(): Boolean {
        while (true) {
            val current = dispatchJob.get()
            if (current?.isActive == true) return true
            if (!hasPipelines()) return false
            val job = VapSharedGlRuntime.launchGl {
                ticks.collect { frameTimeNanos ->
                    // Snapshot under lock; most pipelines early-out when !pendingPresent.
                    val snap = snapshotPipelines()
                    for (p in snap) {
                        p.onVsyncTick(frameTimeNanos)
                    }
                }
            }
            if (job == null) {
                return false
            }
            if (dispatchJob.compareAndSet(current, job)) {
                current?.cancel()
                return true
            }
            job.cancel()
        }
    }

    private fun stopAll() {
        dispatchJob.getAndSet(null)?.cancel()
        pumpJob.getAndSet(null)?.cancel()
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
