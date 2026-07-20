package com.zxhhyj.vap.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zxhhyj.vap.player.VapComposition
import com.zxhhyj.vap.player.VapDemoClip
import com.zxhhyj.vap.player.loadVapComposition


/**
 * Synchronously loads and memoizes a composition by clip label and source; failures become UI errors and `null`.
 *
 * 按片段标签和来源同步加载并缓存合成；加载失败会转换为界面错误和 `null`。
 */
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
