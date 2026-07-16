package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
    }
}
