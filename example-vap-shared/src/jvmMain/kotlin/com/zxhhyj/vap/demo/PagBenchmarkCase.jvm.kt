package com.zxhhyj.vap.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zxhhyj.vap.player.VapDemoClip

// All PAG- and AHB-probe-driven benches are Android-only (libpag + Android HardwareBuffer);
// on JVM we surface a platform-unavailable state so the demo nav stays consistent.
// 所有依赖 PAG / AHB probe 的基准仅在 Android 上有意义(libpag + Android HardwareBuffer);
// JVM 上统一展示「平台不可用」占位,以保证 demo 导航结构一致。

@Composable
internal actual fun PagPresentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    DemoCaseScaffold(case = DemoCase.PagPresentBenchmark, onBack = onBack, modifier = modifier) {
        DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal actual fun PagConcurrentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    DemoCaseScaffold(case = DemoCase.PagConcurrentBenchmark, onBack = onBack, modifier = modifier) {
        DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal actual fun PagImageViewPresentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    DemoCaseScaffold(case = DemoCase.PagImageViewPresentBenchmark, onBack = onBack, modifier = modifier) {
        DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal actual fun PagImageViewConcurrentBenchmarkCase(
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    DemoCaseScaffold(case = DemoCase.PagImageViewConcurrentBenchmark, onBack = onBack, modifier = modifier) {
        DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal actual fun StateSwapBenchmarkCase(
    clips: List<VapDemoClip>,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    launch: PresentBenchLaunch?,
    onAutoFinished: (() -> Unit)?,
) {
    DemoCaseScaffold(case = DemoCase.StateSwapBenchmark, onBack = onBack, modifier = modifier) {
        DemoPlatformUnavailableState(modifier = Modifier.fillMaxSize())
    }
}
