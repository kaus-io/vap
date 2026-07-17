package com.zxhhyj.vap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.zxhhyj.vap.demo.PresentBenchLaunch
import com.zxhhyj.vap.demo.toPresentBenchLaunch

class MainActivity : ComponentActivity() {
    private var benchLaunch by mutableStateOf<PresentBenchLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        benchLaunch = intent.toPresentBenchLaunch()

        setContent {
            App(
                benchLaunch = benchLaunch,
                onBenchFinished = {
                    if (benchLaunch?.finishAfter == true) {
                        finish()
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        benchLaunch = intent.toPresentBenchLaunch()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
