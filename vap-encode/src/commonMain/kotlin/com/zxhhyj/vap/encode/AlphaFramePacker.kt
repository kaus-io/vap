package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapRect

public object AlphaFramePacker {
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
        val output = IntArray(outW * outH) { 0xff000000.toInt() }

        fillRegion(
            output = output,
            outputW = outW,
            dest = layout.rgb,
            isAlpha = false,
            inputArgb = inputArgb,
            inputW = inputW,
        )

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
        val alpha = color ushr 24
        return 0xff000000.toInt() + (alpha shl 16) + (alpha shl 8) + alpha
    }

    internal fun blendOnBlack(color: Int): Int {
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
