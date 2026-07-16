package com.zxhhyj.vap.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zxhhyj.vap.player.VapDemoClip

@Composable
internal fun AnimatedContentExample(
    pages: DemoPages,
    clips: List<VapDemoClip>,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val playingLabels = remember(page, pages) {
        pages.getOrNull(page).orEmpty().map { it.label }.toSet()
    }

    DemoPlaybackHost(
        clips = clips,
        playingLabels = playingLabels,
        onError = onError,
    ) { sessions ->
        ViewportScaffold(
            pageCount = pages.size,
            currentPage = page,
            onGoTo = { page = it.coerceIn(0, pages.lastIndex) },
            modifier = modifier,
        ) {
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { VapFadeTransition },
                label = "vapAnimatedContent",
            ) { pageIndex ->
                DemoPageGrid(
                    pageItems = pages[pageIndex],
                    sessions = sessions,
                )
            }
        }
    }
}
