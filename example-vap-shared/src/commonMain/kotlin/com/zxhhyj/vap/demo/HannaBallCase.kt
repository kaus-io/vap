package com.zxhhyj.vap.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.ball_idle
import com.zxhhyj.example_vap_shared.generated.resources.ball_speech
import com.zxhhyj.example_vap_shared.generated.resources.ball_think
import com.zxhhyj.example_vap_shared.generated.resources.status_hanna_ball_missing
import com.zxhhyj.example_vap_shared.generated.resources.status_hanna_ball_ready
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class BallKind(val labelRes: StringResource, val asset: String) {
    Idle(Res.string.ball_idle, "hanna_window_idle"),
    Think(Res.string.ball_think, "hanna_window_think"),
    Speech(Res.string.ball_speech, "hanna_window_speech"),
}


@Composable
internal fun HannaBallCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(BallKind.Idle) }
    val required =
        BallKind.entries.mapNotNull { ball -> clips.byLabel(ball.asset)?.let { ball to it } }
    val ready = required.size == BallKind.entries.size

    DemoCaseScaffold(
        case = DemoCase.HannaBall,
        onBack = onBack,
        modifier = modifier,
        status = if (ready) {
            stringResource(Res.string.status_hanna_ball_ready, stringResource(kind.labelRes))
        } else {
            stringResource(Res.string.status_hanna_ball_missing)
        },
    ) {
        if (!ready) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        val sessions = rememberBallSessions(
            clipsByKind = required.toMap(),
            playing = kind,
            onError = onError,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BallKind.entries.forEach { item ->
                    DemoChoiceChip(
                        label = stringResource(item.labelRes),
                        selected = item == kind,
                        onClick = { kind = item },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = kind,
                    transitionSpec = { VapFadeTransition },
                    label = "hannaBall",
                    modifier = Modifier
                        .size(120.dp)
                        .wrapContentSize(unbounded = true),
                ) { target ->
                    VapAnimation(
                        animationState = sessions.getValue(target),
                        modifier = Modifier.size(160.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberBallSessions(
    clipsByKind: Map<BallKind, VapDemoClip>,
    playing: BallKind,
    onError: (String) -> Unit,
): Map<BallKind, VapAnimationState> {
    val states = LinkedHashMap<BallKind, VapAnimationState>()
    clipsByKind.forEach { (kind, clip) ->
        key(kind) {
            val composition = rememberSyncDemoComposition(clip, onError)
            states[kind] = animateVapCompositionAsState(
                composition = composition,
                iterations = VapConstants.IterateForever,
                isPlaying = playing == kind,
                onError = { onError("${kind.name}: ${it.message ?: it}") },
            )
        }
    }
    return states
}
