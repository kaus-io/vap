package com.zxhhyj.vap.encode

import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.mp4.Mp4Sources

internal object Mp4BoxInserter {
    fun insert(inputMp4: ByteArray, atom: ByteArray, position: Int): ByteArray {
        require(atom.size >= 8) { "atom too small" }
        require(position >= 0) { "position must be >= 0" }
        val boxes = Mp4BoxParser.parseTopLevel(Mp4Sources.bytes(inputMp4), strict = true)
        boxes.forEach { box ->
            require(box.offset <= Int.MAX_VALUE && box.size <= Int.MAX_VALUE) {
                "box larger than 2GiB at ${box.offset}"
            }
        }
        val at = position.coerceIn(0, boxes.size)
        val outSize = inputMp4.size + atom.size
        val out = ByteArray(outSize)
        var dst = 0
        for (i in 0..boxes.size) {
            if (i == at) {
                atom.copyInto(out, dst)
                dst += atom.size
            }
            if (i < boxes.size) {
                val box = boxes[i]
                val start = box.offset.toInt()
                val end = (box.offset + box.size).toInt()
                inputMp4.copyInto(out, dst, start, end)
                dst += box.size.toInt()
            }
        }
        check(dst == outSize) { "mp4 rewrite size mismatch: $dst != $outSize" }
        return out
    }

    fun insertFile(inputPath: String, atomPath: String, outputPath: String, position: Int = 3) {
        val input = PlatformFs.readBytes(inputPath)
        val atom = PlatformFs.readBytes(atomPath)
        PlatformFs.writeBytes(outputPath, insert(input, atom, position))
    }
}
