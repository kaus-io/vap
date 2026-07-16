package com.zxhhyj.vap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.vap.demo.AnimatedContentExample
import com.zxhhyj.vap.demo.DemoEmptyState
import com.zxhhyj.vap.demo.DemoExample
import com.zxhhyj.vap.demo.DemoTheme
import com.zxhhyj.vap.demo.ExampleSwitcher
import com.zxhhyj.vap.demo.NavDisplayExample
import com.zxhhyj.vap.demo.PAGE_SIZE
import com.zxhhyj.vap.player.rememberDemoVapSources

@Composable
@Preview
fun App() {
    DemoTheme {
        var error by remember { mutableStateOf<String?>(null) }
        var example by remember { mutableStateOf(DemoExample.AnimatedContent) }
        val clips = rememberDemoVapSources()
        val pages = remember(clips) { clips.chunked(PAGE_SIZE) }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "VAP Preview",
                color = MaterialTheme.colors.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            ExampleSwitcher(
                selected = example,
                onSelect = { example = it },
            )
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colors.error,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pages.isEmpty()) {
                DemoEmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                when (example) {
                    DemoExample.AnimatedContent -> AnimatedContentExample(
                        pages = pages,
                        clips = clips,
                        modifier = Modifier.weight(1f),
                        onError = { error = it },
                    )

                    DemoExample.NavDisplay -> NavDisplayExample(
                        pages = pages,
                        clips = clips,
                        modifier = Modifier.weight(1f),
                        onError = { error = it },
                    )
                }
            }
        }
    }
}
