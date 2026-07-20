package com.zxhhyj.vap.player

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.VapPlatformFrame

/**
 * Platform `expect` for blitting a decoded `VapPlatformFrame` onto the current `DrawScope`.
 *
 * 将解码后的 `VapPlatformFrame` 绘制到当前 `DrawScope` 的平台 `expect` 定义。
 *
 * Implementations:
 * - Android: blits an `AndroidVapPlatformFrame` (`Bitmap`) via `nativeCanvas.drawBitmap`.
 * - JVM: wraps a `BufferedImage` into a Compose `ImageBitmap` and `drawImage`s it.
 */
internal expect fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
)
