package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapRect
import com.zxhhyj.vap.player.VapSize
import kotlin.math.max

public data class EncodeLayout(
    val rgb: VapRect,
    val alpha: VapRect,
    val outputWidth: Int,
    val outputHeight: Int,
    val vertical: Boolean,
    val gap: Int = MIN_GAP,
) {
    public companion object {
        public const val MIN_GAP: Int = 4

        public fun compute(rgbW: Int, rgbH: Int, scale: Float): EncodeLayout {
            require(rgbW > 0 && rgbH > 0) { "invalid rgb size ${rgbW}x${rgbH}" }
            val s = scale.coerceIn(0.5f, 1f)
            val alphaW = (rgbW * s).toInt()
            val alphaH = (rgbH * s).toInt()
            val gap = MIN_GAP

            val hW = rgbW + gap + alphaW
            val hH = rgbH
            val hMax = max(hW, hH)

            val vW = rgbW
            val vH = rgbH + gap + alphaH
            val vMax = max(vW, vH)

            val vertical = hMax > vMax
            val alpha: VapRect
            val rawW: Int
            val rawH: Int
            if (vertical) {
                alpha = VapRect(0, rgbH + gap, alphaW, alphaH)
                rawW = rgbW
                rawH = rgbH + gap + alphaH
            } else {
                alpha = VapRect(rgbW + gap, 0, alphaW, alphaH)
                rawW = rgbW + gap + alphaW
                rawH = rgbH
            }

            val fill = sizeFillTo16(rawW, rawH)
            return EncodeLayout(
                rgb = VapRect(0, 0, rgbW, rgbH),
                alpha = alpha,
                outputWidth = rawW + fill.width,
                outputHeight = rawH + fill.height,
                vertical = vertical,
                gap = gap,
            )
        }

        internal fun sizeFillTo16(outW: Int, outH: Int): VapSize {
            val wFill = if (outW % 16 != 0) ((outW / 16) + 1) * 16 - outW else 0
            val hFill = if (outH % 16 != 0) ((outH / 16) + 1) * 16 - outH else 0
            return VapSize(wFill, hFill)
        }
    }
}
