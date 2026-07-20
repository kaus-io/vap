package com.zxhhyj.vap.demo


/**
 * Reports whether the optional Surface backend can host its dedicated Demo case on this platform.
 *
 * 指示可选 Surface 后端能否在当前平台承载其专用 Demo case。
 */
internal expect val demoSurfaceBackendAvailable: Boolean

/**
 * Official libpag Maven artifacts are Android AARs only, with no desktop JVM package.
 *
 * 官方 libpag Maven 制品仅提供 Android AAR，不包含桌面 JVM 包。
 */
internal expect val demoPagAvailable: Boolean

internal val DemoCase.isAvailableOnPlatform: Boolean
    get() = when (this) {
        DemoCase.SettingPagerSurface -> demoSurfaceBackendAvailable
        DemoCase.PagPresentBenchmark,
        DemoCase.PagConcurrentBenchmark,
        DemoCase.PagImageViewPresentBenchmark,
        DemoCase.PagImageViewConcurrentBenchmark,
        DemoCase.StateSwapBenchmark,
        -> demoPagAvailable

        else -> true
    }

/**
 * Benchmarks are adb/script-only and therefore omitted from the interactive demo home.
 *
 * 基准测试仅供 adb/脚本启动，因此不会显示在交互式演示首页。
 */
internal val DemoCase.listedOnHome: Boolean
    get() = when (this) {
        DemoCase.PresentBenchmark,
        DemoCase.ConcurrentBenchmark,
        DemoCase.PagPresentBenchmark,
        DemoCase.PagConcurrentBenchmark,
        DemoCase.PagImageViewPresentBenchmark,
        DemoCase.PagImageViewConcurrentBenchmark,
        DemoCase.StateSwapBenchmark,
        -> false

        else -> true
    }

internal val demoCasesForPlatform: List<DemoCase>
    get() = DemoCase.entries.filter { it.isAvailableOnPlatform && it.listedOnHome }
