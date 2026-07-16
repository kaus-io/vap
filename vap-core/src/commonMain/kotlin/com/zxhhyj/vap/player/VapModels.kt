package com.zxhhyj.vap.player

public data class VapConfig(
    val version: Int,
    val totalFrames: Int,
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int,
    val alphaFrame: VapRect,
    val rgbFrame: VapRect,
)

public data class VapRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

public data class VapSize(
    val width: Int,
    val height: Int,
)

public sealed class VapSource {
    public data class AbsolutePath(val path: String) : VapSource()
    public data class Bytes(val data: ByteArray) : VapSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
}
