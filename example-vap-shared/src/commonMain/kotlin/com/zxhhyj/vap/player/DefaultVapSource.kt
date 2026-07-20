package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

/**
 * Resolves platform-packaged default media; `null` means no usable demo asset is available.
 *
 * 解析平台打包的默认媒体；`null` 表示当前没有可用的演示资源。
 */
@Composable
expect fun rememberDefaultVapSource(): VapSource?

/**
 * Returns platform demo clips whose labels satisfy the common Demo case lookup contract.
 *
 * 返回标签符合公共 Demo case 查找约定的平台演示片段。
 */
@Composable
expect fun rememberDemoVapSources(): List<VapDemoClip>
