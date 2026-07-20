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

/**
 * Serializable navigation-key hierarchy persisted by Navigation 3 saved state.
 *
 * 由 Navigation 3 保存状态持久化的可序列化导航键层级。
 */
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

/**
 * Owns the shared back stack and maps optional external benchmark launches to one destination.
 *
 * 管理共享返回栈，并将可选的外部基准启动参数映射到单一目标页。
 */
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

    // Resolve an external launch in an effect so route mutation stays outside composition.
    // 在副作用中解析外部启动参数，使路由变更发生在组合阶段之外。
    LaunchedEffect(benchLaunch) {
        if (benchLaunch == null) return@LaunchedEffect
        val target = when {
            benchLaunch.stateSwapSeq.isNotEmpty() -> DemoCase.StateSwapBenchmark
            benchLaunch.isPagImageView && benchLaunch.isMulti -> DemoCase.PagImageViewConcurrentBenchmark
            benchLaunch.isPagImageView -> DemoCase.PagImageViewPresentBenchmark
            benchLaunch.isPagView && benchLaunch.isMulti -> DemoCase.PagConcurrentBenchmark
            benchLaunch.isPagView -> DemoCase.PagPresentBenchmark
            benchLaunch.isMulti -> DemoCase.ConcurrentBenchmark
            else -> DemoCase.PresentBenchmark
        }
        if (!target.isAvailableOnPlatform) {
            onError("engine=pag* is Android-only (no official desktop libpag)")
            return@LaunchedEffect
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
                            route.case == DemoCase.ConcurrentBenchmark ||
                            route.case == DemoCase.PagPresentBenchmark ||
                            route.case == DemoCase.PagConcurrentBenchmark ||
                            route.case == DemoCase.PagImageViewPresentBenchmark ||
                            route.case == DemoCase.PagImageViewConcurrentBenchmark ||
                            route.case == DemoCase.StateSwapBenchmark
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

        DemoCase.WindowColdShow -> WindowColdShowCase(
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

        DemoCase.GpuBackend -> GpuBackendCase(
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

        DemoCase.PagPresentBenchmark -> PagPresentBenchmarkCase(
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )

        DemoCase.PagConcurrentBenchmark -> PagConcurrentBenchmarkCase(
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )

        DemoCase.PagImageViewPresentBenchmark -> PagImageViewPresentBenchmarkCase(
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )

        DemoCase.PagImageViewConcurrentBenchmark -> PagImageViewConcurrentBenchmarkCase(
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )

        DemoCase.StateSwapBenchmark -> StateSwapBenchmarkCase(
            clips = clips,
            onBack = onBack,
            onError = onError,
            modifier = modifier,
            launch = benchLaunch,
            onAutoFinished = onBenchFinished,
        )
    }
}
