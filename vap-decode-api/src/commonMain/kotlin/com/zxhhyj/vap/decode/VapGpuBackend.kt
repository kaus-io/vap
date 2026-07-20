package com.zxhhyj.vap.decode

/**
 * GPU present/composite backend for Android VAP.
 *
 * Android VAP 使用的 GPU 呈现/合成后端。
 *
 * Chosen at process default ([VapGpu.defaultBackend]) and/or per decoder session.
 * Not hot-switched while a decode session is open.
 *
 * 通过进程级默认（[VapGpu.defaultBackend]）或每个解码会话单独指定；解码会话打开期间
 * 不可热切换。
 */
public enum class VapGpuBackend {
    /**
     * Vulkan 1.1 path: MediaCodec -> AHardwareBuffer -> VkImage -> swapchain present.
     * Requires API 29+ and device Vulkan 1.1.
     *
     * Vulkan 1.1 路径：MediaCodec -> AHardwareBuffer -> VkImage -> 交换链呈现。
     * 要求 API 29+ 且设备支持 Vulkan 1.1。
     */
    Vulkan,
}
