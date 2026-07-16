package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.settings_block_sensitivity
import com.zxhhyj.example_vap_shared.generated.resources.settings_block_volume
import com.zxhhyj.example_vap_shared.generated.resources.settings_block_wake_lang
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page2_default
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page2_surface
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page3_default
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page3_surface
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page4
import com.zxhhyj.example_vap_shared.generated.resources.settings_hint_page5
import com.zxhhyj.example_vap_shared.generated.resources.settings_page_title
import com.zxhhyj.example_vap_shared.generated.resources.settings_placeholder_item
import org.jetbrains.compose.resources.stringResource

internal const val SETTINGS_PAGER_PAGE_COUNT = 5

@Composable
internal fun SettingsAboutPage(media: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
        ) {
            media()
        }
        SettingsPlaceholderBlock(stringResource(Res.string.settings_block_wake_lang))
        SettingsPlaceholderBlock(stringResource(Res.string.settings_block_sensitivity))
        SettingsPlaceholderBlock(stringResource(Res.string.settings_block_volume))
    }
}

@Composable
internal fun SettingsPlaceholderBlock(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = title, color = MaterialTheme.colors.onSurface, fontSize = 15.sp)
    }
}

@Composable
internal fun SettingsPlainPage(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colors.onSurface,
            fontSize = 20.sp,
        )
        Text(
            text = hint,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            fontSize = 13.sp,
        )
        repeat(6) {
            SettingsPlaceholderBlock(
                stringResource(Res.string.settings_placeholder_item, it + 1),
            )
        }
    }
}

@Composable
internal fun settingsPagerPlainContent(
    page: Int,
    surfaceBackend: Boolean,
): Pair<String, String>? =
    when (page) {
        1 -> stringResource(Res.string.settings_page_title, 2) to if (surfaceBackend) {
            stringResource(Res.string.settings_hint_page2_surface)
        } else {
            stringResource(Res.string.settings_hint_page2_default)
        }

        2 -> stringResource(Res.string.settings_page_title, 3) to if (surfaceBackend) {
            stringResource(Res.string.settings_hint_page3_surface)
        } else {
            stringResource(Res.string.settings_hint_page3_default)
        }

        3 -> stringResource(Res.string.settings_page_title, 4) to
                stringResource(Res.string.settings_hint_page4)

        4 -> stringResource(Res.string.settings_page_title, 5) to
                stringResource(Res.string.settings_hint_page5)

        else -> null
    }
