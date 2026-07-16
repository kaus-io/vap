package com.zxhhyj.vap.vapc

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapRect
import kotlinx.serialization.Serializable

@Serializable
public data class VapcRoot(
    val info: VapcInfo,
)

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
)

public fun VapcRoot.toVapConfig(): VapConfig {
    val info = this.info
    if (info.v != 2) {
        error("unsupported vapc version=${info.v} (expected 2)")
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
        ),
    )
}

private fun List<Int>.toVapRect(): VapRect =
    VapRect(this[0], this[1], this[2], this[3])
