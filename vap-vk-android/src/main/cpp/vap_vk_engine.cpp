#include "vap_vk_engine.h"

#include "shaders/vap_frag_spv.h"
#include "shaders/vap_vert_spv.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

#define VAP_VK_LOG_TAG "VapVk"
#define VAP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VAP_VK_LOG_TAG, __VA_ARGS__)
#define VAP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VAP_VK_LOG_TAG, __VA_ARGS__)

namespace vap_vk {
    namespace {

        constexpr uint32_t kApiVersion = VK_API_VERSION_1_1;
        // Two in-flight slots; keep this value aligned with VapVulkanPipeline.kInflightImages.
        // 两个在途槽位；此值需与 VapVulkanPipeline.kInflightImages 保持一致。
        constexpr int kFramesInFlight = 2;
        // Adreno 643 / D2 measurements (12-way, 2026-07-18) show push descriptors regress
        // CPU by 8 points versus persistent sets: the driver emulates pushes with internal
        // descriptor sets, making each record costlier than vkUpdateDescriptorSets. Keep the
        // path for other GPUs, but disable it here.
        // Adreno 643 / D2 实测（12 路，2026-07-18）表明，相比持久描述符集，推送描述符
        // 令 CPU 指标回退 8 点：驱动以内建描述符集模拟推送，每次录制反而比
        // vkUpdateDescriptorSets 更昂贵。保留该路径供其他 GPU 复测，但此处禁用。
        constexpr bool kEnablePushDescriptors = false;
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID fnGetAhbProps = nullptr;
        PFN_vkCreateSamplerYcbcrConversion fnCreateYcbcr = nullptr;
        PFN_vkDestroySamplerYcbcrConversion fnDestroyYcbcr = nullptr;

        bool check(VkResult r, const char *what) {
            if (r == VK_SUCCESS) return true;
            VAP_LOGE("%s failed: %d", what, (int) r);
            return false;
        }

        // Interleaved vertex layout: clip position.xy | alpha UV.xy | RGB UV.xy.
        // 交错顶点布局：裁剪空间位置.xy | Alpha UV.xy | RGB UV.xy。
        constexpr uint32_t kVertexFloats = 6;
        constexpr uint32_t kVertexStride = kVertexFloats * sizeof(float);
        constexpr uint32_t kVertexOffsetPos = 0;
        constexpr uint32_t kVertexOffsetAlpha = 2 * sizeof(float);
        constexpr uint32_t kVertexOffsetRgb = 4 * sizeof(float);

        struct AhbImport {
            AHardwareBuffer *ahb = nullptr; // Held while GPU sampling or cache residency can use it.
                                            // GPU 采样或缓存驻留期间始终持有。
            VkImage image = VK_NULL_HANDLE;
            VkDeviceMemory memory = VK_NULL_HANDLE;
            VkImageView view = VK_NULL_HANDLE;
            uint64_t bufferId = 0; // AHardwareBuffer_getId; 0 = unknown
        };

        // The cache owns an acquired AHB reference, pinning the native address. Pointer equality
        // is therefore a sound identity key while cached; a miss only adds one redundant import.
        // 缓存持有 AHB 引用以固定原生地址，因此驻留期间指针相等可作为可靠身份键；
        // 未命中最多只会多产生一次重复导入。
        struct ImportEntry {
            VkImage image = VK_NULL_HANDLE;
            VkDeviceMemory memory = VK_NULL_HANDLE;
            VkImageView view = VK_NULL_HANDLE;
            AHardwareBuffer *ahb = nullptr; // Acquired; released on eviction or flush.
                                            // 已获取；在淘汰或清空时释放。
            // Batch slot whose fence covers the latest sampling; -1 means non-batch path.
            // 栅栏覆盖最近一次采样的批次槽位；-1 表示非批次路径。
            int lastBatchSlot = -1;
            // presentCounter value at the most recent presentation of this buffer.
            // 此缓冲区最近一次呈现时的 presentCounter 值。
            uint64_t lastUsedFrame = 0;
        };

        // One batched submit/present completion point shared by all participating engines.
        // 一个由全部参与引擎共享的批量提交/呈现完成点。
        struct BatchSlot {
            VkFence fence = VK_NULL_HANDLE; // Initially signaled so first selection never blocks.
                                            // 初始为已触发，首次选取不会阻塞。
            VkSemaphore renderFinished = VK_NULL_HANDLE;
            int holds = 0; // Participant frame slots still dependent on this batch.
                           // 仍依赖本批次的参与帧槽位数量。
        };

        struct FrameSlot {
            VkCommandBuffer cmd = VK_NULL_HANDLE;
            VkFence fence = VK_NULL_HANDLE;
            VkSemaphore imageAvailable = VK_NULL_HANDLE;
            VkSemaphore renderFinished = VK_NULL_HANDLE;
            VkDescriptorSet descSet = VK_NULL_HANDLE;
            // Import currently sampled by this slot's descriptor set; cache owns its lifetime.
            // 此槽位描述符集当前采样的导入对象；其生命周期由缓存持有。
            VkImageView boundView = VK_NULL_HANDLE;
            AHardwareBuffer *boundAhb = nullptr;
            bool submitted = false;
            // Batch-path state: batch owns completion after submission; prepared means recorded
            // and awaiting submitPreparedBatch.
            // 批次路径状态：提交后由 batch 管理完成事件；prepared 表示已录制并等待
            // submitPreparedBatch。
            BatchSlot *batch = nullptr;
            bool prepared = false;
            uint32_t preparedImageIndex = 0;
        };

        // Process-wide Vulkan 1.1 device shared by all VAP sessions.
        // 由所有 VAP 会话共享的进程级 Vulkan 1.1 设备。
        struct SharedDevice {
            VkInstance instance = VK_NULL_HANDLE;
            VkPhysicalDevice physical = VK_NULL_HANDLE;
            VkDevice device = VK_NULL_HANDLE;
            uint32_t graphicsQueueFamily = 0;
            VkQueue graphicsQueue = VK_NULL_HANDLE;
            VkShaderModule vertModule = VK_NULL_HANDLE;
            VkShaderModule fragModule = VK_NULL_HANDLE;
            // Vulkan queues require external synchronization across sessions for submit,
            // present, and device-idle waits.
            // Vulkan 队列要求跨会话对提交、呈现及设备空闲等待进行外部同步。
            std::mutex queueMutex;
            int refCount = 0;
            VkPhysicalDeviceMemoryProperties memoryProps{};
            bool memoryPropsReady = false;
            // When enabled, write descriptors while recording commands instead of calling
            // vkUpdateDescriptorSets each frame.
            // 启用后在录制命令时写入描述符，替代每帧调用 vkUpdateDescriptorSets。
            bool pushDescriptors = false;
            PFN_vkCmdPushDescriptorSetKHR fnPushDescriptor = nullptr;

            // Round-robin completion slots for the batched submit/present path.
            // 批量提交/呈现路径循环使用的完成槽位。
            static constexpr int kBatchSlots = 6;
            BatchSlot batchSlots[kBatchSlots]{};

            static SharedDevice *acquire();

            static void release();

            bool init();

            void shutdown();
        };

        SharedDevice *gSharedDevice = nullptr;
        std::mutex gSharedDeviceMutex;

        // RAII guard for externally synchronizing the shared graphics queue.
        // 用于对共享图形队列实施外部同步的 RAII 守卫。
        struct QueueGuard {
            std::mutex *mutex = nullptr;

            explicit QueueGuard(SharedDevice *s) : mutex(s ? &s->queueMutex : nullptr) {
                if (mutex) mutex->lock();
            }

            ~QueueGuard() {
                if (mutex) mutex->unlock();
            }

            QueueGuard(const QueueGuard &) = delete;

            QueueGuard &operator=(const QueueGuard &) = delete;
        };

        VkShaderModule makeShaderModule(VkDevice device, const uint32_t *words, size_t count) {
            VkShaderModuleCreateInfo ci{
                    .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .codeSize = count * sizeof(uint32_t),
                    .pCode = words,
            };
            VkShaderModule mod = VK_NULL_HANDLE;
            if (vkCreateShaderModule(device, &ci, nullptr, &mod) != VK_SUCCESS)
                return VK_NULL_HANDLE;
            return mod;
        }

        bool SharedDevice::init() {
            uint32_t instanceApi = 0;
            vkEnumerateInstanceVersion(&instanceApi);
            if (instanceApi < kApiVersion) {
                VAP_LOGE("Instance Vulkan %u.%u < 1.1",
                         VK_VERSION_MAJOR(instanceApi), VK_VERSION_MINOR(instanceApi));
                return false;
            }

            const char *instExts[] = {
                    VK_KHR_SURFACE_EXTENSION_NAME,
                    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
                    VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
            };
            VkApplicationInfo app{
                    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                    .pNext = nullptr,
                    .pApplicationName = "vap",
                    .applicationVersion = 1,
                    .pEngineName = "vap",
                    .engineVersion = 1,
                    .apiVersion = kApiVersion,
            };
            VkInstanceCreateInfo ici{
                    .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .pApplicationInfo = &app,
                    .enabledLayerCount = 0,
                    .ppEnabledLayerNames = nullptr,
                    .enabledExtensionCount = (uint32_t) (sizeof(instExts) / sizeof(instExts[0])),
                    .ppEnabledExtensionNames = instExts,
            };
            if (!check(vkCreateInstance(&ici, nullptr, &instance), "vkCreateInstance"))
                return false;

            uint32_t count = 0;
            vkEnumeratePhysicalDevices(instance, &count, nullptr);
            if (count == 0) return false;
            std::vector<VkPhysicalDevice> devices(count);
            vkEnumeratePhysicalDevices(instance, &count, devices.data());
            bool found = false;
            for (auto pd: devices) {
                VkPhysicalDeviceProperties props{};
                vkGetPhysicalDeviceProperties(pd, &props);
                if (props.apiVersion < kApiVersion) continue;
                uint32_t qCount = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(pd, &qCount, nullptr);
                std::vector<VkQueueFamilyProperties> qprops(qCount);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, &qCount, qprops.data());
                for (uint32_t i = 0; i < qCount; i++) {
                    if (qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                        physical = pd;
                        graphicsQueueFamily = i;
                        VAP_LOGI("Shared VkDevice GPU: %s (api %u.%u)", props.deviceName,
                                 VK_VERSION_MAJOR(props.apiVersion),
                                 VK_VERSION_MINOR(props.apiVersion));
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            if (!found) {
                VAP_LOGE("No Vulkan 1.1 graphics device");
                return false;
            }

            const char *baseDevExts[] = {
                    VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                    VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
                    VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                    VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
                    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            };
            uint32_t extCount = 0;
            vkEnumerateDeviceExtensionProperties(physical, nullptr, &extCount, nullptr);
            std::vector<VkExtensionProperties> availExts(extCount);
            vkEnumerateDeviceExtensionProperties(physical, nullptr, &extCount,
                                                 availExts.data());
            bool hasPushDescriptor = false;
            for (const auto &e: availExts) {
                if (kEnablePushDescriptors &&
                    strcmp(e.extensionName, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME) == 0) {
                    hasPushDescriptor = true;
                    break;
                }
            }
            std::vector<const char *> devExts(
                    baseDevExts, baseDevExts + sizeof(baseDevExts) / sizeof(baseDevExts[0]));
            if (hasPushDescriptor) devExts.push_back(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
            float prio = 1.f;
            VkDeviceQueueCreateInfo qci{
                    .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .queueFamilyIndex = graphicsQueueFamily,
                    .queueCount = 1,
                    .pQueuePriorities = &prio,
            };
            VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{
                    .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
                    .pNext = nullptr,
                    .samplerYcbcrConversion = VK_TRUE,
            };
            VkPhysicalDeviceFeatures2 features2{
                    .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                    .pNext = &ycbcrFeatures,
                    .features = {},
            };
            VkDeviceCreateInfo dci{
                    .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                    .pNext = &features2,
                    .flags = 0,
                    .queueCreateInfoCount = 1,
                    .pQueueCreateInfos = &qci,
                    .enabledLayerCount = 0,
                    .ppEnabledLayerNames = nullptr,
                    .enabledExtensionCount = (uint32_t) devExts.size(),
                    .ppEnabledExtensionNames = devExts.data(),
                    .pEnabledFeatures = nullptr,
            };
            if (!check(vkCreateDevice(physical, &dci, nullptr, &device), "vkCreateDevice"))
                return false;
            vkGetDeviceQueue(device, graphicsQueueFamily, 0, &graphicsQueue);

            pushDescriptors = hasPushDescriptor;
            if (pushDescriptors) {
                fnPushDescriptor = (PFN_vkCmdPushDescriptorSetKHR)
                        vkGetDeviceProcAddr(device, "vkCmdPushDescriptorSetKHR");
                pushDescriptors = fnPushDescriptor != nullptr;
            }
            VAP_LOGI("push descriptors: %s", pushDescriptors ? "on" : "off");

            fnGetAhbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
                    vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
            fnCreateYcbcr = (PFN_vkCreateSamplerYcbcrConversion)
                    vkGetDeviceProcAddr(device, "vkCreateSamplerYcbcrConversion");
            fnDestroyYcbcr = (PFN_vkDestroySamplerYcbcrConversion)
                    vkGetDeviceProcAddr(device, "vkDestroySamplerYcbcrConversion");
            if (!fnGetAhbProps || !fnCreateYcbcr || !fnDestroyYcbcr) {
                VAP_LOGE("Missing AHB / YCbCr device entry points");
                return false;
            }

            vertModule = makeShaderModule(device, vap_vk_shaders::kVertSpv,
                                          vap_vk_shaders::kVertSpv_WORDS);
            fragModule = makeShaderModule(device, vap_vk_shaders::kFragSpv,
                                          vap_vk_shaders::kFragSpv_WORDS);
            if (!vertModule || !fragModule) return false;
            vkGetPhysicalDeviceMemoryProperties(physical, &memoryProps);
            memoryPropsReady = true;

            VkFenceCreateInfo bfci{
                    .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = VK_FENCE_CREATE_SIGNALED_BIT,
            };
            VkSemaphoreCreateInfo bsci{
                    .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
            };
            for (auto &b: batchSlots) {
                if (!check(vkCreateFence(device, &bfci, nullptr, &b.fence), "batchFence") ||
                    !check(vkCreateSemaphore(device, &bsci, nullptr, &b.renderFinished),
                           "batchSemaphore")) {
                    return false;
                }
            }
            VAP_LOGI("Shared VkDevice ready");
            return true;
        }

        void SharedDevice::shutdown() {
            if (device) vkDeviceWaitIdle(device);
            for (auto &b: batchSlots) {
                if (b.fence) vkDestroyFence(device, b.fence, nullptr);
                if (b.renderFinished) vkDestroySemaphore(device, b.renderFinished, nullptr);
                b = {};
            }
            if (vertModule) vkDestroyShaderModule(device, vertModule, nullptr);
            if (fragModule) vkDestroyShaderModule(device, fragModule, nullptr);
            if (device) vkDestroyDevice(device, nullptr);
            if (instance) vkDestroyInstance(instance, nullptr);
            vertModule = fragModule = VK_NULL_HANDLE;
            device = VK_NULL_HANDLE;
            instance = VK_NULL_HANDLE;
            physical = VK_NULL_HANDLE;
            graphicsQueue = VK_NULL_HANDLE;
            memoryPropsReady = false;
            memoryProps = {};
            fnGetAhbProps = nullptr;
            fnCreateYcbcr = nullptr;
            fnDestroyYcbcr = nullptr;
        }

        SharedDevice *SharedDevice::acquire() {
            std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
            if (!gSharedDevice) {
                auto *s = new SharedDevice();
                if (!s->init()) {
                    s->shutdown();
                    delete s;
                    return nullptr;
                }
                gSharedDevice = s;
            }
            gSharedDevice->refCount++;
            VAP_LOGI("Shared VkDevice acquire ref=%d", gSharedDevice->refCount);
            return gSharedDevice;
        }

        void SharedDevice::release() {
            std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
            if (!gSharedDevice) return;
            gSharedDevice->refCount--;
            VAP_LOGI("Shared VkDevice release ref=%d", gSharedDevice->refCount);
            if (gSharedDevice->refCount <= 0) {
                gSharedDevice->shutdown();
                delete gSharedDevice;
                gSharedDevice = nullptr;
            }
        }

    } // namespace

    struct Engine::Impl {
        CreateParams params{};
        SharedDevice *shared = nullptr;

        // Non-owning aliases into SharedDevice; never destroy them from an engine session.
        // 指向 SharedDevice 的非拥有别名；引擎会话不得销毁这些对象。
        VkInstance instance = VK_NULL_HANDLE;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        VkDevice device = VK_NULL_HANDLE;
        uint32_t graphicsQueueFamily = 0;
        VkQueue graphicsQueue = VK_NULL_HANDLE;
        VkShaderModule vertModule = VK_NULL_HANDLE;
        VkShaderModule fragModule = VK_NULL_HANDLE;

        VkCommandPool cmdPool = VK_NULL_HANDLE;
        FrameSlot frames[kFramesInFlight]{};
        uint32_t frameIndex = 0;
        bool frameDescSetsReady = false;
        // Batch-path acquire fence: a CPU wait replaces a queue-side acquire semaphore.
        // 批次路径的获取栅栏：以 CPU 等待替代队列侧的获取信号量。
        VkFence acquireFence = VK_NULL_HANDLE;

        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkDescriptorPool descPool = VK_NULL_HANDLE;

        VkBuffer vertexBuffer = VK_NULL_HANDLE;
        VkDeviceMemory vertexMemory = VK_NULL_HANDLE;

        // Vulkan YCbCr conversion must be baked into the descriptor layout as an immutable sampler.
        // Vulkan YCbCr 转换必须以不可变采样器形式固化在描述符布局中。
        VkSamplerYcbcrConversion ycbcrConv = VK_NULL_HANDLE;
        VkSampler ycbcrSampler = VK_NULL_HANDLE;
        uint64_t cachedExternalFormat = UINT64_MAX;
        VkFormat cachedAhbFormat = VK_FORMAT_MAX_ENUM;

        // ImageReader buffers in one stream share stable AHB properties. Resolve them once with
        // AHardwareBuffer_describe plus the Vulkan driver query, then reuse the cached copies.
        // 同一 ImageReader 流中的 AHB 属性稳定一致；通过 AHardwareBuffer_describe 与
        // Vulkan 驱动查询解析一次，随后复用缓存副本。
        bool ahbPropsCached = false;
        AHardwareBuffer_Desc cachedAhbDesc{};
        VkAndroidHardwareBufferFormatPropertiesANDROID cachedFmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID cachedAhbProps{};

        // The AHB import cache is effectively disabled: stale age 0 plus a sweep every present
        // imports and destroys each frame, approximating pre-cache behavior. On D2 (12-way), a
        // useful cap must cover the BufferQueue ring (>=18): CPU improves 11.5/16 points, but PSS
        // rises from 143 to 336 idle and 148 to 597 home (PAG: 376). Adreno forces dedicated
        // allocations per import; smaller caps thrash the ring. Memory is an acceptance
        // constraint, so caching remains disabled.
        // AHB 导入缓存实际上处于禁用状态：过期年龄为 0 且每次呈现都清扫，使每帧均导入
        // 并销毁，接近启用缓存前的行为。D2（12 路）上，有效容量必须覆盖 BufferQueue 环
        //（>=18）：CPU 改善 11.5/16 点，但 PSS 从空闲 143 增至 336、主页 148 增至
        // 597（PAG 为 376）。Adreno 会为每次导入强制专用分配；较小容量只会使环
        // 抖动。内存是验收硬约束，因此保持禁用。
        static constexpr size_t kImportCacheMax = 8;
        static constexpr uint64_t kImportStaleAge = 0;
        static constexpr uint64_t kImportSweepInterval = 1;
        std::unordered_map<AHardwareBuffer *, ImportEntry> importCache;
        uint64_t importHits = 0;
        uint64_t importMisses = 0;
        uint64_t importEvictions = 0;
        // Successful prepareAhb/presentAhb count used as the lastUsedFrame aging clock.
        // prepareAhb/presentAhb 成功次数，用作 lastUsedFrame 的老化时钟。
        uint64_t presentCounter = 0;
        // Log dedicated-allocation requirements only for the first AHB import.
        // 仅为首次 AHB 导入记录专用分配要求。
        bool ahbDedicatedLogged = false;

        ANativeWindow *window = nullptr;
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkFormat swapFormat = VK_FORMAT_R8G8B8A8_UNORM;
        VkExtent2D swapExtent{1, 1};
        std::vector<VkImage> swapImages;
        std::vector<VkImageView> swapViews;
        std::vector<VkFramebuffer> framebuffers;

        bool bindShared();

        bool createCommandResources();

        bool createShadersAndPipelineBase();

        bool createVertexBuffer();

        bool createSwapchain(int width, int height);

        void destroySwapchain();

        bool ensureRenderPass(VkFormat format);

        bool ensureGraphicsPipeline();

        bool ensureYcbcrResources(const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                                  bool externalFormat);

        void destroyYcbcrResources();

        bool allocateFrameDescSets();

        void freeFrameDescSets();

        void destroyAhbImport(AhbImport &imp) const;

        bool acquireAhbImport(AHardwareBuffer *buffer,
                              uint64_t bufferId,
                              const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                              const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
                              bool externalFormat,
                              AhbImport &out);

        // Cache lookup updates hit/miss counters and returns nullptr on a miss.
        // 缓存查找会更新命中/未命中计数，未命中时返回 nullptr。
        ImportEntry *findCachedImport(AHardwareBuffer *buffer);

        // Evict if needed, perform a full AHB import, and insert the result.
        // 按需淘汰后执行完整 AHB 导入，并插入结果。
        ImportEntry *insertCachedImport(AHardwareBuffer *buffer,
                                        uint64_t bufferId,
                                        const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                                        const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
                                        bool externalFormat);

        // Rebind a slot descriptor only when the cached image view changes.
        // 仅在缓存图像视图变化时重新绑定槽位描述符。
        void bindSlotImport(FrameSlot &slot, const ImportEntry &entry);

        void evictImportCache();

        // Evict retired imports unused for more than kImportStaleAge presents.
        // 淘汰超过 kImportStaleAge 次呈现未使用且已完成的导入对象。
        void evictAgedImports();

        bool importEntryRetired(const ImportEntry &entry) const;

        bool importBoundToPreparedSlot(VkImageView view) const;

        void clearSlotImportBinding(VkImageView view);

        void destroyImportEntry(ImportEntry &entry) const;

        // Destroy all cached imports only after the device is idle with respect to their images.
        // 仅可在设备不再使用这些图像时销毁全部缓存导入对象。
        void flushImportCache();

        bool waitRetireFrame(FrameSlot &slot);

        void waitRetireAllFrames();

        bool resolveAhbProps(AHardwareBuffer *buffer,
                             VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                             VkAndroidHardwareBufferPropertiesANDROID &ahbProps);

        void recordDrawCommands(FrameSlot &slot, VkImage srcImage, VkImageView srcView,
                                uint32_t imageIndex);

        bool presentAhb(AHardwareBuffer *buffer, uint64_t bufferId);

        bool prepareAhb(AHardwareBuffer *buffer, uint64_t bufferId);

        void cancelPrepared();

        // Retire slots whose shared batch fence is signaled. Every session runs this each tick,
        // including skipped presenters, so holds tracks only genuinely in-flight participants.
        // 回收共享批次栅栏已触发的槽位。每个会话每次 tick 都执行，包括跳过呈现者，
        // 因而 holds 只统计真实在途参与者。
        void retireSignaledBatches();

        [[nodiscard]] bool ready() const;

        void destroy();
    };

    bool Engine::Impl::bindShared() {
        shared = SharedDevice::acquire();
        if (!shared) return false;
        instance = shared->instance;
        physical = shared->physical;
        device = shared->device;
        graphicsQueueFamily = shared->graphicsQueueFamily;
        graphicsQueue = shared->graphicsQueue;
        vertModule = shared->vertModule;
        fragModule = shared->fragModule;
        return true;
    }

    bool Engine::Impl::createCommandResources() {
        VkCommandPoolCreateInfo pci{
                .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                .queueFamilyIndex = graphicsQueueFamily,
        };
        if (!check(vkCreateCommandPool(device, &pci, nullptr, &cmdPool), "vkCreateCommandPool")) {
            return false;
        }
        VkCommandBuffer cmds[kFramesInFlight];
        VkCommandBufferAllocateInfo cai{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = kFramesInFlight,
        };
        if (!check(vkAllocateCommandBuffers(device, &cai, cmds), "vkAllocateCommandBuffers")) {
            return false;
        }
        VkFenceCreateInfo fci{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        VkSemaphoreCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        for (int i = 0; i < kFramesInFlight; i++) {
            frames[i].cmd = cmds[i];
            if (!check(vkCreateFence(device, &fci, nullptr, &frames[i].fence), "vkCreateFence") ||
                !check(vkCreateSemaphore(device, &sci, nullptr, &frames[i].imageAvailable),
                       "imageAvailable") ||
                !check(vkCreateSemaphore(device, &sci, nullptr, &frames[i].renderFinished),
                       "renderFinished")) {
                return false;
            }
        }
        if (!check(vkCreateFence(device, &fci, nullptr, &acquireFence), "acquireFence")) {
            return false;
        }
        return true;
    }

    bool Engine::Impl::createShadersAndPipelineBase() {
        // Shader modules live on SharedDevice. Push descriptors need no pool; otherwise each
        // session owns one. The descriptor-set and pipeline layouts are deferred until the
        // first AHB reveals the immutable YCbCr sampler format.
        // Shader 模块位于 SharedDevice。推送描述符无需池，否则每个会话独占一个池；
        // 描述符集与管线布局延迟到首个 AHB 确定不可变 YCbCr 采样器格式后创建。
        if (shared->pushDescriptors) return true;
        VkDescriptorPoolSize poolSize{
                .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = (uint32_t) kFramesInFlight,
        };
        VkDescriptorPoolCreateInfo dpci{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
                .maxSets = (uint32_t) kFramesInFlight,
                .poolSizeCount = 1,
                .pPoolSizes = &poolSize,
        };
        return check(vkCreateDescriptorPool(device, &dpci, nullptr, &descPool), "descPool");
    }

    void Engine::Impl::destroyAhbImport(AhbImport &imp) const {
        if (imp.view) vkDestroyImageView(device, imp.view, nullptr);
        if (imp.image) vkDestroyImage(device, imp.image, nullptr);
        if (imp.memory) vkFreeMemory(device, imp.memory, nullptr);
        if (imp.ahb) AHardwareBuffer_release(imp.ahb);
        imp = {};
    }

    void Engine::Impl::freeFrameDescSets() {
        if (!frameDescSetsReady || !descPool) {
            for (auto &frame: frames) {
                frame.descSet = VK_NULL_HANDLE;
                frame.boundView = VK_NULL_HANDLE;
                frame.boundAhb = nullptr;
            }
            frameDescSetsReady = false;
            return;
        }
        VkDescriptorSet sets[kFramesInFlight];
        int n = 0;
        for (auto &frame: frames) {
            if (frame.descSet) sets[n++] = frame.descSet;
            frame.descSet = VK_NULL_HANDLE;
            frame.boundView = VK_NULL_HANDLE;
            frame.boundAhb = nullptr;
        }
        if (n > 0) vkFreeDescriptorSets(device, descPool, (uint32_t) n, sets);
        frameDescSetsReady = false;
    }

    bool Engine::Impl::allocateFrameDescSets() {
        if (frameDescSetsReady) return true;
        if (shared->pushDescriptors) {
            // No set allocation is needed because the view is pushed during command recording.
            // 视图会在命令录制时推送，因此无需分配描述符集。
            frameDescSetsReady = true;
            return true;
        }
        if (!setLayout || !descPool) return false;
        VkDescriptorSetLayout layouts[kFramesInFlight];
        for (auto &layout: layouts) layout = setLayout;
        VkDescriptorSetAllocateInfo dsai{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                .pNext = nullptr,
                .descriptorPool = descPool,
                .descriptorSetCount = kFramesInFlight,
                .pSetLayouts = layouts,
        };
        VkDescriptorSet sets[kFramesInFlight];
        if (!check(vkAllocateDescriptorSets(device, &dsai, sets), "frameDescSets")) return false;
        for (int i = 0; i < kFramesInFlight; i++) {
            frames[i].descSet = sets[i];
        }
        frameDescSetsReady = true;
        return true;
    }

    bool Engine::Impl::waitRetireFrame(FrameSlot &slot) {
        if (slot.batch) {
            // Batch submissions complete on the shared batch fence, not this frame's fence.
            // 批次提交由共享批次栅栏完成，而非当前帧自身的栅栏。
            if (!check(vkWaitForFences(device, 1, &slot.batch->fence, VK_TRUE, UINT64_MAX),
                       "waitBatchFence")) {
                return false;
            }
            slot.batch->holds--;
            slot.batch = nullptr;
            slot.submitted = false;
            return true;
        }
        if (!slot.submitted) return true;
        if (!check(vkWaitForFences(device, 1, &slot.fence, VK_TRUE, UINT64_MAX),
                   "waitFrameFence")) {
            return false;
        }
        slot.submitted = false;
        return true;
    }

    void Engine::Impl::waitRetireAllFrames() {
        for (auto &frame: frames) {
            waitRetireFrame(frame);
        }
    }

    void Engine::Impl::destroyYcbcrResources() {
        cancelPrepared();
        waitRetireAllFrames();
        // Cached image views embed ycbcrConv and must be destroyed before the conversion.
        // 缓存图像视图内嵌 ycbcrConv，必须先于转换对象销毁。
        flushImportCache();
        freeFrameDescSets();
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout) {
            vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (setLayout) {
            vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
            setLayout = VK_NULL_HANDLE;
        }
        if (ycbcrSampler) {
            vkDestroySampler(device, ycbcrSampler, nullptr);
            ycbcrSampler = VK_NULL_HANDLE;
        }
        if (ycbcrConv && fnDestroyYcbcr) {
            fnDestroyYcbcr(device, ycbcrConv, nullptr);
            ycbcrConv = VK_NULL_HANDLE;
        }
        cachedExternalFormat = UINT64_MAX;
        cachedAhbFormat = VK_FORMAT_MAX_ENUM;
    }

    bool Engine::Impl::ensureYcbcrResources(
            const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            bool externalFormat) {
        const uint64_t ext = externalFormat ? fmtProps.externalFormat : 0;
        const VkFormat fmt = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format;
        if (ycbcrSampler && setLayout && pipelineLayout &&
            cachedExternalFormat == ext && cachedAhbFormat == fmt) {
            return true;
        }

        destroyYcbcrResources();

        VkExternalFormatANDROID extFormat{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
                .pNext = nullptr,
                .externalFormat = fmtProps.externalFormat,
        };
        VkSamplerYcbcrConversionCreateInfo yci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
                .pNext = externalFormat ? &extFormat : nullptr,
                .format = fmt,
                .ycbcrModel = fmtProps.suggestedYcbcrModel,
                .ycbcrRange = fmtProps.suggestedYcbcrRange,
                .components = fmtProps.samplerYcbcrConversionComponents,
                .xChromaOffset = fmtProps.suggestedXChromaOffset,
                .yChromaOffset = fmtProps.suggestedYChromaOffset,
                .chromaFilter = VK_FILTER_LINEAR,
                .forceExplicitReconstruction = VK_FALSE,
        };
        if (!check(fnCreateYcbcr(device, &yci, nullptr, &ycbcrConv), "ycbcrConversion")) {
            return false;
        }

        VkSamplerYcbcrConversionInfo ycbcrInfo{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
                .pNext = nullptr,
                .conversion = ycbcrConv,
        };
        VkSamplerCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
                .pNext = &ycbcrInfo,
                .flags = 0,
                .magFilter = VK_FILTER_LINEAR,
                .minFilter = VK_FILTER_LINEAR,
                .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .mipLodBias = 0.f,
                .anisotropyEnable = VK_FALSE,
                .maxAnisotropy = 1.f,
                .compareEnable = VK_FALSE,
                .compareOp = VK_COMPARE_OP_NEVER,
                .minLod = 0.f,
                .maxLod = 0.f,
                .borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
                .unnormalizedCoordinates = VK_FALSE,
        };
        if (!check(vkCreateSampler(device, &sci, nullptr, &ycbcrSampler), "ycbcrSampler")) {
            destroyYcbcrResources();
            return false;
        }

        VkSampler immutable = ycbcrSampler;
        VkDescriptorSetLayoutBinding binding{
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .pImmutableSamplers = &immutable,
        };
        VkDescriptorSetLayoutCreateInfo slci{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = shared->pushDescriptors
                                 ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
                                 : (VkDescriptorSetLayoutCreateFlags) 0,
                .bindingCount = 1,
                .pBindings = &binding,
        };
        if (!check(vkCreateDescriptorSetLayout(device, &slci, nullptr, &setLayout), "setLayout")) {
            destroyYcbcrResources();
            return false;
        }
        VkPipelineLayoutCreateInfo plci{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .setLayoutCount = 1,
                .pSetLayouts = &setLayout,
                .pushConstantRangeCount = 0,
                .pPushConstantRanges = nullptr,
        };
        if (!check(vkCreatePipelineLayout(device, &plci, nullptr, &pipelineLayout),
                   "pipelineLayout")) {
            destroyYcbcrResources();
            return false;
        }

        cachedExternalFormat = ext;
        cachedAhbFormat = fmt;
        if (!ensureGraphicsPipeline()) return false;
        return allocateFrameDescSets();
    }

    bool Engine::Impl::createVertexBuffer() {
        const auto &a = params.alphaUv;
        const auto &r = params.rgbUv;
        // Triangle-strip order matches the GLES full-screen quad; alpha and RGB UV rectangles
        // remain independent so the shaders can sample both regions of the packed frame.
        // 三角带顺序与 GLES 全屏四边形一致；Alpha 与 RGB 的 UV 矩形彼此独立，
        // 供 shader 分别采样打包帧中的两个区域。
        const float verts[] = {
                -1.f, 1.f, a.x0, a.y0, r.x0, r.y0,
                -1.f, -1.f, a.x0, a.y1, r.x0, r.y1,
                1.f, 1.f, a.x1, a.y0, r.x1, r.y0,
                1.f, -1.f, a.x1, a.y1, r.x1, r.y1,
        };
        static_assert(sizeof(verts) / sizeof(float) == 4u * kVertexFloats);
        static_assert(sizeof(verts) == 4u * kVertexStride);
        VkDeviceSize size = sizeof(verts);
        VkBufferCreateInfo bci{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = size,
                .usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(device, &bci, nullptr, &vertexBuffer), "vertexBuffer"))
            return false;
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, vertexBuffer, &req);
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
        uint32_t memType = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((req.memoryTypeBits & (1u << i)) &&
                (memProps.memoryTypes[i].propertyFlags &
                 (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                memType = i;
                break;
            }
        }
        if (memType == UINT32_MAX) return false;
        VkMemoryAllocateInfo mai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = req.size,
                .memoryTypeIndex = memType,
        };
        if (!check(vkAllocateMemory(device, &mai, nullptr, &vertexMemory), "vertexMemory"))
            return false;
        vkBindBufferMemory(device, vertexBuffer, vertexMemory, 0);
        void *mapped = nullptr;
        vkMapMemory(device, vertexMemory, 0, size, 0, &mapped);
        std::memcpy(mapped, verts, sizeof(verts));
        vkUnmapMemory(device, vertexMemory);
        return true;
    }

    void Engine::Impl::destroySwapchain() {
        waitRetireAllFrames();
        for (auto fb: framebuffers) vkDestroyFramebuffer(device, fb, nullptr);
        framebuffers.clear();
        for (auto v: swapViews) vkDestroyImageView(device, v, nullptr);
        swapViews.clear();
        swapImages.clear();
        if (swapchain) {
            vkDestroySwapchainKHR(device, swapchain, nullptr);
            swapchain = VK_NULL_HANDLE;
        }
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (renderPass) {
            vkDestroyRenderPass(device, renderPass, nullptr);
            renderPass = VK_NULL_HANDLE;
        }
    }

    bool Engine::Impl::ensureRenderPass(VkFormat format) {
        if (renderPass && swapFormat == format) return true;
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (renderPass) {
            vkDestroyRenderPass(device, renderPass, nullptr);
            renderPass = VK_NULL_HANDLE;
        }
        swapFormat = format;

        VkAttachmentDescription color{
                .flags = 0,
                .format = format,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        };
        VkAttachmentReference colorRef{.attachment = 0, .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{
                .flags = 0,
                .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                .inputAttachmentCount = 0,
                .pInputAttachments = nullptr,
                .colorAttachmentCount = 1,
                .pColorAttachments = &colorRef,
                .pResolveAttachments = nullptr,
                .pDepthStencilAttachment = nullptr,
                .preserveAttachmentCount = 0,
                .pPreserveAttachments = nullptr,
        };
        VkSubpassDependency dep{
                .srcSubpass = VK_SUBPASS_EXTERNAL,
                .dstSubpass = 0,
                .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                .dependencyFlags = 0,
        };
        VkRenderPassCreateInfo rpci{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .attachmentCount = 1,
                .pAttachments = &color,
                .subpassCount = 1,
                .pSubpasses = &subpass,
                .dependencyCount = 1,
                .pDependencies = &dep,
        };
        return check(vkCreateRenderPass(device, &rpci, nullptr, &renderPass), "renderPass");
    }

    bool Engine::Impl::ensureGraphicsPipeline() {
        if (!renderPass || !pipelineLayout || !vertModule || !fragModule) return false;
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }

        VkPipelineShaderStageCreateInfo stages[2] = {
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_VERTEX_BIT,
                        .module = vertModule,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
                        .module = fragModule,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
        };
        VkVertexInputBindingDescription bind{
                .binding = 0,
                .stride = kVertexStride,
                .inputRate = VK_VERTEX_INPUT_RATE_VERTEX,
        };
        VkVertexInputAttributeDescription attrs[3] = {
                {.location = 0, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetPos},
                {.location = 1, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetAlpha},
                {.location = 2, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetRgb},
        };
        VkPipelineVertexInputStateCreateInfo vi{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .vertexBindingDescriptionCount = 1,
                .pVertexBindingDescriptions = &bind,
                .vertexAttributeDescriptionCount = 3,
                .pVertexAttributeDescriptions = attrs,
        };
        VkPipelineInputAssemblyStateCreateInfo ia{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
                .primitiveRestartEnable = VK_FALSE,
        };
        VkPipelineViewportStateCreateInfo vp{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .viewportCount = 1,
                .pViewports = nullptr,
                .scissorCount = 1,
                .pScissors = nullptr,
        };
        VkPipelineRasterizationStateCreateInfo rs{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .depthClampEnable = VK_FALSE,
                .rasterizerDiscardEnable = VK_FALSE,
                .polygonMode = VK_POLYGON_MODE_FILL,
                .cullMode = VK_CULL_MODE_NONE,
                .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
                .depthBiasEnable = VK_FALSE,
                .depthBiasConstantFactor = 0.f,
                .depthBiasClamp = 0.f,
                .depthBiasSlopeFactor = 0.f,
                .lineWidth = 1.f,
        };
        VkPipelineMultisampleStateCreateInfo ms{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
                .sampleShadingEnable = VK_FALSE,
                .minSampleShading = 0.f,
                .pSampleMask = nullptr,
                .alphaToCoverageEnable = VK_FALSE,
                .alphaToOneEnable = VK_FALSE,
        };
        VkPipelineColorBlendAttachmentState blendAtt{
                .blendEnable = VK_FALSE,
                .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstColorBlendFactor = VK_BLEND_FACTOR_ZERO,
                .colorBlendOp = VK_BLEND_OP_ADD,
                .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO,
                .alphaBlendOp = VK_BLEND_OP_ADD,
                .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                  VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
        };
        VkPipelineColorBlendStateCreateInfo blend{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .logicOpEnable = VK_FALSE,
                .logicOp = VK_LOGIC_OP_COPY,
                .attachmentCount = 1,
                .pAttachments = &blendAtt,
                .blendConstants = {0.f, 0.f, 0.f, 0.f},
        };
        VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dyn{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .dynamicStateCount = 2,
                .pDynamicStates = dynStates,
        };
        VkGraphicsPipelineCreateInfo gpci{
                .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stageCount = 2,
                .pStages = stages,
                .pVertexInputState = &vi,
                .pInputAssemblyState = &ia,
                .pTessellationState = nullptr,
                .pViewportState = &vp,
                .pRasterizationState = &rs,
                .pMultisampleState = &ms,
                .pDepthStencilState = nullptr,
                .pColorBlendState = &blend,
                .pDynamicState = &dyn,
                .layout = pipelineLayout,
                .renderPass = renderPass,
                .subpass = 0,
                .basePipelineHandle = VK_NULL_HANDLE,
                .basePipelineIndex = -1,
        };
        return check(
                vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &gpci, nullptr, &pipeline),
                "graphicsPipeline");
    }

    bool Engine::Impl::createSwapchain(int width, int height) {
        destroySwapchain();
        if (!surface || !window) return false;

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps);
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, nullptr);
        if (formatCount == 0) {
            VAP_LOGE("swapchain: no surface formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, formats.data());
        VkSurfaceFormatKHR chosen = formats[0];
        for (auto &f: formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
                chosen = f;
                break;
            }
        }
        if (!ensureRenderPass(chosen.format)) return false;
        // Pipeline creation remains deferred until the first AHB determines its YCbCr format.
        // 管线仍延迟到首个 AHB 确定其 YCbCr 格式后创建。
        if (pipelineLayout && !ensureGraphicsPipeline()) return false;

        swapExtent.width = (uint32_t) std::max(1, width);
        swapExtent.height = (uint32_t) std::max(1, height);
        if (caps.currentExtent.width != UINT32_MAX) {
            swapExtent = caps.currentExtent;
        } else {
            swapExtent.width = std::clamp(swapExtent.width, caps.minImageExtent.width,
                                          caps.maxImageExtent.width);
            swapExtent.height =
                    std::clamp(swapExtent.height, caps.minImageExtent.height,
                               caps.maxImageExtent.height);
        }

        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount)
            imageCount = caps.maxImageCount;

        VkSwapchainCreateInfoKHR sci{
                .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
                .pNext = nullptr,
                .flags = 0,
                .surface = surface,
                .minImageCount = imageCount,
                .imageFormat = chosen.format,
                .imageColorSpace = chosen.colorSpace,
                .imageExtent = swapExtent,
                .imageArrayLayers = 1,
                .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .preTransform = caps.currentTransform,
                .compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                .presentMode = VK_PRESENT_MODE_FIFO_KHR,
                .clipped = VK_TRUE,
                .oldSwapchain = VK_NULL_HANDLE,
        };
        if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
        } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        }
        if (!check(vkCreateSwapchainKHR(device, &sci, nullptr, &swapchain), "swapchain"))
            return false;

        uint32_t imgCount = 0;
        vkGetSwapchainImagesKHR(device, swapchain, &imgCount, nullptr);
        swapImages.resize(imgCount);
        vkGetSwapchainImagesKHR(device, swapchain, &imgCount, swapImages.data());
        swapViews.resize(imgCount);
        framebuffers.resize(imgCount);
        for (uint32_t i = 0; i < imgCount; i++) {
            VkImageViewCreateInfo vci{
                    .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .image = swapImages[i],
                    .viewType = VK_IMAGE_VIEW_TYPE_2D,
                    .format = chosen.format,
                    .components = {},
                    .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
            };
            if (!check(vkCreateImageView(device, &vci, nullptr, &swapViews[i]), "swapView"))
                return false;
            VkFramebufferCreateInfo fci{
                    .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .renderPass = renderPass,
                    .attachmentCount = 1,
                    .pAttachments = &swapViews[i],
                    .width = swapExtent.width,
                    .height = swapExtent.height,
                    .layers = 1,
            };
            if (!check(vkCreateFramebuffer(device, &fci, nullptr, &framebuffers[i]),
                       "framebuffer")) {
                return false;
            }
        }
        VAP_LOGI("Swapchain %ux%u format=%d images=%u", swapExtent.width, swapExtent.height,
                 (int) chosen.format, imgCount);
        return true;
    }

    bool Engine::Impl::acquireAhbImport(
            AHardwareBuffer *buffer,
            uint64_t bufferId,
            const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
            bool externalFormat,
            AhbImport &out) {
        destroyAhbImport(out);

        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);

        AHardwareBuffer_acquire(buffer);
        out.ahb = buffer;
        out.bufferId = bufferId != 0 ? bufferId : (uint64_t) (uintptr_t) buffer;

        VkExternalFormatANDROID extFormat{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
                .pNext = nullptr,
                .externalFormat = fmtProps.externalFormat,
        };
        VkExternalMemoryImageCreateInfo extMemImg{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                .pNext = externalFormat ? &extFormat : nullptr,
                .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
        };
        VkImageCreateInfo ici{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = &extMemImg,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format,
                .extent = {desc.width, desc.height, 1},
                .mipLevels = 1,
                .arrayLayers = desc.layers ? desc.layers : 1,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        if (!check(vkCreateImage(device, &ici, nullptr, &out.image), "createAhbImage")) {
            destroyAhbImport(out);
            return false;
        }

        // Query whether this image requires dedicated memory. Unconditionally chaining
        // VkMemoryDedicatedAllocateInfo makes some Adreno drivers privately duplicate an entire
        // AHB per import, so chain it only when required; cache hits avoid repeated queries.
        // 查询该图像是否必须使用专用内存。无条件串接 VkMemoryDedicatedAllocateInfo 会使
        // 部分 Adreno 驱动在每次导入时私下复制整块 AHB，因此仅在 required 时串接；
        // 缓存命中可避免重复查询。
        VkMemoryDedicatedRequirements dedicatedReqs{
                .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS,
                .pNext = nullptr,
                .prefersDedicatedAllocation = VK_FALSE,
                .requiresDedicatedAllocation = VK_FALSE,
        };
        VkMemoryRequirements2 memReqs2{
                .sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2,
                .pNext = &dedicatedReqs,
                .memoryRequirements = {},
        };
        VkImageMemoryRequirementsInfo2 reqInfo{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2,
                .pNext = nullptr,
                .image = out.image,
        };
        vkGetImageMemoryRequirements2(device, &reqInfo, &memReqs2);
        if (!ahbDedicatedLogged) {
            ahbDedicatedLogged = true;
            VAP_LOGI("ahb import dedicated: required=%d preferred=%d",
                     (int) dedicatedReqs.requiresDedicatedAllocation,
                     (int) dedicatedReqs.prefersDedicatedAllocation);
        }

        VkImportAndroidHardwareBufferInfoANDROID importInfo{
                .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
                .pNext = nullptr,
                .buffer = buffer,
        };
        VkMemoryDedicatedAllocateInfo dedicated{
                .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                .pNext = &importInfo,
                .image = out.image,
                .buffer = VK_NULL_HANDLE,
        };
        const bool needsDedicated = dedicatedReqs.requiresDedicatedAllocation == VK_TRUE;
        VkMemoryAllocateInfo mai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = needsDedicated ? (const void *) &dedicated : (const void *) &importInfo,
                .allocationSize = ahbProps.allocationSize,
                .memoryTypeIndex = 0,
        };
        VkPhysicalDeviceMemoryProperties localMemProps{};
        const VkPhysicalDeviceMemoryProperties *memProps =
                (shared && shared->memoryPropsReady) ? &shared->memoryProps : nullptr;
        if (!memProps) {
            vkGetPhysicalDeviceMemoryProperties(physical, &localMemProps);
            memProps = &localMemProps;
        }
        for (uint32_t i = 0; i < memProps->memoryTypeCount; i++) {
            if (ahbProps.memoryTypeBits & (1u << i)) {
                mai.memoryTypeIndex = i;
                break;
            }
        }
        if (!check(vkAllocateMemory(device, &mai, nullptr, &out.memory), "allocAhbMemory")) {
            destroyAhbImport(out);
            return false;
        }
        vkBindImageMemory(device, out.image, out.memory, 0);

        VkSamplerYcbcrConversionInfo ycbcrInfo{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
                .pNext = nullptr,
                .conversion = ycbcrConv,
        };
        VkImageViewCreateInfo vci{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = &ycbcrInfo,
                .flags = 0,
                .image = out.image,
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format,
                .components = {},
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        if (!check(vkCreateImageView(device, &vci, nullptr, &out.view), "srcView")) {
            destroyAhbImport(out);
            return false;
        }
        return true;
    }

    ImportEntry *Engine::Impl::findCachedImport(AHardwareBuffer *buffer) {
        auto it = importCache.find(buffer);
        if (it == importCache.end()) {
            importMisses++;
            return nullptr;
        }
        importHits++;
        it->second.lastUsedFrame = presentCounter;
        return &it->second;
    }

    ImportEntry *Engine::Impl::insertCachedImport(
            AHardwareBuffer *buffer,
            uint64_t bufferId,
            const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
            bool externalFormat) {
        evictImportCache();
        AhbImport imp{};
        if (!acquireAhbImport(buffer, bufferId, fmtProps, ahbProps, externalFormat, imp)) {
            return nullptr;
        }
        ImportEntry entry{};
        entry.image = imp.image;
        entry.memory = imp.memory;
        entry.view = imp.view;
        entry.ahb = imp.ahb;
        entry.lastBatchSlot = -1;
        entry.lastUsedFrame = presentCounter;
        auto res = importCache.emplace(buffer, entry);
        if (!res.second) {
            // Callers already looked up the key; destroy an impossible duplicate defensively.
            // 调用方已先行查键；防御性销毁理论上不可达的重复导入。
            destroyImportEntry(entry);
        }
        return &res.first->second;
    }

    void Engine::Impl::bindSlotImport(FrameSlot &slot, const ImportEntry &entry) {
        if (slot.boundView == entry.view) return; // Descriptor already samples this view.
                                                  // 描述符已采样该视图。
        if (shared->pushDescriptors) {
            // Push-descriptor mode writes the current view later in recordDrawCommands.
            // 推送描述符模式会稍后在 recordDrawCommands 中写入当前视图。
            slot.boundView = entry.view;
            slot.boundAhb = entry.ahb;
            return;
        }
        VkDescriptorImageInfo dii{
                .sampler = VK_NULL_HANDLE,
                .imageView = entry.view,
                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        };
        VkWriteDescriptorSet write{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = slot.descSet,
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .pImageInfo = &dii,
                .pBufferInfo = nullptr,
                .pTexelBufferView = nullptr,
        };
        vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
        slot.boundView = entry.view;
        slot.boundAhb = entry.ahb;
    }

    bool Engine::Impl::importEntryRetired(const ImportEntry &entry) const {
        if (entry.lastBatchSlot >= 0 && entry.lastBatchSlot < SharedDevice::kBatchSlots &&
            shared &&
            vkGetFenceStatus(device, shared->batchSlots[entry.lastBatchSlot].fence) !=
                    VK_SUCCESS) {
            return false;
        }
        // Frame fences cover non-batch submits. Reusing a frame or batch slot implies its older
        // submission completed, so checking every currently submitted frame is conservative.
        // 非批次提交由帧栅栏覆盖。帧槽或批次槽的复用意味着更早提交已完成，因此检查
        // 所有当前已提交帧是一种保守策略。
        for (const auto &frame: frames) {
            if (frame.submitted && vkGetFenceStatus(device, frame.fence) != VK_SUCCESS) {
                return false;
            }
        }
        return true;
    }

    bool Engine::Impl::importBoundToPreparedSlot(VkImageView view) const {
        for (const auto &frame: frames) {
            if (frame.prepared && frame.boundView == view) return true;
        }
        return false;
    }

    void Engine::Impl::clearSlotImportBinding(VkImageView view) {
        for (auto &frame: frames) {
            if (frame.boundView == view) {
                frame.boundView = VK_NULL_HANDLE;
                frame.boundAhb = nullptr;
            }
        }
    }

    void Engine::Impl::destroyImportEntry(ImportEntry &entry) const {
        if (entry.view) vkDestroyImageView(device, entry.view, nullptr);
        if (entry.image) vkDestroyImage(device, entry.image, nullptr);
        if (entry.memory) vkFreeMemory(device, entry.memory, nullptr);
        if (entry.ahb) AHardwareBuffer_release(entry.ahb);
        entry = {};
    }

    void Engine::Impl::evictAgedImports() {
        for (auto it = importCache.begin(); it != importCache.end();) {
            ImportEntry &entry = it->second;
            if (presentCounter - entry.lastUsedFrame <= kImportStaleAge ||
                !importEntryRetired(entry) || importBoundToPreparedSlot(entry.view)) {
                ++it;
                continue;
            }
            clearSlotImportBinding(entry.view);
            destroyImportEntry(entry);
            importEvictions++;
            it = importCache.erase(it);
        }
    }

    void Engine::Impl::evictImportCache() {
        // Remove retired stale entries first, including warm-up BufferQueue generations.
        // 优先移除已完成且过期的条目，包括 BufferQueue 预热阶段的旧代对象。
        evictAgedImports();
        if (importCache.size() < kImportCacheMax) return;
        // Never evict an import still visible to the GPU. If no retired entry qualifies, allow
        // temporary overflow and retry after later frames signal completion.
        // 绝不淘汰 GPU 仍可见的导入对象；若没有已完成条目可淘汰，则允许缓存暂时超限，
        // 待后续帧完成后重试。
        for (auto it = importCache.begin();
             it != importCache.end() && importCache.size() >= kImportCacheMax;) {
            ImportEntry &entry = it->second;
            if (!importEntryRetired(entry) || importBoundToPreparedSlot(entry.view)) {
                ++it;
                continue;
            }
            clearSlotImportBinding(entry.view);
            destroyImportEntry(entry);
            importEvictions++;
            it = importCache.erase(it);
        }
    }

    void Engine::Impl::flushImportCache() {
        for (auto &kv: importCache) destroyImportEntry(kv.second);
        importCache.clear();
        for (auto &frame: frames) {
            frame.boundView = VK_NULL_HANDLE;
            frame.boundAhb = nullptr;
        }
    }

    bool Engine::Impl::resolveAhbProps(
            AHardwareBuffer *buffer,
            VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            VkAndroidHardwareBufferPropertiesANDROID &ahbProps) {
        // All buffers in an ImageReader stream share one description, and format changes recreate
        // the session. After the first query, reuse these properties and even skip per-frame
        // AHardwareBuffer_describe; repair pNext because the cached copy referenced old stack data.
        // 同一 ImageReader 流的缓冲区共享描述，格式变化会重建会话。首次查询后复用属性，
        // 连每帧 AHardwareBuffer_describe 也跳过；缓存副本曾指向旧栈数据，需修复 pNext。
        if (ahbPropsCached) {
            fmtProps = cachedFmtProps;
            ahbProps = cachedAhbProps;
            ahbProps.pNext = &fmtProps; // Cached copy pointed to the previous stack object.
                                        // 缓存副本原先指向旧栈对象。
            return true;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        ahbProps.pNext = &fmtProps;
        if (!check(fnGetAhbProps(device, buffer, &ahbProps), "GetAhbProps")) return false;
        cachedFmtProps = fmtProps;
        cachedAhbProps = ahbProps;
        cachedAhbProps.pNext = nullptr;
        cachedAhbDesc = desc;
        ahbPropsCached = true;
        return true;
    }

    void Engine::Impl::recordDrawCommands(FrameSlot &slot, VkImage srcImage,
                                          VkImageView srcView, uint32_t imageIndex) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);

        // Acquire the imported AHB from the Android foreign queue family and make it readable by
        // fragment sampling before the draw records any texture access.
        // 从 Android 外部队列族接管导入的 AHB，并在绘制发生纹理访问前使其对片元采样可读。
        VkImageMemoryBarrier toSample{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
                .dstQueueFamilyIndex = graphicsQueueFamily,
                .image = srcImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toSample);

        VkClearValue clear{.color = {{0.f, 0.f, 0.f, 0.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkViewport viewport{0, 0, (float) swapExtent.width, (float) swapExtent.height, 0.f, 1.f};
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        if (shared->pushDescriptors) {
            // Embed the sampled view in the command buffer, replacing per-frame descriptor-set
            // updates and the pool; the descriptor layout supplies the immutable YCbCr sampler.
            // 将采样视图直接嵌入命令缓冲，替代逐帧描述符集更新及描述符池；
            // 不可变 YCbCr 采样器由描述符布局提供。
            VkDescriptorImageInfo dii{
                    .sampler = VK_NULL_HANDLE,
                    .imageView = srcView,
                    .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            };
            VkWriteDescriptorSet write{
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .pNext = nullptr,
                    .dstSet = VK_NULL_HANDLE,
                    .dstBinding = 0,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .pImageInfo = &dii,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            };
            shared->fnPushDescriptor(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                     pipelineLayout, 0, 1, &write);
        } else {
            vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                                    0, 1, &slot.descSet, 0, nullptr);
        }
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    bool Engine::Impl::presentAhb(AHardwareBuffer *buffer, uint64_t bufferId) {
        if (!ready() || !buffer || !fnGetAhbProps) return false;

        ImportEntry *entry = findCachedImport(buffer);
        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        bool externalFormat = false;
        if (entry) {
            // A cache hit reuses the properties and YCbCr resources established at import time.
            // 缓存命中会复用导入时确定的属性与 YCbCr 资源。
            if (!pipeline || !frameDescSetsReady) return false;
        } else {
            if (!resolveAhbProps(buffer, fmtProps, ahbProps)) return false;
            externalFormat = (fmtProps.format == VK_FORMAT_UNDEFINED);
            if (!ensureYcbcrResources(fmtProps, externalFormat) || !pipeline ||
                !frameDescSetsReady) {
                return false;
            }
        }

        FrameSlot &slot = frames[frameIndex];
        // Wait only for this slot's prior submission, preserving a two-frame pipeline instead of
        // serializing every presentation.
        // 仅等待当前槽位的上一次提交，保留双帧流水而非串行化每次呈现。
        if (!waitRetireFrame(slot)) return false;
        if (!check(vkResetFences(device, 1, &slot.fence), "resetFrameFence")) return false;

        if (!entry) {
            entry = insertCachedImport(buffer, bufferId, fmtProps, ahbProps, externalFormat);
            if (!entry) return false;
        }
        bindSlotImport(slot, *entry);

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                             VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acq != VK_SUCCESS) {
            VAP_LOGE("acquire failed %d", (int) acq);
            return false;
        }

        recordDrawCommands(slot, entry->image, entry->view, imageIndex);

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence), "queueSubmit")) {
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        // The cache keeps the sampled import alive until this submitted frame retires.
        // 缓存会让采样导入对象存活至该提交帧完成。
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        presentCounter++;
        if (presentCounter % kImportSweepInterval == 0) evictAgedImports();

        if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
        }
        return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
    }

    bool Engine::Impl::prepareAhb(AHardwareBuffer *buffer, uint64_t bufferId) {
        if (!ready() || !buffer || !fnGetAhbProps) return false;

        ImportEntry *entry = findCachedImport(buffer);
        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        bool externalFormat = false;
        if (entry) {
            // A cache hit reuses the properties and YCbCr resources established at import time.
            // 缓存命中会复用导入时确定的属性与 YCbCr 资源。
            if (!pipeline || !frameDescSetsReady) return false;
        } else {
            if (!resolveAhbProps(buffer, fmtProps, ahbProps)) return false;
            externalFormat = (fmtProps.format == VK_FORMAT_UNDEFINED);
            if (!ensureYcbcrResources(fmtProps, externalFormat) || !pipeline ||
                !frameDescSetsReady) {
                return false;
            }
        }

        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot)) return false;

        if (!entry) {
            entry = insertCachedImport(buffer, bufferId, fmtProps, ahbProps, externalFormat);
            if (!entry) return false;
        }
        bindSlotImport(slot, *entry);

        // Acquire with a fence and wait on the CPU instead of queueing an acquire semaphore. The
        // shared in-order GPU queue then carries no vsync-paced wait that could convoy unrelated
        // sessions; this was previously observed as the "fence 等待" stage.
        // 使用栅栏获取并在 CPU 侧等待，而非向队列附加获取信号量。这样共享的顺序 GPU
        // 队列不会携带受 vsync 节奏限制的等待，从而避免拖住无关会话；该现象此前表现为
        // “fence 等待”阶段。
        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, VK_NULL_HANDLE,
                                             acquireFence, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acq != VK_SUCCESS) {
            VAP_LOGE("acquire failed %d", (int) acq);
            return false;
        }
        if (!check(vkWaitForFences(device, 1, &acquireFence, VK_TRUE, UINT64_MAX),
                   "waitAcquireFence") ||
            !check(vkResetFences(device, 1, &acquireFence), "resetAcquireFence")) {
            return false;
        }

        recordDrawCommands(slot, entry->image, entry->view, imageIndex);
        slot.preparedImageIndex = imageIndex;
        slot.prepared = true;
        presentCounter++;
        if (presentCounter % kImportSweepInterval == 0) evictAgedImports();
        return true;
    }

    void Engine::Impl::cancelPrepared() {
        for (auto &frame: frames) {
            frame.prepared = false;
        }
    }

    void Engine::Impl::retireSignaledBatches() {
        for (auto &frame: frames) {
            if (!frame.batch) continue;
            if (vkGetFenceStatus(device, frame.batch->fence) != VK_SUCCESS) continue;
            frame.batch->holds--;
            frame.batch = nullptr;
            frame.submitted = false;
        }
    }

    void Engine::Impl::destroy() {
        if (device && shared) {
            QueueGuard qlock(shared);
            vkDeviceWaitIdle(device);
        }
        cancelPrepared();
        waitRetireAllFrames();
        // Device-idle guarantees no view/image/memory or acquired AHB reference remains in use.
        // 设备空闲保证视图、图像、内存及已获取的 AHB 引用均不再被使用。
        VAP_LOGI("AHB import cache: hits=%llu misses=%llu evictions=%llu cached=%zu",
                 (unsigned long long) importHits, (unsigned long long) importMisses,
                 (unsigned long long) importEvictions, importCache.size());
        flushImportCache();
        destroySwapchain();
        destroyYcbcrResources();
        if (surface) {
            vkDestroySurfaceKHR(instance, surface, nullptr);
            surface = VK_NULL_HANDLE;
        }
        if (window) {
            ANativeWindow_release(window);
            window = nullptr;
        }
        if (vertexBuffer) vkDestroyBuffer(device, vertexBuffer, nullptr);
        if (vertexMemory) vkFreeMemory(device, vertexMemory, nullptr);
        if (descPool) vkDestroyDescriptorPool(device, descPool, nullptr);
        for (auto &frame: frames) {
            if (frame.imageAvailable) vkDestroySemaphore(device, frame.imageAvailable, nullptr);
            if (frame.renderFinished) vkDestroySemaphore(device, frame.renderFinished, nullptr);
            if (frame.fence) vkDestroyFence(device, frame.fence, nullptr);
            frame = {};
        }
        if (acquireFence) {
            vkDestroyFence(device, acquireFence, nullptr);
            acquireFence = VK_NULL_HANDLE;
        }
        if (cmdPool) vkDestroyCommandPool(device, cmdPool, nullptr);
        // Device, shader modules, and instance are process-wide; this engine only drops its ref.
        // 设备、Shader 模块与实例均为进程级对象；当前引擎仅减少共享引用。
        if (shared) {
            SharedDevice::release();
            shared = nullptr;
        }
        device = VK_NULL_HANDLE;
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE;
        graphicsQueue = VK_NULL_HANDLE;
        cmdPool = VK_NULL_HANDLE;
        frameIndex = 0;
        frameDescSetsReady = false;
        vertModule = VK_NULL_HANDLE;
        fragModule = VK_NULL_HANDLE;
        pipelineLayout = VK_NULL_HANDLE;
        setLayout = VK_NULL_HANDLE;
        descPool = VK_NULL_HANDLE;
        vertexBuffer = VK_NULL_HANDLE;
        vertexMemory = VK_NULL_HANDLE;
    }

    Engine *Engine::create(const CreateParams &params) {
        auto *engine = new Engine();
        engine->impl_ = new Impl();
        engine->impl_->params = params;
        if (!engine->impl_->bindShared() || !engine->impl_->createCommandResources() ||
            !engine->impl_->createShadersAndPipelineBase() ||
            !engine->impl_->createVertexBuffer()) {
            engine->destroy();
            return nullptr;
        }
        VAP_LOGI("Vulkan 1.1 engine ready (shared device) video=%dx%d", params.videoW,
                 params.videoH);
        return engine;
    }

    void Engine::destroy() {
        if (!impl_) {
            delete this;
            return;
        }
        impl_->destroy();
        delete impl_;
        impl_ = nullptr;
        delete this;
    }

    bool Engine::setOutputWindow(ANativeWindow *window, int width, int height) {
        if (!impl_ || !window) return false;
        clearOutputWindow();
        ANativeWindow_acquire(window);
        impl_->window = window;
        VkAndroidSurfaceCreateInfoKHR sci{
                .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                .pNext = nullptr,
                .flags = 0,
                .window = window,
        };
        if (!check(vkCreateAndroidSurfaceKHR(impl_->instance, &sci, nullptr, &impl_->surface),
                   "androidSurface")) {
            ANativeWindow_release(window);
            impl_->window = nullptr;
            return false;
        }
        VkBool32 supported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(impl_->physical, impl_->graphicsQueueFamily,
                                             impl_->surface,
                                             &supported);
        if (!supported) {
            VAP_LOGE("Queue family does not support present");
            clearOutputWindow();
            return false;
        }
        return impl_->createSwapchain(width, height);
    }

    void Engine::clearOutputWindow() {
        if (!impl_) return;
        if (impl_->device) {
            {
                QueueGuard qlock(impl_->shared);
                vkDeviceWaitIdle(impl_->device);
            }
            impl_->waitRetireAllFrames();
        }
        impl_->destroySwapchain();
        if (impl_->surface) {
            vkDestroySurfaceKHR(impl_->instance, impl_->surface, nullptr);
            impl_->surface = VK_NULL_HANDLE;
        }
        if (impl_->window) {
            ANativeWindow_release(impl_->window);
            impl_->window = nullptr;
        }
    }

    bool Engine::presentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId) {
        Impl *i = pimpl();
        return i && i->presentAhb(buffer, bufferId);
    }

    bool Engine::preparePresentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId) {
        Impl *i = pimpl();
        return i && i->prepareAhb(buffer, bufferId);
    }

    void Engine::cancelPreparedPresent() {
        Impl *i = pimpl();
        if (i) i->cancelPrepared();
    }

    void Engine::retireSignaledBatches() {
        Impl *i = pimpl();
        if (i) i->retireSignaledBatches();
    }

    bool Engine::submitPreparedBatch(Engine **engines, int count) {
        if (!engines || count <= 0) return true;
        constexpr int kMaxBatch = 16;
        Impl *impls[kMaxBatch];
        FrameSlot *slots[kMaxBatch];
        VkCommandBuffer cmds[kMaxBatch];
        VkSwapchainKHR swaps[kMaxBatch];
        uint32_t imageIndices[kMaxBatch];
        int m = 0;
        SharedDevice *shared = nullptr;
        for (int i = 0; i < count; i++) {
            Engine *e = engines[i];
            if (!e || !e->impl_) continue;
            Impl *im = e->impl_;
            FrameSlot &slot = im->frames[im->frameIndex];
            if (!slot.prepared) continue;
            if (m >= kMaxBatch) {
                VAP_LOGE("batch overflow, dropping prepared frame");
                slot.prepared = false;
                continue;
            }
            impls[m] = im;
            slots[m] = &slot;
            cmds[m] = slot.cmd;
            swaps[m] = im->swapchain;
            imageIndices[m] = slot.preparedImageIndex;
            shared = im->shared;
            m++;
        }
        if (m == 0 || !shared) return true;

        // Select a batch slot with no dependent frames. holds drains as each participant retires;
        // six slots absorb differing skip patterns without reusing an in-flight fence.
        // 选择无依赖帧的批次槽位。每个参与者回收时 holds 递减；六个槽位可吸收不同的
        // 跳帧模式，避免复用仍在途的栅栏。
        BatchSlot *batch = nullptr;
        for (auto &cand: shared->batchSlots) {
            if (cand.holds != 0) continue;
            if (!check(vkWaitForFences(shared->device, 1, &cand.fence, VK_TRUE, UINT64_MAX),
                       "waitBatchSlot") ||
                !check(vkResetFences(shared->device, 1, &cand.fence), "resetBatchSlot")) {
                return false;
            }
            batch = &cand;
            break;
        }
        if (!batch) {
            VAP_LOGE("no free batch slot; dropping %d prepared frames", m);
            for (int j = 0; j < m; j++) {
                slots[j]->prepared = false;
            }
            return false;
        }

        // Swapchain acquisition already completed via CPU-waited fences, so no wait semaphores are
        // needed and all sessions fit into one submit followed by one present call.
        // 交换链获取已通过 CPU 等待的栅栏完成，因此无需等待信号量；所有会话可合并为
        // 一次提交和随后一次呈现调用。
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = nullptr,
                .pWaitDstStageMask = nullptr,
                .commandBufferCount = (uint32_t) m,
                .pCommandBuffers = cmds,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &batch->renderFinished,
        };
        VkResult results[kMaxBatch];
        bool recreate[kMaxBatch];
        bool ok = true;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(shared->graphicsQueue, 1, &submit, batch->fence),
                       "batchQueueSubmit")) {
                ok = false;
            } else {
                VkPresentInfoKHR present{
                        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                        .pNext = nullptr,
                        .waitSemaphoreCount = 1,
                        .pWaitSemaphores = &batch->renderFinished,
                        .swapchainCount = (uint32_t) m,
                        .pSwapchains = swaps,
                        .pImageIndices = imageIndices,
                        .pResults = results,
                };
                VkResult pr = vkQueuePresentKHR(shared->graphicsQueue, &present);
                if (pr == VK_ERROR_OUT_OF_DATE_KHR) {
                    for (int j = 0; j < m; j++) recreate[j] = true;
                } else if (pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR) {
                    for (int j = 0; j < m; j++) {
                        recreate[j] = results[j] == VK_ERROR_OUT_OF_DATE_KHR ||
                                      results[j] == VK_SUBOPTIMAL_KHR;
                    }
                } else {
                    VAP_LOGE("batchPresent failed %d", (int) pr);
                    for (int j = 0; j < m; j++) recreate[j] = false;
                    ok = false;
                }
            }
        }
        if (!ok) {
            // On failure, clear prepared state while leaving cached imports intact.
            // 失败时清除准备状态，同时保留缓存导入对象。
            for (int j = 0; j < m; j++) {
                slots[j]->prepared = false;
            }
            return false;
        }
        const int batchIndex = (int) (batch - shared->batchSlots);
        for (int j = 0; j < m; j++) {
            if (recreate[j]) {
                // The recorded command may still use the old framebuffer. Wait on the batch
                // fence that covers it; per-engine frame fences do not cover batched work.
                // 已录制命令可能仍在使用旧帧缓冲；应等待覆盖它的批次栅栏，
                // 因为单引擎帧栅栏不覆盖批次工作。
                vkWaitForFences(shared->device, 1, &batch->fence, VK_TRUE, UINT64_MAX);
                impls[j]->waitRetireAllFrames();
                impls[j]->createSwapchain((int) impls[j]->swapExtent.width,
                                          (int) impls[j]->swapExtent.height);
            }
            slots[j]->prepared = false;
            slots[j]->submitted = true;
            slots[j]->batch = batch;
            batch->holds++;
            // Associate the sampled import with this batch fence so eviction can prove retirement.
            // 将采样导入对象关联到本批次栅栏，使淘汰逻辑可确认其已完成。
            if (slots[j]->boundAhb) {
                auto eit = impls[j]->importCache.find(slots[j]->boundAhb);
                if (eit != impls[j]->importCache.end()) {
                    eit->second.lastBatchSlot = batchIndex;
                }
            }
            impls[j]->frameIndex = (impls[j]->frameIndex + 1) % kFramesInFlight;
        }
        return true;
    }

    bool Engine::ready() const {
        const Impl *i = pimpl();
        return i && i->device && i->swapchain;
    }

    bool Engine::Impl::ready() const {
        return device != VK_NULL_HANDLE && swapchain != VK_NULL_HANDLE;
    }

} // namespace vap_vk
