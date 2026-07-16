package com.zxhhyj.vap.player

import android.graphics.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.VapPlatformFrame
import android.graphics.Paint as AndroidPaint

private val bitmapPaint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)

internal actual fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
) {
    val dst = Rect(
        dstOffset.x,
        dstOffset.y,
        dstOffset.x + dstSize.width,
        dstOffset.y + dstSize.height,
    )
    drawContext.canvas.nativeCanvas.drawBitmap(frame.bitmap, null, dst, bitmapPaint)
}
