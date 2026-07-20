package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

// Bundled demo clip names; `video.mp4` is reserved as the legacy single-slot fallback.
// 打包内置的演示资源名;`video.mp4` 保留为旧版单槽兜底。
private val DEMO_NAMES = listOf(
    "hanna_about_card.mp4",
    "hanna_home.mp4",
    "hanna_touch_wake.mp4",
    "hanna_window_idle.mp4",
    "hanna_window_speech.mp4",
    "hanna_window_think.mp4",
    "video.mp4",
)

// Search the most likely asset roots relative to the JVM working directory. The first
// entry handles "gradle run from repo root", the second handles "gradle run from
// example-vap-desktop/", and the third is a generic in-module fallback.
// 在 JVM 工作目录下的常见 assets 根路径中查找:第一条覆盖"在仓库根目录运行 gradle",
// 第二条覆盖"在 example-vap-desktop/ 运行",第三条是模块内通用兜底。
private fun demoAssetDirs(): List<File> {
    val cwd = File(System.getProperty("user.dir") ?: ".")
    return listOf(
        File(cwd, "example-vap-android/src/main/assets"),
        File(cwd, "../example-vap-android/src/main/assets"),
        File(cwd, "src/main/assets"),
    )
}

// First directory that contains `name` wins — keeps Desktop demos usable from either
// invocation layout without duplicating assets.
// 首个包含该 `name` 的目录胜出,使得 Desktop 演示无论从哪种方式启动都能复用同一份资产。
private fun findDemoFile(name: String): File? =
    demoAssetDirs().firstNotNullOfOrNull { dir ->
        File(dir, name).takeIf { it.isFile }
    }

/**
 * JVM `actual` of [rememberDefaultVapSource]: picks the first demo clip present on
 * disk and resolves it as an absolute path so the desktop decoder can `mmap` it.
 *
 * JVM 平台 [rememberDefaultVapSource] 的 actual 实现:选取磁盘上首个可用的
 * 演示片段并以绝对路径返回,供桌面端解码器 `mmap`。
 */
@Composable
actual fun rememberDefaultVapSource(): VapSource? = remember {
    DEMO_NAMES.firstNotNullOfOrNull { name ->
        findDemoFile(name)?.let { VapSource.AbsolutePath(it.absolutePath) }
    }
}

/**
 * JVM `actual` of [rememberDemoVapSources]: exposes every non-legacy demo clip found
 * on disk so the desktop picker can render the full hanna scenario list.
 *
 * JVM 平台 [rememberDemoVapSources] 的 actual 实现:暴露磁盘上每个非旧版
 * 演示片段,使桌面端选择器能渲染完整的 hanna 场景列表。
 */
@Composable
actual fun rememberDemoVapSources(): List<VapDemoClip> = remember {
    DEMO_NAMES.mapNotNull { name ->
        if (name == "video.mp4") return@mapNotNull null
        val file = findDemoFile(name) ?: return@mapNotNull null
        VapDemoClip(
            label = name.removeSuffix(".mp4"),
            source = VapSource.AbsolutePath(file.absolutePath),
        )
    }
}
