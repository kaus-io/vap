package com.zxhhyj.vap.player

import androidx.compose.runtime.Stable

/** [Stable]: [source] may be [VapSource.Bytes] with Snapshot-backed data. */
@Stable
data class VapDemoClip(
    val label: String,
    val source: VapSource,
)
