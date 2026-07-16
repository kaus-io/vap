package com.zxhhyj.vap.player

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.VapPlatformFrame

internal actual fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
) {
    val image = frame.image.toComposeImageBitmap()
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = dstOffset,
        dstSize = dstSize,
        filterQuality = FilterQuality.Low,
    )
}
