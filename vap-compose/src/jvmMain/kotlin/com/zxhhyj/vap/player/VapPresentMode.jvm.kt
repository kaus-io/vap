package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

/**
 * JVM `actual`: no `LocalVapRenderBackend` on desktop, so an explicit [override] (or the
 * [VapPresentMode.Canvas] default) is the only option.
 *
 * JVM `actual`：桌面端没有 `LocalVapRenderBackend`，因此只能使用显式 [override]，
 * 否则取 [VapPresentMode.Canvas] 默认值。
 */
@Composable
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode =
    override ?: VapPresentMode.Canvas
