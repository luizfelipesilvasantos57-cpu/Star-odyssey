package com.example.core

import com.example.model.SarcArchiveReport
import com.example.model.SarcEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin Nintendo Yaz0 (.szs) decompressor and SARC (SFAT/SFNT) archive parser.
 */
object Yaz0SarcParser {

    fun isYaz0(data: ByteArray): Boolean =
        data.size >= 16 &&
            data[0] == 'Y'.code.toByte() &&
            data[1] == 'a'.code.toByte() &&
            data[2] == 'z'.code.toByte() &&
            data[3] == '0'.code.toByte()

    fun decompressYaz0(src: ByteArray, maxOutputBytes: Int = 32 * 1024 * 1024): ByteArray {
        if (!isYaz0(src)) return src
        val uncompressedSize = ByteBuffer.wrap(src, 4, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .coerceIn(0, maxOutputBytes)
        if (uncompressedSize == 0) return ByteArray(0)

        val dst = ByteArray(uncompressedSize)
        var inPos = 16
        var outPos = 0
        var mask = 0
        var headerByte = 0

        while (outPos < uncompressedSize && inPos < src.size) {
            if (mask == 0) {
                headerByte = src[inPos++].toInt() and 0xFF
                mask = 0x80
            }

            if ((headerByte and mask) != 0) {
                if (inPos >= src.size) break
                dst[outPos++] = src[inPos++]
            } else {
                if (inPos + 2 > src.size) break
                val b1 = src[inPos++].toInt() and 0xFF
                val b2 = src[inPos++].toInt() and 0xFF
                val dist = (((b1 and 0x0F) shl 8) or b2) + 1
                var count = b1 ushr 4
                if (count == 0) {
                    if (inPos >= src.size) break
                    count = (src[inPos++].toInt() and 0xFF) + 0x12
                } else {
                    count += 2
                }
                if (dist > outPos) break
                val safeCount = count.coerceAtMost(uncompressedSize - outPos)
                for (i in 0 until safeCount) {
                    dst[outPos] = dst[outPos - dist]
                    outPos++
                }
            }
            mask = mask ushr 1
        }

        return if (outPos == dst.size) dst else dst.copyOf(outPos)
    }

    fun inspectArchive(rawBytes: ByteArray, fileName: String): Pair<SarcArchiveReport, ByteArray> {
        val wasYaz0 = isYaz0(rawBytes)
        val payload = if (wasYaz0) decompressYaz0(rawBytes) else rawBytes

        if (payload.size < 0x20 ||
            payload[0] != 'S'.code.toByte() ||
            payload[1] != 'A'.code.toByte() ||
            payload[2] != 'R'.code.toByte() ||
            payload[3] != 'C'.code.toByte()
        ) {
            return SarcArchiveReport(
                fileName = fileName,
                wasYaz0 = wasYaz0,
                decompressedBytes = payload.size,
                entries = listOf(
                    SarcEntry(
                        name = fileName.removeSuffix(".szs"),
                        offset = 0,
                        sizeBytes = payload.size,
                        formatTag = detectMagicTag(payload, 0, payload.size)
                    )
                )
            ) to payload
        }

        val bom = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val order = if (bom == 0xFEFF) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val bb = ByteBuffer.wrap(payload).order(order)
        val headerSize = bb.getShort(4).toInt() and 0xFFFF
        val dataOffset = bb.getInt(0x0C)

        val sfatPos = headerSize
        if (sfatPos + 12 > payload.size ||
            payload[sfatPos] != 'S'.code.toByte() ||
            payload[sfatPos + 1] != 'F'.code.toByte() ||
            payload[sfatPos + 2] != 'A'.code.toByte() ||
            payload[sfatPos + 3] != 'T'.code.toByte()
        ) {
            return SarcArchiveReport(fileName, wasYaz0, payload.size, emptyList()) to payload
        }

        val sfatHeaderSize = bb.getShort(sfatPos + 4).toInt() and 0xFFFF
        val nodeCount = bb.getShort(sfatPos + 6).toInt() and 0xFFFF
        val nodesStart = sfatPos + sfatHeaderSize
        val sfntPos = nodesStart + nodeCount * 16

        val hasSfnt = sfntPos + 8 <= payload.size &&
            payload[sfntPos] == 'S'.code.toByte() &&
            payload[sfntPos + 1] == 'F'.code.toByte() &&
            payload[sfntPos + 2] == 'N'.code.toByte() &&
            payload[sfntPos + 3] == 'T'.code.toByte()
        val sfntHeaderSize = if (hasSfnt) (bb.getShort(sfntPos + 4).toInt() and 0xFFFF) else 8
        val namesBase = sfntPos + sfntHeaderSize

        val entries = ArrayList<SarcEntry>(minOf(nodeCount, 2000))
        for (i in 0 until minOf(nodeCount, 2000)) {
            val nodeBase = nodesStart + i * 16
            if (nodeBase + 16 > payload.size) break
            val fileAttrs = bb.getInt(nodeBase + 4)
            val startOff = bb.getInt(nodeBase + 8)
            val endOff = bb.getInt(nodeBase + 12)
            val absStart = (dataOffset + startOff).coerceIn(0, payload.size)
            val absEnd = (dataOffset + endOff).coerceIn(absStart, payload.size)
            val size = absEnd - absStart

            val name = if (hasSfnt && (fileAttrs and 0x01000000) != 0) {
                val relWordOff = fileAttrs and 0xFFFF
                val strPos = namesBase + relWordOff * 4
                readNullTerminatedString(payload, strPos) ?: "entry_$i"
            } else {
                "hash_${"%08X".format(bb.getInt(nodeBase))}"
            }

            entries.add(
                SarcEntry(
                    name = name,
                    offset = absStart,
                    sizeBytes = size,
                    formatTag = detectMagicTag(payload, absStart, size)
                )
            )
        }

        return SarcArchiveReport(
            fileName = fileName,
            wasYaz0 = wasYaz0,
            decompressedBytes = payload.size,
            entries = entries
        ) to payload
    }

    private fun readNullTerminatedString(buf: ByteArray, start: Int): String? {
        if (start !in buf.indices) return null
        var end = start
        while (end < buf.size && buf[end] != 0.toByte() && (end - start) < 260) {
            end++
        }
        return String(buf, start, end - start, Charsets.UTF_8).ifBlank { null }
    }

    private fun detectMagicTag(buf: ByteArray, offset: Int, size: Int): String {
        if (size < 4 || offset + 4 > buf.size) return "BIN"
        val b0 = buf[offset]
        val b1 = buf[offset + 1]
        val b2 = buf[offset + 2]
        val b3 = buf[offset + 3]
        return when {
            (b0 == 'Y'.code.toByte() && b1 == 'B'.code.toByte()) ||
                (b0 == 'B'.code.toByte() && b1 == 'Y'.code.toByte()) -> "BYML"
            b0 == 'F'.code.toByte() && b1 == 'R'.code.toByte() && b2 == 'E'.code.toByte() && b3 == 'S'.code.toByte() -> "BFRES"
            b0 == 'B'.code.toByte() && b1 == 'N'.code.toByte() && b2 == 'T'.code.toByte() && b3 == 'X'.code.toByte() -> "BNTX"
            b0 == 'F'.code.toByte() && b1 == 'S'.code.toByte() && b2 == 'T'.code.toByte() && b3 == 'M'.code.toByte() -> "BFSTM"
            b0 == 'P'.code.toByte() && b1 == 'T'.code.toByte() && b2 == 'C'.code.toByte() && b3 == 'L'.code.toByte() -> "PTCL"
            else -> "BIN"
        }
    }
}
