package com.zxhhyj.vap.player

import android.graphics.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.AndroidVapPlatformFrame
import com.zxhhyj.vap.decode.VapPlatformFrame
import android.graphics.Paint as AndroidPaint

/**
 * Shared `Paint` with bilinear filtering enabled — reused across frames to avoid allocation.
 *
 * 启用双线性过滤的共享 `Paint` —— 跨帧复用以避免分配。
 */
private val bitmapPaint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)

/**
 * Android `actual`: blits the [AndroidVapPlatformFrame]'s `Bitmap` directly onto the
 * underlying `nativeCanvas` inside the destination rect. Filtering is handled by [bitmapPaint].
 *
 * Android `actual` 实现：将 [AndroidVapPlatformFrame] 的 `Bitmap` 直接绘制到底层 `nativeCanvas`
 * 的目标矩形中，过滤由 [bitmapPaint] 提供。
 */
internal actual fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
) {
    val bitmapFrame = frame as AndroidVapPlatformFrame
    val dst = Rect(
        dstOffset.x,
        dstOffset.y,
        dstOffset.x + dstSize.width,
        dstOffset.y + dstSize.height,
    )
    drawContext.canvas.nativeCanvas.drawBitmap(bitmapFrame.bitmap, null, dst, bitmapPaint)
}
