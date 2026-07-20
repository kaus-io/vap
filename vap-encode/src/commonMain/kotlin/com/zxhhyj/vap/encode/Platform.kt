package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapSize

/**
 * Platform filesystem contract for common encoding code.
 * Paths are platform-native; text is UTF-8 and byte operations preserve content exactly.
 *
 * 公共编码代码的平台文件系统契约。
 * 路径采用平台原生格式；文本使用 UTF-8，字节操作须精确保留内容。
 */
internal expect object PlatformFs {
    fun exists(path: String): Boolean
    fun mkdir(path: String)
    fun deleteIfExists(path: String)
    fun join(parent: String, vararg parts: String): String
    fun ensureTrailingSeparator(path: String): String
    fun writeText(path: String, text: String)
    fun writeBytes(path: String, bytes: ByteArray)
    fun readBytes(path: String): ByteArray
    fun listFileNames(dir: String): List<String>
    fun framePath(dir: String, index: Int): String
}

/**
 * Platform PNG contract for dimensions and row-major ARGB pixels.
 * Decoders return caller-owned storage; writers do not retain the supplied image.
 *
 * PNG 尺寸及行优先 ARGB 像素的平台契约。
 * 解码器返回归调用方所有的存储；写入器不会持有所传图像。
 */
internal expect object PlatformPng {
    fun readSize(path: String): VapSize?
    fun readArgb(path: String): ArgbImage?
    fun writeArgb(path: String, image: ArgbImage)
}

/**
 * Immutable image dimensions paired with mutable, caller-owned ARGB pixel storage.
 * Pixels are row-major and each `Int` uses `0xAARRGGBB` channel order.
 *
 * 不可变图像尺寸及归调用方所有的可变 ARGB 像素存储。
 * 像素按行优先排列，每个 `Int` 使用 `0xAARRGGBB` 通道顺序。
 */
internal data class ArgbImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        // IntArray defaults to reference equality, so two ArgbImage instances with identical pixels
        // would otherwise compare unequal; override uses contentEquals/contentHashCode.
        // IntArray 默认按引用比较，因此两个像素完全相同的 ArgbImage 会被判为不相等；
        // 此处重写使用 contentEquals / contentHashCode 进行按内容比较。
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArgbImage

        if (width != other.width) return false
        if (height != other.height) return false
        if (!argb.contentEquals(other.argb)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argb.contentHashCode()
        return result
    }
}

/**
 * Platform child-process contract: run the exact argument list and return its exit code.
 * Cancellation must stop the child process rather than leave it running.
 *
 * 平台子进程契约：按原参数列表执行并返回退出码。
 * 协程取消时必须终止子进程，不得让其继续运行。
 */
internal expect object PlatformProcess {
    suspend fun run(command: List<String>): Int
}
