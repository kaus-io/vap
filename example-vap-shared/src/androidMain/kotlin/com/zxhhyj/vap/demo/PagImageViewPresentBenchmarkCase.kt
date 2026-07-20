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
 * Single-instance PAGImageView present benchmark. Same shape as [PagPresentBenchmarkCase]
 * but uses the software-decoded `PAGImageView` engine so its present rate can be
 * compared directly against `PAGView` and VAP in the bench report.
 *
 * 单实例 PAGImageView present 基准。结构与 [PagPresentBenchmarkCase] 相同,但驱动
 * 的是软件解码的 `PAGImageView`,便于在报告中与 `PAGView` / VAP 直接对比。
 *
 * adb / script only — no interactive controls.
 */
@Composable
internal actual fun PagImageViewPresentBenchmarkCase(
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
        case = DemoCase.PagImageViewPresentBenchmark,
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

        // FIRST_PRESENT: mirror of the PAGView hook; same elapsed-ms contract.
        // FIRST_PRESENT:与 PAGView 一致的钩子,对外 elapsed-ms 语义保持一致。
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
                    "WARMUP engine=pagimageview asset=${asset.key} clip=$assetPath target_fps=$targetFps " +
                        "warmup_ms=$warmUpMs duration_ms=$durationMs gpu=pagimageview resolved=pagimageview",
                )
                kotlinx.coroutines.delay(warmUpMs)
            }
            val snap = runPresentSample(
                readPresents = { presentCounter.get() },
                targetFps = targetFps,
                assetKey = asset.key,
                assetLabel = assetPath.removeSuffix(".pag"),
                durationMs = durationMs,
                gpuPrefer = "pagimageview",
                gpuResolved = "pagimageview",
                readInstancePresents = { longArrayOf(presentCounter.get()) },
                intervalRecorders = listOf(presentCounter.recorder),
            )
            if (snap != null) logVapBench(snap.toLogLine("single") + " engine=pagimageview")
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
                PagImageViewAnimation(
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