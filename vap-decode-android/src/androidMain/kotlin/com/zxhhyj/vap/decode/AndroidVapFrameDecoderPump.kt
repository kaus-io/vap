package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Outputs-only decode pump running on the decoder `CoroutineScope`.
 * Only consumes packets already queued by the callback thread; input feeding is
 * done inline on `vap-codec-cb` so the pump never starves.
 * 仅消费已由回调线程入队的 packet；input 喂入在 `vap-codec-cb` 上内联完成，
 * 因此泵永远不会饥饿。
 */
internal suspend fun AndroidVapFrameDecoder.pumpCodecAsync(
    extractor: MediaExtractor,
    codec: MediaCodec,
    async: VapMediaCodecAsync,
    totalFrames: Int,
) {
    var renderedInLoop = 0
    try {
        while (coroutineContext.isActive && !stopped.load()) {
            if (!playbackGate.isActive()) {
                playbackGate.awaitActive { stopped.load() }
                if (stopped.load() || !coroutineContext.isActive) break
                // Reset pacing anchors after pause: speedControl must not emit a stale
                // catch-up burst, direct / vk clocks must realign to the next release.
                // 暂停后重置节拍锚点：speedControl 不应再发陈旧的追赶 burst；
                // direct / vk 时钟需对齐到下一次 release。
                speedControl.reset()
                resetDirectClock()
                directPipeline?.resetPresentClock()
                vkSession?.resetPresentClock()
                continue
            }

            // Outputs-only pump: inputs are fed inline on the codec callback thread.
            // 仅消费的泵：input 喂入在 codec 回调线程上内联完成。
            var packet = async.awaitOutputBuffer()
            while (true) {
                val result: OutputHandleResult
                try {
                    result = handleOutputBuffer(codec, packet, renderedInLoop, totalFrames)
                } finally {
                    // Decrement only after handling completes: while the pump is busy
                    // the count stays > 0, so the inline fast path cannot overtake.
                    // 仅在处理完成后递减：泵忙时计数保持 > 0，内联快速路径无法抢先。
                    queuedOutputs.addAndFetch(-1)
                }
                renderedInLoop = result.renderedInLoop
                if (result.endLoop) {
                    if (loop && !stopped.load() && coroutineContext.isActive) {
                        softRewind(extractor, codec, async)
                        renderedInLoop = 0
                        break
                    }
                    return
                }
                // Burst drain: process already-queued outputs without another dispatch.
                // 批量排空：处理已入队的 output，无需再次分派。
                packet = async.pollOutputBuffer() ?: break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        // MediaCodec.CodecException / async onError during teardown or surface loss.
        // teardown 或 surface 丢失期间的 MediaCodec.CodecException / async onError。
        if (!stopped.load()) {
            android.util.Log.w("VapDecode", "MediaCodec pump stopped", e)
        }
    }
}

internal fun AndroidVapFrameDecoder.softRewind(
    extractor: MediaExtractor,
    codec: MediaCodec,
    async: VapMediaCodecAsync,
) {
    synchronized(inputLock) {
        // Invalidate any input callback that is currently blocked on this lock; those
        // buffer indices were just flushed and would otherwise crash in getInputBuffer().
        // 使任何正阻塞在该锁上的 input 回调失效；这些 buffer index 刚被 flush，
        // 继续使用会在 getInputBuffer() 处崩溃。
        inputGeneration.addAndFetch(1)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // Async mode: flush invalidates outstanding buffer indices; must start() again.
        // 异步模式：flush 会令已发出的 buffer 索引失效，必须再次 start()。
        async.clearPending()
        codec.flush()
        async.clearPending()
        // Stale queued events were dropped without pump handling; re-zero the gate so
        // the inline fast path stays enabled. Fresh callbacks only come after start().
        // 没有经过泵处理的陈旧入队事件已被丢弃；将计数门归零，使内联快速路径保持启用。
        // 新的回调只有在 start() 之后才会到来。
        queuedOutputs.store(0)
        async.start(codec)
        inputDone.store(false)
    }
    speedControl.reset()
    // Direct mode: PTS wall-clock anchor must reset on loop so the re-decoded
    // first frame (PTS~0) doesn't get a stale (past) release timestamp.
    // Direct 模式：循环时必须重置 PTS 挂钟锚点，否则重新解码的第一帧（PTS≈0）
    // 会拿到陈旧的（过去的）release 时间戳。
    resetDirectClock()
    directPipeline?.discardPendingPresent()
    directPipeline?.resetPresentClock()
    vkSession?.discardPendingPresent()
    vkSession?.resetPresentClock()
    drainFrameChannel()
    drainPresentedTicks()
}
