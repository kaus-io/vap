package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.demo_back
import org.jetbrains.compose.resources.stringResource

private val ControlMinHeight = 48.dp
private val ControlPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)

@Composable
internal fun DemoBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ControlMinHeight)
            .defaultMinSize(minHeight = ControlMinHeight),
        contentPadding = ControlPadding,
    ) {
        Text(text = stringResource(Res.string.demo_back), fontSize = 15.sp)
    }
}


@Composable
internal fun DemoChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val minMod = modifier
        .heightIn(min = ControlMinHeight)
        .defaultMinSize(minHeight = ControlMinHeight)
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = minMod,
            contentPadding = ControlPadding,
            elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        ) {
            Text(text = label, fontSize = 14.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = minMod,
            contentPadding = ControlPadding,
        ) {
            Text(text = label, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun DemoActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = ControlMinHeight)
            .defaultMinSize(minHeight = ControlMinHeight),
        contentPadding = ControlPadding,
    ) {
        Text(text = label, fontSize = 14.sp, maxLines = 1)
    }
}
