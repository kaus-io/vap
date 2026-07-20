package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.action_hide
import com.zxhhyj.example_vap_shared.generated.resources.action_show
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.animateVapCompositionAsState
import org.jetbrains.compose.resources.stringResource

private val PanelHeight = 180.dp
private val BallSize = 120.dp

@Composable
internal fun WindowColdShowCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = clips.byLabel("hanna_window_idle")
        ?: clips.byLabel("hanna_touch_wake")
    var showing by remember { mutableStateOf(false) }
    var remountKey by remember { mutableIntStateOf(0) }

    DemoCaseScaffold(
        case = DemoCase.WindowColdShow,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (clip == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        val composition = rememberSyncDemoComposition(clip, onError)

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
                    selected = showing,
                    onClick = {
                        remountKey++
                        showing = true
                    },
                    modifier = Modifier.weight(1f),
                )
                DemoChoiceChip(
                    label = stringResource(Res.string.action_hide),
                    selected = !showing,
                    onClick = { showing = false },
                    modifier = Modifier.weight(1f),
                )
            }

            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(PanelHeight),
                    ) {
                        if (showing) {
                            key(remountKey) {
                                val anim = animateVapCompositionAsState(
                                    composition = composition,
                                    iterations = VapConstants.IterateForever,
                                    isPlaying = true,
                                    onError = { onError(it.message ?: it.toString()) },
                                )
                                VapAnimation(
                                    animationState = anim,
                                    modifier = Modifier.size(BallSize),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
