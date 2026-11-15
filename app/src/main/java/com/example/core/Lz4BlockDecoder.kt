package com.example.core

import java.io.ByteArrayOutputStream

/**
 * Raw LZ4 block decoder compatible with Nintendo Switch NSO0 compressed segments
 * (.text, .rodata, .data). Does not expect an LZ4 frame header.
 */
object Lz4BlockDecoder {

    fun decompress(
        src: ByteArray,
        expectedSize: Int,
        maxOutputBytes: Int = 44 * 1024 * 1024
    ): ByteArray {
        val targetCap = expectedSize.coerceIn(0, maxOutputBytes)
        if (targetCap == 0 || src.isEmpty()) return ByteArray(0)

        val dst = ByteArray(targetCap)
        var inPos = 0
        var outPos = 0

        while (inPos < src.size && outPos < targetCap) {
            val token = src[inPos++].toInt() and 0xFF

            // 1. Literal length
            var litLen = token ushr 4
            if (litLen == 15) {
                while (inPos < src.size) {
                    val extra = src[inPos++].toInt() and 0xFF
                    litLen += extra
                    if (extra != 255) break
                }
            }

            if (litLen > 0) {
                val safeLit = litLen.coerceAtMost(src.size - inPos).coerceAtMost(targetCap - outPos)
                if (safeLit > 0) {
                    System.arraycopy(src, inPos, dst, outPos, safeLit)
                    inPos += safeLit
                    outPos += safeLit
                }
            }

            if (inPos >= src.size || outPos >= targetCap) break

            // 2. Match offset (16-bit little endian)
            if (inPos + 2 > src.size) break
            val offset = (src[inPos++].toInt() and 0xFF) or
                ((src[inPos++].toInt() and 0xFF) shl 8)
            if (offset <= 0 || offset > outPos) break

            // 3. Match length
            var matchLen = (token and 0x0F) + 4
            if ((token and 0x0F) == 15) {
                while (inPos < src.size) {
                    val extra = src[inPos++].toInt() and 0xFF
                    matchLen += extra
                    if (extra != 255) break
                }
            }

            val safeMatch = matchLen.coerceAtMost(targetCap - outPos)
            for (k in 0 until safeMatch) {
                dst[outPos] = dst[outPos - offset]
                outPos++
            }
        }

        return if (outPos == dst.size) dst else dst.copyOf(outPos)
    }

    fun compressSimple(src: ByteArray): ByteArray {
        // Minimal valid raw LZ4 block encoder (literals-only chunks) for unit tests.
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < src.size) {
            val chunk = minOf(src.size - pos, 255)
            if (chunk < 15) {
                out.write(chunk shl 4)
            } else {
                out.write(15 shl 4)
                var rem = chunk - 15
                while (rem >= 255) {
                    out.write(255)
                    rem -= 255
                }
                out.write(rem)
            }
            out.write(src, pos, chunk)
            pos += chunk
        }
        return out.toByteArray()
    }
}
