package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.status_instance_stress
import com.zxhhyj.example_vap_shared.generated.resources.status_no_asset
import com.zxhhyj.example_vap_shared.generated.resources.stress_cell_label
import com.zxhhyj.example_vap_shared.generated.resources.stress_clear
import com.zxhhyj.example_vap_shared.generated.resources.stress_empty_hint
import com.zxhhyj.example_vap_shared.generated.resources.stress_minus_one
import com.zxhhyj.example_vap_shared.generated.resources.stress_plus_four
import com.zxhhyj.example_vap_shared.generated.resources.stress_plus_one
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapConstants
import com.zxhhyj.vap.player.VapDemoClip
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.sqrt


@Composable
internal fun InstanceStressCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val template = clips.byLabel("hanna_about_card")
        ?: clips.byLabel("hanna_home")
        ?: clips.firstOrNull()
    var count by remember { mutableIntStateOf(1) }
    val ids = remember(count) { List(count) { it } }
    val columns = remember(count) {
        when {
            count <= 1 -> 1
            count <= 4 -> 2
            count <= 9 -> 3
            else -> ceil(sqrt(count.toFloat())).toInt().coerceAtLeast(1)
        }
    }
    val rows = remember(count, columns) {
        if (count == 0) 0 else (count + columns - 1) / columns
    }

    DemoCaseScaffold(
        case = DemoCase.InstanceStress,
        onBack = onBack,
        modifier = modifier,
        status = template?.let {
            stringResource(
                Res.string.status_instance_stress,
                it.label,
                count,
                columns,
                rows,
            )
        } ?: stringResource(Res.string.status_no_asset),
    ) {
        if (template == null) {
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        VapSurfaceBackendScope {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DemoActionChip(
                        label = stringResource(Res.string.stress_minus_one),
                        enabled = count > 0,
                        onClick = { count = (count - 1).coerceAtLeast(0) },
                        modifier = Modifier.weight(1f),
                    )
                    DemoActionChip(
                        label = stringResource(Res.string.stress_plus_one),
                        onClick = { count += 1 },
                        modifier = Modifier.weight(1f),
                    )
                    DemoActionChip(
                        label = stringResource(Res.string.stress_plus_four),
                        onClick = { count += 4 },
                        modifier = Modifier.weight(1f),
                    )
                    DemoActionChip(
                        label = stringResource(Res.string.stress_clear),
                        enabled = count > 0,
                        onClick = { count = 0 },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (count == 0) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.stress_empty_hint),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colors.surface)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ids.chunked(columns).forEach { rowIds ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                rowIds.forEach { id ->
                                    SurfaceStressCell(
                                        id = id,
                                        clip = template,
                                        onError = onError,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    )
                                }
                                repeat(columns - rowIds.size) {
                                    Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceStressCell(
    id: Int,
    clip: VapDemoClip,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(id) {
        val composition = rememberSyncDemoComposition(clip) { msg ->
            onError("#$id: $msg")
        }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f)),
        ) {
            VapAnimation(
                composition = composition,
                iterations = VapConstants.IterateForever,
                isPlaying = true,
                onError = { onError("#$id: ${it.message ?: it.toString()}") },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = stringResource(Res.string.stress_cell_label, id),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
