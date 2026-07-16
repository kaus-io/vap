package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapSize

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

internal expect object PlatformPng {
    fun readSize(path: String): VapSize?
    fun readArgb(path: String): ArgbImage?
    fun writeArgb(path: String, image: ArgbImage)
}

internal data class ArgbImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    override fun equals(other: Any?): Boolean {
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

internal expect object PlatformProcess {
    suspend fun run(command: List<String>): Int
}
