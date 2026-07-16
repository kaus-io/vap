package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ExampleSwitcher(
    selected: DemoExample,
    onSelect: (DemoExample) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        DemoExample.entries.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colors.primary else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title,
                    color = if (active) {
                        MaterialTheme.colors.onPrimary
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
                    },
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun ViewportScaffold(
    pageCount: Int,
    currentPage: Int,
    onGoTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF10131A),
            shape = RoundedCornerShape(14.dp),
            elevation = 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                content()
            }
        }
        PageControls(
            pageCount = pageCount,
            currentPage = currentPage,
            onGoTo = onGoTo,
        )
    }
}

@Composable
private fun PageControls(
    pageCount: Int,
    currentPage: Int,
    onGoTo: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ControlChip(
            label = "上一组",
            enabled = currentPage > 0,
            onClick = { onGoTo(currentPage - 1) },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount) { index ->
                val selected = currentPage == index
                Box(
                    modifier = Modifier
                        .size(if (selected) 8.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.28f)
                            },
                        )
                        .clickable { onGoTo(index) },
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${currentPage + 1} / $pageCount",
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
        ControlChip(
            label = "下一组",
            enabled = currentPage < pageCount - 1,
            onClick = { onGoTo(currentPage + 1) },
        )
    }
}

@Composable
private fun ControlChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colors.surface
                } else {
                    MaterialTheme.colors.surface.copy(alpha = 0.45f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.9f else 0.35f),
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun DemoEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF10131A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "无可用 VAP 素材",
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
    }
}
