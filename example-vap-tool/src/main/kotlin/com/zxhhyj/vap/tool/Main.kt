package com.zxhhyj.vap.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapCompositionSpec
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.rememberVapComposition
import com.zxhhyj.vap.tool.generated.resources.Res
import com.zxhhyj.vap.tool.generated.resources.app_title
import com.zxhhyj.vap.tool.generated.resources.preview_error
import com.zxhhyj.vap.tool.generated.resources.preview_title
import io.github.vinceglb.filekit.FileKit
import org.jetbrains.compose.resources.stringResource

fun main() {
    FileKit.init(appId = "com.zxhhyj.vap.tool")
    application {
        var previewPath by remember { mutableStateOf<String?>(null) }

        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.app_title),
        ) {
            ToolApp(
                onEncodeSuccess = { path -> previewPath = path },
            )
        }

        val path = previewPath
        if (path != null) {
            Window(
                onCloseRequest = { previewPath = null },
                title = stringResource(Res.string.preview_title),
                state = rememberWindowState(width = 480.dp, height = 480.dp),
            ) {
                PreviewWindow(path = path)
            }
        }
    }
}

@Composable
private fun PreviewWindow(path: String) {
    MaterialTheme {
        var error by remember(path) { mutableStateOf<String?>(null) }
        val composition by rememberVapComposition(
            spec = VapCompositionSpec.File(path),
            onError = { error = it.message ?: it.toString() },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center,
        ) {
            if (error != null) {
                Text(
                    text = stringResource(Res.string.preview_error, error!!),
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                VapAnimation(
                    composition = composition,
                    modifier = Modifier.fillMaxSize(),
                    iterations = VapConstants.IterateForever,
                )
            }
        }
    }
}
