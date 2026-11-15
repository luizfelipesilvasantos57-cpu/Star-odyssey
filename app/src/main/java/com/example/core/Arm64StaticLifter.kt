package com.example.core

import com.example.model.LiftedFunctionMeta
import com.example.model.LiftedInstructionLine
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin Switch NSO0 decompressor, ARM64 (AArch64) static decoder,
 * function boundary analyzer, and C++ / CMake NDK project emitter.
 */
object Arm64StaticLifter {

    private const val MAX_TEXT_BYTES = 40 * 1024 * 1024
    private const val MAX_RODATA_BYTES = 16 * 1024 * 1024

    data class DecodedOp(
        val asm: String,
        val cpp: String,
        val isDecoded: Boolean,
        val blTarget: Int? = null,
        val svcNumber: Int? = null
    )

    data class LoadedNsoSession(
        val fileName: String,
        val buildIdHex: String,
        val textMemOffset: Int,
        val roMemOffset: Int,
        val textCompressedBytes: Int,
        val textBytes: ByteArray,
        val rodataBytes: ByteArray,
        val totalInstructions: Int,
        val decodedInstructions: Int,
        val svcCount: Int,
        val functions: List<LiftedFunctionMeta>,
        val rodataStrings: List<String>
    )

    fun loadAndAnalyzeNso0(stream: InputStream, fileName: String): LoadedNsoSession {
        val header = ByteArray(0x100)
        val headerRead = readExactUpTo(stream, header, 0, header.size)
        if (headerRead < 0x100 ||
            header[0] != 'N'.code.toByte() ||
            header[1] != 'S'.code.toByte() ||
            header[2] != 'O'.code.toByte() ||
            header[3] != '0'.code.toByte()
        ) {
            throw IllegalArgumentException("O arquivo selecionado não possui cabeçalho NSO0 válido.")
        }

        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getInt(0x0C)
        val textFileOffset = bb.getInt(0x10).coerceAtLeast(0x100)
        val textMemOffset = bb.getInt(0x14).coerceAtLeast(0)
        val textSize = bb.getInt(0x18).coerceIn(0, MAX_TEXT_BYTES)
        val roFileOffset = bb.getInt(0x20).coerceAtLeast(textFileOffset)
        val roMemOffset = bb.getInt(0x24).coerceAtLeast(textMemOffset)
        val roSize = bb.getInt(0x28).coerceIn(0, MAX_RODATA_BYTES)

        val buildIdHex = header.copyOfRange(0x40, 0x60).joinToString("") { "%02x".format(it) }
        val textCompressedSize = bb.getInt(0x60).let { if (it > 0) it else textSize }
        val roCompressedSize = bb.getInt(0x64).let { if (it > 0) it else roSize }

        val textLz4 = (flags and 0x01) != 0
        val roLz4 = (flags and 0x02) != 0

        var streamPos = headerRead.toLong()
        if (textFileOffset > streamPos) {
            skipFully(stream, textFileOffset - streamPos)
            streamPos = textFileOffset.toLong()
        }

        val textReadLen = (if (textLz4) textCompressedSize else textSize).coerceIn(0, MAX_TEXT_BYTES)
        val rawText = ByteArray(textReadLen)
        val actualTextRead = readExactUpTo(stream, rawText, 0, textReadLen)
        streamPos += actualTextRead

        val textBytes = if (textLz4) {
            Lz4BlockDecoder.decompress(rawText.copyOf(actualTextRead), textSize, MAX_TEXT_BYTES)
        } else {
            rawText.copyOf(actualTextRead)
        }

        val roBytes = if (roSize > 0 && roFileOffset >= streamPos) {
            if (roFileOffset > streamPos) {
                skipFully(stream, roFileOffset - streamPos)
            }
            val roReadLen = (if (roLz4) roCompressedSize else roSize).coerceIn(0, MAX_RODATA_BYTES)
            val rawRo = ByteArray(roReadLen)
            val actualRoRead = readExactUpTo(stream, rawRo, 0, roReadLen)
            if (roLz4) {
                Lz4BlockDecoder.decompress(rawRo.copyOf(actualRoRead), roSize, MAX_RODATA_BYTES)
            } else {
                rawRo.copyOf(actualRoRead)
            }
        } else {
            ByteArray(0)
        }

        val totalInsns = textBytes.size / 4
        var decodedCount = 0
        var svcCount = 0
        val entryPoints = TreeSet<Int>()
        if (totalInsns > 0) entryPoints.add(0)
        val svcByInsnOffset = HashMap<Int, Int>()

        val wordBuf = ByteBuffer.wrap(textBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (idx in 0 until totalInsns) {
            val offset = idx * 4
            val insn = wordBuf.getInt(offset)

            // Detect AArch64 BL target as function entry
            if (((insn ushr 26) and 0x3F) == 0x25) {
                val imm26 = signExtend(insn and 0x03FFFFFF, 26) shl 2
                val target = offset + imm26
                if (target in 0 until (totalInsns * 4) && (target and 3) == 0) {
                    entryPoints.add(target)
                }
            }
            // Detect STP X29, X30, [SP, #imm]! prologue
            if ((insn and 0xFFE07FFF.toInt()) == 0xA9807BFD.toInt() ||
                (insn and 0xFFE07FFF.toInt()) == 0xA9007BFD.toInt()
            ) {
                entryPoints.add(offset)
            }

            // Detect SVC #imm16
            if ((insn and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) {
                svcCount++
                svcByInsnOffset[offset] = (insn ushr 5) and 0xFFFF
            }

            if (isRecognizedOpcode(insn)) decodedCount++
        }

        val sortedEntries = entryPoints.toList()
        val functions = ArrayList<LiftedFunctionMeta>(minOf(sortedEntries.size, 8000))
        val textLimit = totalInsns * 4

        for (i in sortedEntries.indices) {
            val start = sortedEntries[i]
            val nextEntry = if (i + 1 < sortedEntries.size) sortedEntries[i + 1] else textLimit
            val boundedEnd = minOf(nextEntry, start + 4096).coerceAtMost(textLimit)
            val insnCount = ((boundedEnd - start) / 4).coerceAtLeast(1)
            val svcs = svcByInsnOffset.entries
                .filter { it.key in start until boundedEnd }
                .map { it.value }
                .distinct()

            functions.add(
                LiftedFunctionMeta(
                    name = "sub_%08x".format(start),
                    startOffset = start,
                    endOffset = boundedEnd,
                    insnCount = insnCount,
                    svcCalls = svcs
                )
            )
            if (functions.size >= 8000) break
        }

        val strings = extractRodataStrings(roBytes)

        return LoadedNsoSession(
            fileName = fileName,
            buildIdHex = buildIdHex,
            textMemOffset = textMemOffset,
            roMemOffset = roMemOffset,
            textCompressedBytes = actualTextRead,
            textBytes = textBytes,
            rodataBytes = roBytes,
            totalInstructions = totalInsns,
            decodedInstructions = decodedCount,
            svcCount = svcCount,
            functions = functions,
            rodataStrings = strings
        )
    }

    fun liftFunctionPreview(
        textBytes: ByteArray,
        fn: LiftedFunctionMeta,
        maxInstructions: Int = 180
    ): List<LiftedInstructionLine> {
        val result = ArrayList<LiftedInstructionLine>()
        if (fn.startOffset < 0 || fn.startOffset >= textBytes.size) return result

        val bb = ByteBuffer.wrap(textBytes).order(ByteOrder.LITTLE_ENDIAN)
        var off = fn.startOffset
        var count = 0
        while (off + 4 <= fn.endOffset && off + 4 <= textBytes.size && count < maxInstructions) {
            val insn = bb.getInt(off)
            val op = decodeArm64Instruction(insn, off)
            result.add(
                LiftedInstructionLine(
                    offsetHex = "0x%08X".format(off),
                    rawHex = "%08X".format(insn),
                    asm = op.asm,
                    cpp = op.cpp
                )
            )
            off += 4
            count++
        }
        return result
    }

    fun exportRecompiledProjectZip(
        session: LoadedNsoSession,
        titleId: String?,
        programName: String?,
        outStream: OutputStream,
        maxFunctions: Int = 2500
    ) {
        val emittedFunctions = session.functions.take(maxFunctions)
        val bb = ByteBuffer.wrap(session.textBytes).order(ByteOrder.LITTLE_ENDIAN)

        ZipOutputStream(outStream).use { zos ->
            // 1. CMakeLists.txt
            zos.putNextEntry(ZipEntry("CMakeLists.txt"))
            zos.write(buildCMakeLists(programName ?: "StarOdysseyRecomp").toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. include/recomp_runtime.h
            zos.putNextEntry(ZipEntry("include/recomp_runtime.h"))
            zos.write(buildRuntimeHeader(emittedFunctions).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. src/recomp_runtime_stubs.cpp
            zos.putNextEntry(ZipEntry("src/recomp_runtime_stubs.cpp"))
            zos.write(buildRuntimeStubsCpp(session, titleId, programName).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. src/recomp_functions.cpp (streamed line-by-line to stay low-memory)
            zos.putNextEntry(ZipEntry("src/recomp_functions.cpp"))
            val writer = BufferedWriter(OutputStreamWriter(zos, Charsets.UTF_8))
            writer.write("#include \"recomp_runtime.h\"\n\n")
            for (fn in emittedFunctions) {
                writer.write("void ${fn.name}(RecompContext* ctx) {\n")
                var off = fn.startOffset
                while (off + 4 <= fn.endOffset && off + 4 <= session.textBytes.size) {
                    val insn = bb.getInt(off)
                    val op = decodeArm64Instruction(insn, off)
                    writer.write("    ${op.cpp} // 0x%08X: ${op.asm}\n".format(off))
                    off += 4
                }
                writer.write("}\n\n")
            }
            writer.flush()
            zos.closeEntry()

            // 5. symbols/rodata_strings.txt
            zos.putNextEntry(ZipEntry("symbols/rodata_strings.txt"))
            val strWriter = BufferedWriter(OutputStreamWriter(zos, Charsets.UTF_8))
            session.rodataStrings.forEach { s ->
                strWriter.write(s)
                strWriter.newLine()
            }
            strWriter.flush()
            zos.closeEntry()

            // 6. recomp_manifest.json
            zos.putNextEntry(ZipEntry("recomp_manifest.json"))
            val manifest = JSONObject().apply {
                put("source", session.fileName)
                put("programName", programName ?: "")
                put("titleId", titleId ?: "")
                put("buildId", session.buildIdHex)
                put("textBytes", session.textBytes.size)
                put("rodataBytes", session.rodataBytes.size)
                put("totalInstructions", session.totalInstructions)
                put("decodedInstructions", session.decodedInstructions)
                put("discoveredFunctions", session.functions.size)
                put("emittedCppFunctions", emittedFunctions.size)
                put("svcCount", session.svcCount)
            }.toString(2)
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    fun decodeArm64Instruction(insn: Int, pcOffset: Int): DecodedOp {
        // NOP
        if (insn == 0xD503201F.toInt()) {
            return DecodedOp("NOP", "/* nop */", true)
        }

        // RET (Xn)
        if ((insn and 0xFFFFFC1F.toInt()) == 0xD65F0000.toInt()) {
            val rn = (insn ushr 5) and 0x1F
            return if (rn == 30) {
                DecodedOp("RET", "return;", true)
            } else {
                DecodedOp("RET X$rn", "ctx->pc = ctx->x[$rn]; return;", true)
            }
        }

        // SVC #imm16
        if ((insn and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) {
            val imm16 = (insn ushr 5) and 0xFFFF
            return DecodedOp("SVC #0x${imm16.toString(16)}", "horizon_svc(ctx, 0x${imm16.toString(16)});", true, svcNumber = imm16)
        }

        // B / BL imm26
        val top6 = (insn ushr 26) and 0x3F
        if (top6 == 0x05 || top6 == 0x25) {
            val imm26 = signExtend(insn and 0x03FFFFFF, 26) shl 2
            val target = pcOffset + imm26
            val targetHex = "%08x".format(target)
            return if (top6 == 0x25) {
                DecodedOp("BL sub_$targetHex", "sub_$targetHex(ctx);", true, blTarget = target)
            } else {
                DecodedOp("B 0x$targetHex", "/* branch 0x$targetHex */ return;", true)
            }
        }

        // B.cond imm19
        if ((insn and 0xFF000010.toInt()) == 0x54000000) {
            val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
            val target = pcOffset + imm19
            val cond = COND_CODES[insn and 0xF]
            return DecodedOp(
                "B.$cond 0x${"%08x".format(target)}",
                "if (cond_$cond(ctx)) /* -> 0x${"%08x".format(target)} */ ;",
                true
            )
        }

        // CBZ / CBNZ
        if ((insn and 0x7E000000) == 0x34000000) {
            val sf = (insn ushr 31) and 1
            val isNz = ((insn ushr 24) and 1) == 1
            val rt = insn and 0x1F
            val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
            val target = pcOffset + imm19
            val opName = if (isNz) "CBNZ" else "CBZ"
            val reg = regName(rt, sf == 1)
            val cmp = if (isNz) "!= 0" else "== 0"
            val mask = if (sf == 1) "" else " & 0xFFFFFFFFULL"
            return DecodedOp(
                "$opName $reg, 0x${"%08x".format(target)}",
                "if ((${regExpr(rt)}$mask) $cmp) /* -> 0x${"%08x".format(target)} */ ;",
                true
            )
        }

        // ADRP / ADR
        if ((insn and 0x1F000000) == 0x10000000) {
            val isAdrp = ((insn ushr 31) and 1) == 1
            val rd = insn and 0x1F
            val immlo = (insn ushr 29) and 0x3
            val immhi = (insn ushr 5) and 0x7FFFF
            val imm = signExtend((immhi shl 2) or immlo, 21).toLong()
            val target = if (isAdrp) {
                (pcOffset.toLong() and -4096L) + (imm shl 12)
            } else {
                pcOffset.toLong() + imm
            }
            val op = if (isAdrp) "ADRP" else "ADR"
            return DecodedOp(
                "$op X$rd, 0x${target.toString(16)}",
                "${assignReg(rd, "0x${target.toString(16)}ULL")}",
                true
            )
        }

        // MOVZ / MOVK / MOVN
        if ((insn and 0x1F800000) == 0x12800000) {
            val sf = (insn ushr 31) and 1
            val opc = (insn ushr 29) and 3
            val hw = (insn ushr 21) and 3
            val imm16 = ((insn ushr 5) and 0xFFFF).toLong()
            val rd = insn and 0x1F
            val shift = hw * 16
            val reg = regName(rd, sf == 1)
            return when (opc) {
                2 -> {
                    val value = imm16 shl shift
                    DecodedOp(
                        "MOVZ $reg, #0x${imm16.toString(16)}${if (shift > 0) ", LSL #$shift" else ""}",
                        assignReg(rd, "0x${value.toString(16)}ULL"),
                        true
                    )
                }
                3 -> {
                    val clearMask = (0xFFFFL shl shift).inv()
                    val shifted = imm16 shl shift
                    DecodedOp(
                        "MOVK $reg, #0x${imm16.toString(16)}, LSL #$shift",
                        "${regExpr(rd)} = (${regExpr(rd)} & 0x${clearMask.toULong().toString(16)}ULL) | 0x${shifted.toULong().toString(16)}ULL;",
                        true
                    )
                }
                else -> {
                    val value = (imm16 shl shift).inv()
                    DecodedOp(
                        "MOVN $reg, #0x${imm16.toString(16)}",
                        assignReg(rd, "0x${value.toULong().toString(16)}ULL"),
                        true
                    )
                }
            }
        }

        // ADD / SUB immediate
        if ((insn and 0x1F000000) == 0x11000000) {
            val sf = (insn ushr 31) and 1
            val isSub = ((insn ushr 30) and 1) == 1
            val sh = (insn ushr 22) and 1
            val imm12 = ((insn ushr 10) and 0xFFF) shl (if (sh == 1) 12 else 0)
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val op = if (isSub) "SUB" else "ADD"
            val sym = if (isSub) "-" else "+"
            return DecodedOp(
                "$op ${regNameSp(rd, sf == 1)}, ${regNameSp(rn, sf == 1)}, #0x${imm12.toString(16)}",
                "${regSpExpr(rd)} = ${regSpExpr(rn)} $sym 0x${imm12.toString(16)}ULL;",
                true
            )
        }

        // MOV Xd, Xm alias (ORR Xd, XZR, Xm)
        if ((insn and 0x7FE0FFE0) == 0x2A0003E0) {
            val sf = (insn ushr 31) and 1
            val rm = (insn ushr 16) and 0x1F
            val rd = insn and 0x1F
            return DecodedOp(
                "MOV ${regName(rd, sf == 1)}, ${regName(rm, sf == 1)}",
                assignReg(rd, regExpr(rm)),
                true
            )
        }

        // LDR / STR unsigned immediate (32-bit / 64-bit)
        if ((insn and 0x3B200000) == 0x39000000 && ((insn ushr 30) and 3) >= 2) {
            val sizeBits = (insn ushr 30) and 3
            val is64 = sizeBits == 3
            val isLoad = ((insn ushr 22) and 3) == 1
            val offset = ((insn ushr 10) and 0xFFF) shl sizeBits
            val rn = (insn ushr 5) and 0x1F
            val rt = insn and 0x1F
            val op = if (isLoad) "LDR" else "STR"
            val fnMem = if (isLoad) (if (is64) "mem_read64" else "mem_read32") else (if (is64) "mem_write64" else "mem_write32")
            val cpp = if (isLoad) {
                assignReg(rt, "$fnMem(ctx, ${regSpExpr(rn)} + 0x${offset.toString(16)})")
            } else {
                "$fnMem(ctx, ${regSpExpr(rn)} + 0x${offset.toString(16)}, ${regExpr(rt)});"
            }
            return DecodedOp(
                "$op ${regName(rt, is64)}, [${regNameSp(rn, true)}, #0x${offset.toString(16)}]",
                cpp,
                true
            )
        }

        // STP / LDP 64-bit (stack frame prologues/epilogues)
        if ((insn and 0x7E000000) == 0x28000000) {
            val isLoad = ((insn ushr 22) and 1) == 1
            val rt = insn and 0x1F
            val rn = (insn ushr 5) and 0x1F
            val rt2 = (insn ushr 10) and 0x1F
            val imm7 = signExtend((insn ushr 15) and 0x7F, 7) * 8
            val op = if (isLoad) "LDP" else "STP"
            val cpp = if (isLoad) {
                "${regExpr(rt)} = mem_read64(ctx, ${regSpExpr(rn)}); ${regExpr(rt2)} = mem_read64(ctx, ${regSpExpr(rn)} + 8);"
            } else {
                "mem_write64(ctx, ${regSpExpr(rn)} + ($imm7), ${regExpr(rt)}); mem_write64(ctx, ${regSpExpr(rn)} + (${imm7 + 8}), ${regExpr(rt2)});"
            }
            return DecodedOp(
                "$op X$rt, X$rt2, [${regNameSp(rn, true)}, #$imm7]",
                cpp,
                true
            )
        }

        // BLR / BR
        if ((insn and 0xFFFFFC1F.toInt()) == 0xD63F0000.toInt() ||
            (insn and 0xFFFFFC1F.toInt()) == 0xD61F0000.toInt()
        ) {
            val isLink = ((insn ushr 21) and 1) == 1
            val rn = (insn ushr 5) and 0x1F
            val op = if (isLink) "BLR" else "BR"
            return DecodedOp(
                "$op X$rn",
                "dispatch_indirect(ctx, ${regExpr(rn)});",
                true
            )
        }

        return DecodedOp(
            ".word 0x%08X".format(insn),
            "/* unlifted_aarch64(0x%08X); */".format(insn),
            false
        )
    }

    private fun isRecognizedOpcode(insn: Int): Boolean {
        if (insn == 0xD503201F.toInt()) return true
        if ((insn and 0xFFFFFC1F.toInt()) == 0xD65F0000.toInt()) return true
        if ((insn and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) return true
        val top6 = (insn ushr 26) and 0x3F
        if (top6 == 0x05 || top6 == 0x25) return true
        if ((insn and 0xFF000010.toInt()) == 0x54000000) return true
        if ((insn and 0x7E000000) == 0x34000000) return true
        if ((insn and 0x1F000000) == 0x10000000) return true
        if ((insn and 0x1F800000) == 0x12800000) return true
        if ((insn and 0x1F000000) == 0x11000000) return true
        if ((insn and 0x7FE0FFE0) == 0x2A0003E0) return true
        if ((insn and 0x3B200000) == 0x39000000 && ((insn ushr 30) and 3) >= 2) return true
        if ((insn and 0x7E000000) == 0x28000000) return true
        if ((insn and 0xFFFFFC1F.toInt()) == 0xD63F0000.toInt() ||
            (insn and 0xFFFFFC1F.toInt()) == 0xD61F0000.toInt()
        ) return true
        return false
    }

    private fun extractRodataStrings(rodata: ByteArray, limit: Int = 500): List<String> {
        if (rodata.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        val priority = ArrayList<String>()
        val regular = ArrayList<String>()
        val sb = StringBuilder()

        for (b in rodata) {
            val ch = b.toInt() and 0xFF
            if (ch in 0x20..0x7E) {
                if (sb.length < 180) sb.append(ch.toChar())
            } else {
                if (sb.length >= 6) {
                    val s = sb.toString().trim()
                    if (s.length >= 6 && found.add(s)) {
                        val interesting = s.contains("nn::", true) ||
                            s.contains("Stage", true) ||
                            s.contains("Kingdom", true) ||
                            s.contains("Mario", true) ||
                            s.contains("Odyssey", true) ||
                            s.contains(".szs", true) ||
                            s.contains(".byml", true) ||
                            s.contains("al::", true)
                        if (interesting) priority.add(s) else if (regular.size < limit) regular.add(s)
                    }
                }
                sb.setLength(0)
                if (priority.size >= limit) break
            }
        }
        return (priority + regular).take(limit)
    }

    private fun regName(r: Int, is64: Boolean): String =
        if (r == 31) (if (is64) "XZR" else "WZR") else (if (is64) "X$r" else "W$r")

    private fun regNameSp(r: Int, is64: Boolean): String =
        if (r == 31) (if (is64) "SP" else "WSP") else (if (is64) "X$r" else "W$r")

    private fun regExpr(r: Int): String = if (r == 31) "0ULL" else "ctx->x[$r]"
    private fun regSpExpr(r: Int): String = if (r == 31) "ctx->sp" else "ctx->x[$r]"

    private fun assignReg(r: Int, valueExpr: String): String =
        if (r == 31) "/* discard -> XZR */ (void)($valueExpr);" else "ctx->x[$r] = $valueExpr;"

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    private fun readExactUpTo(stream: InputStream, dest: ByteArray, offset: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = stream.read(dest, offset + total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun skipFully(stream: InputStream, toSkip: Long) {
        var rem = toSkip
        val scratch = ByteArray(8192)
        while (rem > 0L) {
            val skipped = stream.skip(rem)
            if (skipped > 0L) {
                rem -= skipped
            } else {
                val r = stream.read(scratch, 0, minOf(rem, scratch.size.toLong()).toInt())
                if (r <= 0) break
                rem -= r
            }
        }
    }

    private fun buildCMakeLists(projectName: String): String {
        val safeName = projectName.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "StarOdysseyRecomp" }
        return """
            cmake_minimum_required(VERSION 3.22.1)
            project($safeName LANGUAGES CXX)

            set(CMAKE_CXX_STANDARD 17)
            set(CMAKE_CXX_STANDARD_REQUIRED ON)

            add_library(starodyssey_recomp SHARED
                src/recomp_runtime_stubs.cpp
                src/recomp_functions.cpp
            )

            target_include_directories(starodyssey_recomp PUBLIC
                ${'$'}{CMAKE_CURRENT_SOURCE_DIR}/include
            )
        """.trimIndent() + "\n"
    }

    private fun buildRuntimeHeader(functions: List<LiftedFunctionMeta>): String = buildString {
        appendLine("#pragma once")
        appendLine("#include <cstdint>")
        appendLine("#include <cstddef>")
        appendLine()
        appendLine("struct RecompContext {")
        appendLine("    uint64_t x[31]{};")
        appendLine("    uint64_t sp = 0x7FFF0000ULL;")
        appendLine("    uint64_t pc = 0;")
        appendLine("    uint32_t nzcv = 0;")
        appendLine("    uint8_t* ram = nullptr;")
        appendLine("    size_t ram_size = 0;")
        appendLine("};")
        appendLine()
        appendLine("uint32_t mem_read32(RecompContext* ctx, uint64_t addr);")
        appendLine("uint64_t mem_read64(RecompContext* ctx, uint64_t addr);")
        appendLine("void mem_write32(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void mem_write64(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void horizon_svc(RecompContext* ctx, uint32_t svc_id);")
        appendLine("void dispatch_indirect(RecompContext* ctx, uint64_t target);")
        appendLine()
        appendLine("inline bool cond_EQ(const RecompContext* c) { return (c->nzcv & 0x40000000u) != 0; }")
        appendLine("inline bool cond_NE(const RecompContext* c) { return (c->nzcv & 0x40000000u) == 0; }")
        appendLine("inline bool cond_CS(const RecompContext* c) { return (c->nzcv & 0x20000000u) != 0; }")
        appendLine("inline bool cond_CC(const RecompContext* c) { return (c->nzcv & 0x20000000u) == 0; }")
        appendLine("inline bool cond_MI(const RecompContext* c) { return (c->nzcv & 0x80000000u) != 0; }")
        appendLine("inline bool cond_PL(const RecompContext* c) { return (c->nzcv & 0x80000000u) == 0; }")
        appendLine("inline bool cond_VS(const RecompContext* c) { return (c->nzcv & 0x10000000u) != 0; }")
        appendLine("inline bool cond_VC(const RecompContext* c) { return (c->nzcv & 0x10000000u) == 0; }")
        appendLine("inline bool cond_HI(const RecompContext* c) { return cond_CS(c) && cond_NE(c); }")
        appendLine("inline bool cond_LS(const RecompContext* c) { return !cond_HI(c); }")
        appendLine("inline bool cond_GE(const RecompContext* c) { return ((c->nzcv >> 31) & 1u) == ((c->nzcv >> 28) & 1u); }")
        appendLine("inline bool cond_LT(const RecompContext* c) { return !cond_GE(c); }")
        appendLine("inline bool cond_GT(const RecompContext* c) { return cond_NE(c) && cond_GE(c); }")
        appendLine("inline bool cond_LE(const RecompContext* c) { return !cond_GT(c); }")
        appendLine("inline bool cond_AL(const RecompContext*) { return true; }")
        appendLine("inline bool cond_NV(const RecompContext*) { return false; }")
        appendLine()
        functions.forEach { fn ->
            appendLine("void ${fn.name}(RecompContext* ctx);")
        }
    }

    private fun buildRuntimeStubsCpp(
        session: LoadedNsoSession,
        titleId: String?,
        programName: String?
    ): String = """
        #include "recomp_runtime.h"
        #include <cstring>

        // Generated from NSO0: ${session.fileName}
        // Program: ${programName ?: "Unknown"} | TitleID: ${titleId ?: "Unknown"} | BuildID: ${session.buildIdHex}

        uint32_t mem_read32(RecompContext* ctx, uint64_t addr) {
            if (!ctx || !ctx->ram || addr + 4 > ctx->ram_size) return 0;
            uint32_t v = 0;
            std::memcpy(&v, ctx->ram + addr, sizeof(v));
            return v;
        }

        uint64_t mem_read64(RecompContext* ctx, uint64_t addr) {
            if (!ctx || !ctx->ram || addr + 8 > ctx->ram_size) return 0;
            uint64_t v = 0;
            std::memcpy(&v, ctx->ram + addr, sizeof(v));
            return v;
        }

        void mem_write32(RecompContext* ctx, uint64_t addr, uint64_t val) {
            if (!ctx || !ctx->ram || addr + 4 > ctx->ram_size) return;
            uint32_t v = static_cast<uint32_t>(val);
            std::memcpy(ctx->ram + addr, &v, sizeof(v));
        }

        void mem_write64(RecompContext* ctx, uint64_t addr, uint64_t val) {
            if (!ctx || !ctx->ram || addr + 8 > ctx->ram_size) return;
            std::memcpy(ctx->ram + addr, &val, sizeof(val));
        }

        void horizon_svc(RecompContext* ctx, uint32_t svc_id) {
            (void)ctx;
            (void)svc_id;
            // Connect Horizon OS HLE kernel syscalls (nn::os / nvdrv / nn::fs) here.
        }

        void dispatch_indirect(RecompContext* ctx, uint64_t target) {
            if (ctx) ctx->pc = target;
        }
    """.trimIndent() + "\n"

    private val COND_CODES = arrayOf(
        "EQ", "NE", "CS", "CC", "MI", "PL", "VS", "VC",
        "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV"
    )
}
