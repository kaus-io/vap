#include "vap_ahb_probe.h"
#include "vap_vk_engine.h"

#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <vector>

#define LOG_TAG "VapVkJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

    inline float uv(jint px, float denom) {
        return static_cast<float>(px) / denom;
    }

} // namespace

/**
 * Create a native compositor and convert packed pixel rectangles to normalized UVs.
 * 创建原生合成器，并将打包像素矩形转换为归一化 UV。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeCreate(
        JNIEnv *,
        jobject,
        jint videoW,
        jint videoH,
        jint alphaX,
        jint alphaY,
        jint alphaW,
        jint alphaH,
        jint rgbX,
        jint rgbY,
        jint rgbW,
        jint rgbH) {
    const float vw = static_cast<float>(std::max(1, static_cast<int>(videoW)));
    const float vh = static_cast<float>(std::max(1, static_cast<int>(videoH)));
    vap_vk::CreateParams params{};
    params.videoW = videoW;
    params.videoH = videoH;
    params.alphaUv = {
            uv(alphaX, vw),
            uv(alphaY, vh),
            uv(alphaX + alphaW, vw),
            uv(alphaY + alphaH, vh),
    };
    params.rgbUv = {
            uv(rgbX, vw),
            uv(rgbY, vh),
            uv(rgbX + rgbW, vw),
            uv(rgbY + rgbH, vh),
    };
    auto *engine = vap_vk::Engine::create(params);
    return reinterpret_cast<jlong>(engine);
}

/**
 * Destroy the native compositor identified by an opaque Java handle.
 * 销毁由 Java 不透明句柄标识的原生合成器。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (engine) engine->destroy();
}

/**
 * Bind a Java Surface as the engine output while balancing the JNI window reference.
 * 将 Java Surface 绑定为引擎输出，并配平 JNI 获取的窗口引用。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSetOutputSurface(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject surface,
        jint width,
        jint height) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (!engine || !surface) return JNI_FALSE;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    const bool ok = engine->setOutputWindow(window, width, height);
    ANativeWindow_release(window); // setOutputWindow retains its own reference.
                                   // setOutputWindow 会持有独立引用。
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Detach and release the engine's current output Surface.
 * 解除并释放引擎当前的输出 Surface。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeClearOutputSurface(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (engine) engine->clearOutputWindow();
}

/**
 * Present one Java HardwareBuffer through the immediate Vulkan path.
 * 通过 Vulkan 即时路径呈现一个 Java HardwareBuffer。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePresentHardwareBuffer(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject hardwareBuffer,
        jlong bufferId) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (!engine || !hardwareBuffer) return JNI_FALSE;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }
    // Do not release the borrowed result here: some OEM implementations can UAF-crash if
    // ImageReader/MediaCodec still touches the queue. The engine acquires a separate GPU-lifetime ref.
    // 此处不要释放借用结果：部分 OEM 在 ImageReader/MediaCodec 仍访问队列时会触发 UAF；
    // 引擎会另行获取一份覆盖 GPU 使用期的引用。
    return engine->presentHardwareBuffer(ahb, static_cast<uint64_t>(bufferId))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

/**
 * Prepare one Java HardwareBuffer for a later shared batch submission.
 * 为后续共享批量提交准备一个 Java HardwareBuffer。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePreparePresent(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject hardwareBuffer,
        jlong bufferId) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (!engine || !hardwareBuffer) return JNI_FALSE;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }
    // Apply the same borrowed-handle lifetime rule as nativePresentHardwareBuffer.
    // 与 nativePresentHardwareBuffer 遵循相同的借用句柄生命周期规则。
    return engine->preparePresentHardwareBuffer(ahb, static_cast<uint64_t>(bufferId))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

/**
 * Submit non-null engine handles as one prepared Vulkan batch.
 * 将非空引擎句柄作为一个已准备的 Vulkan 批次提交。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSubmitBatch(JNIEnv *env, jobject, jlongArray handles) {
    if (!handles) return JNI_TRUE;
    jsize n = env->GetArrayLength(handles);
    if (n <= 0) return JNI_TRUE;
    if (n > 64) n = 64;
    jlong raw[64];
    env->GetLongArrayRegion(handles, 0, n, raw);
    vap_vk::Engine *engines[64];
    int m = 0;
    for (jsize i = 0; i < n; i++) {
        if (raw[i] != 0) engines[m++] = reinterpret_cast<vap_vk::Engine *>(raw[i]);
    }
    return vap_vk::Engine::submitPreparedBatch(engines, m) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Retire batch-backed frame slots without blocking on unfinished fences.
 * 在不等待未完成栅栏的前提下回收批次帧槽位。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeRetireSignaled(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    if (engine) engine->retireSignaledBatches();
}

/**
 * Report whether the native engine has a usable device and swapchain.
 * 报告原生引擎是否具有可用的设备与交换链。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeReady(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<vap_vk::Engine *>(handle);
    return (engine && engine->ready()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Run AHB diagnostics and return a report prefixed with its failure count.
 * 运行 AHB 诊断并返回以失败数开头的报告。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhb(JNIEnv *env, jobject, jstring videoPath) {
    std::vector<char> buf(16 * 1024);
    const char *path = videoPath ? env->GetStringUTFChars(videoPath, nullptr) : nullptr;
    const int fails = vap_ahb_probe::run(buf.data(), buf.size(), path ? path : "");
    if (path) env->ReleaseStringUTFChars(videoPath, path);
    // Prefix the failure count so adb can grep it without parsing the full report.
    // 将失败数置于首行，便于 adb 无需解析完整报告即可检索。
    char headed[16 * 1024 + 64];
    snprintf(headed, sizeof(headed), "fails=%d\n%s", fails, buf.data());
    return env->NewStringUTF(headed);
}

/**
 * Pin a Java HardwareBuffer wrapper and return its native pointer for B0 diagnostics.
 * The caller owns the acquired reference and must release it through nativeProbeAhbRelease;
 * pointer equality among held references denotes the same underlying buffer because a
 * referenced address cannot be recycled.
 * 为 B0 诊断固定 Java HardwareBuffer 包装并返回原生指针。调用方拥有所获取的引用，
 * 必须通过 nativeProbeAhbRelease 释放；持有期间地址不会复用，因此指针相等表示同一底层缓冲区。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhbAcquire(JNIEnv *env, jobject, jobject buffer) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (ahb) AHardwareBuffer_acquire(ahb);
    return reinterpret_cast<jlong>(ahb);
}

/**
 * Release a native AHB reference returned by nativeProbeAhbAcquire.
 * 释放 nativeProbeAhbAcquire 返回的原生 AHB 引用。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhbRelease(JNIEnv *, jobject, jlong ptr) {
    if (ptr) AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer *>(ptr));
}
