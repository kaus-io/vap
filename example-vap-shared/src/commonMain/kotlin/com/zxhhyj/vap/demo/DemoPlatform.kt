package com.zxhhyj.vap.demo


internal expect val demoSurfaceBackendAvailable: Boolean

internal val DemoCase.isAvailableOnPlatform: Boolean
    get() = when (this) {
        DemoCase.SettingPagerSurface -> demoSurfaceBackendAvailable

        else -> true
    }

internal val demoCasesForPlatform: List<DemoCase>
    get() = DemoCase.entries.filter { it.isAvailableOnPlatform }
