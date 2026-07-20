package com.zxhhyj.vap.demo

// JVM has no Android SurfaceFlinger integration, so the VapGpu Surface backend is unavailable.
// JVM 平台没有 Android SurfaceFlinger 集成,VapGpu Surface 后端不可用。
internal actual val demoSurfaceBackendAvailable: Boolean = false

// libpag ships only for Android/iOS/macOS/Windows; PAG benches are unsupported on JVM desktop.
// libpag 仅支持 Android/iOS/macOS/Windows,JVM 桌面端无法运行 PAG 基准。
internal actual val demoPagAvailable: Boolean = false
