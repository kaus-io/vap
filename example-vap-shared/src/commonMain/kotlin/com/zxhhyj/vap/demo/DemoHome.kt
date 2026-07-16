package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.demo_home_title),
            color = MaterialTheme.colors.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.demo_home_subtitle),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        demoCasesForPlatform.forEach { case ->
            CaseEntryButton(case = case, onClick = { onOpenCase(case) })
        }
    }
}

@Composable
private fun CaseEntryButton(
    case: DemoCase,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = case.titleText(),
            color = MaterialTheme.colors.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = case.subtitleText(),
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            fontSize = 12.sp,
        )
    }
}
