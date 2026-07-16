package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable

@Composable
internal actual fun VapSurfaceBackendScope(content: @Composable () -> Unit) {
    content()
}
