package com.zxhhyj.vap.encode

import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.vapc.toVapcRoot
import kotlinx.serialization.json.Json

internal object VapcJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun build(config: VapConfig): String =
        json.encodeToString(config.toVapcRoot())

    fun toBin(jsonUtf8: ByteArray): ByteArray {
        val total = 8 + jsonUtf8.size
        val out = ByteArray(total)
        out[0] = (total ushr 24 and 0xff).toByte()
        out[1] = (total ushr 16 and 0xff).toByte()
        out[2] = (total ushr 8 and 0xff).toByte()
        out[3] = (total and 0xff).toByte()
        out[4] = 'v'.code.toByte()
        out[5] = 'a'.code.toByte()
        out[6] = 'p'.code.toByte()
        out[7] = 'c'.code.toByte()
        jsonUtf8.copyInto(out, 8)
        return out
    }
}
