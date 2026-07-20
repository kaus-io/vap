package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        Card(
            modifier = Modifier.fillMaxWidth().height(220.dp),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.body1,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun SettingsPlainPage(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.h5,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
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
