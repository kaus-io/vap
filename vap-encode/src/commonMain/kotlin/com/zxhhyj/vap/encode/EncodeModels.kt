package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapConfig
import kotlinx.coroutines.flow.Flow

public enum class VideoCodec {
    H264,
    H265,
}

public sealed class Quality {
    public data class Bitrate(val kbps: Int) : Quality()
    public data class Vbr(val targetKbps: Int, val maxKbps: Int) : Quality()
    public data class Crf(val value: Int) : Quality()
}

public data class EncodeRequest(
    val inputDir: String,
    val fps: Int = 30,
    val scale: Float = 0.5f,
    val codec: VideoCodec = VideoCodec.H265,
    val quality: Quality = Quality.Bitrate(3000),
    val ffmpegPath: String = "ffmpeg",
    val version: Int = 2,
)

public data class EncodeResult(
    val videoPath: String,
    val vapcJsonPath: String,
    val config: VapConfig,
)

public sealed class EncodeProgress {
    public data class Running(val fraction: Float, val message: String = "") : EncodeProgress()
    public data class Warning(val message: String) : EncodeProgress()
    public data class Success(val result: EncodeResult) : EncodeProgress()
    public data class Failed(val message: String, val cause: Throwable? = null) : EncodeProgress()
}

public interface VapEncoder {
    public fun encode(request: EncodeRequest): Flow<EncodeProgress>
}
