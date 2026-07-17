package com.zxhhyj.vap.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /**
     * In-memory MP4 bytes held in Compose Snapshot [State].
     *
     * Replacing the buffer via [update] notifies readers in composition.
     * In-place mutation of the array is not observed — always [update] (or
     * construct a new [Bytes]) with a new/copied array.
     */
    @Stable
    public class Bytes private constructor(
        initial: ByteArray,
    ) : VapSource() {
        private var dataState: ByteArray by mutableStateOf(initial)

        public val data: ByteArray
            get() = dataState

        /** Replace bytes (copied); triggers recomposition for Snapshot readers. */
        public fun update(data: ByteArray) {
            dataState = data.copyOf()
        }

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

            /** Takes ownership of [data]; prefer not to mutate it — use [update] instead. */
            public fun wrap(data: ByteArray): Bytes = Bytes(data)
        }
    }
}
