package com.zxhhyj.vap.vapc

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapRect
import kotlinx.serialization.Serializable

/**
 * Wire schema for the JSON document stored in the MP4 `vapc` box.
 *
 * 存储于 MP4 `vapc` 盒中 JSON 文档的有线协议模型。
 *
 * Format is single-key root (`info`) so the document remains compatible with
 * VAP spec v3 while remaining lenient for future field additions.
 */
@Serializable
public data class VapcRoot(
    val info: VapcInfo,
)

/**
 * Header/manifest describing the VAP clip's geometry, frame budget and
 * per-track rectangular regions in the video frame.
 *
 * 描述 VAP 片段几何信息、帧预算以及视频帧内各轨道矩形区域的头部/清单。
 *
 * `aFrame` is the alpha track's `[x, y, w, h]` rect (x/y top-left, both inclusive).
 * `aFrame` 为 alpha 轨道在视频帧中的 `[x, y, w, h]` 矩形（左上角为原点，坐标取整）。
 */
@Serializable
public data class VapcInfo(
    val v: Int,
    val f: Int,
    val w: Int,
    val h: Int,
    val videoW: Int,
    val videoH: Int,
    val fps: Int,
    val aFrame: List<Int>,
    val rgbFrame: List<Int>,
    val hasAlpha: Boolean = true,
)

/**
 * Decode/validate a parsed [VapcRoot] into a runtime [VapConfig].
 *
 * 将已解析的 [VapcRoot] 解码并校验为运行期 [VapConfig]。
 *
 * Throws [IllegalStateException] when the schema version is not 3, and
 * [IllegalArgumentException] when frame rects are malformed.
 */
public fun VapcRoot.toVapConfig(): VapConfig {
    val info = this.info
    if (info.v != 3) {
        error("unsupported vapc version=${info.v} (expected 3)")
    }
    require(info.aFrame.size >= 4) { "vapc aFrame needs 4 ints" }
    require(info.rgbFrame.size >= 4) { "vapc rgbFrame needs 4 ints" }
    return VapConfig(
        version = info.v,
        totalFrames = info.f,
        width = info.w,
        height = info.h,
        videoWidth = info.videoW,
        videoHeight = info.videoH,
        fps = info.fps.coerceAtLeast(1),
        alphaFrame = info.aFrame.toVapRect(),
        rgbFrame = info.rgbFrame.toVapRect(),
        hasAlpha = info.hasAlpha,
    )
}

public fun VapConfig.toVapcRoot(): VapcRoot {
    val a = alphaFrame
    val c = rgbFrame
    return VapcRoot(
        info = VapcInfo(
            v = version,
            f = totalFrames,
            w = width,
            h = height,
            videoW = videoWidth,
            videoH = videoHeight,
            fps = fps,
            aFrame = listOf(a.x, a.y, a.w, a.h),
            rgbFrame = listOf(c.x, c.y, c.w, c.h),
            hasAlpha = hasAlpha,
        ),
    )
}

private fun List<Int>.toVapRect(): VapRect =
    VapRect(this[0], this[1], this[2], this[3])
