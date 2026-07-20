package com.zxhhyj.vap.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.demo_home_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.demo_home_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DemoHome(
    onOpenCase: (DemoCase) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.demo_home_title),
            style = MaterialTheme.typography.h6,
        )
        Text(
            text = stringResource(Res.string.demo_home_subtitle),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        demoCasesForPlatform.forEach { case ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onOpenCase(case) }),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = case.titleText(),
                        style = MaterialTheme.typography.subtitle1,
                    )
                    Text(
                        text = case.subtitleText(),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
