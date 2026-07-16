package com.zxhhyj.vap.player

import androidx.compose.runtime.Immutable

@Immutable
data class VapDemoClip(
    val label: String,
    val source: VapSource,
)
