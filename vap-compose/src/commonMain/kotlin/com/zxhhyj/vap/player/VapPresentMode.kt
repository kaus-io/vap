package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

/**
 * How a [VapAnimationState] will physically present its frames.
 *
 * [VapAnimationState] 在物理层面呈现帧的策略。
 */
internal enum class VapPresentMode {
    /**
     * Draw via Compose `drawBehind` on a Canvas / `drawImage` path.
     *
     * 通过 Compose `drawBehind` 在 Canvas / `drawImage` 路径上绘制。
     */
    Canvas,
    /**
     * Present into a platform Surface (Android Embedded External Surface).
     *
     * 直接呈现到平台 Surface（Android Embedded External Surface）。
     */
    Surface,
}

/**
 * Resolves the present mode from an explicit [override] (if non-null) or the platform default.
 *
 * 按以下顺序解析 present 模式：显式 [override]（若非空）优先，否则取平台默认值。
 */
@Composable
internal expect fun currentVapPresentMode(override: VapPresentMode? = null): VapPresentMode
