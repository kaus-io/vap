package com.zxhhyj.vap.tool

import com.zxhhyj.vap.encode.DefaultVapEncoder
import com.zxhhyj.vap.encode.EncodeProgress
import com.zxhhyj.vap.encode.EncodeRequest
import com.zxhhyj.vap.encode.HasAlpha
import com.zxhhyj.vap.encode.Quality
import com.zxhhyj.vap.encode.VideoCodec
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.PrintStream

internal object Cli {

    private const val USAGE = """Usage: vap-tool <input-dir> [options]

Encodes a VAP animation from a directory of PNG frames.

Options:
  -o, --output <dir>      Output directory (default: <input-dir>/output)
      --fps <int>         Frame rate (default: 60)
      --scale <float>     Alpha mask scale, 0.5-1.0 (default: 0.5)
      --codec <h264|h265> Video codec (default: h265)
      --quality <mode>    bitrate | vbr | crf (default: bitrate)
      --bitrate <kbps>    Target bitrate for 'bitrate' mode (default: 3000)
      --vbr-target <kbps> Target bitrate for 'vbr' mode (default: 3000)
      --vbr-max <kbps>    Max bitrate for 'vbr' mode (default: 4500)
      --crf <0-51>        CRF value for 'crf' mode (default: 29)
      --ffmpeg <path>     ffmpeg executable (default: ffmpeg)
      --alpha <mode>      on | off | auto (required); auto scans all frames
      --vapc-version <int>  vapc box version (default: 3)
      --no-progress       Suppress per-frame progress output
  -h, --help              Show this help

Exit codes: 0 success, 1 encode failure, 2 invalid arguments.
"""

    /**
     * Entry point used by [Main]. Returns the process exit code so the host can
     * pass it to `System.exit`. I/O streams are injectable for testability.
     *
     * [Main] 使用的入口。返回进程退出码,由宿主传给 `System.exit`;IO 流可注入,
     * 便于测试。
     */
    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        when (val r = parseArgs(args, out, err)) {
            is ParseResult.Help -> return 0
            is ParseResult.Error -> return 2
            is ParseResult.Ok -> {
                return execute(r.parsed, out, err)
            }
        }
    }

    /**
     * Runs [DefaultVapEncoder.encode] inside [runBlocking] and mirrors [EncodeProgress]
     * to the supplied streams. Exit code: 0 on success, 1 on failure.
     *
     * 在 [runBlocking] 中执行 [DefaultVapEncoder.encode],并将 [EncodeProgress] 事件
     * 镜像输出到给定流。退出码:成功 0,失败 1。
     */
    private fun execute(
        parsed: Parsed,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val request = EncodeRequest(
            inputDir = parsed.inputDir,
            fps = parsed.fps,
            scale = parsed.scale,
            codec = parsed.codec,
            quality = parsed.quality,
            ffmpegPath = parsed.ffmpegPath,
            version = parsed.version,
            hasAlpha = parsed.hasAlpha,
            outputDir = parsed.outputDir,
        )
        // Echo the resolved request first so a CI log shows exactly what was encoded
        // even if the run later aborts.
        // 先回显解析后的 request,便于 CI 日志即使后续中断也能看到实际编码参数。
        out.println("input  : ${request.inputDir}")
        out.println("output : ${request.outputDir ?: "<input>/output"}")
        out.println("fps    : ${request.fps}")
        out.println("scale  : ${request.scale}")
        out.println("codec  : ${request.codec.name.lowercase()}")
        out.println("quality: ${formatQuality(request.quality)}")
        out.println("alpha  : ${request.hasAlpha.name.lowercase()}")
        out.println()

        var failed: String? = null
        var result: com.zxhhyj.vap.encode.EncodeResult? = null
        val encoder = DefaultVapEncoder()
        val showProgress = parsed.showProgress
        // CLI is a short-lived foreground process so a blocking collect is fine and
        // keeps the call site simpler than juggling a Job.
        // CLI 是短生命周期的前台进程,直接阻塞 collect 既可,
        // 比管理 Job 更直观。
        runBlocking {
            encoder.encode(request).collect { event ->
                when (event) {
                    is EncodeProgress.Running -> {
                        if (showProgress) {
                            val pct = (event.fraction * 100).toInt().coerceIn(0, 100)
                            // \r + padEnd keeps the single-line progress on TTYs that
                            // overwrite in place; println() at 100% finalizes the line.
                            // \r + padEnd 让单行进度在支持原地覆盖的 TTY 上保持同一行,
                            // 到 100% 时 println 换行收尾。
                            out.print("\r[$pct%] ${event.message}".padEnd(40))
                            if (event.fraction >= 1f) out.println()
                        }
                    }
                    is EncodeProgress.Warning -> {
                        out.println(event.message)
                    }
                    is EncodeProgress.Success -> {
                        result = event.result
                    }
                    is EncodeProgress.Failed -> {
                        failed = event.message
                    }
                }
            }
        }
        val r = result
        return if (r != null) {
            out.println()
            out.println("video     : ${r.videoPath}")
            out.println("vapc.json : ${r.vapcJsonPath}")
            out.println("size      : ${r.config.width}x${r.config.height}")
            out.println("frames    : ${r.config.totalFrames} @ ${r.config.fps} fps")
            out.println("videoW/H  : ${r.config.videoWidth}x${r.config.videoHeight}")
            out.println("alpha     : ${if (r.hasAlpha) "transparent (with mask)" else "opaque (no mask)"}")
            0
        } else {
            err.println("error: ${failed ?: "unknown failure"}")
            1
        }
    }

    private fun formatQuality(q: Quality): String = when (q) {
        is Quality.Bitrate -> "bitrate ${q.kbps}kbps"
        is Quality.Vbr -> "vbr ${q.targetKbps}/${q.maxKbps}kbps"
        is Quality.Crf -> "crf ${q.value}"
    }

    private sealed class ParseResult {
        data class Ok(val parsed: Parsed) : ParseResult()
        object Help : ParseResult()
        object Error : ParseResult()
    }

    private data class Parsed(
        val inputDir: String,
        val outputDir: String?,
        val fps: Int,
        val scale: Float,
        val codec: VideoCodec,
        val quality: Quality,
        val ffmpegPath: String,
        val version: Int,
        val hasAlpha: HasAlpha,
        val showProgress: Boolean,
    )

    /**
     * Hand-rolled argv parser. Each `when` branch validates its own option; any
     * failure short-circuits with `ParseResult.Error` so [execute] is only called
     * on a fully validated [Parsed].
     *
     * 手写 argv 解析器。每个 `when` 分支独立校验自身参数,失败即短路返回
     * `ParseResult.Error`,保证 [execute] 只会在完整合法的 [Parsed] 上被调用。
     */
    private fun parseArgs(args: Array<String>, out: PrintStream, err: PrintStream): ParseResult {
        if (args.isEmpty()) {
            err.println(USAGE)
            return ParseResult.Error
        }
        var inputDir: String? = null
        var outputDir: String? = null
        var fps = 60
        var scale = 0.5f
        var codec = VideoCodec.H265
        var qualityMode = "bitrate"
        var bitrate = 3000
        var vbrTarget = 3000
        var vbrMax = 4500
        var crf = 29
        var ffmpegPath = "ffmpeg"
        var hasAlpha: HasAlpha? = null
        var version = 3
        var showProgress = true

        var i = 0
        // `needValue` advances `i` past the value on success and returns it; returns
        // null (and prints an error) when the next token is missing or another flag.
        // `needValue` 成功时把 `i` 推进到 value 之后并返回;若下一项缺失或又是个
        // flag 则返回 null 并打印错误。
        fun needValue(name: String): String? {
            val v = args.getOrNull(i + 1)
            if (v == null || v.startsWith("-")) {
                err.println("error: $name requires a value")
                return null
            }
            i++
            return v
        }

        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> {
                    out.println(USAGE)
                    return ParseResult.Help
                }
                a == "--no-progress" -> showProgress = false
                a == "-o" || a == "--output" -> {
                    outputDir = needValue(a) ?: return ParseResult.Error
                }
                a == "--fps" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v <= 0) { err.println("error: --fps must be a positive int"); return ParseResult.Error }
                    fps = v
                }
                a == "--scale" -> {
                    val v = needValue(a)?.toFloatOrNull()
                    if (v == null || v < 0.5f || v > 1f) { err.println("error: --scale must be in [0.5, 1.0]"); return ParseResult.Error }
                    scale = v
                }
                a == "--codec" -> {
                    val v = needValue(a)?.lowercase()
                    codec = when (v) {
                        "h264" -> VideoCodec.H264
                        "h265" -> VideoCodec.H265
                        else -> { err.println("error: --codec must be h264 or h265"); return ParseResult.Error }
                    }
                }
                a == "--quality" -> {
                    val v = needValue(a)?.lowercase()
                    qualityMode = when (v) {
                        "bitrate", "vbr", "crf" -> v
                        else -> { err.println("error: --quality must be bitrate, vbr or crf"); return ParseResult.Error }
                    }
                }
                a == "--bitrate" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v <= 0) { err.println("error: --bitrate must be a positive int"); return ParseResult.Error }
                    bitrate = v
                }
                a == "--vbr-target" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v <= 0) { err.println("error: --vbr-target must be a positive int"); return ParseResult.Error }
                    vbrTarget = v
                }
                a == "--vbr-max" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v <= 0) { err.println("error: --vbr-max must be a positive int"); return ParseResult.Error }
                    vbrMax = v
                }
                a == "--crf" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v !in 0..51) { err.println("error: --crf must be in 0..51"); return ParseResult.Error }
                    crf = v
                }
                a == "--ffmpeg" -> {
                    ffmpegPath = needValue(a) ?: return ParseResult.Error
                }
                a == "--alpha" -> {
                    val v = needValue(a)?.lowercase()
                    hasAlpha = when (v) {
                        "on" -> HasAlpha.On
                        "off" -> HasAlpha.Off
                        "auto" -> HasAlpha.Auto
                        else -> { err.println("error: --alpha must be on, off or auto"); return ParseResult.Error }
                    }
                }
                a == "--vapc-version" -> {
                    val v = needValue(a)?.toIntOrNull()
                    if (v == null || v <= 0) { err.println("error: --vapc-version must be a positive int"); return ParseResult.Error }
                    version = v
                }
                a.startsWith("-") -> {
                    err.println("error: unknown option: $a")
                    err.println(USAGE)
                    return ParseResult.Error
                }
                else -> {
                    if (inputDir != null) {
                        err.println("error: multiple positional arguments given: '$inputDir' and '$a'")
                        return ParseResult.Error
                    }
                    inputDir = a
                }
            }
            i++
        }
        val dir = inputDir
        if (dir == null) {
            err.println("error: <input-dir> is required")
            err.println(USAGE)
            return ParseResult.Error
        }
        val alpha = hasAlpha
        if (alpha == null) {
            err.println("error: --alpha is required (on or off)")
            err.println(USAGE)
            return ParseResult.Error
        }
        val quality: Quality = when (qualityMode) {
            "vbr" -> Quality.Vbr(vbrTarget, vbrMax.coerceAtLeast(vbrTarget))
            "crf" -> Quality.Crf(crf)
            else -> Quality.Bitrate(bitrate)
        }
        return ParseResult.Ok(
            Parsed(
                inputDir = dir,
                outputDir = outputDir,
                fps = fps,
                scale = scale,
                codec = codec,
                quality = quality,
                ffmpegPath = ffmpegPath,
                version = version,
                hasAlpha = alpha,
                showProgress = showProgress,
            ),
        )
    }
}