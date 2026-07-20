package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.demo_empty_assets
import com.zxhhyj.example_vap_shared.generated.resources.demo_platform_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DemoEmptyState(modifier: Modifier = Modifier) {
    DemoMessageState(
        text = stringResource(Res.string.demo_empty_assets),
        modifier = modifier,
    )
}

@Composable
internal fun DemoPlatformUnavailableState(modifier: Modifier = Modifier) {
    DemoMessageState(
        text = stringResource(Res.string.demo_platform_unavailable),
        modifier = modifier,
    )
}

@Composable
private fun DemoMessageState(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
