package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.selects.SelectClause1

/**
 * MediaCodec asynchronous mode bridged to suspending receives.
 *
 * Callbacks only [Channel.trySend]; the decode coroutine awaits buffers instead of
 * polling [MediaCodec.dequeueInputBuffer] / [MediaCodec.dequeueOutputBuffer] with timeouts.
 *
 * Call [attach] before [MediaCodec.configure], then [start] after configure.
 * After [MediaCodec.flush], call [clearPending] then [start] again (async flush rule).
 */
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

    private val inputs = Channel<Int>(capacity = Channel.UNLIMITED)
    private val outputs = Channel<OutputBuffer>(capacity = Channel.UNLIMITED)

    /** For [kotlinx.coroutines.selects.select] on input buffers. */
    val onInputBuffer: SelectClause1<Int>
        get() = inputs.onReceive

    /** For [kotlinx.coroutines.selects.select] on output buffers. */
    val onOutputBuffer: SelectClause1<OutputBuffer>
        get() = outputs.onReceive

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputs.trySend(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            // Copy fields — MediaCodec may reuse [info].
            outputs.trySend(OutputBuffer.from(index, info))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val ex = IllegalStateException("MediaCodec error: ${e.diagnosticInfo}", e)
            inputs.close(ex)
            outputs.close(ex)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    fun attach(codec: MediaCodec) {
        codec.setCallback(callback)
    }

    fun start(codec: MediaCodec) {
        codec.start()
    }

    /** Drop stale buffer indices after [MediaCodec.flush] (indices become invalid). */
    fun clearPending() {
        while (inputs.tryReceive().isSuccess) {
        }
        while (outputs.tryReceive().isSuccess) {
        }
    }

    fun close() {
        inputs.close()
        outputs.close()
        clearPending()
    }

    suspend fun awaitInputBuffer(): Int =
        try {
            inputs.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw CancellationException("MediaCodec input closed", e)
        }

    suspend fun awaitOutputBuffer(): OutputBuffer =
        try {
            outputs.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw CancellationException("MediaCodec output closed", e)
        }
}
