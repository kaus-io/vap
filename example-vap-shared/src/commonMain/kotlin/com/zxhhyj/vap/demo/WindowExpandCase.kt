package com.zxhhyj.vap.demo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.action_hide
import com.zxhhyj.example_vap_shared.generated.resources.action_show
import com.zxhhyj.example_vap_shared.generated.resources.gate_hint_like_hanna
import com.zxhhyj.example_vap_shared.generated.resources.status_missing_idle
import com.zxhhyj.example_vap_shared.generated.resources.status_progress_paused
import com.zxhhyj.example_vap_shared.generated.resources.status_progress_playing
import com.zxhhyj.example_vap_shared.generated.resources.status_window_expand
import com.zxhhyj.example_vap_shared.generated.resources.window_collapsed_hint
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.stringResource

/** Reads playing/progress only here so the expand panel / VapAnimation can skip. */
@Composable
private fun WindowExpandProgressLabel(
    anim: VapAnimationState,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(
            if (anim.isPlaying) {
                Res.string.status_progress_playing
            } else {
                Res.string.status_progress_paused
            },
            (anim.progress * 100).toInt(),
        ),
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = modifier,
    )
}

private const val EXPAND_MS = 500
private val ExpandEasing = CubicBezierEasing(0.04f, 0.34f, 0.94f, 1.0f)
private val PanelMaxHeight = 180.dp
private val BallSize = 120.dp

@Composable
internal fun WindowExpandCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = clips.byLabel("hanna_window_idle")
        ?: clips.byLabel("hanna_touch_wake")
    var targetShowing by remember { mutableStateOf(false) }
    var currentShowing by remember { mutableStateOf(false) }


    LaunchedEffect(targetShowing) {
        if (targetShowing && !currentShowing) {
            currentShowing = true
        } else if (!targetShowing) {
            currentShowing = false
        }
    }

    val panelHeight by animateDpAsState(
        targetValue = if (currentShowing) PanelMaxHeight else 0.dp,
        animationSpec = tween(EXPAND_MS, easing = ExpandEasing),
        label = "windowExpandHeight",
    )

    val isPlaying = currentShowing

    DemoCaseScaffold(
        case = DemoCase.WindowExpand,
        onBack = onBack,
        modifier = modifier,
        status = stringResource(
            Res.string.status_window_expand,
            targetShowing.toString(),
            currentShowing.toString(),
            isPlaying.toString(),
            panelHeight.value.toInt(),
            clip?.label ?: stringResource(Res.string.status_missing_idle),
        ),
    ) {
        if (clip == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }


        val composition = rememberSyncDemoComposition(clip, onError)
        val anim = animateVapCompositionAsState(
            composition = composition,
            iterations = VapConstants.IterateForever,
            isPlaying = isPlaying,
            onError = { onError(it.message ?: it.toString()) },
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DemoChoiceChip(
                    label = stringResource(Res.string.action_show),
                    selected = targetShowing,
                    onClick = { targetShowing = true },
                    modifier = Modifier.weight(1f),
                )
                DemoChoiceChip(
                    label = stringResource(Res.string.action_hide),
                    selected = !targetShowing,
                    onClick = { targetShowing = false },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(Res.string.gate_hint_like_hanna),
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                            .height(PanelMaxHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        VapAnimation(
                            animationState = anim,
                            modifier = Modifier.size(BallSize),
                        )
                    }
                }

                if (panelHeight < 8.dp) {
                    Text(
                        text = stringResource(Res.string.window_collapsed_hint),
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            WindowExpandProgressLabel(anim = anim)
        }
    }
}
