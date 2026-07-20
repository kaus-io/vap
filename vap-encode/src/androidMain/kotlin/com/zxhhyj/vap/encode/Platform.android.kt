package com.zxhhyj.vap.encode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zxhhyj.vap.player.VapSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Android filesystem actual used by the common encoder pipeline.
 *
 * 公共编码流程使用的 Android 文件系统 actual 实现。
 */
internal actual object PlatformFs {
    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun mkdir(path: String) {
        File(path).mkdirs()
    }

    actual fun deleteIfExists(path: String) {
        File(path).delete()
    }

    actual fun join(parent: String, vararg parts: String): String {
        var f = File(parent)
        for (p in parts) {
            f = File(f, p)
        }
        return f.path
    }

    actual fun ensureTrailingSeparator(path: String): String {
        val sep = File.separator
        return if (path.endsWith(sep)) path else path + sep
    }

    actual fun writeText(path: String, text: String) {
        File(path).writeText(text, Charsets.UTF_8)
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
    }

    actual fun readBytes(path: String): ByteArray = File(path).readBytes()

    actual fun listFileNames(dir: String): List<String> {
        return File(dir).list()?.toList().orEmpty()
    }

    actual fun framePath(dir: String, index: Int): String {
        val base = ensureTrailingSeparator(dir)
        return base + index.toString().padStart(3, '0') + ".png"
    }
}

/**
 * Android PNG actual; Bitmap pixels are copied into caller-owned row-major ARGB arrays.
 *
 * Android PNG actual 实现；Bitmap 像素会复制到归调用方所有的行优先 ARGB 数组中。
 */
internal actual object PlatformPng {
    actual fun readSize(path: String): VapSize? {
        // `inJustDecodeBounds = true` makes BitmapFactory parse only the PNG header and skip pixel
        // allocation; outWidth/outHeight are populated without decoding the full image.
        // `inJustDecodeBounds = true` 让 BitmapFactory 仅解析 PNG 头而跳过像素分配；
        // outWidth/outHeight 在不解码整张图像的情况下被填充。
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return VapSize(opts.outWidth, opts.outHeight)
    }

    actual fun readArgb(path: String): ArgbImage? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        // getPixels copies out the data; recycling the Bitmap does not invalidate the returned array.
        // getPixels 会复制数据；回收 Bitmap 不会使返回的数组失效。
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return ArgbImage(w, h, argb)
    }

    actual fun writeArgb(path: String, image: ArgbImage) {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
        File(path).parentFile?.mkdirs()
        FileOutputStream(File(path)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}

/**
 * Android process actual; output is drained to avoid pipe backpressure and cancellation forcibly stops the child.
 *
 * Android 进程 actual 实现；持续排空输出以避免管道反压，协程取消时强制终止子进程。
 */
internal actual object PlatformProcess {
    actual suspend fun run(command: List<String>): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        try {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                coroutineContext.ensureActive()
                if (!reader.ready() && !process.isAlive) break
                if (reader.ready()) {
                    reader.readLine() ?: break
                } else {
                    process.waitFor(50, TimeUnit.MILLISECONDS)
                }
            }
            if (process.isAlive) {
                process.waitFor()
            }
            process.exitValue()
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
    }
}
