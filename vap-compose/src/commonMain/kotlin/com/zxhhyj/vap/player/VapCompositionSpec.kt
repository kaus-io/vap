package com.zxhhyj.vap.player

import androidx.compose.runtime.Immutable

/**
 * Compile-time constants used across the Compose player.
 *
 * Compose 播放器共用的编译期常量。
 */
public object VapConstants {
    /**
     * Sentinel for [VapAnimation] / [animateVapCompositionAsState] `iterations` to loop forever.
     *
     * 传给 [VapAnimation] / [animateVapCompositionAsState] 的 `iterations` 时表示无限循环。
     */
    public const val IterateForever: Int = Int.MAX_VALUE
}

/**
 * Source spec for [loadVapComposition]: either a file path or an owned byte buffer.
 *
 * [loadVapComposition] 的输入来源：文件路径或已持有的字节缓冲。
 */
@Immutable
public sealed class VapCompositionSpec {
    /**
     * MP4 located on the filesystem at absolute [path].
     *
     * 位于文件系统绝对路径 [path] 的 MP4。
     */
    public data class File(val path: String) : VapCompositionSpec()

    /**
     * In-memory MP4 bytes. [invoke] copies [data]; use [wrap] only for an
     * already-owned buffer that will not be mutated.
     *
     * 内存中的 MP4 字节。[invoke] 会复制 [data]；仅当缓冲区已为你所有、不会被外部修改时，
     * 才应使用 [wrap] 以避免再次拷贝。
     */
    public class Bytes private constructor(
        public val data: ByteArray,
    ) : VapCompositionSpec() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "Bytes(size=${data.size})"

        public companion object {
            /**
             * Defensive copy: safe for buffers the caller may still mutate.
             *
             * 防御性拷贝：适用于调用方后续仍可能修改原缓冲区的场景。
             */
            public operator fun invoke(data: ByteArray): Bytes = Bytes(data.copyOf())

            /**
             * Takes ownership of [data]; caller must not mutate it afterwards.
             *
             * 直接持有 [data]；调用方必须在之后保证不再修改它。
             */
            public fun wrap(data: ByteArray): Bytes = Bytes(data)
        }
    }
}

/**
 * Adapt a [VapCompositionSpec] into the decoder-side `VapSource`.
 *
 * 将 [VapCompositionSpec] 转成解码器侧的 `VapSource`。
 */
public fun VapCompositionSpec.toVapSource(): VapSource = when (this) {
    is VapCompositionSpec.File -> VapSource.AbsolutePath(path)
    // Already a defensive copy (or wrap); share buffer without a second copy.
    // 已经是防御性拷贝（或 wrap）后的缓冲区，无需再次拷贝。
    is VapCompositionSpec.Bytes -> VapSource.Bytes.wrap(data)
}

/**
 * Inverse of [toVapSource] for callers that already hold a `VapSource`.
 *
 * [toVapSource] 的反向操作，供已持有 `VapSource` 的调用方使用。
 */
public fun VapSource.toCompositionSpec(): VapCompositionSpec = when (this) {
    is VapSource.AbsolutePath -> VapCompositionSpec.File(path)
    is VapSource.Bytes -> VapCompositionSpec.Bytes.wrap(data)
}
