package com.zxhhyj.vap.demo

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import com.zxhhyj.example_vap_shared.generated.resources.Res
import com.zxhhyj.example_vap_shared.generated.resources.case_hanna_ball_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_hanna_ball_title
import com.zxhhyj.example_vap_shared.generated.resources.case_home_hero_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_home_hero_title
import com.zxhhyj.example_vap_shared.generated.resources.case_instance_stress_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_instance_stress_title
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_surface_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_surface_title
import com.zxhhyj.example_vap_shared.generated.resources.case_setting_pager_title
import com.zxhhyj.example_vap_shared.generated.resources.case_touch_wake_subtitle
import com.zxhhyj.example_vap_shared.generated.resources.case_touch_wake_title
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


@Serializable
enum class DemoCase {
    HomeHero,
    SettingPager,
    SettingPagerSurface,
    HannaBall,
    WindowExpand,
    TouchWake,
    InstanceStress,
}

internal val DemoCase.titleRes: StringResource
    get() = when (this) {
        DemoCase.HomeHero -> Res.string.case_home_hero_title
        DemoCase.SettingPager -> Res.string.case_setting_pager_title
        DemoCase.SettingPagerSurface -> Res.string.case_setting_pager_surface_title
        DemoCase.HannaBall -> Res.string.case_hanna_ball_title
        DemoCase.WindowExpand -> Res.string.case_window_expand_title
        DemoCase.TouchWake -> Res.string.case_touch_wake_title
        DemoCase.InstanceStress -> Res.string.case_instance_stress_title
    }

internal val DemoCase.subtitleRes: StringResource
    get() = when (this) {
        DemoCase.HomeHero -> Res.string.case_home_hero_subtitle
        DemoCase.SettingPager -> Res.string.case_setting_pager_subtitle
        DemoCase.SettingPagerSurface -> Res.string.case_setting_pager_surface_subtitle
        DemoCase.HannaBall -> Res.string.case_hanna_ball_subtitle
        DemoCase.WindowExpand -> Res.string.case_window_expand_subtitle
        DemoCase.TouchWake -> Res.string.case_touch_wake_subtitle
        DemoCase.InstanceStress -> Res.string.case_instance_stress_subtitle
    }

@Composable
internal fun DemoCase.titleText(): String = stringResource(titleRes)

@Composable
internal fun DemoCase.subtitleText(): String = stringResource(subtitleRes)

internal fun List<VapDemoClip>.byLabel(label: String): VapDemoClip? =
    firstOrNull { it.label == label }

