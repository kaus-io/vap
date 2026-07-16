package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapSize

internal object VapOutputSize {
    fun fit(contentW: Int, contentH: Int, viewW: Int, viewH: Int): VapSize {
        val cw = contentW.coerceAtLeast(1)
        val ch = contentH.coerceAtLeast(1)
        val vw = viewW.coerceAtLeast(1)
        val vh = viewH.coerceAtLeast(1)
        if (vw >= cw && vh >= ch) return VapSize(cw, ch)
        val scale = minOf(vw.toFloat() / cw, vh.toFloat() / ch)
        return VapSize(
            width = (cw * scale).toInt().coerceAtLeast(1),
            height = (ch * scale).toInt().coerceAtLeast(1),
        )
    }
}
