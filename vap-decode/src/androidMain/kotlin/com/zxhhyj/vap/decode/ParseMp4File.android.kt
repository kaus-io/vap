package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4Source
import com.zxhhyj.vap.player.VapConfig
import java.io.RandomAccessFile

public actual fun parseMp4File(path: String): VapConfig {
    RandomAccessFile(path, "r").use { raf ->
        val source = object : Mp4Source {
            override fun size(): Long = raf.length()

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                raf.seek(position)
                raf.readFully(buffer, offset, length)
            }
        }
        return VapcParser.parseMp4(source)
    }
}
