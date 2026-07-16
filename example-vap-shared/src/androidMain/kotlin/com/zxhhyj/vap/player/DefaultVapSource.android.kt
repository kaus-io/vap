package com.zxhhyj.vap.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException

private const val LEGACY_DEMO_ASSET = "video.mp4"

private val DEMO_ASSETS = listOf(
    "hanna_about_card.mp4",
    "hanna_home.mp4",
    "hanna_touch_wake.mp4",
    "hanna_window_idle.mp4",
    "hanna_window_speech.mp4",
    "hanna_window_think.mp4",
)

@Composable
actual fun rememberDefaultVapSource(): VapSource? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val name = DEMO_ASSETS.firstOrNull { assetExists(context, it) } ?: LEGACY_DEMO_ASSET
        copyAssetToCacheOrNull(context, name)?.let { VapSource.AbsolutePath(it.absolutePath) }
    }
}

@Composable
actual fun rememberDemoVapSources(): List<VapDemoClip> {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        DEMO_ASSETS.mapNotNull { assetName ->
            if (!assetExists(context, assetName)) return@mapNotNull null
            val file = copyAssetToCacheOrNull(context, assetName) ?: return@mapNotNull null
            VapDemoClip(
                label = assetName.removeSuffix(".mp4"),
                source = VapSource.AbsolutePath(file.absolutePath),
            )
        }
    }
}

private fun assetExists(context: Context, assetName: String): Boolean =
    try {
        context.assets.open(assetName).close()
        true
    } catch (_: IOException) {
        false
    }

private fun copyAssetToCacheOrNull(context: Context, assetName: String): File? =
    try {
        val out = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out
    } catch (_: IOException) {
        null
    }
