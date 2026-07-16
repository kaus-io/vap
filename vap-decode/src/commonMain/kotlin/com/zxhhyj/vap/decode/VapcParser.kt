package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.mp4.Mp4Source
import com.zxhhyj.vap.mp4.Mp4Sources
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vapc.VapcRoot
import com.zxhhyj.vap.vapc.toVapConfig
import kotlinx.serialization.json.Json

public object VapcParser {
    private val json = Json { ignoreUnknownKeys = true }

    public fun parseMp4(bytes: ByteArray): VapConfig = parseMp4(Mp4Sources.bytes(bytes))

    public fun parseMp4(source: Mp4Source): VapConfig {
        val vapc = findVapcPayload(source) ?: error("vapc box not found in mp4")
        return parseVapcJson(vapc.decodeToString())
    }

    public fun parseVapcJson(jsonText: String): VapConfig =
        json.decodeFromString<VapcRoot>(jsonText.trim()).toVapConfig()

    public fun findVapcPayload(bytes: ByteArray): ByteArray? =
        Mp4BoxParser.findPayload(Mp4Sources.bytes(bytes), "vapc")

    public fun findVapcPayload(source: Mp4Source): ByteArray? =
        Mp4BoxParser.findPayload(source, "vapc")
}
