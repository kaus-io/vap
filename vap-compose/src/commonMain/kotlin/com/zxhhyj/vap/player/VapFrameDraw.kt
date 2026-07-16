package com.zxhhyj.vap.player

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zxhhyj.vap.decode.VapPlatformFrame

internal expect fun DrawScope.drawVapPlatformFrame(
    frame: VapPlatformFrame,
    dstOffset: IntOffset,
    dstSize: IntSize,
)
