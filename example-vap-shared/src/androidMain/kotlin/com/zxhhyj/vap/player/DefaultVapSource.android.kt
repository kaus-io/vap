package com.zxhhyj.vap.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException

// Fallback clip when none of the bundled hanna_*.mp4 demos are present.
// 新工程若尚未放置 hanna 系列演示资源时使用的兜底片名。
private const val LEGACY_DEMO_ASSET = "video.mp4"

// Bundled hanna scenario demos surfaced in the picker; first hit wins for the default slot.
// 打包内置的 hanna 场景演示,首个命中的资源作为默认槽位。
private val DEMO_ASSETS = listOf(
    "hanna_about_card.mp4",
    "hanna_home.mp4",
    "hanna_touch_wake.mp4",
    "hanna_window_idle.mp4",
    "hanna_window_speech.mp4",
    "hanna_window_think.mp4",
)

/**
 * Android `actual` of [rememberDefaultVapSource]: picks the first present bundled demo
 * clip and stages it into the app cache so the native decoder can mmap a real file path.
 *
 * Android 平台 [rememberDefaultVapSource] 的 actual 实现:选取首个已存在的内置
 * 演示资源,并拷贝到应用 cache 目录,使原生解码器能 mmap 到真实文件路径。
 */
@Composable
actual fun rememberDefaultVapSource(): VapSource? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val name = DEMO_ASSETS.firstOrNull { assetExists(context, it) } ?: LEGACY_DEMO_ASSET
        copyAssetToCacheOrNull(context, name)?.let { VapSource.AbsolutePath(it.absolutePath) }
    }
}

/**
 * Android `actual` of [rememberDemoVapSources]: enumerates every bundled demo clip
 * that is actually present in `assets/`, copying each to cache so VapSource.AbsolutePath
 * can resolve to a stable path across launches.
 *
 * Android 平台 [rememberDemoVapSources] 的 actual 实现:枚举 assets 中实际存在的
 * 每个演示片段并拷贝到 cache,使 VapSource.AbsolutePath 能在多次启动间保持稳定路径。
 */
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

// Probes an APK asset by trying to open it; mirrors the legacy `list()` based check
// without paying its cost on cold start. IOException means the asset is absent.
// 通过尝试打开来探测 APK 资源是否存在;避免冷启动时使用 list() 的开销。
// 捕获 IOException 表示资源缺失。
private fun assetExists(context: Context, assetName: String): Boolean =
    try {
        context.assets.open(assetName).close()
        true
    } catch (_: IOException) {
        false
    }

// Streams an asset into the app cache so the native decoder sees a real `File` path.
// Returns null on IO failure so callers can fall back / skip the clip gracefully.
// 将 assets 流式拷贝到应用 cache 目录,使原生解码器能拿到真实的 File 路径;
// IO 失败时返回 null,调用方据此回退或跳过该片段。
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
