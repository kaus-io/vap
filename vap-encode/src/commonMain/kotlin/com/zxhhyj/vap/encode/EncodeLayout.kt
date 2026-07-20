package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapRect
import com.zxhhyj.vap.player.VapSize
import kotlin.math.max

/**
 * Geometry of the RGB image and optional alpha mask inside the encoded video canvas.
 * Padding belongs to the canvas only and is excluded from both content rectangles.
 *
 * RGB 图像与可选 alpha 蒙版在编码视频画布中的布局。
 * 补齐区域仅属于画布，不计入两个内容矩形。
 *
 * @property rgb RGB content rectangle in source pixels. RGB 内容矩形，单位为源像素。
 * @property alpha Alpha-mask rectangle, or an empty rectangle when [hasAlpha] is false. Alpha 蒙版矩形；[hasAlpha] 为 false 时为空矩形。
 * @property outputWidth Codec-aligned canvas width. 编解码器对齐后的画布宽度。
 * @property outputHeight Codec-aligned canvas height. 编解码器对齐后的画布高度。
 * @property vertical Whether the alpha mask is below rather than beside RGB. Alpha 蒙版是否位于 RGB 下方而非右侧。
 * @property gap Pixels separating the RGB and alpha regions. RGB 与 alpha 区域之间的间隔像素数。
 * @property hasAlpha Whether the canvas contains an alpha-mask region. 画布是否包含 alpha 蒙版区域。
 */
public data class EncodeLayout(
    val rgb: VapRect,
    val alpha: VapRect,
    val outputWidth: Int,
    val outputHeight: Int,
    val vertical: Boolean,
    val gap: Int = MIN_GAP,
    val hasAlpha: Boolean = true,
) {
    public companion object {
        /** Minimum separation between RGB and alpha-mask pixels. RGB 与 alpha 蒙版像素之间的最小间隔。 */
        public const val MIN_GAP: Int = 4

        /**
         * Computes the more compact horizontal or vertical alpha layout and pads the canvas to multiples of 16.
         * [scale] is clamped to 0.5–1.0 when an alpha region is present.
         *
         * 计算更紧凑的横向或纵向 alpha 布局，并将画布补齐到 16 的倍数。
         * 存在 alpha 区域时，[scale] 会限制在 0.5–1.0。
         */
        public fun compute(rgbW: Int, rgbH: Int, scale: Float, hasAlpha: Boolean = true): EncodeLayout {
            require(rgbW > 0 && rgbH > 0) { "invalid rgb size ${rgbW}x${rgbH}" }
            if (!hasAlpha) {
                val fill = sizeFillTo16(rgbW, rgbH)
                return EncodeLayout(
                    rgb = VapRect(0, 0, rgbW, rgbH),
                    alpha = VapRect(0, 0, 0, 0),
                    outputWidth = rgbW + fill.width,
                    outputHeight = rgbH + fill.height,
                    vertical = false,
                    hasAlpha = false,
                )
            }
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

            // Minimize the canvas's longest raw edge to improve codec/device compatibility.
            // 最小化画布未补齐前的最长边，以提升编解码器及设备兼容性。
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

            // Alignment padding extends the canvas only; content rectangles keep their unpadded geometry.
            // 对齐补边仅扩展画布；内容矩形保持补边前的几何范围。
            val fill = sizeFillTo16(rawW, rawH)
            return EncodeLayout(
                rgb = VapRect(0, 0, rgbW, rgbH),
                alpha = alpha,
                outputWidth = rawW + fill.width,
                outputHeight = rawH + fill.height,
                vertical = vertical,
                gap = gap,
                hasAlpha = true,
            )
        }

        internal fun sizeFillTo16(outW: Int, outH: Int): VapSize {
            // H.264/H.265 macroblocks are 16x16; pad up to a multiple of 16 so the codec's CTU grid
            // never straddles a content pixel and we avoid potential encoder upscaling.
            // H.264/H.265 的宏块大小为 16x16；向上补齐到 16 的倍数可保证编解码器的 CTU 网格
            // 不会跨越内容像素，避免编码器出现意外的放大处理。
            val wFill = if (outW % 16 != 0) ((outW / 16) + 1) * 16 - outW else 0
            val hFill = if (outH % 16 != 0) ((outH / 16) + 1) * 16 - outH else 0
            return VapSize(wFill, hFill)
        }
    }
}
