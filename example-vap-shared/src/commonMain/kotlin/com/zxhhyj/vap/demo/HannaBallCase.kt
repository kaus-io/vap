package com.zxhhyj.vap.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.ball_idle
import com.zxhhyj.example_vap_shared.generated.resources.ball_speech
import com.zxhhyj.example_vap_shared.generated.resources.ball_think
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

/**
 * Cross-fades among three retained playback states while only the selected state advances.
 *
 * 在三个保留的播放状态之间淡入淡出，并且仅推进当前选中的状态。
 */
@Composable
internal fun HannaBallCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(BallKind.Idle) }
    val idleClip = clips.byLabel(BallKind.Idle.asset)
    val thinkClip = clips.byLabel(BallKind.Think.asset)
    val speechClip = clips.byLabel(BallKind.Speech.asset)

    DemoCaseScaffold(
        case = DemoCase.HannaBall,
        onBack = onBack,
        modifier = modifier,
    ) {
        val idle = idleClip
        val think = thinkClip
        val speech = speechClip
        if (idle == null || think == null || speech == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        // Retain every session and toggle playback to avoid a cold rebuild on each state change.
        // 保留每个会话并切换播放状态，避免每次状态变化时冷重建。
        val idleAnim = ballAnim(idle, playing = kind == BallKind.Idle, onError)
        val thinkAnim = ballAnim(think, playing = kind == BallKind.Think, onError)
        val speechAnim = ballAnim(speech, playing = kind == BallKind.Speech, onError)
        val sessions = mapOf(
            BallKind.Idle to idleAnim,
            BallKind.Think to thinkAnim,
            BallKind.Speech to speechAnim,
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

            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = kind,
                        transitionSpec = { VapFadeTransition },
                        label = "hanna-ball",
                        modifier = Modifier.wrapContentSize(),
                    ) { current ->
                        key(current) {
                            VapAnimation(
                                animationState = sessions.getValue(current),
                                modifier = Modifier.size(160.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ballAnim(
    clip: VapDemoClip,
    playing: Boolean,
    onError: (String) -> Unit,
): VapAnimationState {
    val composition = rememberSyncDemoComposition(clip, onError)
    return animateVapCompositionAsState(
        composition = composition,
        iterations = VapConstants.IterateForever,
        isPlaying = playing,
        onError = { onError(it.message ?: it.toString()) },
    )
}
