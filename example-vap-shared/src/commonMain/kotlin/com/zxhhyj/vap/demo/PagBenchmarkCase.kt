package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zxhhyj.vap.player.VapDemoClip

/**
 * Measures single-instance PAGView presentation cadence on supported platforms.
 *
 * 在支持的平台上测量单实例 PAGView 的呈现节奏。
 */
@Composable
internal expect fun PagPresentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
)

/**
 * Measures concurrent PAGView presentation cadence on supported platforms.
 *
 * 在支持的平台上测量并发 PAGView 的呈现节奏。
 */
@Composable
internal expect fun PagConcurrentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
)

/**
 * Measures single-instance PAGImageView presentation cadence on supported platforms.
 *
 * 在支持的平台上测量单实例 PAGImageView 的呈现节奏。
 */
@Composable
internal expect fun PagImageViewPresentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
)

/**
 * Measures concurrent PAGImageView presentation cadence on supported platforms.
 *
 * 在支持的平台上测量并发 PAGImageView 的呈现节奏。
 */
@Composable
internal expect fun PagImageViewConcurrentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
)

/**
 * StateSwap benchmark cycles through [PresentBenchLaunch.stateSwapSeq] every
 * [PresentBenchLaunch.stateSwapCycleMs] for [PresentBenchLaunch.stateSwapCycles] full cycles.
 * It logs `SWAP_RESULT cycle=<n> asset=<key> first_present_ms=<x>` per cycle.
 *
 * StateSwap 基准每隔 [PresentBenchLaunch.stateSwapCycleMs] 遍历一次
 * [PresentBenchLaunch.stateSwapSeq]，共执行 [PresentBenchLaunch.stateSwapCycles] 个完整周期。
 * 每个周期都会记录 `SWAP_RESULT cycle=<n> asset=<key> first_present_ms=<x>`。
 */
@Composable
internal expect fun StateSwapBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    launch: PresentBenchLaunch? = null,
    onAutoFinished: (() -> Unit)? = null,
)
