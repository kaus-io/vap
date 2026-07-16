package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

private val DEMO_NAMES = listOf(
    "hanna_about_card.mp4",
    "hanna_home.mp4",
    "hanna_touch_wake.mp4",
    "hanna_window_idle.mp4",
    "hanna_window_speech.mp4",
    "hanna_window_think.mp4",
    "video.mp4",
)

private fun demoAssetDirs(): List<File> {
    val cwd = File(System.getProperty("user.dir") ?: ".")
    return listOf(
        File(cwd, "example-vap-android/src/main/assets"),
        File(cwd, "../example-vap-android/src/main/assets"),
        File(cwd, "src/main/assets"),
    )
}

private fun findDemoFile(name: String): File? =
    demoAssetDirs().firstNotNullOfOrNull { dir ->
        File(dir, name).takeIf { it.isFile }
    }

@Composable
actual fun rememberDefaultVapSource(): VapSource? = remember {
    DEMO_NAMES.firstNotNullOfOrNull { name ->
        findDemoFile(name)?.let { VapSource.AbsolutePath(it.absolutePath) }
    }
}

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
