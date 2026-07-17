package com.zxhhyj.vap.demo

import android.content.Intent
import android.util.Log

internal actual fun logVapBench(message: String) {
    Log.i(PresentBenchLaunch.LOG_TAG, message)
}

fun Intent.toPresentBenchLaunch(): PresentBenchLaunch? {
    val isBenchAction = action == PresentBenchLaunch.ACTION
    val hasExtras = hasExtra(PresentBenchLaunch.EXTRA_ASSET) ||
        hasExtra(PresentBenchLaunch.EXTRA_FPS) ||
        hasExtra(PresentBenchLaunch.EXTRA_AUTO) ||
        hasExtra(PresentBenchLaunch.EXTRA_DURATION_MS) ||
        hasExtra(PresentBenchLaunch.EXTRA_COUNT)
    if (!isBenchAction && !hasExtras) return null

    return PresentBenchLaunch(
        assetKey = getStringExtra(PresentBenchLaunch.EXTRA_ASSET) ?: "idle",
        targetFps = getIntExtra(PresentBenchLaunch.EXTRA_FPS, 30),
        auto = if (hasExtra(PresentBenchLaunch.EXTRA_AUTO)) {
            getBooleanExtra(PresentBenchLaunch.EXTRA_AUTO, true)
        } else {
            isBenchAction
        },
        durationMs = getIntExtra(PresentBenchLaunch.EXTRA_DURATION_MS, 10_000).toLong().coerceAtLeast(1_000L),
        warmUpMs = getIntExtra(PresentBenchLaunch.EXTRA_WARMUP_MS, 2_000).toLong().coerceAtLeast(0L),
        finishAfter = if (hasExtra(PresentBenchLaunch.EXTRA_FINISH)) {
            getBooleanExtra(PresentBenchLaunch.EXTRA_FINISH, true)
        } else {
            true
        },
        count = getIntExtra(PresentBenchLaunch.EXTRA_COUNT, 1).coerceIn(1, 16),
    )
}
