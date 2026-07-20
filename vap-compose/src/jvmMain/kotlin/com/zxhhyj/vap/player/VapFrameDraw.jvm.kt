package com.zxhhyj.vap.player

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.JvmVapPlatformFrame
import com.zxhhyj.vap.decode.VapPlatformFrame

/**
 * JVM `actual`: wraps the decoder's `BufferedImage` as a Compose `ImageBitmap` and
 * blits it via [DrawScope.drawImage]. Uses [FilterQuality.Low] (nearest-neighbour) —
 * the source is pixel data, not vectors, and this matches the Android `FILTER_BITMAP_FLAG`
 * baseline.
 *
 * JVM `actual`：将解码器产出的 `BufferedImage` 包装为 Compose `ImageBitmap` 后通过
 * [DrawScope.drawImage] 绘制。使用 [FilterQuality.Low]（最近邻）；源是像素数据而非矢量，
 * 同时也与 Android `FILTER_BITMAP_FLAG` 基线保持一致。
 */
internal actual fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
) {
    val image = (frame as JvmVapPlatformFrame).image.toComposeImageBitmap()
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = dstOffset,
        dstSize = dstSize,
        filterQuality = FilterQuality.Low,
    )
}
