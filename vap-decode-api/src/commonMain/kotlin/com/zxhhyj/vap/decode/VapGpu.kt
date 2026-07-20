package com.zxhhyj.vap.decode

/**
 * Process-wide GPU backend preference.
 *
 * 进程级 GPU 后端偏好。
 *
 * Platform implementations validate backend availability when a decode session opens,
 * so no startup registration or context installation is required.
 * 平台实现在解码会话打开时验证后端可用性，因此无需启动注册或安装 Context。
 */
public object VapGpu {
    /** Process default used when a session does not override. / 会话未覆盖时使用的进程默认值。 */
    public var defaultBackend: VapGpuBackend = VapGpuBackend.Vulkan

    /** Resolves a per-session preference against [defaultBackend]. / 根据 [defaultBackend] 解析会话偏好。 */
    public fun resolve(preferred: VapGpuBackend? = null): VapGpuBackend =
        preferred ?: defaultBackend

    public fun isOpenGl(backend: VapGpuBackend): Boolean = false
}
