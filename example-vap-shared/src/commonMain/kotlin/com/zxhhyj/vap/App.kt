package com.zxhhyj.vap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.vap.demo.DemoNavHost
import com.zxhhyj.vap.demo.DemoTheme
import com.zxhhyj.vap.player.rememberDemoVapSources

@Composable
@Preview
fun App() {
    DemoTheme {
        var error by remember { mutableStateOf<String?>(null) }
        val clips = rememberDemoVapSources()

        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colors.error,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }

            DemoNavHost(
                clips = clips,
                onError = { error = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

