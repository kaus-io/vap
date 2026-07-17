package com.zxhhyj.vap.player

import androidx.compose.runtime.Immutable

public object VapConstants {
    public const val IterateForever: Int = Int.MAX_VALUE
}

@Immutable
public sealed class VapCompositionSpec {
    public data class File(val path: String) : VapCompositionSpec()

    /**
     * In-memory MP4 bytes. [invoke] copies [data]; use [wrap] only for an
     * already-owned buffer that will not be mutated.
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
            public operator fun invoke(data: ByteArray): Bytes = Bytes(data.copyOf())

            /** Takes ownership of [data]; caller must not mutate it afterwards. */
            public fun wrap(data: ByteArray): Bytes = Bytes(data)
        }
    }
}

public fun VapCompositionSpec.toVapSource(): VapSource = when (this) {
    is VapCompositionSpec.File -> VapSource.AbsolutePath(path)
    // Already a defensive copy (or wrap); share buffer without a second copy.
    is VapCompositionSpec.Bytes -> VapSource.Bytes.wrap(data)
}

public fun VapSource.toCompositionSpec(): VapCompositionSpec = when (this) {
    is VapSource.AbsolutePath -> VapCompositionSpec.File(path)
    is VapSource.Bytes -> VapCompositionSpec.Bytes.wrap(data)
}
