package com.zxhhyj.vap.demo

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.zxhhyj.vap.player.VapDemoClip

internal const val PAGE_SIZE = 3
internal const val VIEWPORT_SWITCH_ANIM_MS = 280

internal val VapFadeTransition = ContentTransform(
    targetContentEnter = fadeIn(animationSpec = tween(VIEWPORT_SWITCH_ANIM_MS)),
    initialContentExit = fadeOut(animationSpec = tween(VIEWPORT_SWITCH_ANIM_MS)),
)

internal enum class DemoExample(val title: String) {
    AnimatedContent("AnimatedContent"),
    NavDisplay("NavDisplay"),
}

internal data class DemoPage(val index: Int)

internal typealias DemoPages = List<List<VapDemoClip>>
