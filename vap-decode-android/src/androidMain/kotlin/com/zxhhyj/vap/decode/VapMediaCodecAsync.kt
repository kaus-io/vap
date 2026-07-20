package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * MediaCodec asynchronous mode bridged to inline handlers + a suspending output queue.
 * 将 MediaCodec 异步模式桥接到内联 handler 与挂起输出队列。
 *
 * Input feeding has no backpressure constraint, so [inputFeeder] runs **inline on the
 * shared `vap-codec-cb` callback thread** — no Channel handoff, no Dispatchers.IO wakeup.
 * Input 侧无背压约束，因此 [inputFeeder] **直接在共享的 `vap-codec-cb` 回调线程上内联运行**，
 * 没有 Channel 转发、没有 Dispatchers.IO 唤醒。
 *
 * Output buffers first try [outputHandler] (an inline fast path that may release the
 * buffer immediately when the compositor slot is free); unhandled packets are queued to
 * [outputs] for the decode pump, preserving arrival order.
 * Output 缓冲先尝试 [outputHandler]（合成槽位空闲时可立即释放缓冲的内联快速路径）；
 * 未被处理的包则按到达顺序入队到 [outputs] 供解码泵消费。
 *
 * Call [attach] before [MediaCodec.configure], then [start] after configure.
 * After [MediaCodec.flush], call [clearPending] then [start] again (async flush rule).
 * 在 [MediaCodec.configure] 之前调用 [attach]，之后调用 [start]；调用 [MediaCodec.flush]
 * 之后必须先 [clearPending] 再 [start]（异步模式的 flush 规则）。
 *
 * Callbacks run on [VapSharedCodecCallbackRuntime] so N codecs do not spawn N
 * dedicated MediaCodec callback threads.
 * 回调在 [VapSharedCodecCallbackRuntime] 上运行，避免 N 个 codec 各生成 N 条专用回调线程。
 */
@OptIn(ExperimentalAtomicApi::class)
internal class VapMediaCodecAsync {
    data class OutputBuffer(
        val index: Int,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    ) {
        val isEos: Boolean
            get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        companion object {
            fun from(index: Int, info: MediaCodec.BufferInfo): OutputBuffer =
                OutputBuffer(
                    index = index,
                    offset = info.offset,
                    size = info.size,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                )
        }
    }

    private val outputs = Channel<OutputBuffer>(capacity = Channel.UNLIMITED)
    private val sharedCallbackHeld = AtomicBoolean(false)

    /**
     * Inline input feed on the callback thread. Must be non-blocking and serialized
     * against extractor seek/flush by the owner ([VapFrameDecoder] holds an input lock).
     * 在回调线程上内联执行 input 喂入。必须是非阻塞的，且与 extractor seek/flush 由
     * 拥有者（[VapFrameDecoder] 持有一把 input 锁）串行化。
     */
    var inputFeeder: ((MediaCodec, Int) -> Unit)? = null

    /**
     * Output router on the callback thread. Implementations may release the buffer
     * inline (fast path) or fall back to [enqueueOutput]; ordering rules are owned
     * by [VapFrameDecoder] (queued-count gate).
     * 在回调线程上的 output 路由器。实现可选择内联释放缓冲（快速路径）或回退到
     * [enqueueOutput]；顺序规则由 [VapFrameDecoder] 通过排队计数门控制。
     */
    var outputHandler: ((MediaCodec, OutputBuffer) -> Unit)? = null

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputFeeder?.invoke(codec, index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            // Copy fields — MediaCodec may reuse [info].
            // 拷贝字段 —— MediaCodec 可能会复用 [info]。
            val packet = OutputBuffer.from(index, info)
            val handler = outputHandler
            if (handler != null) {
                handler(codec, packet)
            } else {
                outputs.trySend(packet)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            outputs.close(IllegalStateException("MediaCodec error: ${e.diagnosticInfo}", e))
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    fun attach(codec: MediaCodec) {
        if (sharedCallbackHeld.compareAndSet(expectedValue = false, newValue = true)) {
            VapSharedCodecCallbackRuntime.acquire()
        }
        val handler = VapSharedCodecCallbackRuntime.handler()
            ?: error("Missing shared MediaCodec callback handler")
        codec.setCallback(callback, handler)
    }

    fun start(codec: MediaCodec) {
        codec.start()
    }

    /** Queue an output packet to the decode pump (arrival order preserved). / 将 output 包入队至解码泵（保留到达顺序）。 */
    fun enqueueOutput(packet: OutputBuffer): Boolean = outputs.trySend(packet).isSuccess

    /** Drop stale buffer indices after [MediaCodec.flush] (indices become invalid). / 在 [MediaCodec.flush] 之后丢弃失效的缓冲索引（索引已无效）。 */
    fun clearPending() {
        while (outputs.tryReceive().isSuccess) {
        }
    }

    fun close() {
        outputs.close()
        clearPending()
        if (sharedCallbackHeld.compareAndSet(expectedValue = true, newValue = false)) {
            VapSharedCodecCallbackRuntime.release()
        }
    }

    suspend fun awaitOutputBuffer(): OutputBuffer =
        try {
            outputs.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw CancellationException("MediaCodec output closed", e)
        }

    /** Non-blocking burst drain after [awaitOutputBuffer]. / [awaitOutputBuffer] 之后的非阻塞批量排空。 */
    fun pollOutputBuffer(): OutputBuffer? = outputs.tryReceive().getOrNull()
}
