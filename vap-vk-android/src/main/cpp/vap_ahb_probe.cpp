#include "vap_ahb_probe.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <vulkan/vulkan.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <cerrno>
#include <dlfcn.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#define TAG "VapAhbProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace vap_ahb_probe {
namespace {

struct Report {
    char *buf;
    size_t cap;
    size_t len;
    int fails;

    void line(const char *s) {
        LOGI("%s", s);
        if (!buf || cap == 0) return;
        const size_t n = std::strlen(s);
        if (len + n + 2 >= cap) return;
        std::memcpy(buf + len, s, n);
        len += n;
        buf[len++] = '\n';
        buf[len] = '\0';
    }

    void fmt(const char *fmt, ...) {
        char tmp[512];
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(tmp, sizeof(tmp), fmt, ap);
        va_end(ap);
        line(tmp);
    }

    void ok(const char *name) { fmt("PASS  %s", name); }

    void fail(const char *name, const char *detail) {
        fails++;
        fmt("FAIL  %s — %s", name, detail);
        LOGE("FAIL %s — %s", name, detail);
    }
};

using GetIdFn = uint64_t (*)(const AHardwareBuffer *);

GetIdFn resolveGetId() {
    // API 31 exports AHardwareBuffer_getId; dynamic lookup keeps older NDK headers buildable.
    // API 31 起导出 AHardwareBuffer_getId；动态查找可保持旧版 NDK 头文件可编译。
    void *sym = dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId");
    return reinterpret_cast<GetIdFn>(sym);
}

bool fillDesc(AHardwareBuffer_Desc *d, uint32_t w, uint32_t h, uint32_t format, uint64_t usage) {
    std::memset(d, 0, sizeof(*d));
    d->width = w;
    d->height = h;
    d->layers = 1;
    d->format = format;
    d->usage = usage;
    d->stride = 0;
    d->rfu0 = 0;
    d->rfu1 = 0;
    return true;
}

void testIsSupported(Report &r) {
    AHardwareBuffer_Desc d{};
    fillDesc(&d, 64, 64, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN);
    if (AHardwareBuffer_isSupported(&d)) {
        r.ok("isSupported RGBA8888 CPU_RW");
    } else {
        r.fail("isSupported RGBA8888 CPU_RW", "false");
    }

    fillDesc(&d, 64, 64, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT);
    if (AHardwareBuffer_isSupported(&d)) {
        r.ok("isSupported RGBA8888 GPU_SAMPLED|COLOR_OUT");
    } else {
        r.fail("isSupported RGBA8888 GPU_SAMPLED|COLOR_OUT", "false");
    }

    fillDesc(&d, 64, 64, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE);
    if (AHardwareBuffer_isSupported(&d)) {
        r.ok("isSupported RGBA8888 GPU_SAMPLED only");
    } else {
        r.fail("isSupported RGBA8888 GPU_SAMPLED only", "false");
    }
}

AHardwareBuffer *allocOrFail(Report &r, const char *name, const AHardwareBuffer_Desc &desc) {
    AHardwareBuffer *buf = nullptr;
    const int rc = AHardwareBuffer_allocate(&desc, &buf);
    if (rc != 0 || !buf) {
        char detail[64];
        snprintf(detail, sizeof(detail), "rc=%d", rc);
        r.fail(name, detail);
        return nullptr;
    }
    r.ok(name);
    return buf;
}

void testAllocateDescribe(Report &r) {
    AHardwareBuffer_Desc want{};
    fillDesc(&want, 128, 96, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT);
    AHardwareBuffer *buf = allocOrFail(r, "allocate RGBA8888 GPU", want);
    if (!buf) return;

    AHardwareBuffer_Desc got{};
    AHardwareBuffer_describe(buf, &got);
    if (got.width == want.width && got.height == want.height && got.format == want.format) {
        r.fmt("PASS  describe %ux%u fmt=0x%x usage=0x%llx stride=%u",
              got.width, got.height, got.format,
              (unsigned long long) got.usage, got.stride);
    } else {
        r.fail("describe", "mismatch vs allocate");
    }

    AHardwareBuffer_acquire(buf);
    AHardwareBuffer_release(buf);
    r.ok("acquire/release extra ref");

    AHardwareBuffer_release(buf);
}

void testCpuLock(Report &r) {
    AHardwareBuffer_Desc d{};
    fillDesc(&d, 32, 32, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN);
    AHardwareBuffer *buf = allocOrFail(r, "allocate RGBA8888 CPU", d);
    if (!buf) return;

    void *vaddr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &vaddr) != 0 ||
        !vaddr) {
        r.fail("lock WRITE", "null/rc");
        AHardwareBuffer_release(buf);
        return;
    }
    auto *px = static_cast<uint32_t *>(vaddr);
    px[0] = 0xAABBCCDDu;
    if (AHardwareBuffer_unlock(buf, nullptr) != 0) {
        r.fail("unlock WRITE", "rc!=0");
        AHardwareBuffer_release(buf);
        return;
    }
    r.ok("lock/unlock WRITE");

    vaddr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &vaddr) != 0 ||
        !vaddr) {
        r.fail("lock READ", "null/rc");
        AHardwareBuffer_release(buf);
        return;
    }
    const uint32_t v = static_cast<uint32_t *>(vaddr)[0];
    AHardwareBuffer_unlock(buf, nullptr);
    if (v == 0xAABBCCDDu) {
        r.ok("CPU roundtrip pixel");
    } else {
        char detail[48];
        snprintf(detail, sizeof(detail), "got=0x%08x", v);
        r.fail("CPU roundtrip pixel", detail);
    }
    AHardwareBuffer_release(buf);
}

void testGetId(Report &r) {
    GetIdFn getId = resolveGetId();
    if (!getId) {
        r.line("SKIP  AHardwareBuffer_getId (symbol missing — OEM/API < 31 or stripped)");
        return;
    }
    r.ok("dlsym AHardwareBuffer_getId");

    AHardwareBuffer_Desc d{};
    fillDesc(&d, 16, 16, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT);
    AHardwareBuffer *a = nullptr;
    AHardwareBuffer *b = nullptr;
    if (AHardwareBuffer_allocate(&d, &a) != 0 || !a) {
        r.fail("getId alloc A", "rc");
        return;
    }
    if (AHardwareBuffer_allocate(&d, &b) != 0 || !b) {
        r.fail("getId alloc B", "rc");
        AHardwareBuffer_release(a);
        return;
    }
    const uint64_t idA = getId(a);
    const uint64_t idB = getId(b);
    r.fmt("INFO  getId A=%llu B=%llu ptrA=%p ptrB=%p",
          (unsigned long long) idA, (unsigned long long) idB, (void *) a, (void *) b);
    if (idA == 0 && idB == 0) {
        // D2 (SM7325/A13) and bengal (A14) expose a platform stub, not a TEYES-specific
        // OEM quirk, that always returns zero; an observed D2 decStrong crash after getId
        // makes leaking these two probe buffers safer.
        // D2（SM7325/A13）与 bengal（A14）暴露的是恒返零的平台桩，并非 TEYES 专属
        // OEM 特例；D2 曾在 getId 后触发 decStrong 崩溃，因此保守泄漏这两个探测缓冲区更安全。
        r.fail("getId non-zero", "both 0 (platform-wide stub) — skip further getId/release");
        r.line("WARN  leaking 2 AHB after getId=0 (defensive)");
        return;
    }
    if (idA == idB) {
        r.fail("getId unique", "A==B");
    } else {
        r.ok("getId unique non-zero");
    }
    AHardwareBuffer_acquire(a);
    const uint64_t idA2 = getId(a);
    AHardwareBuffer_release(a);
    if (idA2 == idA) {
        r.ok("getId stable across acquire");
    } else {
        r.fail("getId stable across acquire", "changed");
    }
    AHardwareBuffer_release(a);
    AHardwareBuffer_release(b);
}

bool checkVk(VkResult res, Report &r, const char *name) {
    if (res == VK_SUCCESS) {
        r.ok(name);
        return true;
    }
    char detail[48];
    snprintf(detail, sizeof(detail), "VkResult=%d", (int) res);
    r.fail(name, detail);
    return false;
}

void testVulkanImport(Report &r) {
    // Create an isolated Vulkan device only to validate AHB-to-VkImage import.
    // 创建隔离的 Vulkan 设备，仅用于验证 AHB 到 VkImage 的导入链路。
    const char *instExt[] = {
            "VK_KHR_get_physical_device_properties2",
            "VK_KHR_external_memory_capabilities",
            "VK_KHR_external_semaphore_capabilities",
    };
    VkApplicationInfo app{
            .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
            .pNext = nullptr,
            .pApplicationName = "VapAhbProbe",
            .applicationVersion = 1,
            .pEngineName = "vap",
            .engineVersion = 1,
            .apiVersion = VK_API_VERSION_1_1,
    };
    VkInstanceCreateInfo ici{
            .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .pApplicationInfo = &app,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = 3,
            .ppEnabledExtensionNames = instExt,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (!checkVk(vkCreateInstance(&ici, nullptr, &instance), r, "vkCreateInstance")) return;

    uint32_t pdCount = 0;
    vkEnumeratePhysicalDevices(instance, &pdCount, nullptr);
    if (pdCount == 0) {
        r.fail("enumeratePhysicalDevices", "count=0");
        vkDestroyInstance(instance, nullptr);
        return;
    }
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    {
        VkPhysicalDevice pds[8];
        uint32_t n = pdCount < 8 ? pdCount : 8;
        vkEnumeratePhysicalDevices(instance, &n, pds);
        physical = pds[0];
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(physical, &props);
        r.fmt("INFO  GPU=%s api=0x%x", props.deviceName, props.apiVersion);
    }

    uint32_t qCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &qCount, nullptr);
    uint32_t gfxFamily = UINT32_MAX;
    {
        VkQueueFamilyProperties qf[16];
        uint32_t n = qCount < 16 ? qCount : 16;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &n, qf);
        for (uint32_t i = 0; i < n; i++) {
            if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                gfxFamily = i;
                break;
            }
        }
    }
    if (gfxFamily == UINT32_MAX) {
        r.fail("graphicsQueueFamily", "none");
        vkDestroyInstance(instance, nullptr);
        return;
    }

    const char *devExt[] = {
            "VK_KHR_external_memory",
            "VK_KHR_dedicated_allocation",
            "VK_KHR_get_memory_requirements2",
            "VK_ANDROID_external_memory_android_hardware_buffer",
            "VK_EXT_queue_family_foreign",
            "VK_KHR_sampler_ycbcr_conversion",
    };
    float prio = 1.f;
    VkDeviceQueueCreateInfo qci{
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queueFamilyIndex = gfxFamily,
            .queueCount = 1,
            .pQueuePriorities = &prio,
    };
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
            .pNext = nullptr,
            .samplerYcbcrConversion = VK_TRUE,
    };
    VkPhysicalDeviceFeatures2 features2{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &ycbcr,
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
            .enabledExtensionCount = (uint32_t) (sizeof(devExt) / sizeof(devExt[0])),
            .ppEnabledExtensionNames = devExt,
            .pEnabledFeatures = nullptr,
    };
    VkDevice device = VK_NULL_HANDLE;
    if (!checkVk(vkCreateDevice(physical, &dci, nullptr, &device), r, "vkCreateDevice+AHB exts")) {
        vkDestroyInstance(instance, nullptr);
        return;
    }

    auto fnGetAhbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!fnGetAhbProps) {
        r.fail("vkGetAndroidHardwareBufferPropertiesANDROID", "null proc");
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return;
    }
    r.ok("GetAhbProps proc");

    AHardwareBuffer_Desc desc{};
    fillDesc(&desc, 64, 64, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
             AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT);
    AHardwareBuffer *ahb = nullptr;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || !ahb) {
        r.fail("vk-path allocate AHB", "rc");
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return;
    }

    auto importOnce = [&](const char *label) -> bool {
        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        ahbProps.pNext = &fmtProps;
        if (fnGetAhbProps(device, ahb, &ahbProps) != VK_SUCCESS) {
            r.fail(label, "GetAhbProps");
            return false;
        }
        r.fmt("INFO  %s fmt=0x%x extFmt=%llu allocSize=%llu memTypes=0x%x",
              label, (unsigned) fmtProps.format,
              (unsigned long long) fmtProps.externalFormat,
              (unsigned long long) ahbProps.allocationSize,
              ahbProps.memoryTypeBits);

        const bool externalFormat = (fmtProps.format == VK_FORMAT_UNDEFINED);
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
        VkImageCreateInfo imageCi{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = &extMemImg,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format,
                .extent = {64, 64, 1},
                .mipLevels = 1,
                .arrayLayers = 1,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        VkImage image = VK_NULL_HANDLE;
        if (vkCreateImage(device, &imageCi, nullptr, &image) != VK_SUCCESS) {
            r.fail(label, "vkCreateImage");
            return false;
        }
        VkImportAndroidHardwareBufferInfoANDROID importInfo{
                .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
                .pNext = nullptr,
                .buffer = ahb,
        };
        VkMemoryDedicatedAllocateInfo dedicated{
                .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                .pNext = &importInfo,
                .image = image,
                .buffer = VK_NULL_HANDLE,
        };
        VkMemoryAllocateInfo mai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = &dedicated,
                .allocationSize = ahbProps.allocationSize,
                .memoryTypeIndex = 0,
        };
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if (ahbProps.memoryTypeBits & (1u << i)) {
                mai.memoryTypeIndex = i;
                break;
            }
        }
        VkDeviceMemory memory = VK_NULL_HANDLE;
        if (vkAllocateMemory(device, &mai, nullptr, &memory) != VK_SUCCESS) {
            r.fail(label, "vkAllocateMemory(import)");
            vkDestroyImage(device, image, nullptr);
            return false;
        }
        if (vkBindImageMemory(device, image, memory, 0) != VK_SUCCESS) {
            r.fail(label, "vkBindImageMemory");
            vkFreeMemory(device, memory, nullptr);
            vkDestroyImage(device, image, nullptr);
            return false;
        }
        r.ok(label);
        vkDestroyImage(device, image, nullptr);
        vkFreeMemory(device, memory, nullptr);
        return true;
    };

    // Re-import the same AHB after destroying its Vulkan objects to model an idle cache entry.
    // 销毁 Vulkan 对象后重新导入同一 AHB，以模拟空闲缓存条目的复用。
    const bool first = importOnce("vkImport AHB #1");
    const bool second = first && importOnce("vkImport AHB #2 (reuse same AHB)");
    if (first && second) {
        r.ok("AHB Vulkan re-import (self-alloc, no ImageReader)");
    }

    AHardwareBuffer_release(ahb);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
}

// ---- Real AImageReader + AMediaCodec round trip: candidate-B AHB identity probe ----
// ---- 真实 AImageReader + AMediaCodec 往返：候选方案 B 的 AHB 身份探测 ----

int64_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

struct DecodeStats {
    int frames = 0;
    int acquireMisses = 0;
    int64_t wallMs = 0;
    bool eos = false;
    std::vector<const void *> ptrs;
    // References explicitly acquired during decode. AImage_getHardwareBuffer only borrows;
    // release these after codec stop and before deleting the reader.
    // 解码期间显式获取的引用。AImage_getHardwareBuffer 仅返回借用句柄；应在停止
    // codec 后、删除 reader 前释放这些引用。
    std::vector<AHardwareBuffer *> acquired;
    // Total references acquired during the pass; acquired is cleared during cleanup.
    // 本轮获取的引用总数；acquired 会在清理阶段清空。
    int acquiredTotal = 0;
};

// Decode a full pass into a PRIVATE ImageReader with maxImages=4 and GPU sampling usage;
// every codec output is rendered. Keeping up to two images open mirrors production's delayed
// Image.close and prevents trivial allocator reuse; holdAhbRing additionally pins recent AHBs
// while codec slots recycle, matching the import-cache lifetime model.
// 将完整视频的每个 codec 输出都渲染到 maxImages=4、支持 GPU 采样的 PRIVATE ImageReader。
// 最多保留两张未关闭图像以模拟生产环境延迟 Image.close 并排除简单地址复用；
// holdAhbRing 还会在 codec 槽位回收期间固定近期 AHB，以匹配导入缓存的生命周期模型。
bool decodeOnePass(Report &r, const char *path, int holdAhbRing, DecodeStats &st) {
    int srcFd = open(path, O_RDONLY);
    if (srcFd < 0) {
        char detail[96];
        snprintf(detail, sizeof(detail), "open errno=%d (%s)", errno, strerror(errno));
        r.fail("decode/open", detail);
        return false;
    }
    struct stat sb{};
    fstat(srcFd, &sb);
    r.fmt("INFO  decode src size=%lld bytes", (long long) sb.st_size);

    AMediaExtractor *ex = AMediaExtractor_new();
    if (!ex) {
        r.fail("decode/extractor", "new");
        close(srcFd);
        return false;
    }
    if (AMediaExtractor_setDataSourceFd(ex, srcFd, 0, (off64_t) sb.st_size) != AMEDIA_OK) {
        r.fail("decode/setDataSourceFd", path);
        AMediaExtractor_delete(ex);
        close(srcFd);
        return false;
    }
    AMediaCodec *codec = nullptr;
    AMediaFormat *trackFmt = nullptr;
    int32_t w = 0, h = 0;
    const size_t trackCount = AMediaExtractor_getTrackCount(ex);
    for (size_t i = 0; i < trackCount && !codec; i++) {
        AMediaFormat *fmt = AMediaExtractor_getTrackFormat(ex, i);
        const char *mime = nullptr;
        if (!AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) || !mime ||
            std::strncmp(mime, "video/", 6) != 0) {
            AMediaFormat_delete(fmt);
            continue;
        }
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
        codec = AMediaCodec_createDecoderByType(mime);
        if (codec) {
            AMediaExtractor_selectTrack(ex, i);
            trackFmt = fmt;
            r.fmt("INFO  decode track %zu mime=%s %dx%d", i, mime, (int) w, (int) h);
        } else {
            AMediaFormat_delete(fmt);
        }
    }
    if (!codec) {
        r.fail("decode/createDecoder", "no video track");
        AMediaExtractor_delete(ex);
        close(srcFd);
        return false;
    }

    AImageReader *reader = nullptr;
    if (AImageReader_newWithUsage(w, h, AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &reader) != AMEDIA_OK || !reader) {
        r.fail("decode/AImageReader", "newWithUsage");
        AMediaFormat_delete(trackFmt);
        AMediaCodec_delete(codec);
        AMediaExtractor_delete(ex);
        close(srcFd);
        return false;
    }
    ANativeWindow *win = nullptr;
    AImageReader_getWindow(reader, &win);

    if (AMediaCodec_configure(codec, trackFmt, win, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec) != AMEDIA_OK) {
        r.fail("decode/configure+start", "rc");
        // The reader owns this window; releasing it separately would double-release the ref.
        // 该窗口由 reader 持有，单独释放会导致引用被重复释放。
        AImageReader_delete(reader);
        AMediaFormat_delete(trackFmt);
        AMediaCodec_delete(codec);
        AMediaExtractor_delete(ex);
        close(srcFd);
        return false;
    }

    const int64_t t0 = nowMs();
    bool inputDone = false, outputDone = false;
    std::vector<AImage *> openFifo;
    int64_t lastProgressMs = t0;

    while (!outputDone) {
        if (!inputDone) {
            const ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 5000);
            if (inIdx >= 0) {
                size_t cap = 0;
                uint8_t *inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &cap);
                const ssize_t n = inBuf ? AMediaExtractor_readSampleData(ex, inBuf, cap) : -1;
                if (n <= 0) {
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0,
                            AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, (size_t) n,
                            AMediaExtractor_getSampleTime(ex), 0);
                    AMediaExtractor_advance(ex);
                }
            }
        }
        AMediaCodecBufferInfo info{};
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 100000);
        if (outIdx >= 0) {
            const bool eosOut = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            AMediaCodec_releaseOutputBuffer(codec, outIdx, true);
            lastProgressMs = nowMs();
            // Rendering into ImageReader is asynchronous; poll briefly for the produced image.
            // 渲染到 ImageReader 是异步的；短暂轮询以取得已生成的图像。
            AImage *img = nullptr;
            for (int tries = 0; tries < 200 && !img; tries++) {
                if (AImageReader_acquireLatestImage(reader, &img) != AMEDIA_OK) img = nullptr;
                if (!img) usleep(1000);
            }
            if (img) {
                AHardwareBuffer *ahb = nullptr;
                if (AImage_getHardwareBuffer(img, &ahb) == AMEDIA_OK && ahb) {
                    if (st.frames == 0) {
                        // Query one image twice to distinguish per-image wrappers from per-call wrappers.
                        // 对同一图像查询两次，以区分每图像包装与每调用包装。
                        AHardwareBuffer *ahb2 = nullptr;
                        AImage_getHardwareBuffer(img, &ahb2);
                        r.fmt("INFO  getHardwareBuffer x2 same image: %p vs %p",
                              (void *) ahb, (void *) ahb2);
                        // Both handles are borrowed; neither owns a releasable reference.
                        // 两个句柄均为借用句柄，都不拥有可释放的引用。
                    }
                    st.ptrs.push_back((const void *) ahb);
                    if (holdAhbRing > 0) {
                        // Hold one extra reference per frame while images recycle, matching the
                        // import-cache model; cleanup balances it after codec shutdown.
                        // 图像回收期间每帧额外持有一个引用，以匹配导入缓存模型；
                        // 清理阶段会在 codec 停止后配平释放。
                        AHardwareBuffer_acquire(ahb);
                        st.acquired.push_back(ahb);
                        st.acquiredTotal++;
                    }
                    // The returned AHB is borrowed; releasing it would underflow the
                    // reader/BufferQueue reference count because NDK acquired no ref for us.
                    // 返回的 AHB 是借用句柄；NDK 未替调用方增加引用，释放它会导致
                    // reader/BufferQueue 引用计数下溢。
                }
                st.frames++;
                openFifo.push_back(img);
                if ((int) openFifo.size() > 2) {
                    AImage_delete(openFifo.front());
                    openFifo.erase(openFifo.begin());
                }
            } else {
                st.acquireMisses++;
            }
            if (eosOut) outputDone = true;
        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (nowMs() - lastProgressMs > 3000) {
                r.fmt("WARN  decode watchdog: no output for 3s at frame %d", st.frames);
                break;
            }
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            lastProgressMs = nowMs();
        }
    }
    st.wallMs = nowMs() - t0;
    st.eos = outputDone;

    for (AImage *img : openFifo) AImage_delete(img);

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);
    // Release only references explicitly acquired above, after codec shutdown but before
    // reader deletion; borrowed getHardwareBuffer handles are never released here.
    // 仅释放上文显式获取的引用，时序为 codec 停止后、reader 删除前；
    // getHardwareBuffer 返回的借用句柄绝不在此释放。
    for (AHardwareBuffer *b : st.acquired) AHardwareBuffer_release(b);
    st.acquired.clear();
    AMediaExtractor_delete(ex);
    close(srcFd);
    AMediaFormat_delete(trackFmt);
    AImageReader_delete(reader); // Also releases win; do not release it separately.
                                 // 同时释放 win；不要再单独释放。
    return true;
}

int countDistinct(const std::vector<const void *> &v) {
    std::vector<const void *> seen;
    for (const void *p : v) {
        bool found = false;
        for (const void *q : seen) {
            if (q == p) {
                found = true;
                break;
            }
        }
        if (!found) seen.push_back(p);
    }
    return (int) seen.size();
}

// Smallest p in [1..maxP] such that v[i] == v[i-p] for all i >= startIdx+p; 0 if none.
int detectPeriod(const std::vector<const void *> &v, int startIdx, int maxP) {
    for (int p = 1; p <= maxP; p++) {
        if ((int) v.size() < startIdx + 2 * p) continue;
        bool ok = true;
        for (size_t i = (size_t) startIdx + p; i < v.size(); i++) {
            if (v[i] != v[i - p]) {
                ok = false;
                break;
            }
        }
        if (ok) return p;
    }
    return 0;
}

void testImageReaderIdentity(Report &r, const char *path) {
    DecodeStats st;
    if (!decodeOnePass(r, path, /*holdAhbRing=*/0, st)) return;
    r.fmt("INFO  identity frames=%d wall=%lldms misses=%d eos=%d",
          st.frames, (long long) st.wallMs, st.acquireMisses, st.eos ? 1 : 0);
    {
        std::string hex;
        char tmp[32];
        const size_t n = st.ptrs.size() < 12 ? st.ptrs.size() : 12;
        for (size_t i = 0; i < n; i++) {
            snprintf(tmp, sizeof(tmp), "%p ", st.ptrs[i]);
            hex += tmp;
        }
        r.fmt("INFO  first ptrs: %s", hex.c_str());
    }
    if (st.frames < 24) {
        r.fail("identity sample size", "<24 frames decoded");
        return;
    }
    const int distinct = countDistinct(st.ptrs);
    // Borrowed wrappers die with AImage_delete, allowing allocator address reuse. This pass
    // demonstrates why an unheld pointer is not a stable key; held references are tested below.
    // 借用包装会随 AImage_delete 销毁，地址可被分配器复用。本轮仅说明未持有的指针
    // 不能作为稳定键；持有引用的方案由后续测试验证。
    const int period = detectPeriod(st.ptrs, 8, 8);
    r.fmt("INFO  identity(borrowed) distinct=%d period=%d — unsound key (address reuse),"
          " see held-refs test", distinct, period);
}

// A held AHB address cannot be recycled, so pointer equality becomes a sound identity key.
// The remaining question is cache value: per-acquire wrappers make distinct pointers grow with
// frame count, while per-buffer wrappers converge to the bounded BufferQueue ring and yield hits.
// 被持有的 AHB 地址不会复用，因此指针相等可作为可靠身份键。剩余问题是缓存收益：
// 若每次 acquire 都新建包装，指针数会随帧数增长；若包装属于底层缓冲区，指针数会
// 收敛到有界的 BufferQueue 环，并产生稳定命中。
void testIdentityHeldRefs(Report &r, const char *path) {
    DecodeStats st;
    if (!decodeOnePass(r, path, /*holdAhbRing=*/1, st)) return;
    const int distinct = countDistinct(st.ptrs);
    // Cache viability is captured by the last newly observed pointer and the second-half
    // repeat ratio: linear growth is unusable, while early convergence indicates a reusable ring.
    // 以最后一个新指针的位置和后半程重复率衡量缓存可行性：线性增长不可用，
    // 提前收敛则表明存在可复用的缓冲环。
    int lastNew = -1, secondHalfHits = 0, secondHalfTotal = 0;
    {
        std::vector<const void *> seen;
        for (size_t i = 0; i < st.ptrs.size(); i++) {
            bool found = false;
            for (const void *q : seen) {
                if (q == st.ptrs[i]) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                seen.push_back(st.ptrs[i]);
                lastNew = (int) i;
            } else if ((int) i >= st.frames / 2) {
                secondHalfHits++;
            }
            if ((int) i >= st.frames / 2) secondHalfTotal++;
        }
    }
    r.fmt("INFO  held-refs identity frames=%d distinct=%d lastNew@%d halfHits=%d/%d acquired=%d",
          st.frames, distinct, lastNew, secondHalfHits, secondHalfTotal, st.acquiredTotal);
    if (st.frames >= 24 && secondHalfTotal > 0 &&
        secondHalfHits * 100 >= secondHalfTotal * 90) {
        r.ok("AHB pointer key viable with held refs (steady-state hits >=90%)");
    } else {
        r.fail("AHB pointer key viable with held refs",
               "pointers keep growing — no stable key");
    }
}

void testHoldRefsNoStall(Report &r, const char *path) {
    DecodeStats control, held;
    if (!decodeOnePass(r, path, /*holdAhbRing=*/0, control)) return;
    if (!decodeOnePass(r, path, /*holdAhbRing=*/2, held)) return;
    r.fmt("INFO  no-stall control frames=%d wall=%lldms",
          control.frames, (long long) control.wallMs);
    r.fmt("INFO  no-stall held(%d refs) frames=%d wall=%lldms",
          held.acquiredTotal, held.frames, (long long) held.wallMs);
    if (held.frames >= control.frames * 95 / 100 &&
        held.wallMs <= control.wallMs * 2 + 500) {
        r.ok("hold AHB refs while images recycle — no BufferQueue stall");
    } else {
        r.fail("hold AHB refs", "frame production collapsed / queue stalled");
    }
    // decodeOnePass balances acquired refs after codec shutdown; reaching here confirms that
    // release path did not crash.
    // decodeOnePass 会在 codec 停止后配平所获取的引用；执行至此说明该释放路径未崩溃。
    r.ok("release acquired AHB refs after codec stop");
}

} // namespace

int run(char *out, size_t outCap, const char *videoPath) {
    Report r{out, outCap, 0, 0};
    if (out && outCap) out[0] = '\0';
    r.line("=== VapAhbProbe: C AHardwareBuffer smoke ===");
    r.fmt("INFO  __ANDROID_API__=%d", __ANDROID_API__);

    // Run basic AHB and Vulkan checks first, then getId; media identity runs last because
    // an OEM RefBase defect makes that probe known-fatal on D2.
    // 先运行基础 AHB、Vulkan 与 getId 检查；媒体身份探测置于最后，因为 D2 的
    // OEM RefBase 缺陷会使该探测发生已知致命崩溃。
    testIsSupported(r);
    testAllocateDescribe(r);
    testCpuLock(r);
    testVulkanImport(r);
    testGetId(r);
    if (videoPath && videoPath[0]) {
        r.fmt("INFO  video=%s", videoPath);
        testImageReaderIdentity(r, videoPath);
        testIdentityHeldRefs(r, videoPath);
        testHoldRefsNoStall(r, videoPath);
    } else {
        r.line("SKIP  media round-trip tests (no video path)");
    }

    r.fmt("=== done fails=%d ===", r.fails);
    return r.fails;
}

} // namespace vap_ahb_probe
