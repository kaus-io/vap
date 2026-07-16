package com.zxhhyj.vap.player

import androidx.compose.runtime.Immutable

public object VapConstants {
    public const val IterateForever: Int = Int.MAX_VALUE
}

@Immutable
public sealed class VapCompositionSpec {
    public data class File(val path: String) : VapCompositionSpec()

    public data class Bytes(val data: ByteArray) : VapCompositionSpec() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
}

public fun VapCompositionSpec.toVapSource(): VapSource = when (this) {
    is VapCompositionSpec.File -> VapSource.AbsolutePath(path)
    is VapCompositionSpec.Bytes -> VapSource.Bytes(data)
}

public fun VapSource.toCompositionSpec(): VapCompositionSpec = when (this) {
    is VapSource.AbsolutePath -> VapCompositionSpec.File(path)
    is VapSource.Bytes -> VapCompositionSpec.Bytes(data)
}
