package com.zxhhyj.vap.demo

// Android supports the VapGpu Surface backend (MediaCodec + Surface compose path).
// Android 平台支持 VapGpu Surface 后端(MediaCodec + Surface 合成路径)。
internal actual val demoSurfaceBackendAvailable: Boolean = true

// Android ships libpag and exposes both PAGView + PAGImageView for the present benches.
// Android 平台内置 libpag,基准可同时使用 PAGView 与 PAGImageView。
internal actual val demoPagAvailable: Boolean = true
