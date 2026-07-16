package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

@Composable
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode =
    VapPresentMode.Canvas
