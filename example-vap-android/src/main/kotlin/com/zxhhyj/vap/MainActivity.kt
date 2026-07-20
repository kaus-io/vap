package com.zxhhyj.vap

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.zxhhyj.vap.demo.PresentBenchLaunch
import com.zxhhyj.vap.demo.toPresentBenchLaunch
import com.zxhhyj.vap.vk.VapVkNative
import java.io.File

class MainActivity : ComponentActivity() {
    private var benchLaunch by mutableStateOf<PresentBenchLaunch?>(null)

    /**
     * Android entry point. Probe intents are routed out of the UI path before the
     * Compose demo or benchmark content is mounted.
     *
     * Android 入口。先将 probe intent 路由出 UI 路径，再挂载 Compose 演示或基准内容。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (maybeRunAhbProbe(intent)) return
        if (maybeRunJavaAhbProbe(intent)) return
        benchLaunch = intent.toPresentBenchLaunch()
        setContent {
            App(
                benchLaunch = benchLaunch,
                onBenchFinished = {
                    if (benchLaunch?.finishAfter == true) {
                        finish()
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-route probe actions on intent re-entry so a script can re-launch the same
        // Activity to a different probe without restarting the app.
        // 在 intent 重新投递时再次走 probe 路由,脚本可以不重启 app 就复用同一 Activity
        // 切换到不同的 probe。
        if (maybeRunAhbProbe(intent)) return
        if (maybeRunJavaAhbProbe(intent)) return
        benchLaunch = intent.toPresentBenchLaunch()
    }

    /**
     * Runs the Java-path AHB identity probe on a background thread and finishes.
     * The probe reads `hanna_window_idle.mp4` from assets into the cache dir so it
     * gets a real `File` path for `MediaExtractor.setDataSource`.
     *
     * 在后台线程跑 Java 路径 AHB identity probe 然后 finish。先把 `hanna_window_idle.mp4`
     * 从 assets 拷贝到 cache 目录,让 `MediaExtractor.setDataSource` 拿到真实文件路径。
     *
     * adb: am start -a com.zxhhyj.vap.action.JAVA_AHB_PROBE -n com.zxhhyj.vap/.MainActivity
     */
    private fun maybeRunJavaAhbProbe(intent: Intent?): Boolean {
        if (intent?.action != ACTION_JAVA_AHB_PROBE) return false
        Thread {
            try {
                val video = File(cacheDir, "probe_idle.mp4")
                assets.open("hanna_window_idle.mp4").use { input ->
                    video.outputStream().use { input.copyTo(it) }
                }
                JavaAhbIdentityProbe.run(
                    video.absolutePath,
                    lowLatency = intent.getBooleanExtra("probe_ll", false),
                )
            } catch (t: Throwable) {
                Log.e(AHB_PROBE_TAG, "java ahb probe crashed", t)
            }
            runOnUiThread { finish() }
        }.start()
        return true
    }

    /**
     * Runs the native AHB probe (and optionally the ImageReader/MediaCodec identity probe).
     * Pass `--ez probe_media true` to opt in to the candidate-B media path; it runs last
     * so a media-side failure cannot hide the base smoke results.
     *
     * 运行原生 AHB probe(可选叠加 ImageReader/MediaCodec identity probe)。
     * 加 `--ez probe_media true` 开启候选 B 媒体路径;B 在 A 之后运行,
     * 即使 B 失败也能保留 A 的冒烟结果。
     *
     * adb: am start -a com.zxhhyj.vap.action.AHB_PROBE -n com.zxhhyj.vap/.MainActivity
     */
    private fun maybeRunAhbProbe(intent: Intent?): Boolean {
        if (intent?.action != ACTION_AHB_PROBE) return false
        val withMedia = intent.getBooleanExtra("probe_media", false)
        Thread {
            try {
                val videoPath = if (withMedia) {
                    val video = File(cacheDir, "probe_idle.mp4")
                    assets.open("hanna_window_idle.mp4").use { input ->
                        video.outputStream().use { input.copyTo(it) }
                    }
                    video.absolutePath
                } else {
                    ""
                }
                val report = VapVkNative.nativeProbeAhb(videoPath)
                for (line in report.lineSequence()) {
                    Log.i(AHB_PROBE_TAG, line)
                }
            } catch (t: Throwable) {
                Log.e(AHB_PROBE_TAG, "probe crashed", t)
            }
            runOnUiThread { finish() }
        }.start()
        return true
    }

    companion object {
        private const val ACTION_AHB_PROBE = "com.zxhhyj.vap.action.AHB_PROBE"
        private const val ACTION_JAVA_AHB_PROBE = "com.zxhhyj.vap.action.JAVA_AHB_PROBE"
        private const val AHB_PROBE_TAG = "VapAhbProbe"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
