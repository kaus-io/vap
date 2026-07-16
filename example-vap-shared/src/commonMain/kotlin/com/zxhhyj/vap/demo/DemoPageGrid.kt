package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxhhyj.vap.player.VapAnimation
import com.zxhhyj.vap.player.VapDemoClip

@Composable
internal fun DemoPageGrid(
    pageItems: List<VapDemoClip>,
    sessions: Map<String, DemoClipSession>,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pageItems.forEach { clip ->
            val session = sessions[clip.label] ?: return@forEach
            DemoCell(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                clip = clip,
                session = session,
            )
        }
    }
}

@Composable
private fun DemoCell(
    modifier: Modifier,
    clip: VapDemoClip,
    session: DemoClipSession,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1F2B)),
    ) {
        VapAnimation(
            animationState = session.animationState,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = clip.label.removePrefix("hanna_").replace('_', ' '),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
