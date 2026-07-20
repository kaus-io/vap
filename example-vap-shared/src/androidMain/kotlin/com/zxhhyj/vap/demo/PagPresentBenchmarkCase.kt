package com.zxhhyj.vap.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.time.TimeSource

/**
 * Single-instance PAGView present benchmark. Drives one [PagAnimation], records a
 * `FIRST_PRESENT` stamp, then samples steady-state presents over `durationMs` after
 * a `warmUpMs` settle window. Driven by `am start` extras; no on-screen controls.
 *
 * 单实例 PAGView present 基准:驱动一个 [PagAnimation],记录 `FIRST_PRESENT` 打点,
 * 在 `warmUpMs` 预热后采样 `durationMs` 时长的稳态 present 数。由 `am start` extras
 * 驱动,界面上不暴露交互控件。
 *
 * adb / script only — no interactive controls.
 */
@Composable
internal actual fun PagPresentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    val context = LocalContext.current
    val asset = remember(launch?.assetKey) { parseBenchAsset(launch?.assetKey) }
    val targetFps = launch?.targetFps ?: 30
    val durationMs = launch?.durationMs ?: 10_000L
    val warmUpMs = launch?.warmUpMs ?: 2_000L
    val assetPath = asset.pagAssetPath()
    val assetExists = remember(assetPath) {
        runCatching { context.assets.open(assetPath).close(); true }.getOrDefault(false)
    }
    val presentCounter = remember { PagPresentCounter() }
    val firstPresentMark = remember { TimeSource.Monotonic.markNow() }
    val firstPresentEmitted = remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    DemoCaseScaffold(
        case = DemoCase.PagPresentBenchmark,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (!assetExists) {
            LaunchedEffect(Unit) {
                val msg = "missing PAG asset path=$assetPath"
                logVapBench("ERROR $msg")
                onError(msg)
                if (launch?.finishAfter != false) onAutoFinished?.invoke()
            }
            DemoEmptyState(modifier = Modifier.fillMaxSize())
            return@DemoCaseScaffold
        }

        // FIRST_PRESENT: stamp the elapsed time the first time a content frame is
        // presented; recorder.onPresent is the unified hook used by both PAG engines.
        // FIRST_PRESENT:在首个内容帧呈现时打点;recorder.onPresent 是两种 PAG 引擎
        // 共用的统一钩子。
        LaunchedEffect(assetPath) {
            firstPresentEmitted.value = false
            val orig = presentCounter.recorder.onPresent
            presentCounter.recorder.onPresent = {
                orig()
                if (!firstPresentEmitted.value) {
                    firstPresentEmitted.value = true
                    logVapBench(
                        "FIRST_PRESENT asset=${asset.key} clip=$assetPath " +
                            "elapsed_ms=${firstPresentMark.elapsedNow().inWholeMilliseconds}",
                    )
                }
            }
        }

        LaunchedEffect(assetPath, targetFps, asset) {
            if (finished) return@LaunchedEffect
            if (warmUpMs > 0) {
                logVapBench(
                    "WARMUP engine=pagview asset=${asset.key} clip=$assetPath target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs gpu=pagview resolved=pagview",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            val snap = runPresentSample(
                readPresents = { presentCounter.get() },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = assetPath.removeSuffix(".pag"),
                durationMs = durationMs,
                gpuPrefer = "pagview",
                gpuResolved = "pagview",
                readInstancePresents = { longArrayOf(presentCounter.get()) },
                intervalRecorders = listOf(presentCounter.recorder),
            )
            if (snap != null) logVapBench(snap.toLogLine("single") + " engine=pagview")
            finished = true
            if (launch?.finishAfter != false) onAutoFinished?.invoke()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            key(assetPath, targetFps) {
                PagAnimation(
                    assetPath = assetPath,
                    targetFps = targetFps,
                    presentCounter = presentCounter,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
            }
        }
    }
}
