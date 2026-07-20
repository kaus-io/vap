package com.zxhhyj.vap.mp4

/**
 * Random-access byte source used by the MP4 box parser.
 *
 * MP4 盒解析器所用的随机访问字节源。
 *
 * `size()` is the total number of bytes exposed; `readAt` must copy exactly
 * `length` bytes starting at `position` into the destination buffer. The
 * implementation is expected to be safe to call from any thread as long as the
 * underlying storage is not mutated concurrently.
 */
public interface Mp4Source {
    public fun size(): Long
    public fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

/**
 * A single ISO BMFF (MP4) box, including its header layout.
 *
 * 单个 ISO BMFF (MP4) 盒，包含其头部的布局信息。
 *
 * `size` includes the header bytes. When `extendsToEof` is true the box spans to
 * the end of the container (size field was 0); `payloadSize` is clamped to 0
 * in that case to avoid negative offsets.
 */
public data class Mp4Box(
    val offset: Long,
    val size: Long,
    val type: String,
    val headerSize: Int,
    val extendsToEof: Boolean = false,
) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = size - headerSize
}

public object Mp4Sources {
    public fun bytes(data: ByteArray): Mp4Source = ByteArrayMp4Source(data)
}

/**
 * Minimal ISO BMFF box scanner that walks top-level boxes.
 *
 * 极简 ISO BMFF 盒扫描器，仅遍历顶层盒。
 *
 * Supports 32-bit and 64-bit (`size==1`) headers and the `size==0` "to EOF"
 * convention. The parser is intentionally stateless beyond the input source so
 * the same instance can be reused across reads.
 */
public object Mp4BoxParser {
    public fun parseTopLevel(source: Mp4Source, strict: Boolean = true): List<Mp4Box> {
        val fileSize = source.size()
        val boxes = ArrayList<Mp4Box>()
        if (fileSize == 0L) return boxes
        if (fileSize < 8L) {
            if (strict) error("mp4 too small: $fileSize")
            return boxes
        }
        var offset = 0L
        val header = ByteArray(16)
        while (offset + 8 <= fileSize) {
            val box = readBoxAt(source, offset, fileSize, header, strict) ?: break
            boxes += box
            offset = box.offset + box.size
            if (box.extendsToEof || offset >= fileSize) break
        }
        if (strict && offset != fileSize) {
            error("trailing bytes after last box: $offset/$fileSize")
        }
        return boxes
    }

    public fun findPayload(source: Mp4Source, type: String): ByteArray? {
        for (box in parseTopLevel(source, strict = false)) {
            if (box.type != type) continue
            val payloadSize = box.payloadSize
            if (payloadSize < 0L || payloadSize > Int.MAX_VALUE) return null
            val payload = ByteArray(payloadSize.toInt())
            if (payload.isNotEmpty()) {
                source.readAt(box.payloadOffset, payload, 0, payload.size)
            }
            return payload
        }
        return null
    }

    // Decodes one box header starting at `offset`; returns null on a clean EOF
    // only when `strict == false`, otherwise raises on malformed headers.
    private fun readBoxAt(
        source: Mp4Source,
        offset: Long,
        fileSize: Long,
        header: ByteArray,
        strict: Boolean,
    ): Mp4Box? {
        source.readAt(offset, header, 0, 8)
        val size32 = readU32(header, 0)
        val type = header.decodeToString(4, 8)
        val headerSize: Int
        val boxSize: Long
        val extendsToEof: Boolean
        when {
            size32 == 1L -> {
                if (offset + 16 > fileSize) {
                    if (strict) error("truncated 64-bit box at $offset")
                    return null
                }
                source.readAt(offset + 8, header, 8, 8)
                val size64 = readU64(header, 8)
                if (size64 < 16L) {
                    if (strict) error("invalid 64-bit box size $size64 at $offset")
                    return null
                }
                headerSize = 16
                boxSize = size64
                extendsToEof = false
            }

            size32 == 0L -> {
                headerSize = 8
                boxSize = fileSize - offset
                extendsToEof = true
            }

            else -> {
                if (size32 < 8L) {
                    if (strict) error("invalid box size $size32 at $offset")
                    return null
                }
                headerSize = 8
                boxSize = size32
                extendsToEof = false
            }
        }
        if (offset + boxSize > fileSize) {
            if (strict) {
                error("box overflows file: type=$type offset=$offset size=$boxSize fileSize=$fileSize")
            }
            return null
        }
        return Mp4Box(
            offset = offset,
            size = boxSize,
            type = type,
            headerSize = headerSize,
            extendsToEof = extendsToEof,
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toInt() and 0xff).toLong() shl 24) or
                ((bytes[offset + 1].toInt() and 0xff).toLong() shl 16) or
                ((bytes[offset + 2].toInt() and 0xff).toLong() shl 8) or
                (bytes[offset + 3].toInt() and 0xff).toLong()
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toInt() and 0xff).toLong()
        }
        return v
    }
}

private class ByteArrayMp4Source(private val bytes: ByteArray) : Mp4Source {
    override fun size(): Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        require(position >= 0 && length >= 0)
        require(position + length <= bytes.size)
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
    }
}
