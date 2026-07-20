package com.zxhhyj.vap.decode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Decode advances only when [playing] && [visible] (PAG-style: invisible stops the clock).
 *
 * 解码仅在同时处于 playing 与 visible 时推进（与 PAG 一致：不可见时停止计时）。
 *
 * Both inputs default to `true` so a freshly created gate is active. The gate
 * is intended for use from a single owner coroutine; mutators are not safe to
 * invoke concurrently with each other.
 */
public class VapPlaybackGate {
    private var playing: Boolean = true
    private var visible: Boolean = true
    private val active = MutableStateFlow(true)

    public fun isActive(): Boolean = active.value

    /** @return true if transitioned from inactive -> active */
    public fun setPlaying(value: Boolean): Boolean {
        playing = value
        return publish()
    }

    /** @return true if transitioned from inactive -> active */
    public fun setVisible(value: Boolean): Boolean {
        visible = value
        return publish()
    }

    /**
     * Suspend until the gate becomes active, or returns immediately when the
     * decoder has already been torn down ([isStopped] is true).
     *
     * 挂起直至门控变为 active；若解码器已销毁则立即返回。
     */
    public suspend fun awaitActive(isStopped: () -> Boolean) {
        if (isStopped()) return
        active.first { it || isStopped() }
    }

    private fun publish(): Boolean {
        val next = playing && visible
        val prev = active.value
        active.value = next
        return next && !prev
    }
}
