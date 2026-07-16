package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zxhhyj.vap.player.VapComposition
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.loadVapComposition


@Composable
internal fun rememberSyncDemoComposition(
    clip: VapDemoClip?,
    onError: (String) -> Unit,
): VapComposition? {
    if (clip == null) return null
    return remember(clip.label, clip.source) {
        try {
            loadVapComposition(clip.source)
        } catch (t: Throwable) {
            onError("${clip.label}: ${t.message ?: t.toString()}")
            null
        }
    }
}
