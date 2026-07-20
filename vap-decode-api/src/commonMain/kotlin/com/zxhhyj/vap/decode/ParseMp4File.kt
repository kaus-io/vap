package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapConfig

/**
 * Parses VAP metadata from the MP4 at [path] using the current platform's file I/O.
 *
 * 使用当前平台的文件 I/O，从 [path] 指向的 MP4 中解析 VAP 元数据。
 */
public expect fun parseMp4File(path: String): VapConfig
