package com.zxhhyj.vap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.ui.unit.dp
import androidx.compose.material.Snackbar
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.zxhhyj.vap.demo.DemoNavHost
import com.zxhhyj.vap.demo.DemoTheme
import com.zxhhyj.vap.demo.PresentBenchLaunch
import com.zxhhyj.vap.player.rememberDemoVapSources

@Composable
@Preview
fun App(
    benchLaunch: PresentBenchLaunch? = null,
    onBenchFinished: (() -> Unit)? = null,
) {
    DemoTheme {
        var error by remember { mutableStateOf<String?>(null) }
        val clips = rememberDemoVapSources()

        Scaffold(
            snackbarHost = {
                error?.let {
                    Snackbar(modifier = Modifier.padding(16.dp)) {
                        Text(text = it, maxLines = 2)
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                DemoNavHost(
                    clips = clips,
                    onError = { error = it },
                    modifier = Modifier.fillMaxSize(),
                    benchLaunch = benchLaunch,
                    onBenchFinished = onBenchFinished,
                )
            }
        }
    }
}

