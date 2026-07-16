package com.zxhhyj.vap.decode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class VapPlaybackGate {
    private val playing = MutableStateFlow(true)

    fun isPlaying(): Boolean = playing.value

    fun setPlaying(value: Boolean): Boolean {
        val prev = playing.value
        playing.value = value
        return value && !prev
    }

    suspend fun awaitPlaying(isStopped: () -> Boolean) {
        if (isStopped()) return
        playing.first { it || isStopped() }
    }
}
