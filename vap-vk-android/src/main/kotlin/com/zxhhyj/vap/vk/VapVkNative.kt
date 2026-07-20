package com.zxhhyj.vap.vk

import android.hardware.HardwareBuffer
import android.view.Surface
import java.lang.reflect.Method

/**
 * JNI bridge to the Vulkan 1.1 AHB composite/present engine
 * (`MediaCodec → AHardwareBuffer → VkImage → swapchain`).
 *
 * Vulkan 1.1 AHB 合成与呈现引擎的 JNI 桥接层
 *（`MediaCodec → AHardwareBuffer → VkImage → swapchain`）。
 *
 * Lives in a classic [com.android.library] module because AGP 9's
 * `com.android.kotlin.multiplatform.library` does not support NDK/CMake.
 *
 * 由于 AGP 9 的 `com.android.kotlin.multiplatform.library` 不支持 NDK/CMake，
 * 本桥接层位于传统 [com.android.library] 模块中。
 */
public object VapVkNative {
    init {
        System.loadLibrary("vap_vk")
    }

    /**
     * Some OEM API-31+ images omit [HardwareBuffer.getId]; reflect and fall back to 0.
     *
     * 部分 OEM 的 API 31+ 系统镜像缺少 [HardwareBuffer.getId]；因此通过反射调用并在不可用时回退为 0。
     */
    private val hardwareBufferGetId: Method? = try {
        HardwareBuffer::class.java.getMethod("getId")
    } catch (_: Throwable) {
        null
    }

    /**
     * D2 TEYES stubs getId to always return 0; stop reflecting after the first useless call.
     *
     * D2 TEYES 将 getId 固定返回 0；首次确认无效后停止反射调用。
     */
    @Volatile
    private var hardwareBufferGetIdUsable = hardwareBufferGetId != null

    private fun hardwareBufferId(buffer: HardwareBuffer): Long {
        if (!hardwareBufferGetIdUsable) return 0L
        val m = hardwareBufferGetId ?: return 0L
        return try {
            val id = m.invoke(buffer) as Long
            if (id == 0L) hardwareBufferGetIdUsable = false
            id
        } catch (_: Throwable) {
            hardwareBufferGetIdUsable = false
            0L
        }
    }

    /**
     * Creates an engine from the packed-video dimensions and alpha/RGB pixel rectangles.
     * Returns an opaque native handle, or 0 on failure; each nonzero handle must be passed to [nativeDestroy].
     *
     * 根据打包视频尺寸及 alpha/RGB 像素矩形创建引擎。
     * 返回不透明原生句柄，失败时返回 0；每个非零句柄最终都必须传给 [nativeDestroy]。
     */
    public external fun nativeCreate(
        videoW: Int,
        videoH: Int,
        alphaX: Int,
        alphaY: Int,
        alphaW: Int,
        alphaH: Int,
        rgbX: Int,
        rgbY: Int,
        rgbW: Int,
        rgbH: Int,
    ): Long

    /**
     * Destroys a native engine; 0 is a no-op and a destroyed handle must not be reused.
     *
     * 销毁原生引擎；传入 0 时不执行操作，已销毁句柄不得再次使用。
     */
    public external fun nativeDestroy(handle: Long)

    /**
     * Sets or replaces the output [Surface] and requested extent.
     * Native code retains its own window reference and does not take ownership of the Java [Surface].
     *
     * 设置或替换输出 [Surface] 及请求尺寸。
     * 原生层持有独立的窗口引用，不接管 Java [Surface] 的所有权。
     */
    public external fun nativeSetOutputSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * Detaches and releases the engine's native output-window reference.
     * The caller remains responsible for the Java [Surface].
     *
     * 解除并释放引擎持有的原生输出窗口引用。
     * Java [Surface] 仍由调用方负责管理。
     */
    public external fun nativeClearOutputSurface(handle: Long)

    /**
     * Imports, composites, submits, and presents one [HardwareBuffer].
     * [bufferId] is a stable cache identity, or 0 when unavailable. The caller owns the Java wrapper;
     * native code does not close it and retains the references required for in-flight GPU work.
     *
     * 导入、合成、提交并呈现一个 [HardwareBuffer]。
     * [bufferId] 是稳定的缓存标识，不可用时为 0。Java 包装对象归调用方所有；
     * 原生层不会将其关闭，并会持有 GPU 在途工作所需的引用。
     */
    public external fun nativePresentHardwareBuffer(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        bufferId: Long,
    ): Boolean

    /**
     * Present helper that supplies a stable buffer id when available for native AHB import reuse.
     * The caller retains ownership of [hardwareBuffer].
     *
     * 呈现辅助方法：在可用时传递稳定的缓冲区 ID，以便原生层复用 AHB 导入。
     * [hardwareBuffer] 的所有权仍归调用方。
     */
    public fun presentHardwareBuffer(handle: Long, hardwareBuffer: HardwareBuffer): Boolean {
        return nativePresentHardwareBuffer(handle, hardwareBuffer, hardwareBufferId(hardwareBuffer))
    }

    /**
     * Batch path: imports the AHB and records commands without queue submission.
     * A following [nativeSubmitBatch] submits and presents all selected prepared engines together.
     * The [HardwareBuffer] ownership and [bufferId] rules match [nativePresentHardwareBuffer].
     *
     * 批处理路径：导入 AHB 并记录命令，但不提交队列。
     * 随后的 [nativeSubmitBatch] 会一起提交并呈现所有选中的已准备引擎。
     * [HardwareBuffer] 所有权及 [bufferId] 规则与 [nativePresentHardwareBuffer] 相同。
     */
    public external fun nativePreparePresent(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        bufferId: Long,
    ): Boolean

    /**
     * Prepare helper that supplies a stable buffer id when available; the caller retains buffer ownership.
     *
     * 准备辅助方法：在可用时传递稳定的缓冲区 ID；缓冲区所有权仍归调用方。
     */
    public fun preparePresent(handle: Long, hardwareBuffer: HardwareBuffer): Boolean {
        return nativePreparePresent(handle, hardwareBuffer, hardwareBufferId(hardwareBuffer))
    }

    /**
     * Uses one `vkQueueSubmit` and one `vkQueuePresentKHR` for prepared work from up to the first 64 nonzero handles.
     *
     * 对最多前 64 个非零句柄的已准备工作使用一次 `vkQueueSubmit` 和一次 `vkQueuePresentKHR`。
     */
    public external fun nativeSubmitBatch(handles: LongArray): Boolean

    /**
     * Retires submitted slots whose batch GPU work has signaled, releasing their retained resources.
     *
     * 回收批次 GPU 工作已发出完成信号的提交槽，并释放其持有的资源。
     */
    public external fun nativeRetireSignaled(handle: Long)

    /**
     * Returns true only when the handle has both an initialized Vulkan device and swapchain.
     *
     * 仅当句柄已初始化 Vulkan 设备和交换链时返回 true。
     */
    public external fun nativeReady(handle: Long): Boolean

    /**
     * Runs a device smoke test for C `AHardwareBuffer` Vulkan import and the
     * ImageReader/MediaCodec AHB identity probe (candidate B).
     * [videoPath] is an MP4 on app-readable storage used for decode round-trip tests.
     * Returns a multi-line report and also logs under tag `VapAhbProbe`.
     *
     * 执行 C `AHardwareBuffer` Vulkan 导入设备冒烟测试，以及
     * ImageReader/MediaCodec AHB 身份探测（候选 B）。
     * [videoPath] 是应用可读存储中的 MP4，用于解码往返测试。
     * 返回多行报告，并同时使用 `VapAhbProbe` 标签写入日志。
     */
    public external fun nativeProbeAhb(videoPath: String): String

    /**
     * B0 diagnostic: pins [buffer]'s native wrapper and returns its pointer, or 0 on failure.
     * The caller owns the returned native reference and must release it exactly once through
     * [nativeProbeAhbRelease]; the Java [HardwareBuffer] remains caller-owned.
     *
     * B0 诊断：固定 [buffer] 的原生包装并返回其指针，失败时返回 0。
     * 调用方拥有返回的原生引用，且必须通过 [nativeProbeAhbRelease] 恰好释放一次；
     * Java [HardwareBuffer] 的所有权仍归调用方。
     */
    public external fun nativeProbeAhbAcquire(buffer: HardwareBuffer): Long

    /**
     * Releases a native reference returned by [nativeProbeAhbAcquire]; 0 is a no-op.
     *
     * 释放 [nativeProbeAhbAcquire] 返回的原生引用；传入 0 时不执行操作。
     */
    public external fun nativeProbeAhbRelease(ptr: Long)
}
