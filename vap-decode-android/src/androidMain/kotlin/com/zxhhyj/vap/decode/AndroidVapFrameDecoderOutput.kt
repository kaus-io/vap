package com.zxhhyj.vap.decode

import android.media.MediaCodec
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

internal data class OutputHandleResult(
    val renderedInLoop: Int,
    val endLoop: Boolean,
)

/**
 * Output router on the codec callback thread: inline fast path first; otherwise queue
 * to the pump. The queued-count gate keeps frame order (see [AndroidVapFrameDecoder.queuedOutputs]).
 * [async] is the owning pipeline's queue, captured at attach time.
 * 在 codec 回调线程上的 output 路由器：先尝试内联快速路径，否则入队给泵。
 * 排队计数门保证帧顺序（参见 [AndroidVapFrameDecoder.queuedOutputs]）。[async] 是
 * 当前管线自有的队列，在 attach 时捕获。
 */
internal fun AndroidVapFrameDecoder.routeOutputBuffer(
    async: VapMediaCodecAsync,
    codec: MediaCodec,
    packet: VapMediaCodecAsync.OutputBuffer,
) {
    if (tryHandleOutputInline(codec, packet)) return
    queuedOutputs.addAndFetch(1)
    // Roll back the count if the queue rejected (closed during teardown): otherwise
    // a stale "queued > 0" would block the inline fast path on the next session.
    // 入队失败（teardown 中已关闭）时回滚计数：否则陈旧的「queued > 0」会在下一会话
    // 阻塞内联快速路径。
    if (!async.enqueueOutput(packet)) {
        queuedOutputs.addAndFetch(-1)
    }
}

/**
 * Inline render-release when the compositor slot is free. Returns false for anything
 * that must stay in the pump (EOS/teardown bookkeeping, gated playback, busy slot).
 * 当合成槽位空闲时内联 render+release。对于必须留在泵中的情况（EOS / 拆除簿记、
 * 暂停门控、槽位忙），返回 false。
 */
internal fun AndroidVapFrameDecoder.tryHandleOutputInline(codec: MediaCodec, packet: VapMediaCodecAsync.OutputBuffer): Boolean {
    if (stopped.load()) return false
    // HardwareBuffer mode is sleep-paced by speedControl in the pump; keep it there.
    // Direct mode also writes to a Surface, but it must be paced by the pump via
    // speedControl so that SurfaceFlinger timestamp release is not the only gate
    // (some surfaces / devices ignore it, causing 2x/refresh-rate playback).
    // HardwareBuffer 模式由泵中的 speedControl 按睡眠节拍控制，让它留在泵中。
    // Direct 模式虽然写入 Surface，也必须由泵的 speedControl 节拍控制，
    // 否则仅依赖 SurfaceFlinger 时间戳释放并不可靠（部分 surface / 设备会忽略，
    // 导致 2 倍 / 刷新率回放）。
    if (outputMode != VapGlOutputMode.WindowSurface && !isDirectMode) return false
    // EOS / empty buffers carry endLoop bookkeeping — pump handles them.
    // EOS / 空 buffer 携带 endLoop 簿记，由泵处理。
    if (packet.isEos || packet.size <= 0) return false
    if (!playbackGate.isActive()) return false
    // Direct mode: always go through the pump so preRender can sleep to media rate.
    // Direct 模式：始终走泵，以便 preRender 可以按媒体速率睡眠。
    if (isDirectMode) return false
    // An earlier output is queued or being handled by the pump — do not overtake it.
    // 更早的 output 已被入队或正在被泵处理 —— 不可抢先。
    if (queuedOutputs.load() != 0) return false
    // Direct mode has no compositor slot to gate; GL/Vulkan keep the single-slot invariant.
    // Direct 模式无合成槽位需要门控；GL/Vulkan 维持「单槽位」不变量。
    if (!isDirectMode && !glSlots.tryAcquire()) return false
    try {
        if (isDirectMode) {
            // Direct mode: pace releases to PTS so BufferQueue holds each frame until
            // its media timestamp. Without this the codec drains the entire file in
            // <1s and SF plays it back at 2x (or refresh-rate-bound) speed.
            // presentationTimeUs is source-relative (PTS=0 at stream start);
            // releaseOutputBuffer wants wall-clock nanos (System.nanoTime base),
            // so translate via playStartNs captured at the first release.
            // Direct 模式：按 PTS 节拍释放，使 BufferQueue 持有每一帧直到其媒体时间戳。
            // 没有这一步，codec 会在 <1s 内排空整个文件，SF 以 2 倍（或刷新率）速度回放。
            // presentationTimeUs 是源相对（PTS=0 表示流起始）；releaseOutputBuffer 要求
            // 挂钟时间纳秒（System.nanoTime 基线），所以需要在首次 release 时捕获的
            // playStartNs 之上做平移。
            val ptsNs = computeDirectReleaseTimestampNs(packet.presentationTimeUs)
            codec.releaseOutputBuffer(packet.index, ptsNs)
            // No GPU present callback in direct mode — signal VapAnimationState
            // here so presentedCount advances and the play loop ticks.
            // Direct 模式下没有 GPU present 回调 —— 在此通知 VapAnimationState，
            // 以推进 presentedCount 并驱动播放循环。
            presentedTicks.trySend(Unit)
        } else {
            codec.releaseOutputBuffer(packet.index, true)
        }
    } catch (e: IllegalStateException) {
        // Stale index during flush/teardown: the codec reclaims the buffer itself.
        // flush / teardown 期间的陈旧索引：codec 会自行回收该 buffer。
        if (!isDirectMode) glSlots.release()
        if (!stopped.load()) {
            android.util.Log.w("VapDecode", "inline releaseOutputBuffer failed", e)
        }
    }
    return true
}

internal suspend fun AndroidVapFrameDecoder.handleOutputBuffer(
    codec: MediaCodec,
    packet: VapMediaCodecAsync.OutputBuffer,
    renderedInLoop: Int,
    totalFrames: Int,
): OutputHandleResult {
    val eos = packet.isEos
    val render = packet.size > 0 && !eos
    var rendered = renderedInLoop
    if (render) {
        if (!playbackGate.isActive()) {
            codec.releaseOutputBuffer(packet.index, false)
            return OutputHandleResult(rendered, endLoop = false)
        }
        var submittedToGl = false
        if (!isDirectMode) glSlots.acquire()
        try {
            if (stopped.load() || !coroutineContext.isActive) {
                codec.releaseOutputBuffer(packet.index, false)
                return OutputHandleResult(rendered, endLoop = true)
            }
            if (!playbackGate.isActive()) {
                codec.releaseOutputBuffer(packet.index, false)
                return OutputHandleResult(rendered, endLoop = false)
            }
            // WindowSurface / Direct: vsync + PTS / target FPS paces present (no sleep).
            // HardwareBuffer: keep sleep-based realtime control.
            // WindowSurface / Direct：vsync + PTS / 目标 FPS 控制 present（无 sleep）。
            // HardwareBuffer：保留基于 sleep 的实时控制。
            if (outputMode != VapGlOutputMode.WindowSurface || isDirectMode) {
                speedControl.preRender(packet.presentationTimeUs)
            }
            if (isDirectMode) {
                // Timestamp release is kept as a secondary gate for SurfaceFlinger-backed
                // windows, but the pump is now primarily paced by preRender sleep.
                // 时间戳释放仍作为 SurfaceFlinger 窗口的次级门控，但泵主要由 preRender sleep 节拍。
                val ptsNs = computeDirectReleaseTimestampNs(packet.presentationTimeUs)
                codec.releaseOutputBuffer(packet.index, ptsNs)
                presentedTicks.trySend(Unit)
            } else {
                codec.releaseOutputBuffer(packet.index, true)
            }
            submittedToGl = true
            rendered++
        } finally {
            if (!submittedToGl && !isDirectMode) glSlots.release()
        }
    } else {
        codec.releaseOutputBuffer(packet.index, false)
    }

    return OutputHandleResult(rendered, endLoop = eos || rendered >= totalFrames)
}
