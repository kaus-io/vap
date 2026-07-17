package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.settings_page_title
import com.zxhhyj.example_vap_shared.generated.resources.status_missing_about_card
import com.zxhhyj.example_vap_shared.generated.resources.status_setting_pager_surface
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingPagerSurfaceCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = clips.byLabel("hanna_about_card")
    val pagerState = rememberPagerState(pageCount = { SETTINGS_PAGER_PAGE_COUNT })

    DemoCaseScaffold(
        case = DemoCase.SettingPagerSurface,
        onBack = onBack,
        modifier = modifier,
        status = stringResource(
            Res.string.status_setting_pager_surface,
            pagerState.currentPage + 1,
            SETTINGS_PAGER_PAGE_COUNT,
            pagerState.settledPage,
            clip?.label ?: stringResource(Res.string.status_missing_about_card),
        ),
    ) {
        if (clip == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        val composition = rememberSyncDemoComposition(clip, onError)
        val aboutAnim = animateVapCompositionAsState(
            composition = composition,
            iterations = VapConstants.IterateForever,
            isPlaying = true,
            onError = { onError(it.message ?: it.toString()) },
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colors.surface),
        ) { page ->
            if (page == 0) {
                SettingsAboutPage {
                    VapAnimation(
                        animationState = aboutAnim,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val (title, hint) = settingsPagerPlainContent(page, surfaceBackend = true)
                    ?: (stringResource(Res.string.settings_page_title, page + 1) to "")
                SettingsPlainPage(title, hint)
            }
        }
    }
}
