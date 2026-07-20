package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapConfig
import kotlinx.coroutines.flow.Flow

/**
 * Video codec requested from FFmpeg.
 *
 * 传给 FFmpeg 的视频编解码器。
 */
public enum class VideoCodec {
    H264,
    H265,
}

/**
 * FFmpeg rate-control strategy for the encoded video.
 *
 * 编码视频使用的 FFmpeg 码率控制策略。
 */
public sealed class Quality {
    /** Fixed average bitrate in kilobits per second. 固定平均码率，单位为千比特每秒。 */
    public data class Bitrate(val kbps: Int) : Quality()

    /** Target and maximum variable bitrates in kilobits per second. 可变码率的目标值与最大值，单位为千比特每秒。 */
    public data class Vbr(val targetKbps: Int, val maxKbps: Int) : Quality()

    /** Constant-rate-factor value; lower values request higher quality. 恒定质量因子；值越低，目标质量越高。 */
    public data class Crf(val value: Int) : Quality()
}

/**
 * Policy for including an alpha-mask region in the packed video.
 *
 * 是否在打包视频中包含 alpha 蒙版区域的策略。
 */
public enum class HasAlpha {
    /**
     * Transparent VAP layout with an alpha mask region.
     *
     * 包含 alpha 蒙版区域的透明 VAP 布局。
     */
    On,

    /**
     * Opaque layout with no mask region and a smaller output canvas.
     *
     * 不含蒙版区域、输出画布更小的不透明布局。
     */
    Off,

    /**
     * Scan every frame's alpha bytes; if any pixel anywhere is not fully
     * opaque the result is [On], otherwise [Off]. Layout is computed from
     * the scan result, so the encoded video matches the actual content.
     * Cost: one extra PNG-decode pass over the full sequence.
     *
     * 扫描每一帧的 alpha 字节；任一像素并非完全不透明时结果为 [On]，
     * 否则为 [Off]。布局依据扫描结果计算，使编码视频匹配实际内容。
     * 代价是对完整序列额外执行一次 PNG 解码。
     */
    Auto,
}

/**
 * Parameters for one encoding job.
 *
 * 单次编码任务的参数。
 *
 * @property inputDir Directory containing a contiguous zero-based sequence of numerically named PNG frames. 包含从 0 开始、文件名为数字且连续的 PNG 帧目录。
 * @property fps Output frame rate; values below one are encoded as one. 输出帧率；小于 1 时按 1 编码。
 * @property scale Alpha-mask scale, clamped to 0.5–1.0 when used. Alpha 蒙版缩放比例；使用时限制在 0.5–1.0。
 * @property codec Requested output codec. 请求的输出编解码器。
 * @property quality FFmpeg rate-control settings. FFmpeg 码率控制设置。
 * @property ffmpegPath FFmpeg executable path or command name. FFmpeg 可执行文件路径或命令名。
 * @property version VAP metadata version written into the configuration. 写入配置的 VAP 元数据版本。
 * @property hasAlpha Alpha-layout selection policy. Alpha 布局选择策略。
 * @property outputDir Destination directory; defaults to `inputDir/output`. 目标目录；默认为 `inputDir/output`。
 */
public data class EncodeRequest(
    val inputDir: String,
    val fps: Int = 60,
    val scale: Float = 0.5f,
    val codec: VideoCodec = VideoCodec.H265,
    val quality: Quality = Quality.Bitrate(3000),
    val ffmpegPath: String = "ffmpeg",
    val version: Int = 3,
    val hasAlpha: HasAlpha,
    val outputDir: String? = null,
)

/**
 * Successful output paths and the exact configuration embedded in the MP4.
 *
 * 成功产物的路径，以及嵌入 MP4 的精确配置。
 *
 * @property videoPath Final MP4 path. 最终 MP4 路径。
 * @property vapcJsonPath Sidecar JSON configuration path. JSON 配置旁路文件路径。
 * @property config Configuration serialized into both outputs. 序列化到两个产物中的配置。
 * @property hasAlpha Whether the encoded layout actually contains an alpha mask. 编码布局实际是否包含 alpha 蒙版。
 */
public data class EncodeResult(
    val videoPath: String,
    val vapcJsonPath: String,
    val config: VapConfig,
    val hasAlpha: Boolean,
)

/**
 * Events emitted by [VapEncoder.encode] from work start through one terminal result.
 *
 * [VapEncoder.encode] 从任务开始到单个终态结果所发出的事件。
 */
public sealed class EncodeProgress {
    /** Approximate normalized progress and a phase message. 近似归一化进度及阶段消息。 */
    public data class Running(val fraction: Float, val message: String = "") : EncodeProgress()

    /** Recoverable compatibility warning; encoding continues. 可恢复的兼容性警告；编码继续进行。 */
    public data class Warning(val message: String) : EncodeProgress()

    /** Terminal successful result. 成功终态结果。 */
    public data class Success(val result: EncodeResult) : EncodeProgress()

    /** Terminal failure with an optional originating exception. 失败终态结果及可选的原始异常。 */
    public data class Failed(val message: String, val cause: Throwable? = null) : EncodeProgress()
}

/**
 * Encodes numbered PNG frame sequences into VAP MP4 files.
 *
 * 将按数字编号的 PNG 帧序列编码为 VAP MP4 文件。
 */
public interface VapEncoder {
    /**
     * Starts a cold progress `Flow`; collection performs the job and cancellation stops ongoing work.
     * Exactly one [EncodeProgress.Success] or [EncodeProgress.Failed] is emitted unless collection is cancelled.
     *
     * 创建冷进度 `Flow`；收集时执行任务，取消收集会终止正在进行的工作。
     * 除非收集被取消，否则只会发出一个 [EncodeProgress.Success] 或 [EncodeProgress.Failed]。
     */
    public fun encode(request: EncodeRequest): Flow<EncodeProgress>
}
