package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.zxhhyj.vap.player.ExperimentalVapSurfaceApi
import com.zxhhyj.vap.player.LocalVapRenderBackend
import com.zxhhyj.vap.player.VapRenderBackend

@OptIn(ExperimentalVapSurfaceApi::class)
@Composable
internal actual fun VapSurfaceBackendScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalVapRenderBackend provides VapRenderBackend.Surface,
        content = content,
    )
}
