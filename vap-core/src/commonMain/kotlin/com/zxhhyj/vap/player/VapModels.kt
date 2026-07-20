package com.zxhhyj.vap.player

/**
 * Runtime view of a parsed VAP clip header.
 *
 * 已解析 VAP 片段头部的运行期模型。
 *
 * `width`/`height` describe the composited output canvas; `videoWidth` /
 * `videoHeight` describe the underlying encoded MP4 dimensions which may differ
 * when alpha/RGB tracks are cropped. `hasAlpha` indicates whether an alpha
 * track is present (older v2 clips may omit it).
 */
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
    val hasAlpha: Boolean = true,
)

/**
 * Integer rectangle in pixel space (origin top-left, both width/height in px).
 *
 * 像素空间中的整数矩形（原点在左上角，宽高单位均为像素）。
 */
public data class VapRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/**
 * Output canvas size in pixels; consumers should treat both fields as >= 1.
 *
 * 输出画布尺寸（像素），使用方应将两个字段视为不小于 1。
 */
public data class VapSize(
    val width: Int,
    val height: Int,
)

/**
 * Source kind passed to a VAP decoder.
 *
 * 传递给 VAP 解码器的源类型。
 *
 * Selects between a filesystem path (URI-on-disk access, decoder-managed I/O)
 * and an in-memory buffer. The two variants have different lifecycle rules,
 * so they are modeled as a sealed hierarchy.
 */
public sealed class VapSource {
    public data class AbsolutePath(val path: String) : VapSource()

    /**
     * In-memory MP4 bytes. The array must not be mutated after construction;
     * create a new [Bytes] instance to replace the buffer.
     *
     * 内存中的 MP4 字节。构造后不得再修改底层数组；如需替换缓冲，请创建新的 [Bytes] 实例。
     */
    public class Bytes private constructor(
        public val data: ByteArray,
    ) : VapSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "Bytes(size=${data.size})"

        public companion object {
            public operator fun invoke(data: ByteArray): Bytes = Bytes(data.copyOf())

            /** Takes ownership of [data]; caller must not mutate it afterwards. */
            public fun wrap(data: ByteArray): Bytes = Bytes(data)
        }
    }
}
