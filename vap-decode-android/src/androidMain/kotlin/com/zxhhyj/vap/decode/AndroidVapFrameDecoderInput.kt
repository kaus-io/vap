package com.zxhhyj.vap.decode

import android.media.MediaCodec
import android.media.MediaExtractor

/** Inline input feed on the codec callback thread; serialized against softRewind. / 在 codec 回调线程上内联执行的 input 喂入；与 softRewind 串行化。 */
internal fun AndroidVapFrameDecoder.feedInputInline(extractor: MediaExtractor, codec: MediaCodec, index: Int) {
    if (stopped.load()) return
    val gen = inputGeneration.load()
    synchronized(inputLock) {
        // generation check: a softRewind that just completed bumped [inputGeneration], so
        // the captured index is invalidated and any queueInputBuffer() would crash with BAD_INDEX.
        // generation 检查：刚完成的 softRewind 已递增 [inputGeneration]，捕获到的索引失效，
        // 继续 queueInputBuffer() 会以 BAD_INDEX 崩溃。
        if (stopped.load() || inputGeneration.load() != gen) return
        feedInputBuffer(extractor, codec, index)
    }
}

internal fun AndroidVapFrameDecoder.feedInputBuffer(extractor: MediaExtractor, codec: MediaCodec, index: Int) {
    if (stopped.load()) return
    try {
        val buffer = codec.getInputBuffer(index) ?: run {
            // getInputBuffer() can return null transiently on some devices during flush;
            // queue an empty buffer to release the slot back to the codec.
            // getInputBuffer() 在某些设备上 flush 期间可能短暂返回 null；提交一个空缓冲以归还槽位。
            codec.queueInputBuffer(index, 0, 0, 0L, 0)
            return
        }
        val sample = extractor.readSampleData(buffer, 0)
        if (sample < 0) {
            codec.queueInputBuffer(
                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone.store(true)
        } else {
            codec.queueInputBuffer(index, 0, sample, extractor.sampleTime, 0)
            extractor.advance()
        }
    } catch (e: Exception) {
        // Surface disconnected / codec flushed while a buffer index was already selected,
        // or a MediaCodec.CodecException on a stale index. Swallow to keep the callback thread alive.
        // 在已选中 buffer index 后 surface 断开 / codec flush，或在陈旧 index 上出现
        // MediaCodec.CodecException。吞掉以保持回调线程存活。
        if (!stopped.load()) {
            android.util.Log.w("VapDecode", "queueInputBuffer failed", e)
        }
        inputDone.store(true)
    }
}
