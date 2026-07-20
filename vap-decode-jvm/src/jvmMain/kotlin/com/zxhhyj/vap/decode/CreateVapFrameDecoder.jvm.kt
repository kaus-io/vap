package com.zxhhyj.vap.decode

/**
 * Creates the JVM decoder implementation without global registration.
 *
 * 直接创建 JVM 解码器实现，无需全局注册。
 */
public fun createJvmVapFrameDecoder(): VapFrameDecoder = JvmVapFrameDecoder()
