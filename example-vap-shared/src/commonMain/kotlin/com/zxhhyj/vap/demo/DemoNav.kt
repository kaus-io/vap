package com.zxhhyj.vap.demo

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.zxhhyj.vap.player.VapDemoClip
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

@Serializable
sealed interface DemoRoute : NavKey

@Serializable
data object DemoHomeRoute : DemoRoute

@Serializable
data class DemoCaseRoute(val case: DemoCase) : DemoRoute


private val demoNavConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(DemoHomeRoute::class, DemoHomeRoute.serializer())
            subclass(DemoCaseRoute::class, DemoCaseRoute.serializer())
        }
    }
}

@Composable
internal fun DemoNavHost(
    clips: List<VapDemoClip>,
    onError: (String?) -> Unit,
    modifier: Modifier = Modifier,
    benchLaunch: PresentBenchLaunch? = null,
    onBenchFinished: (() -> Unit)? = null,
) {
    val backStack = rememberNavBackStack(demoNavConfig, DemoHomeRoute)
    fun popBack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    LaunchedEffect(benchLaunch) {
        if (benchLaunch == null) return@LaunchedEffect
        val target = if (benchLaunch.isMulti) {
            DemoCase.ConcurrentBenchmark
        } else {
            DemoCase.PresentBenchmark
        }
        val alreadyOpen = backStack.any { it is DemoCaseRoute && it.case == target }
        if (!alreadyOpen) {
            onError(null)
            backStack.add(DemoCaseRoute(target))
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { popBack() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
        ),
        transitionSpec = {
            (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 8 } + fadeOut())
        },
        popTransitionSpec = {
            (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 5 } + fadeOut())
        },
        entryProvider = entryProvider {
            entry<DemoHomeRoute> {
                if (clips.isEmpty()) {
                    DemoEmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    DemoHome(
                        onOpenCase = { case ->
                            onError(null)
                            backStack.add(DemoCaseRoute(case))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            entry<DemoCaseRoute> { route ->
                DemoCaseContent(
                    case = route.case,
                    clips = clips,
                    onBack = { popBack() },
                    onError = { onError(it) },
                    modifier = Modifier.fillMaxSize(),
                    benchLaunch = benchLaunch.takeIf {
                        route.case == DemoCase.PresentBenchmark ||
                            route.case == DemoCase.ConcurrentBenchmark
                    },
                    onBenchFinished = onBenchFinished,
                )
            }
        },
    )
}

@Composable
private fun DemoCaseContent(
    case: DemoCase,
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    benchLaunch: PresentBenchLaunch? = null,
    onBenchFinished: (() -> Unit)? = null,
) {
    if (!case.isAvailableOnPlatform) {
        DemoCaseScaffold(case = case, onBack = onBack, modifier = modifier) {
            DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
        }
        return
    }

    when (case) {
        DemoCase.HomeHero -> HomeHeroCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.SettingPager -> SettingPagerCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.SettingPagerSurface -> SettingPagerSurfaceCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.HannaBall -> HannaBallCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.WindowExpand -> WindowExpandCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.TouchWake -> TouchWakeCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
        )

        DemoCase.PresentBenchmark -> PresentBenchmarkCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )

        DemoCase.ConcurrentBenchmark -> ConcurrentBenchmarkCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )
    }
}
