package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState

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
        Card(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                VapAnimation(
                    animationState = anim,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(420.dp),
                )
            }
        }
    }
}
