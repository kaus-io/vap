package com.zxhhyj.vap.demo

import android.content.Intent
import android.util.Log

/**
 * Android `actual` of [logVapBench]: routes bench log lines through `Log.i` with the
 * shared `LOG_TAG`, so adb `logcat -s` can grep them deterministically.
 *
 * Android 平台 [logVapBench] 的 actual 实现:通过 `Log.i` 输出并使用统一 `LOG_TAG`,
 * 便于 `adb logcat -s` 过滤抓取。
 */
internal actual fun logVapBench(message: String) {
    Log.i(PresentBenchLaunch.LOG_TAG, message)
}

/**
 * Decodes an `am start ...` Intent into a [PresentBenchLaunch] configuration.
 * Recognizes both the explicit bench action and any Intent carrying known bench
 * extras; falls back to `null` so the host can launch the normal interactive UI.
 *
 * 将 `am start` 启动的 Intent 解析为 [PresentBenchLaunch];支持显式 bench action
 * 或携带已知 bench extras 的 Intent;否则返回 null 让宿主进入普通交互 UI。
 */
fun Intent.toPresentBenchLaunch(): PresentBenchLaunch? {
    val isBenchAction = action == PresentBenchLaunch.ACTION
    val hasExtras = hasExtra(PresentBenchLaunch.EXTRA_ASSET) ||
        hasExtra(PresentBenchLaunch.EXTRA_FPS) ||
        hasExtra(PresentBenchLaunch.EXTRA_AUTO) ||
        hasExtra(PresentBenchLaunch.EXTRA_DURATION_MS) ||
        hasExtra(PresentBenchLaunch.EXTRA_COUNT) ||
        hasExtra(PresentBenchLaunch.EXTRA_GPU) ||
        hasExtra(PresentBenchLaunch.EXTRA_ENGINE) ||
        hasExtra(PresentBenchLaunch.EXTRA_ASSET2) ||
        hasExtra(PresentBenchLaunch.EXTRA_STATE_SWAP_SEQ)
    if (!isBenchAction && !hasExtras) return null

    // `state_swap_seq` is a comma/semicolon-separated list; trimmed and empty-filtered
    // so a trailing separator is harmless.
    // `state_swap_seq` 是逗号或分号分隔的列表,经 trim 与空值过滤,
    // 末尾分隔符不会产生空槽位。
    val stateSwapSeq = getStringExtra(PresentBenchLaunch.EXTRA_STATE_SWAP_SEQ)
        ?.split(',', ';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

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
        gpuBackendKey = getStringExtra(PresentBenchLaunch.EXTRA_GPU),
        engineKey = getStringExtra(PresentBenchLaunch.EXTRA_ENGINE),
        asset2Key = getStringExtra(PresentBenchLaunch.EXTRA_ASSET2),
        stateSwapSeq = stateSwapSeq,
        stateSwapCycleMs = getIntExtra(PresentBenchLaunch.EXTRA_STATE_SWAP_CYCLE_MS, 3000).toLong().coerceAtLeast(500L),
        stateSwapCycles = getIntExtra(PresentBenchLaunch.EXTRA_STATE_SWAP_CYCLES, 10).coerceIn(1, 50),
    )
}
