package com.zxhhyj.vap

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Desktop entry point. The JVM decoder is selected automatically by the platform source set.
 *
 * 桌面端入口。JVM 解码器由平台 source set 自动选择。
 */
fun main() {
    application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "vap",
    ) {
        App()
    }
}
}