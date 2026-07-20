@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import org.jetbrains.skia.Image
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * JVM [VapPlatformFrame] backed by a Skia [Image]. The native pixel buffer is freed
 * on the first [release] call; subsequent calls are ignored.
 * JVM 平台 [VapPlatformFrame] 实现，底层为 Skia [Image]。首次 [release] 时释放原生像素
 * 缓冲区，后续调用将被忽略。
 */
public class JvmVapPlatformFrame internal constructor(
    public val image: Image,
) : VapPlatformFrame {
    private val released = AtomicBoolean(false)

    override val width: Int get() = image.width
    override val height: Int get() = image.height

    // Single-shot guard so multiple release paths (caller + drain on close) are safe.
    // 单次执行保护：调用方与 close 排空同时触发 release 也不会重复释放。
    override fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) {
            image.close()
        }
    }
}
