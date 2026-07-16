@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.Bitmap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual class VapPlatformFrame internal constructor(
    public val bitmap: Bitmap,
    private val onRelease: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    public actual val width: Int get() = bitmap.width
    public actual val height: Int get() = bitmap.height

    public actual fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) onRelease()
    }

    internal companion object {
        fun fromGlFrame(frame: VapGlFrame): VapPlatformFrame =
            VapPlatformFrame(frame.bitmap) { frame.release() }
    }
}
