@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import org.jetbrains.skia.Image
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual class VapPlatformFrame internal constructor(
    public val image: Image,
) {
    private val released = AtomicBoolean(false)

    public actual val width: Int get() = image.width
    public actual val height: Int get() = image.height

    public actual fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) {
            image.close()
        }
    }
}
