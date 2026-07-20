@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.Bitmap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Android [VapPlatformFrame] backed by a [Bitmap]. The pool reclaims native pixels
 * through the `onRelease` callback so callers must pair every successful `nextFrame`
 * (from [VapFrameDecoder]) with one [release]; double-release is a no-op via the
 * private `released` guard.
 * Android 平台 [VapPlatformFrame] 实现，底层为 [Bitmap]。位图池通过 `onRelease` 回调
 * 回收原生像素，因此调用方必须为每一次成功的 `nextFrame`（来自 [VapFrameDecoder]）
 * 调用配对一次 [release]，重复释放会被私有的 `released` 守卫短路为 no-op。
 */
public class AndroidVapPlatformFrame internal constructor(
    public val bitmap: Bitmap,
    private val onRelease: () -> Unit,
) : VapPlatformFrame {
    private val released = AtomicBoolean(false)

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    // Single-shot guard so multiple release paths (caller + drain on close) are safe.
    // 单次执行的保护，多次 release 路径（调用方 + close 时排空）也不会重复归还。
    override fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) onRelease()
    }
}
