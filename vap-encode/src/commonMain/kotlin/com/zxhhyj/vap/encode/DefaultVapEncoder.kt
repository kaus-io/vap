package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapConfig
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
import kotlin.coroutines.cancellation.CancellationException

public class DefaultVapEncoder(
    private val parallelism: Int = 16,
) : VapEncoder {

    override fun encode(request: EncodeRequest): Flow<EncodeProgress> = channelFlow {
        try {
            val warnings = mutableListOf<String>()
            val prepared = withContext(Dispatchers.Default) {
                prepare(request) { warnings += it }
            }
            warnings.forEach { send(EncodeProgress.Warning(it)) }
            val (layout, inputFrames, outputDir, framesDir) = prepared
            val totalFrames = inputFrames.size

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
                                val fraction = done.toFloat() / totalFrames * 0.9f
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
        val layout: EncodeLayout,
        val inputFrames: List<String>,
        val outputDir: String,
        val framesDir: String,
    )

    private fun prepare(
        request: EncodeRequest,
        onWarning: (String) -> Unit,
    ): Prepared {
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
        val size = PlatformPng.readSize(inputFrames.first())
            ?: error("cannot read first frame: ${inputFrames.first()}")
        val layout = EncodeLayout.compute(size.width, size.height, request.scale)
        if (layout.outputWidth > 1504 || layout.outputHeight > 1504) {
            onWarning(
                "[Warning] Output video ${layout.outputWidth}x${layout.outputHeight} is over 1504. " +
                        "Some devices may show green screen.",
            )
        }

        val outputDir = PlatformFs.ensureTrailingSeparator(PlatformFs.join(inputDir, "output"))
        val framesDir = PlatformFs.ensureTrailingSeparator(PlatformFs.join(outputDir, "frames"))
        return Prepared(layout, inputFrames, outputDir, framesDir)
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
                cmd += listOf("-profile:v", "main", "-level", "4.0", "-tag:v", "hvc1")
            }

            VideoCodec.H264 -> {
                cmd += listOf("-vcodec", "libx264")
                appendQualityArgs(cmd, request.quality)
                cmd += listOf("-profile:v", "main", "-level", "4.0", "-bf", "0")
            }
        }
        cmd += listOf("-y", tmpVideo)
        return cmd
    }

    private fun appendQualityArgs(cmd: MutableList<String>, quality: Quality) {
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
