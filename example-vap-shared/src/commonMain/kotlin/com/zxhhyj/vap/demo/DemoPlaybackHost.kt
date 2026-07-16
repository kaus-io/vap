package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.zxhhyj.vap.player.VapAnimationState
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import com.zxhhyj.vap.player.rememberVapComposition
import com.zxhhyj.vap.player.toCompositionSpec

internal class DemoClipSession(
    val animationState: VapAnimationState,
)

@Composable
internal fun DemoPlaybackHost(
    clips: List<VapDemoClip>,
    playingLabels: Set<String>,
    onError: (String) -> Unit,
    content: @Composable (Map<String, DemoClipSession>) -> Unit,
) {
    val sessions = HashMap<String, DemoClipSession>(clips.size)
    clips.forEach { clip ->
        key(clip.label) {
            val composition by rememberVapComposition(
                spec = clip.source.toCompositionSpec(),
                onError = { t ->
                    onError("${clip.label}: ${t.message ?: t.toString()}")
                },
            )
            val animationState = animateVapCompositionAsState(
                composition = composition,
                isPlaying = clip.label in playingLabels,
                iterations = VapConstants.IterateForever,
                onError = { t ->
                    onError("${clip.label}: ${t.message ?: t.toString()}")
                },
            )
            sessions[clip.label] = DemoClipSession(animationState)
        }
    }
    content(sessions)
}
