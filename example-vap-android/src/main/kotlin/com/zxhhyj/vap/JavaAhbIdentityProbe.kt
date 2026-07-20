package com.zxhhyj.vap

import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.zxhhyj.vap.vk.VapVkNative

/**
 * B0 probe: does the Java production path (`ImageReader` + `Image.getHardwareBuffer()`)
 * give a per-UNDERLYING-BUFFER native wrapper, like the native AImageReader path does?
 *
 * B0 探针:Java 生产路径(`ImageReader` + `Image.getHardwareBuffer()`)是否能像原生
 * AImageReader 那样,对每个底层 buffer 提供稳定的 native 包装?
 *
 * Method: decode a clip exactly like production (PRIVATE reader, maxImages=4,
 * USAGE_GPU_SAMPLED_IMAGE, up to 2 images held open), call getHardwareBuffer() per
 * frame, pin the native wrapper (fromHardwareBuffer + acquire) and record the pointer.
 * While a ref is held, an address can never be recycled, so pointer equality among
 * held refs is a SOUND buffer-identity test. If distinct stays bounded (~ring size)
 * and second-half frames all hit, the Vulkan import cache can be keyed on the Java
 * path — no native decode rewrite needed. If distinct grows linearly, the wrapper is
 * per-call and B must go through the native chain (B1).
 *
 * 方法:与生产路径一致地解码片段(PRIVATE reader、maxImages=4、USAGE_GPU_SAMPLED_IMAGE、
 * 最多保留 2 个 image),每帧调用 getHardwareBuffer(),固定其原生包装
 * (fromHardwareBuffer + acquire)并记录指针。引用未释放时地址不会被回收,因此持有引用间
 * 的指针相等是可靠的 buffer identity 测试。若 distinct 维持有界(≈ 环形队列大小),
 * 且后半段帧全部命中,则可在 Java 路径上以指针为 key 做 Vulkan import 缓存,无需改写
 * 原生解码;若 distinct 线性增长,说明包装按调用分配,B 必须走原生链(B1)。
 *
 * adb: am start -a com.zxhhyj.vap.action.JAVA_AHB_PROBE -n com.zxhhyj.vap/.MainActivity
 */
public object JavaAhbIdentityProbe {
    private const val TAG = "VapAhbProbe"

    public fun run(videoPath: String, targetFrames: Int = 300, lowLatency: Boolean = false) {
        val extractor = MediaExtractor()
        extractor.setDataSource(videoPath)
        var mime: String? = null
        var format: MediaFormat? = null
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) {
                track = i
                mime = m
                format = f
                break
            }
        }
        check(track >= 0 && mime != null && format != null) { "no video track" }
        extractor.selectTrack(track)
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (lowLatency) {
            // Ring-size experiment: does low-latency mode shrink the codec's undequeued
            // output buffer count (≈ the BufferQueue ring the import cache must cover)?
            // 环形队列实验:low-latency 模式是否会让 codec 未出队的输出 buffer 数变小
            // (≈ import 缓存必须覆盖的 BufferQueue 环形大小)?
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        log("decode track mime=$mime ${w}x$h (Java path, lowLatency=$lowLatency)")

        val reader = ImageReader.newInstance(
            w, h, android.graphics.ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, reader.surface, null, 0)
        codec.start()

        val ptrs = ArrayList<Long>(targetFrames)
        val heldBuffers = ArrayList<HardwareBuffer>(targetFrames)
        val openImages = ArrayDeque<Image>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val t0 = SystemClock.elapsedRealtime()

        try {
            while (!outputDone && ptrs.size < targetFrames) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val n = buf?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (n <= 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 100_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outIdx >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIdx, true)
                        // acquireLatestImage may transiently return null right after
                        // surface release; spin briefly (≤200ms) so we don't under-count.
                        // surface release 后 acquireLatestImage 偶发返回 null,
                        // 短暂自旋(≤200ms)避免欠计。
                        var img: Image? = null
                        var tries = 0
                        while (img == null && tries < 200) {
                            img = reader.acquireLatestImage()
                            if (img == null) {
                                tries++
                                Thread.sleep(1)
                            }
                        }
                        if (img != null) {
                            val hb = img.hardwareBuffer
                            if (hb != null) {
                                val ptr = VapVkNative.nativeProbeAhbAcquire(hb)
                                ptrs.add(ptr)
                                // Import-cache model: hold BOTH refs (Java object + native
                                // acquire) so the address stays pinned.
                                // import 缓存模型:同时持有 Java 对象与原生 acquire 引用,
                                // 以保证地址不被回收。
                                heldBuffers.add(hb)
                            }
                            openImages.addLast(img)
                            // Production caps the in-flight Image count at 2 to mirror
                            // normal app behavior; otherwise the codec may hand out
                            // more slots than a typical client.
                            // 与生产保持一致,保留最多 2 张未 close 的 image;
                            // 否则 codec 可能分配超出典型客户端使用的槽位数。
                            if (openImages.size > 2) openImages.removeFirst().close()
                        }
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
            openImages.forEach { it.close() }
            reader.close()
        }
        val wallMs = SystemClock.elapsedRealtime() - t0

        val frames = ptrs.size
        val seen = HashSet<Long>()
        var lastNew = -1
        var halfHits = 0
        var halfTotal = 0
        // Split the run into an early "warm-up" half and a steady-state half.
        // Steady-state hits >=90% is the gating criterion — if wrappers are
        // per-call the second half will accumulate fresh pointers.
        // 将运行切分为前半预热段与后半稳态段;稳态命中 ≥90% 是通过门槛——
        // 包装若按调用分配,后半段就会不断出现新指针。
        ptrs.forEachIndexed { i, p ->
            if (seen.add(p)) {
                lastNew = i
            } else if (i >= frames / 2) {
                halfHits++
            }
            if (i >= frames / 2) halfTotal++
        }
        log("held-refs identity(Java) frames=$frames distinct=${seen.size} " +
            "lastNew@$lastNew halfHits=$halfHits/$halfTotal wall=${wallMs}ms")
        val viable = frames >= 24 && halfTotal > 0 && halfHits * 100 >= halfTotal * 90
        if (viable) {
            log("PASS  Java-path AHB pointer key viable (steady-state hits >=90%)")
        } else {
            log("FAIL  Java-path AHB pointer key — wrapper per call, B1 native rewrite required")
        }

        // Balanced cleanup of the pinned refs (post codec stop). Held HardwareBuffers
        // must be closed BEFORE releasing the codec-side acquire, otherwise the
        // BufferQueue can hold the last reference and leak the underlying allocation.
        // 对固定的引用做对称清理(codec 停止之后)。必须先 close HardwareBuffer,
        // 再 release 原生 acquire,否则 BufferQueue 可能保留最后一个引用
        // 导致底层分配泄漏。
        heldBuffers.forEach { it.close() }
        ptrs.forEach { VapVkNative.nativeProbeAhbRelease(it) }
        log("PASS  release pinned refs + close held HardwareBuffers (Java path)")
        log("done frames=$frames")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}
