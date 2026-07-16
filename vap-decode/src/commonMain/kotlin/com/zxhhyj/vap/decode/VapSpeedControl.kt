package com.zxhhyj.vap.decode

import kotlinx.coroutines.delay

internal class VapSpeedControl {
    private val oneMillion = 1_000_000L
    private var prevPresentUsec = 0L
    private var prevMonoUsec = 0L
    private var fixedFrameDurationUsec = 0L
    private var loopReset = true

    fun setFixedPlaybackRate(fps: Int) {
        fixedFrameDurationUsec = if (fps <= 0) 0L else oneMillion / fps
    }

    suspend fun preRender(presentationTimeUsec: Long): Long {
        if (prevMonoUsec == 0L) {
            prevMonoUsec = System.nanoTime() / 1000
            prevPresentUsec = presentationTimeUsec
            return 0L
        }
        if (loopReset) {
            prevPresentUsec = presentationTimeUsec - oneMillion / 30
            loopReset = false
        }
        var frameDelta = if (fixedFrameDurationUsec != 0L) {
            fixedFrameDurationUsec
        } else {
            presentationTimeUsec - prevPresentUsec
        }
        when {
            frameDelta < 0 -> frameDelta = 0
            frameDelta > 10 * oneMillion -> frameDelta = 5 * oneMillion
        }
        val desiredUsec = prevMonoUsec + frameDelta
        var nowUsec = System.nanoTime() / 1000
        var sleptUsec = 0L
        while (nowUsec < desiredUsec - 100) {
            var sleepTimeUsec = desiredUsec - nowUsec
            if (sleepTimeUsec > 500_000) sleepTimeUsec = 500_000
            delay(sleepTimeUsec / 1000)
            sleptUsec += sleepTimeUsec
            nowUsec = System.nanoTime() / 1000
        }
        prevMonoUsec += frameDelta
        prevPresentUsec += frameDelta
        return sleptUsec / 1000
    }

    fun reset() {
        prevPresentUsec = 0
        prevMonoUsec = 0
        loopReset = true
    }
}
