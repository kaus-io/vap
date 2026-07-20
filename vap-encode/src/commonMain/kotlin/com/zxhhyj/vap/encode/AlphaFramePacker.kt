package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapRect

/**
 * Packs one ARGB frame into the RGB/alpha regions described by [EncodeLayout].
 * Input arrays remain caller-owned and are never mutated or retained.
 *
 * 按 [EncodeLayout] 描述的 RGB/alpha 区域打包单个 ARGB 帧。
 * 输入数组始终归调用方所有，既不会被修改，也不会被持有。
 */
public object AlphaFramePacker {
    /**
     * Returns a newly allocated opaque ARGB canvas with RGB composited over black and alpha encoded as grayscale.
     *
     * 返回新分配的不透明 ARGB 画布：RGB 合成到黑底，alpha 编码为灰度。
     */
    public fun pack(
        inputArgb: IntArray,
        inputW: Int,
        inputH: Int,
        layout: EncodeLayout,
        scale: Float,
    ): IntArray {
        require(inputArgb.size >= inputW * inputH)
        val outW = layout.outputWidth
        val outH = layout.outputHeight
        // Opaque black initializes codec padding and prevents transparent pixels from carrying stray RGB.
        // 不透明黑色用于初始化编解码器补齐区，并避免透明像素携带杂散 RGB。
        val output = IntArray(outW * outH) { 0xff000000.toInt() }

        fillRegion(
            output = output,
            outputW = outW,
            dest = layout.rgb,
            isAlpha = false,
            inputArgb = inputArgb,
            inputW = inputW,
        )

        if (!layout.hasAlpha) return output

        // Downsample only the alpha mask; the RGB region always preserves source resolution.
        // 仅缩小 alpha 蒙版；RGB 区域始终保留源分辨率。
        val alphaArgb = if (scale < 1f) {
            scaleNearest(inputArgb, inputW, inputH, scale)
        } else {
            inputArgb
        }

        fillRegion(
            output = output,
            outputW = outW,
            dest = layout.alpha,
            isAlpha = true,
            inputArgb = alphaArgb,
            inputW = inputW,
        )
        return output
    }

    private fun fillRegion(
        output: IntArray,
        outputW: Int,
        dest: VapRect,
        isAlpha: Boolean,
        inputArgb: IntArray,
        inputW: Int,
    ) {
        for (y in 0 until dest.h) {
            val outY = dest.y + y
            for (x in 0 until dest.w) {
                val outX = dest.x + x
                val color = inputArgb[x + y * inputW]
                output[outX + outY * outputW] =
                    if (isAlpha) alphaGray(color) else blendOnBlack(color)
            }
        }
    }

    internal fun alphaGray(color: Int): Int {
        // Store source alpha in all RGB channels while keeping the packed video pixel opaque.
        // 将源 alpha 写入全部 RGB 通道，同时保持打包视频像素为不透明。
        val alpha = color ushr 24
        return 0xff000000.toInt() + (alpha shl 16) + (alpha shl 8) + alpha
    }

    internal fun blendOnBlack(color: Int): Int {
        // Composite source RGB over black so lossy video carries color already weighted by alpha.
        // 将源 RGB 合成到黑底，使有损视频承载已按 alpha 加权的颜色。
        val alpha = (color ushr 24) / 255f
        val r = ((color and 0x00ff0000) ushr 16) / 255f
        val g = ((color and 0x0000ff00) ushr 8) / 255f
        val b = (color and 0x000000ff) / 255f
        val or = (alpha * r * 255f).toInt()
        val og = (alpha * g * 255f).toInt()
        val ob = (alpha * b * 255f).toInt()
        return 0xff000000.toInt() + (or shl 16) + (og shl 8) + ob
    }

    private fun scaleNearest(
        inputArgb: IntArray,
        inputW: Int,
        inputH: Int,
        scale: Float,
    ): IntArray {
        // Preserve the original row stride in scratch storage; only the top-left scaled extent is read later.
        // 临时存储保留原始行跨度；后续只读取左上角缩放后的有效范围。
        val out = IntArray(inputW * inputH)
        val scaledW = (inputW * scale).toInt().coerceAtLeast(1)
        val scaledH = (inputH * scale).toInt().coerceAtLeast(1)
        for (y in 0 until scaledH) {
            val srcY = ((y / scale).toInt()).coerceIn(0, inputH - 1)
            for (x in 0 until scaledW) {
                val srcX = ((x / scale).toInt()).coerceIn(0, inputW - 1)
                out[x + y * inputW] = inputArgb[srcX + srcY * inputW]
            }
        }
        return out
    }
}
