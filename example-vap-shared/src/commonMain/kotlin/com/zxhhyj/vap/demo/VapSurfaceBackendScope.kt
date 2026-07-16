package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable

@Composable
internal expect fun VapSurfaceBackendScope(content: @Composable () -> Unit)
