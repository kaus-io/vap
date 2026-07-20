package com.zxhhyj.vap.player

import androidx.compose.runtime.Stable

/**
 * Demo media descriptor whose [label] is the cross-platform lookup key.
 * `@Stable` remains valid when [source] is `VapSource.Bytes` backed by Snapshot-aware data.
 *
 * 演示媒体描述模型，[label] 是跨平台查找键。
 * 当 [source] 为由 Snapshot 感知数据支持的 `VapSource.Bytes` 时，`@Stable` 仍然成立。
 */
@Stable
data class VapDemoClip(
    val label: String,
    val source: VapSource,
)
