package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * JVM filesystem actual used by the common encoder pipeline.
 *
 * 公共编码流程使用的 JVM 文件系统 actual 实现。
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
        return if (path.endsWith(File.separator)) path else path + File.separator
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
 * JVM PNG actual; decoded pixels are returned as a new row-major ARGB array.
 *
 * JVM PNG actual 实现；解码像素以新分配的行优先 ARGB 数组返回。
 */
internal actual object PlatformPng {
    actual fun readSize(path: String): VapSize? {
        val file = File(path)
        if (!file.exists()) return null
        val img = ImageIO.read(file) ?: return null
        return VapSize(img.width, img.height)
    }

    actual fun readArgb(path: String): ArgbImage? {
        val file = File(path)
        if (!file.exists()) return null
        val img = ImageIO.read(file) ?: return null
        val w = img.width
        val h = img.height
        val argb = IntArray(w * h)
        // getRGB copies pixels out of BufferedImage, so ArgbImage owns its array independently.
        // getRGB 会从 BufferedImage 复制像素，因此 ArgbImage 独立拥有其数组。
        img.getRGB(0, 0, w, h, argb, 0, w)
        return ArgbImage(w, h, argb)
    }

    actual fun writeArgb(path: String, image: ArgbImage) {
        val buf = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buf.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        File(path).parentFile?.mkdirs()
        ImageIO.write(buf, "PNG", File(path))
    }
}

/**
 * JVM process actual; output is drained to avoid pipe backpressure and cancellation forcibly stops the child.
 *
 * JVM 进程 actual 实现；持续排空输出以避免管道反压，协程取消时强制终止子进程。
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
