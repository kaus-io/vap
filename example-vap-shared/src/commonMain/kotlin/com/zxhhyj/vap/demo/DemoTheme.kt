package com.zxhhyj.vap.demo

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DemoColors = darkColors(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE8EAF0),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE8EAF0),
    error = Color(0xFFFF8A80),
)

@Composable
internal fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = DemoColors, content = content)
}
