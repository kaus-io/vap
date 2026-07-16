package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

@Composable
expect fun rememberDefaultVapSource(): VapSource?

@Composable
expect fun rememberDemoVapSources(): List<VapDemoClip>
