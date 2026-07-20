@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coroutine-based VAP encoder that packs PNG frames, invokes FFmpeg, and embeds VAP metadata.
 *
 * 基于协程的 VAP 编码器：打包 PNG 帧、调用 FFmpeg，并嵌入 VAP 元数据。
 *
 * @param parallelism Maximum concurrent PNG scan or packing tasks; values below one act as one. PNG 扫描或打包任务的最大并发数；小于 1 时按 1 处理。
 */
public class DefaultVapEncoder(
    private val parallelism: Int = 16,
) : VapEncoder {

    override fun encode(request: EncodeRequest): Flow<EncodeProgress> = channelFlow {
        try {
            val prepared = withContext(Dispatchers.Default) {
                prepare(request)
            }
            val (rgbSize, inputFrames, outputDir, framesDir) = prepared
            val totalFrames = inputFrames.size

            // Scan phase: only when user asked for auto-detection. Layout depends on it,
            // so we cannot start packing until we know whether the sequence is transparent.
            // 扫描阶段仅用于自动检测；布局依赖检测结果，因此确定序列是否透明前不能开始打包。
            val hasAlpha: Boolean = if (request.hasAlpha == HasAlpha.Auto) {
                val outer = this@channelFlow
                scanAnyTransparent(inputFrames) { fraction, message ->
                    outer.send(EncodeProgress.Running(fraction * 0.3f, message))
                }
            } else {
                request.hasAlpha == HasAlpha.On
            }

            val layout = EncodeLayout.compute(rgbSize.width, rgbSize.height, request.scale, hasAlpha)
            // 1504 is the well-known Android hardware decoder ceiling: many MediaCodec OMX/GPU
            // paths fail or render green above this on either edge, even though the encoder accepts it.
            // 1504 是已知的 Android 硬件解码器上限；任一边超过此值时，多条 MediaCodec OMX/GPU
            // 路径会失败或出现绿屏，即便编码器本身接受更大的分辨率。
            if (layout.outputWidth > 1504 || layout.outputHeight > 1504) {
                send(
                    EncodeProgress.Warning(
                        "[Warning] Output video ${layout.outputWidth}x${layout.outputHeight} is over 1504. " +
                                "Some devices may show green screen.",
                    ),
                )
            }

            PlatformFs.mkdir(outputDir)
            PlatformFs.mkdir(framesDir)

            val progressMutex = Mutex()
            var done = 0
            val semaphore = Semaphore(parallelism.coerceAtLeast(1))
            val outer = this

            coroutineScope {
                inputFrames.mapIndexed { index, inPath ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
                            ensureActive()
                            val image = PlatformPng.readArgb(inPath)
                                ?: error("missing frame $inPath")
                            val packed = AlphaFramePacker.pack(
                                inputArgb = image.argb,
                                inputW = image.width,
                                inputH = image.height,
                                layout = layout,
                                scale = request.scale.coerceIn(0.5f, 1f),
                            )
                            val outPath = PlatformFs.framePath(framesDir, index)
                            PlatformPng.writeArgb(
                                outPath,
                                ArgbImage(layout.outputWidth, layout.outputHeight, packed),
                            )
                            progressMutex.withLock {
                                done++
                                // Progress budget: 0..0.30 = scan, 0.30..0.95 = pack, 0.95..1.00 = FFmpeg+rewrite.
                                // 进度预算分配：0..0.30 扫描，0.30..0.95 打包，0.95..1.00 FFmpeg 与封装。
                                val fraction = 0.3f + done.toFloat() / totalFrames * 0.65f
                                outer.send(
                                    EncodeProgress.Running(fraction, "frames $done/$totalFrames"),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            ensureActive()
            send(EncodeProgress.Running(0.95f, "encoding video"))

            val config = VapConfig(
                version = request.version,
                totalFrames = totalFrames,
                width = layout.rgb.w,
                height = layout.rgb.h,
                videoWidth = layout.outputWidth,
                videoHeight = layout.outputHeight,
                fps = request.fps.coerceAtLeast(1),
                alphaFrame = layout.alpha,
                rgbFrame = layout.rgb,
                hasAlpha = layout.hasAlpha,
            )
            val json = VapcJson.build(config)
            val jsonPath = PlatformFs.join(outputDir, VAPC_JSON)
            val binPath = PlatformFs.join(outputDir, VAPC_BIN)
            val tmpVideo = PlatformFs.join(outputDir, TEMP_VIDEO)
            val finalVideo = PlatformFs.join(outputDir, VIDEO)

            withContext(Dispatchers.IO) {
                PlatformFs.writeText(jsonPath, json)
                PlatformFs.writeBytes(binPath, VapcJson.toBin(json.encodeToByteArray()))

                val ffmpegCode = PlatformProcess.run(buildFfmpegCmd(request, framesDir, tmpVideo))
                if (ffmpegCode != 0) {
                    error("ffmpeg failed with exit code $ffmpegCode")
                }

                Mp4BoxInserter.insertFile(
                    inputPath = tmpVideo,
                    atomPath = binPath,
                    outputPath = finalVideo,
                    position = 3,
                )

                PlatformFs.deleteIfExists(tmpVideo)
                PlatformFs.deleteIfExists(binPath)
            }

            send(
                EncodeProgress.Success(
                    EncodeResult(
                        videoPath = finalVideo,
                        vapcJsonPath = jsonPath,
                        config = config,
                        hasAlpha = layout.hasAlpha,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(EncodeProgress.Failed(e.message ?: "encode failed", e))
        }
    }

    private data class Prepared(
        val rgbSize: VapSize,
        val inputFrames: List<String>,
        val outputDir: String,
        val framesDir: String,
    )

    private fun prepare(request: EncodeRequest): Prepared {
        when (val q = request.quality) {
            is Quality.Bitrate -> require(q.kbps > 0) { "bitrate must be > 0" }
            is Quality.Vbr -> {
                require(q.targetKbps > 0) { "vbr target bitrate must be > 0" }
                require(q.maxKbps >= q.targetKbps) { "vbr max bitrate must be >= target" }
            }

            is Quality.Crf -> require(q.value in 0..51) { "crf must be in [0,51]" }
        }

        val inputDir = PlatformFs.ensureTrailingSeparator(request.inputDir)
        require(PlatformFs.exists(inputDir)) { "input path invalid: $inputDir" }

        val inputFrames = resolveInputFrames(inputDir)
        val rgbSize = PlatformPng.readSize(inputFrames.first())
            ?: error("cannot read first frame: ${inputFrames.first()}")

        val outputDir = PlatformFs.ensureTrailingSeparator(
            request.outputDir ?: PlatformFs.join(inputDir, "output"),
        )
        val framesDir = PlatformFs.ensureTrailingSeparator(PlatformFs.join(outputDir, "frames"))
        return Prepared(rgbSize, inputFrames, outputDir, framesDir)
    }

    /**
     * Decode every input PNG in parallel and inspect each ARGB pixel's alpha byte.
     * Returns `true` as soon as any pixel anywhere in the sequence has alpha < 0xFF.
     * Subsequent coroutines short-circuit on the shared atomic to avoid wasting work
     * once transparency has been observed.
     *
     * 并行解码所有输入 PNG，并检查每个 ARGB 像素的 alpha 字节。
     * 序列中任一像素的 alpha 小于 0xFF 时即返回 `true`；检测到透明像素后，
     * 其余协程通过共享原子状态尽早退出，以避免无效工作。
     */
    private suspend fun scanAnyTransparent(
        inputFrames: List<String>,
        onProgress: suspend (Float, String) -> Unit,
    ): Boolean = coroutineScope {
        val found = AtomicBoolean(false)
        val semaphore = Semaphore(parallelism.coerceAtLeast(1))
        val progressMutex = Mutex()
        val total = inputFrames.size
        var done = 0
        inputFrames.map { path ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    ensureActive()
                    if (found.load()) return@async
                    val img = PlatformPng.readArgb(path) ?: return@async
                    for (color in img.argb) {
                        ensureActive()
                        if ((color ushr 24) != 0xff) {
                            found.store(true)
                            return@async
                        }
                    }
                    progressMutex.withLock {
                        done++
                        onProgress(done.toFloat() / total, "scan $done/$total")
                    }
                }
            }
        }.awaitAll()
        found.load()
    }

    private fun buildFfmpegCmd(
        request: EncodeRequest,
        framesDir: String,
        tmpVideo: String
    ): List<String> {
        val pattern = PlatformFs.join(framesDir, "%03d.png")
        val cmd = mutableListOf(
            request.ffmpegPath,
            "-framerate", request.fps.toString(),
            "-i", pattern,
            "-pix_fmt", "yuv420p",
        )
        when (request.codec) {
            VideoCodec.H265 -> {
                cmd += listOf("-vcodec", "libx265")
                appendQualityArgs(cmd, request.quality)
                // `hvc1` is the QuickTime/iOS HEVC tag; without it Apple players reject the file
                // even though the bitstream itself is valid.
                // `hvc1` 是 QuickTime/iOS 的 HEVC 标签；缺少该标签时 Apple 播放器会拒绝文件，
                // 即便码流本身合法。
                cmd += listOf("-profile:v", "main", "-level", "4.0", "-tag:v", "hvc1")
            }

            VideoCodec.H264 -> {
                cmd += listOf("-vcodec", "libx264")
                appendQualityArgs(cmd, request.quality)
                // `-bf 0` disables B-frames so every frame is independently seekable; required by
                // several low-latency MediaCodec pipelines that cannot reorder reference pictures.
                // `-bf 0` 关闭 B 帧以确保每帧独立可寻址；部分无法重排参考帧的低延迟
                // MediaCodec 管线必须如此设置。
                cmd += listOf("-profile:v", "main", "-level", "4.0", "-bf", "0")
            }
        }
        cmd += listOf("-y", tmpVideo)
        return cmd
    }

    private fun appendQualityArgs(cmd: MutableList<String>, quality: Quality) {
        // The trailing `k` tells FFmpeg the value is in kilobits; `-bufsize` mirrors the rate
        // cap so the encoder's rate-control window matches the user's intent.
        // 末尾的 `k` 表示数值为千比特；`-bufsize` 与速率上限对齐，保证编码器码率控制窗口
        // 与用户意图一致。
        cmd += when (quality) {
            is Quality.Bitrate -> {
                listOf("-b:v", "${quality.kbps}k", "-bufsize", "${quality.kbps}k")
            }

            is Quality.Vbr -> {
                listOf(
                    "-b:v", "${quality.targetKbps}k",
                    "-maxrate", "${quality.maxKbps}k",
                    "-bufsize", "${quality.maxKbps}k",
                )
            }

            // CRF mode uses a fixed 2000k bufsize so target quality is decoupled from a chosen bitrate.
            // CRF 模式采用固定的 2000k bufsize，使目标质量与所选码率解耦。
            is Quality.Crf -> {
                listOf("-crf", quality.value.toString(), "-bufsize", "2000k")
            }
        }
    }

    public companion object {
        public const val VIDEO: String = "video.mp4"
        public const val TEMP_VIDEO: String = "tmp_video.mp4"
        public const val VAPC_JSON: String = "vapc.json"
        public const val VAPC_BIN: String = "vapc.bin"
    }
}
