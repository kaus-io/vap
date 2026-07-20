package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.mp4.Mp4Source
import com.zxhhyj.vap.mp4.Mp4Sources
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vapc.VapcRoot
import com.zxhhyj.vap.vapc.toVapConfig
import kotlinx.serialization.json.Json

/**
 * Parses the `vapc` JSON manifest embedded in VAP MP4 files into a `VapConfig`.
 *
 * 将嵌入在 VAP MP4 文件中的 `vapc` JSON 清单解析为 `VapConfig`。
 *
 * The JSON parser tolerates unknown keys so future schema additions do not break
 * older readers; structural validation (rect arity, schema version) is deferred
 * to the `toVapConfig` mapping.
 */
public object VapcParser {
    private val json = Json { ignoreUnknownKeys = true }

    public fun parseMp4(bytes: ByteArray): VapConfig = parseMp4(Mp4Sources.bytes(bytes))

    public fun parseMp4(source: Mp4Source): VapConfig {
        val vapc = findVapcPayload(source) ?: error("vapc box not found in mp4")
        return parseVapcJson(vapc.decodeToString())
    }

    public fun parseVapcJson(jsonText: String): VapConfig =
        json.decodeFromString<VapcRoot>(jsonText.trim()).toVapConfig()

    // Locate the top-level `vapc` box; returns the raw payload bytes (UTF-8 JSON).
    // 定位顶层 `vapc` 盒，返回原始 payload 字节（UTF-8 编码的 JSON）。
    public fun findVapcPayload(bytes: ByteArray): ByteArray? =
        Mp4BoxParser.findPayload(Mp4Sources.bytes(bytes), "vapc")

    public fun findVapcPayload(source: Mp4Source): ByteArray? =
        Mp4BoxParser.findPayload(source, "vapc")
}
