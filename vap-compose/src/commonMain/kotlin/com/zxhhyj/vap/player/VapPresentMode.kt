package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

internal enum class VapPresentMode {
    Canvas,
    Surface,
}

@Composable
internal expect fun currentVapPresentMode(override: VapPresentMode? = null): VapPresentMode
