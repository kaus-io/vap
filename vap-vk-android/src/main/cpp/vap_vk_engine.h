#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <cstdint>

namespace vap_vk {

    /**
     * Normalized texture-coordinate rectangle within the packed video frame.
     * 打包视频帧中的归一化纹理坐标矩形。
     */
    struct RectUv {
        float x0 = 0.f;
        float y0 = 0.f;
        float x1 = 1.f;
        float y1 = 1.f;
    };

    /**
     * Immutable packed-frame dimensions and alpha/RGB sampling regions.
     * 不可变的打包帧尺寸及 Alpha/RGB 采样区域。
     */
    struct CreateParams {
        int videoW = 1;
        int videoH = 1;
        RectUv alphaUv{};
        RectUv rgbUv{};
    };

    /**
     * Vulkan compositor for reconstructing and presenting VAP frames from AHBs.
     * 用于从 AHB 重建并呈现 VAP 帧的 Vulkan 合成器。
     */
    class Engine {
    public:
        /**
         * Create an engine; returns nullptr if shared Vulkan resources cannot initialize.
         * 创建引擎；共享 Vulkan 资源初始化失败时返回 nullptr。
         */
        static Engine *create(const CreateParams &params);

        /**
         * Release all resources and delete this engine; the pointer is invalid afterward.
         * 释放全部资源并删除当前引擎；调用后该指针失效。
         */
        void destroy();

        /**
         * Bind an output window; the engine retains its own ANativeWindow reference.
         * 绑定输出窗口；引擎会持有独立的 ANativeWindow 引用。
         */
        bool setOutputWindow(ANativeWindow *window, int width, int height);

        /**
         * Wait for outstanding device work, then release the surface and window.
         * 等待未完成的设备工作后释放 Surface 与窗口。
         */
        void clearOutputWindow();

        /**
         * Import an AHB, reconstruct VAP alpha, and present with fence synchronization.
         * 导入 AHB、重建 VAP Alpha，并通过栅栏同步完成呈现。
         * @param bufferId HardwareBuffer.getId() on API 31+, or 0 when unavailable.
         *                 API 31+ 传 HardwareBuffer.getId()，不可用时传 0。
         */
        bool presentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId);

        /**
         * Prepare one frame without queue access: retire the slot, import the AHB,
         * acquire and CPU-wait for a swapchain image, then record commands.
         * 在不访问队列的情况下准备一帧：回收槽位、导入 AHB、获取并在 CPU
         * 等待交换链图像，然后录制命令。
         */
        bool preparePresentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId);

        /**
         * Submit all prepared engines once and present all participating swapchains once.
         * 通过一次提交呈现全部已准备引擎所参与的交换链。
         */
        static bool submitPreparedBatch(Engine **engines, int count);

        /**
         * Discard a prepared frame that has not reached the queue.
         * 丢弃尚未进入队列的已准备帧。
         */
        void cancelPreparedPresent();

        /**
         * Retire frame slots whose shared batch fence is already signaled.
         * 回收共享批次栅栏已触发的帧槽位。
         */
        void retireSignaledBatches();

        /**
         * Return whether both the Vulkan device and output swapchain are usable.
         * 返回 Vulkan 设备与输出交换链是否均可用。
         */
        [[nodiscard]] bool ready() const;

        Engine(const Engine &) = delete;

        Engine &operator=(const Engine &) = delete;

    private:
        Engine() = default;

        ~Engine() = default;

        struct Impl;
        Impl *impl_ = nullptr;

        /**
         * Keep the implementation reachable for analyzers that inspect only this header.
         * 让仅检查此头文件的分析器仍可追踪实现对象。
         */
        [[nodiscard]] Impl *pimpl() noexcept { return impl_; }

        [[nodiscard]] const Impl *pimpl() const noexcept { return impl_; }
    };

} // namespace vap_vk
