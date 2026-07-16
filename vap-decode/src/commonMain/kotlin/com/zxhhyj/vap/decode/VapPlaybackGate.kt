package com.zxhhyj.vap.decode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Decode advances only when [playing] && [visible] (PAG-style: invisible stops the clock).
 */
internal class VapPlaybackGate {
    private var playing: Boolean = true
    private var visible: Boolean = true
    private val active = MutableStateFlow(true)

    fun isActive(): Boolean = active.value

    /** @return true if transitioned from inactive → active */
    fun setPlaying(value: Boolean): Boolean {
        playing = value
        return publish()
    }

    /** @return true if transitioned from inactive → active */
    fun setVisible(value: Boolean): Boolean {
        visible = value
        return publish()
    }

    suspend fun awaitActive(isStopped: () -> Boolean) {
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
