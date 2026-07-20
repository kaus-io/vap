package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapSize

/**
 * Helpers for sizing the decoder output canvas to a target viewport.
 *
 * 用于将解码输出画布适配到目标视口的辅助方法。
 *
 * The fit uses "contain" semantics (uniform scale, no cropping); any zero or
 * negative input is treated as 1 to keep the resulting dimensions valid.
 */
public object VapOutputSize {
    public fun fit(contentW: Int, contentH: Int, maxW: Int, maxH: Int): VapSize {
        val cw = contentW.coerceAtLeast(1)
        val ch = contentH.coerceAtLeast(1)
        val mw = maxW.coerceAtLeast(1)
        val mh = maxH.coerceAtLeast(1)
        val scale = minOf(mw.toFloat() / cw, mh.toFloat() / ch)
        return VapSize(
            width = (cw * scale).toInt().coerceAtLeast(1),
            height = (ch * scale).toInt().coerceAtLeast(1),
        )
    }
}
