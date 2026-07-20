package com.zxhhyj.vap.decode

import kotlinx.coroutines.delay

/**
 * Frame pacing helper used by the JVM decoder to keep presentation aligned with
 * a wall-clock grid.
 *
 * JVM 解码器使用的帧节奏控制辅助类，用于使呈现对齐到挂钟时间网格。
 *
 * The state is purely last-frame deltas; callers may interleave `setFixedPlaybackRate`,
 * `preRender` and `reset` from a single coroutine. Not thread-safe across callers.
 */
public class VapSpeedControl {
    private val oneMillion = 1_000_000L
    private var prevPresentUsec = 0L
    private var prevMonoUsec = 0L
    private var fixedFrameDurationUsec = 0L
    private var loopReset = true

    public fun setFixedPlaybackRate(fps: Int) {
        fixedFrameDurationUsec = if (fps <= 0) 0L else oneMillion / fps
    }

    public suspend fun preRender(presentationTimeUsec: Long): Long {
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
        // Clamp pathological deltas: negative on stream wrap, huge on seek/loop.
        // 夹断病态差值：环绕时为负、跳转/循环时过大。
        when {
            frameDelta < 0 -> frameDelta = 0
            frameDelta > 10 * oneMillion -> frameDelta = 5 * oneMillion
        }
        val desiredUsec = prevMonoUsec + frameDelta
        var nowUsec = System.nanoTime() / 1000
        var sleptUsec = 0L
        // Sleep in 500ms slices so coroutine cancellation stays responsive.
        // 按 500ms 分片睡眠，以保持协程取消的响应性。
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

    public fun reset() {
        prevPresentUsec = 0
        prevMonoUsec = 0
        loopReset = true
    }
}
