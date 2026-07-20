package com.zxhhyj.vap.demo

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DemoColors = lightColors(
    primary = Color(0xFF1976D2),
    primaryVariant = Color(0xFF1565C0),
    secondary = Color(0xFF26A69A),
    secondaryVariant = Color(0xFF00897B),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    onError = Color.White,
)

private val DemoShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
internal fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = DemoColors,
        shapes = DemoShapes,
        content = content,
    )
}
