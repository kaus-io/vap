package com.zxhhyj.vap.demo

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.case_concurrent_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_concurrent_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_gpu_backend_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_gpu_backend_title
import com.zxhhyj.example_vap_shared.generated.resources.case_hanna_ball_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_hanna_ball_title
import com.zxhhyj.example_vap_shared.generated.resources.case_home_hero_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_home_hero_title
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_concurrent_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_concurrent_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_image_view_concurrent_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_image_view_concurrent_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_image_view_present_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_image_view_present_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_present_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_pag_present_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_present_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_present_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_surface_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_surface_title
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_title
import com.zxhhyj.example_vap_shared.generated.resources.case_state_swap_benchmark_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_state_swap_benchmark_title
import com.zxhhyj.example_vap_shared.generated.resources.case_touch_wake_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_touch_wake_title
import com.zxhhyj.example_vap_shared.generated.resources.case_window_cold_show_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_window_cold_show_title
import com.zxhhyj.example_vap_shared.generated.resources.case_window_expand_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_window_expand_title
import com.zxhhyj.vap.player.VapDemoClip
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val VapFadeTransition = ContentTransform(
    targetContentEnter = fadeIn(),
    initialContentExit = fadeOut(),
)


/**
 * Closed model of interactive Demo cases and script-only benchmark destinations.
 *
 * 交互式 Demo case 与仅供脚本使用的基准目标的封闭模型。
 */
@Serializable
enum class DemoCase {
    HomeHero,
    SettingPager,
    SettingPagerSurface,
    HannaBall,
    WindowExpand,
    WindowColdShow,
    TouchWake,
    GpuBackend,
    PresentBenchmark,
    ConcurrentBenchmark,
    PagPresentBenchmark,
    PagConcurrentBenchmark,
    PagImageViewPresentBenchmark,
    PagImageViewConcurrentBenchmark,
    StateSwapBenchmark,
}

internal val DemoCase.titleRes: StringResource
    get() = when (this) {
        DemoCase.HomeHero -> Res.string.case_home_hero_title
        DemoCase.SettingPager -> Res.string.case_setting_pager_title
        DemoCase.SettingPagerSurface -> Res.string.case_setting_pager_surface_title
        DemoCase.HannaBall -> Res.string.case_hanna_ball_title
        DemoCase.WindowExpand -> Res.string.case_window_expand_title
        DemoCase.WindowColdShow -> Res.string.case_window_cold_show_title
        DemoCase.TouchWake -> Res.string.case_touch_wake_title
        DemoCase.GpuBackend -> Res.string.case_gpu_backend_title
        DemoCase.PresentBenchmark -> Res.string.case_present_benchmark_title
        DemoCase.ConcurrentBenchmark -> Res.string.case_concurrent_benchmark_title
        DemoCase.PagPresentBenchmark -> Res.string.case_pag_present_benchmark_title
        DemoCase.PagConcurrentBenchmark -> Res.string.case_pag_concurrent_benchmark_title
        DemoCase.PagImageViewPresentBenchmark -> Res.string.case_pag_image_view_present_benchmark_title
        DemoCase.PagImageViewConcurrentBenchmark -> Res.string.case_pag_image_view_concurrent_benchmark_title
        DemoCase.StateSwapBenchmark -> Res.string.case_state_swap_benchmark_title
    }

internal val DemoCase.subtitleRes: StringResource
    get() = when (this) {
        DemoCase.HomeHero -> Res.string.case_home_hero_subtitle
        DemoCase.SettingPager -> Res.string.case_setting_pager_subtitle
        DemoCase.SettingPagerSurface -> Res.string.case_setting_pager_surface_subtitle
        DemoCase.HannaBall -> Res.string.case_hanna_ball_subtitle
        DemoCase.WindowExpand -> Res.string.case_window_expand_subtitle
        DemoCase.WindowColdShow -> Res.string.case_window_cold_show_subtitle
        DemoCase.TouchWake -> Res.string.case_touch_wake_subtitle
        DemoCase.GpuBackend -> Res.string.case_gpu_backend_subtitle
        DemoCase.PresentBenchmark -> Res.string.case_present_benchmark_subtitle
        DemoCase.ConcurrentBenchmark -> Res.string.case_concurrent_benchmark_subtitle
        DemoCase.PagPresentBenchmark -> Res.string.case_pag_present_benchmark_subtitle
        DemoCase.PagConcurrentBenchmark -> Res.string.case_pag_concurrent_benchmark_subtitle
        DemoCase.PagImageViewPresentBenchmark -> Res.string.case_pag_image_view_present_benchmark_subtitle
        DemoCase.PagImageViewConcurrentBenchmark -> Res.string.case_pag_image_view_concurrent_benchmark_subtitle
        DemoCase.StateSwapBenchmark -> Res.string.case_state_swap_benchmark_subtitle
    }

@Composable
internal fun DemoCase.titleText(): String = stringResource(titleRes)

@Composable
internal fun DemoCase.subtitleText(): String = stringResource(subtitleRes)

/**
 * Matches platform clip labels exactly; labels are a resource contract, not localized display text.
 *
 * 精确匹配平台片段标签；标签是资源约定，而不是本地化显示文本。
 */
internal fun List<VapDemoClip>.byLabel(label: String): VapDemoClip? =
    firstOrNull { it.label == label }

