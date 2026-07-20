package com.zxhhyj.vap.decode

/**
 * Creates the Android decoder implementation without global registration.
 *
 * 直接创建 Android 解码器实现，无需全局注册。
 */
public fun createAndroidVapFrameDecoder(): VapSurfaceFrameDecoder = AndroidVapFrameDecoder()
