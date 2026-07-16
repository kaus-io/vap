package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.status_missing_hanna_home
import com.zxhhyj.example_vap_shared.generated.resources.status_playing_asset
import com.zxhhyj.example_vap_shared.generated.resources.status_progress_percent
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.stringResource


@Composable
internal fun HomeHeroCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = clips.byLabel("hanna_home")
    DemoCaseScaffold(
        case = DemoCase.HomeHero,
        onBack = onBack,
        modifier = modifier,
        status = clip?.let {
            stringResource(Res.string.status_playing_asset, it.label)
        } ?: stringResource(Res.string.status_missing_hanna_home),
    ) {
        if (clip == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }
        val composition = rememberSyncDemoComposition(clip, onError)
        val anim = animateVapCompositionAsState(
            composition = composition,
            iterations = VapConstants.IterateForever,
            isPlaying = true,
            onError = { onError(it.message ?: it.toString()) },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            VapAnimation(
                animationState = anim,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(420.dp),
            )
            Text(
                text = stringResource(
                    Res.string.status_progress_percent,
                    (anim.progress * 100).toInt(),
                ),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }
    }
}
