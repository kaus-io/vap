package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.zxhhyj.vap.player.VapDemoClip

@Composable
internal fun NavDisplayExample(
    pages: DemoPages,
    clips: List<VapDemoClip>,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val backStack = remember { mutableStateListOf(DemoPage(0)) }
    val currentPage = backStack.lastOrNull()?.index ?: 0
    val playingLabels = remember(currentPage, pages) {
        pages.getOrNull(currentPage).orEmpty().map { it.label }.toSet()
    }

    fun goTo(target: Int) {
        val clamped = target.coerceIn(0, pages.lastIndex)
        val current = backStack.lastOrNull()?.index ?: 0
        if (clamped == current) return
        if (clamped > current) {
            for (i in (current + 1)..clamped) backStack.add(DemoPage(i))
        } else {
            while (backStack.size > 1 && (backStack.lastOrNull()?.index ?: 0) > clamped) {
                backStack.removeLastOrNull()
            }
        }
    }

    DemoPlaybackHost(
        clips = clips,
        playingLabels = playingLabels,
        onError = onError,
    ) { sessions ->
        ViewportScaffold(
            pageCount = pages.size,
            currentPage = currentPage,
            onGoTo = ::goTo,
            modifier = modifier,
        ) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = {
                    if (backStack.size > 1) backStack.removeLastOrNull()
                },
                transitionSpec = { VapFadeTransition },
                popTransitionSpec = { VapFadeTransition },
                entryProvider = entryProvider {
                    entry<DemoPage> { demoPage ->
                        DemoPageGrid(
                            pageItems = pages.getOrNull(demoPage.index).orEmpty(),
                            sessions = sessions,
                        )
                    }
                },
            )
        }
    }
}
