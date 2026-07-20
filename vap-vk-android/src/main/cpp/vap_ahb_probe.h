#pragma once

#include <cstddef>

namespace vap_ahb_probe {

/**
 * Run AHardwareBuffer smoke tests, including Vulkan import and optional media round trips.
 * 运行 AHardwareBuffer 冒烟测试，包括 Vulkan 导入及可选的媒体往返测试。
 * @param out Destination for the NUL-terminated, multi-line report.
 *            用于接收 NUL 结尾多行报告的缓冲区。
 * @param outCap Capacity of out in bytes.
 *               out 的字节容量。
 * @param videoPath MP4 path for ImageReader/MediaCodec identity tests; nullptr or empty skips them.
 *                  ImageReader/MediaCodec 身份测试所用的 MP4 路径；nullptr 或空串表示跳过。
 * @return Number of failed checks.
 *         失败检查项数量。
 */
int run(char *out, size_t outCap, const char *videoPath);

} // namespace vap_ahb_probe
