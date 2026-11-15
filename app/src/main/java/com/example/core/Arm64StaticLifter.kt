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
        val svcByInsnOffset = HashMap<Int, Int>()

        val wordBuf = ByteBuffer.wrap(textBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (totalInsns > 0) {
            val firstWord = wordBuf.getInt(0)
            if (firstWord != 0 && isRecognizedOpcode(firstWord)) {
                entryPoints.add(0)
            }
        }
        for (idx in 0 until totalInsns) {
            val offset = idx * 4
            val insn = wordBuf.getInt(offset)

            // Detect AArch64 BL target as function entry
            if (((insn ushr 26) and 0x3F) == 0x25) {
                val imm26 = signExtend(insn and 0x03FFFFFF, 26) shl 2
                val target = offset + imm26
                if (target in 0 until (totalInsns * 4) && (target and 3) == 0) {
                    val targetWord = wordBuf.getInt(target)
                    if (targetWord != 0) {
                        entryPoints.add(target)
                    }
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
            if (start + 4 <= textLimit && wordBuf.getInt(start) == 0) continue
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

    fun decodeBitmaskImmediate(nBit: Int, immr: Int, imms: Int, is64: Boolean): Long? {
        val len = 31 - Integer.numberOfLeadingZeros((nBit shl 6) or (imms.inv() and 0x3F))
        if (len < 1) return null
        val esize = 1 shl len
        val levels = esize - 1
        val s = imms and levels
        val r = immr and levels
        if (s == levels) return null
        val elemMask = if (esize == 64) -1L else (1L shl esize) - 1L
        val welem = if (s + 1 >= 64) -1L else (1L shl (s + 1)) - 1L
        val rotated = if (r == 0) {
            welem
        } else {
            ((welem ushr r) or (welem shl (esize - r))) and elemMask
        }
        var result = rotated
        var step = esize
        val targetWidth = if (is64) 64 else 32
        while (step < targetWidth) {
            result = result or (result shl step)
            step *= 2
        }
        return if (is64) result else (result and 0xFFFF_FFFFL)
    }

    fun decodeArm64Instruction(insn: Int, pcOffset: Int): DecodedOp {
        // NOP / HINT
        if (insn == 0xD503201F.toInt()) {
            return DecodedOp("NOP", "/* nop */", true)
        }
        if ((insn and 0xFFFFF01F.toInt()) == 0xD503201F.toInt()) {
            val hint = (insn ushr 5) and 0x7F
            return DecodedOp("HINT #$hint", "/* hint #$hint */", true)
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

        // BRK / HLT
        if ((insn and 0xFFE0001F.toInt()) == 0xD4200000.toInt()) {
            val imm16 = (insn ushr 5) and 0xFFFF
            return DecodedOp("BRK #0x${imm16.toString(16)}", "return; /* brk 0x${imm16.toString(16)} */", true)
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

        // TBZ / TBNZ
        if ((insn and 0x7E000000) == 0x36000000) {
            val bitPos = (((insn ushr 31) and 1) shl 5) or ((insn ushr 19) and 0x1F)
            val isNz = ((insn ushr 24) and 1) == 1
            val rt = insn and 0x1F
            val imm14 = signExtend((insn ushr 5) and 0x3FFF, 14) shl 2
            val target = pcOffset + imm14
            val opName = if (isNz) "TBNZ" else "TBZ"
            val cmp = if (isNz) "!= 0" else "== 0"
            return DecodedOp(
                "$opName ${regName(rt, bitPos >= 32)}, #$bitPos, 0x${"%08x".format(target)}",
                "if (((${regExpr(rt)} >> $bitPos) & 1ULL) $cmp) /* -> 0x${"%08x".format(target)} */ ;",
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
                assignReg(rd, "0x${target.toString(16)}ULL"),
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

        // ADD / ADDS / SUB / SUBS / CMP / CMN immediate
        if ((insn and 0x1F000000) == 0x11000000) {
            val sf = (insn ushr 31) and 1
            val isSub = ((insn ushr 30) and 1) == 1
            val setFlags = ((insn ushr 29) and 1) == 1
            val sh = (insn ushr 22) and 1
            val imm12 = ((insn ushr 10) and 0xFFF) shl (if (sh == 1) 12 else 0)
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val sym = if (isSub) "-" else "+"
            if (setFlags && rd == 31) {
                val alias = if (isSub) "CMP" else "CMN"
                return DecodedOp(
                    "$alias ${regNameSp(rn, sf == 1)}, #0x${imm12.toString(16)}",
                    "/* update flags: ${regSpExpr(rn)} $sym 0x${imm12.toString(16)}ULL */",
                    true
                )
            }
            val op = if (isSub) (if (setFlags) "SUBS" else "SUB") else (if (setFlags) "ADDS" else "ADD")
            val destStr = if (setFlags) regName(rd, sf == 1) else regNameSp(rd, sf == 1)
            val assignStmt = if (setFlags) {
                assignReg(rd, "${regSpExpr(rn)} $sym 0x${imm12.toString(16)}ULL")
            } else {
                "${regSpExpr(rd)} = ${regSpExpr(rn)} $sym 0x${imm12.toString(16)}ULL;"
            }
            return DecodedOp(
                "$op $destStr, ${regNameSp(rn, sf == 1)}, #0x${imm12.toString(16)}",
                assignStmt,
                true
            )
        }

        // ADD / ADDS / SUB / SUBS / CMP / CMN (shifted register)
        if ((insn and 0x1F200000) == 0x0B000000) {
            val sf = (insn ushr 31) and 1
            val isSub = ((insn ushr 30) and 1) == 1
            val setFlags = ((insn ushr 29) and 1) == 1
            val shiftType = (insn ushr 22) and 3
            val rm = (insn ushr 16) and 0x1F
            val imm6 = (insn ushr 10) and 0x3F
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val sym = if (isSub) "-" else "+"
            val shiftNames = arrayOf("LSL", "LSR", "ASR", "ROR")
            val shiftSuffix = if (imm6 > 0) ", ${shiftNames[shiftType]} #$imm6" else ""
            if (setFlags && rd == 31) {
                val alias = if (isSub) "CMP" else "CMN"
                return DecodedOp(
                    "$alias ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}$shiftSuffix",
                    "/* update flags: ${regExpr(rn)} $sym ${regExpr(rm)} */",
                    true
                )
            }
            val op = if (isSub) (if (setFlags) "SUBS" else "SUB") else (if (setFlags) "ADDS" else "ADD")
            return DecodedOp(
                "$op ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}$shiftSuffix",
                assignReg(rd, "${regExpr(rn)} $sym ${regExpr(rm)}"),
                true
            )
        }

        // ADD / ADDS / SUB / SUBS (extended register)
        if ((insn and 0x1FE00000) == 0x0B200000) {
            val sf = (insn ushr 31) and 1
            val isSub = ((insn ushr 30) and 1) == 1
            val setFlags = ((insn ushr 29) and 1) == 1
            val rm = (insn ushr 16) and 0x1F
            val option = (insn ushr 13) and 7
            val imm3 = (insn ushr 10) and 7
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val sym = if (isSub) "-" else "+"
            val extNames = arrayOf("UXTB", "UXTH", "UXTW", "UXTX", "SXTB", "SXTH", "SXTW", "SXTX")
            val op = if (isSub) (if (setFlags) "SUBS" else "SUB") else (if (setFlags) "ADDS" else "ADD")
            return DecodedOp(
                "$op ${regNameSp(rd, sf == 1)}, ${regNameSp(rn, sf == 1)}, ${regName(rm, (option and 3) == 3)}, ${extNames[option]} #$imm3",
                "${if (setFlags) regExpr(rd) else regSpExpr(rd)} = ${regSpExpr(rn)} $sym (${regExpr(rm)} << $imm3);",
                true
            )
        }

        // Logical bitmask immediate (AND / ORR / EOR / ANDS / TST / MOV)
        if ((insn and 0x1F800000) == 0x12000000) {
            val sf = (insn ushr 31) and 1
            val opc = (insn ushr 29) and 3
            val nBit = (insn ushr 22) and 1
            val immr = (insn ushr 16) and 0x3F
            val imms = (insn ushr 10) and 0x3F
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val immVal = decodeBitmaskImmediate(nBit, immr, imms, sf == 1)
            if (immVal != null) {
                val hexImm = "0x${immVal.toULong().toString(16)}"
                if (opc == 1 && rn == 31 && rd != 31) {
                    return DecodedOp(
                        "MOV ${regName(rd, sf == 1)}, #$hexImm",
                        assignReg(rd, "${hexImm}ULL"),
                        true
                    )
                }
                if (opc == 3 && rd == 31) {
                    return DecodedOp(
                        "TST ${regName(rn, sf == 1)}, #$hexImm",
                        "/* flags: ${regExpr(rn)} & ${hexImm}ULL */",
                        true
                    )
                }
                val opName = when (opc) {
                    0 -> "AND"
                    1 -> "ORR"
                    2 -> "EOR"
                    else -> "ANDS"
                }
                val cOp = when (opc) {
                    1 -> "|"
                    2 -> "^"
                    else -> "&"
                }
                val destStmt = if (opc == 3) assignReg(rd, "${regExpr(rn)} $cOp ${hexImm}ULL")
                else "${regSpExpr(rd)} = ${regExpr(rn)} $cOp ${hexImm}ULL;"
                val destName = if (opc == 3) regName(rd, sf == 1) else regNameSp(rd, sf == 1)
                return DecodedOp(
                    "$opName $destName, ${regName(rn, sf == 1)}, #$hexImm",
                    destStmt,
                    true
                )
            }
        }

        // Logical shifted register (AND / ORR / EOR / ANDS / BIC / ORN / EON / BICS / MOV / MVN / TST)
        if ((insn and 0x1F000000) == 0x0A000000) {
            val sf = (insn ushr 31) and 1
            val opc = (insn ushr 29) and 3
            val shiftType = (insn ushr 22) and 3
            val invertRm = ((insn ushr 21) and 1) == 1
            val rm = (insn ushr 16) and 0x1F
            val imm6 = (insn ushr 10) and 0x3F
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val shiftNames = arrayOf("LSL", "LSR", "ASR", "ROR")
            val shiftSuffix = if (imm6 > 0) ", ${shiftNames[shiftType]} #$imm6" else ""

            if (opc == 1 && !invertRm && rn == 31 && imm6 == 0) {
                return DecodedOp(
                    "MOV ${regName(rd, sf == 1)}, ${regName(rm, sf == 1)}",
                    assignReg(rd, regExpr(rm)),
                    true
                )
            }
            if (opc == 1 && invertRm && rn == 31 && imm6 == 0) {
                return DecodedOp(
                    "MVN ${regName(rd, sf == 1)}, ${regName(rm, sf == 1)}",
                    assignReg(rd, "~${regExpr(rm)}"),
                    true
                )
            }
            if (opc == 3 && !invertRm && rd == 31) {
                return DecodedOp(
                    "TST ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}$shiftSuffix",
                    "/* flags: ${regExpr(rn)} & ${regExpr(rm)} */",
                    true
                )
            }
            val opName = when ((opc shl 1) or (if (invertRm) 1 else 0)) {
                0 -> "AND"
                1 -> "BIC"
                2 -> "ORR"
                3 -> "ORN"
                4 -> "EOR"
                5 -> "EON"
                6 -> "ANDS"
                else -> "BICS"
            }
            val cOp = when (opc) {
                1 -> "|"
                2 -> "^"
                else -> "&"
            }
            val rhs = if (invertRm) "(~${regExpr(rm)})" else regExpr(rm)
            return DecodedOp(
                "$opName ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}$shiftSuffix",
                assignReg(rd, "${regExpr(rn)} $cOp $rhs"),
                true
            )
        }

        // Bitfield (SBFM / BFM / UBFM -> LSL / LSR / ASR / UXTB / UXTH / SXTW / UBFX / SBFX)
        if ((insn and 0x1F800000) == 0x13000000) {
            val sf = (insn ushr 31) and 1
            val opc = (insn ushr 29) and 3
            val immr = (insn ushr 16) and 0x3F
            val imms = (insn ushr 10) and 0x3F
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            if (opc != 3) {
                val width = if (sf == 1) 64 else 32
                val topBit = width - 1
                val asmStr = when {
                    opc == 2 && imms == topBit -> "LSR ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, #$immr"
                    opc == 2 && imms + 1 == immr -> "LSL ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, #${width - immr}"
                    opc == 2 && immr == 0 && imms == 7 -> "UXTB ${regName(rd, false)}, W$rn"
                    opc == 2 && immr == 0 && imms == 15 -> "UXTH ${regName(rd, false)}, W$rn"
                    opc == 0 && imms == topBit -> "ASR ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, #$immr"
                    opc == 0 && immr == 0 && imms == 31 && sf == 1 -> "SXTW X$rd, W$rn"
                    else -> {
                        val name = when (opc) {
                            0 -> "SBFM"
                            1 -> "BFM"
                            else -> "UBFM"
                        }
                        "$name ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, #$immr, #$imms"
                    }
                }
                return DecodedOp(asmStr, assignReg(rd, "bitfield_${opc}(${regExpr(rn)}, $immr, $imms)"), true)
            }
        }

        // CSEL / CSINC / CSINV / CSNEG (plus CSET alias)
        if ((insn and 0x3FE00800) == 0x1A800000) {
            val sf = (insn ushr 31) and 1
            val op = (insn ushr 30) and 1
            val rm = (insn ushr 16) and 0x1F
            val cond = (insn ushr 12) and 0xF
            val op2 = (insn ushr 10) and 1
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            val condName = COND_CODES[cond]
            if (op == 0 && op2 == 1 && rn == 31 && rm == 31) {
                val invCond = COND_CODES[cond xor 1]
                return DecodedOp(
                    "CSET ${regName(rd, sf == 1)}, $invCond",
                    assignReg(rd, "cond_$invCond(ctx) ? 1ULL : 0ULL"),
                    true
                )
            }
            val opName = when ((op shl 1) or op2) {
                0 -> "CSEL"
                1 -> "CSINC"
                2 -> "CSINV"
                else -> "CSNEG"
            }
            val elseExpr = when ((op shl 1) or op2) {
                0 -> regExpr(rm)
                1 -> "(${regExpr(rm)} + 1ULL)"
                2 -> "(~${regExpr(rm)})"
                else -> "(-${regExpr(rm)})"
            }
            return DecodedOp(
                "$opName ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}, $condName",
                assignReg(rd, "cond_$condName(ctx) ? ${regExpr(rn)} : $elseExpr"),
                true
            )
        }

        // MADD / MSUB / MUL
        if ((insn and 0x7FE00000) == 0x1B000000) {
            val sf = (insn ushr 31) and 1
            val rm = (insn ushr 16) and 0x1F
            val o0 = (insn ushr 15) and 1
            val ra = (insn ushr 10) and 0x1F
            val rn = (insn ushr 5) and 0x1F
            val rd = insn and 0x1F
            if (o0 == 0 && ra == 31) {
                return DecodedOp(
                    "MUL ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}",
                    assignReg(rd, "${regExpr(rn)} * ${regExpr(rm)}"),
                    true
                )
            }
            val opName = if (o0 == 0) "MADD" else "MSUB"
            val sym = if (o0 == 0) "+" else "-"
            return DecodedOp(
                "$opName ${regName(rd, sf == 1)}, ${regName(rn, sf == 1)}, ${regName(rm, sf == 1)}, ${regName(ra, sf == 1)}",
                assignReg(rd, "${regExpr(ra)} $sym (${regExpr(rn)} * ${regExpr(rm)})"),
                true
            )
        }

        // LDR (literal) 32-bit / 64-bit / LDRSW
        if ((insn and 0x3F000000) == 0x18000000) {
            val opc = (insn ushr 30) and 3
            if (opc != 3) {
                val is64 = opc >= 1
                val rt = insn and 0x1F
                val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
                val target = pcOffset + imm19
                val op = if (opc == 2) "LDRSW" else "LDR"
                val fnMem = if (opc == 0) "mem_read32" else "mem_read64"
                return DecodedOp(
                    "$op ${regName(rt, is64)}, 0x${"%08x".format(target)}",
                    assignReg(rt, "$fnMem(ctx, 0x${"%08x".format(target)}ULL)"),
                    true
                )
            }
        }

        // LDR / STR unsigned immediate (8 / 16 / 32 / 64-bit)
        if ((insn and 0x3B000000) == 0x39000000) {
            val sizeBits = (insn ushr 30) and 3
            val opc = (insn ushr 22) and 3
            if (!(sizeBits == 3 && opc >= 2)) {
                val isLoad = opc != 0
                val is64 = sizeBits == 3 || opc == 2
                val offset = ((insn ushr 10) and 0xFFF) shl sizeBits
                val rn = (insn ushr 5) and 0x1F
                val rt = insn and 0x1F
                val suffix = when (sizeBits) {
                    0 -> if (opc >= 2) "SB" else "B"
                    1 -> if (opc >= 2) "SH" else "H"
                    2 -> if (opc == 2) "SW" else ""
                    else -> ""
                }
                val op = (if (isLoad) "LDR" else "STR") + suffix
                val fnMem = when (sizeBits) {
                    0 -> if (isLoad) "mem_read8" else "mem_write8"
                    1 -> if (isLoad) "mem_read16" else "mem_write16"
                    2 -> if (isLoad) "mem_read32" else "mem_write32"
                    else -> if (isLoad) "mem_read64" else "mem_write64"
                }
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
        }

        // LDUR / STUR (unscaled), LDR / STR pre-index & post-index
        if ((insn and 0x3B200000) == 0x38000000 && ((insn ushr 10) and 3) != 2) {
            val sizeBits = (insn ushr 30) and 3
            val opc = (insn ushr 22) and 3
            val mode = (insn ushr 10) and 3 // 0=unscaled, 1=post, 3=pre
            val imm9 = signExtend((insn ushr 12) and 0x1FF, 9)
            val rn = (insn ushr 5) and 0x1F
            val rt = insn and 0x1F
            val isLoad = opc != 0
            val is64 = sizeBits == 3 || opc == 2
            val suffix = when (sizeBits) {
                0 -> if (opc >= 2) "SB" else "B"
                1 -> if (opc >= 2) "SH" else "H"
                2 -> if (opc == 2) "SW" else ""
                else -> ""
            }
            val opBase = if (mode == 0) (if (isLoad) "LDUR" else "STUR") else (if (isLoad) "LDR" else "STR")
            val op = opBase + suffix
            val addrAsm = when (mode) {
                1 -> "[${regNameSp(rn, true)}], #$imm9"
                3 -> "[${regNameSp(rn, true)}, #$imm9]!"
                else -> "[${regNameSp(rn, true)}, #$imm9]"
            }
            val fnMem = when (sizeBits) {
                0 -> if (isLoad) "mem_read8" else "mem_write8"
                1 -> if (isLoad) "mem_read16" else "mem_write16"
                2 -> if (isLoad) "mem_read32" else "mem_write32"
                else -> if (isLoad) "mem_read64" else "mem_write64"
            }
            val cpp = if (isLoad) {
                assignReg(rt, "$fnMem(ctx, ${regSpExpr(rn)} + ($imm9))")
            } else {
                "$fnMem(ctx, ${regSpExpr(rn)} + ($imm9), ${regExpr(rt)});"
            }
            return DecodedOp("$op ${regName(rt, is64)}, $addrAsm", cpp, true)
        }

        // LDR / STR (register offset)
        if ((insn and 0x3B200C00) == 0x38200800) {
            val sizeBits = (insn ushr 30) and 3
            val opc = (insn ushr 22) and 3
            val rm = (insn ushr 16) and 0x1F
            val option = (insn ushr 13) and 7
            val s = (insn ushr 12) and 1
            val rn = (insn ushr 5) and 0x1F
            val rt = insn and 0x1F
            val isLoad = opc != 0
            val is64 = sizeBits == 3 || opc == 2
            val op = if (isLoad) "LDR" else "STR"
            val shift = if (s == 1) sizeBits else 0
            val idx64 = (option and 1) == 1
            val fnMem = if (isLoad) (if (sizeBits == 3) "mem_read64" else "mem_read32") else (if (sizeBits == 3) "mem_write64" else "mem_write32")
            val cpp = if (isLoad) {
                assignReg(rt, "$fnMem(ctx, ${regSpExpr(rn)} + (${regExpr(rm)} << $shift))")
            } else {
                "$fnMem(ctx, ${regSpExpr(rn)} + (${regExpr(rm)} << $shift), ${regExpr(rt)});"
            }
            return DecodedOp(
                "$op ${regName(rt, is64)}, [${regNameSp(rn, true)}, ${regName(rm, idx64)}${if (shift > 0) ", LSL #$shift" else ""}]",
                cpp,
                true
            )
        }

        // STP / LDP (32-bit or 64-bit)
        if ((insn and 0x7E000000) == 0x28000000) {
            val opc = (insn ushr 30) and 3
            val is64 = opc >= 2
            val scale = if (is64) 8 else 4
            val isLoad = ((insn ushr 22) and 1) == 1
            val modeBits = (insn ushr 23) and 3
            val rt = insn and 0x1F
            val rn = (insn ushr 5) and 0x1F
            val rt2 = (insn ushr 10) and 0x1F
            val imm7 = signExtend((insn ushr 15) and 0x7F, 7) * scale
            val op = if (isLoad) "LDP" else "STP"
            val addrAsm = when (modeBits) {
                1 -> "[${regNameSp(rn, true)}], #$imm7"
                3 -> "[${regNameSp(rn, true)}, #$imm7]!"
                else -> "[${regNameSp(rn, true)}, #$imm7]"
            }
            val fnMem = if (isLoad) (if (is64) "mem_read64" else "mem_read32") else (if (is64) "mem_write64" else "mem_write32")
            val cpp = if (isLoad) {
                "${regExpr(rt)} = $fnMem(ctx, ${regSpExpr(rn)} + ($imm7)); ${regExpr(rt2)} = $fnMem(ctx, ${regSpExpr(rn)} + (${imm7 + scale}));"
            } else {
                "$fnMem(ctx, ${regSpExpr(rn)} + ($imm7), ${regExpr(rt)}); $fnMem(ctx, ${regSpExpr(rn)} + (${imm7 + scale}), ${regExpr(rt2)});"
            }
            return DecodedOp(
                "$op ${regName(rt, is64)}, ${regName(rt2, is64)}, $addrAsm",
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
        if (insn == 0) return false
        if ((insn and 0xFFFFF01F.toInt()) == 0xD503201F.toInt()) return true
        if ((insn and 0xFFFFFC1F.toInt()) == 0xD65F0000.toInt()) return true
        if ((insn and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) return true
        if ((insn and 0xFFE0001F.toInt()) == 0xD4200000.toInt()) return true
        val top6 = (insn ushr 26) and 0x3F
        if (top6 == 0x05 || top6 == 0x25) return true
        if ((insn and 0xFF000010.toInt()) == 0x54000000) return true
        if ((insn and 0x7E000000) == 0x34000000) return true
        if ((insn and 0x7E000000) == 0x36000000) return true
        if ((insn and 0x1F000000) == 0x10000000) return true
        if ((insn and 0x1F800000) == 0x12800000) return true
        if ((insn and 0x1F000000) == 0x11000000) return true
        if ((insn and 0x1F200000) == 0x0B000000) return true
        if ((insn and 0x1FE00000) == 0x0B200000) return true
        if ((insn and 0x1F800000) == 0x12000000) return true
        if ((insn and 0x1F000000) == 0x0A000000) return true
        if ((insn and 0x1F800000) == 0x13000000 && ((insn ushr 29) and 3) != 3) return true
        if ((insn and 0x3FE00800) == 0x1A800000) return true
        if ((insn and 0x7FE00000) == 0x1B000000) return true
        if ((insn and 0x3F000000) == 0x18000000 && ((insn ushr 30) and 3) != 3) return true
        if ((insn and 0x3B000000) == 0x39000000) return true
        if ((insn and 0x3B200000) == 0x38000000 && ((insn ushr 10) and 3) != 2) return true
        if ((insn and 0x3B200C00) == 0x38200800) return true
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
        appendLine("uint8_t mem_read8(RecompContext* ctx, uint64_t addr);")
        appendLine("uint16_t mem_read16(RecompContext* ctx, uint64_t addr);")
        appendLine("uint32_t mem_read32(RecompContext* ctx, uint64_t addr);")
        appendLine("uint64_t mem_read64(RecompContext* ctx, uint64_t addr);")
        appendLine("void mem_write8(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void mem_write16(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void mem_write32(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void mem_write64(RecompContext* ctx, uint64_t addr, uint64_t val);")
        appendLine("void horizon_svc(RecompContext* ctx, uint32_t svc_id);")
        appendLine("void dispatch_indirect(RecompContext* ctx, uint64_t target);")
        appendLine()
        appendLine("inline uint64_t bitfield_2(uint64_t v, int immr, int imms) {")
        appendLine("    return (imms >= immr) ? ((v >> immr) & ((imms - immr + 1 >= 64) ? ~0ULL : ((1ULL << (imms - immr + 1)) - 1ULL))) : (v << (64 - immr));")
        appendLine("}")
        appendLine("inline uint64_t bitfield_0(uint64_t v, int immr, int imms) { return bitfield_2(v, immr, imms); }")
        appendLine("inline uint64_t bitfield_1(uint64_t v, int immr, int imms) { return bitfield_2(v, immr, imms); }")
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

        // Generated from ExeFS/main (NSO0): ${session.fileName}
        // Program: ${programName ?: "Unknown"} | TitleID: ${titleId ?: "Unknown"} | BuildID: ${session.buildIdHex}

        uint8_t mem_read8(RecompContext* ctx, uint64_t addr) {
            if (!ctx || !ctx->ram || addr + 1 > ctx->ram_size) return 0;
            return ctx->ram[addr];
        }

        uint16_t mem_read16(RecompContext* ctx, uint64_t addr) {
            if (!ctx || !ctx->ram || addr + 2 > ctx->ram_size) return 0;
            uint16_t v = 0;
            std::memcpy(&v, ctx->ram + addr, sizeof(v));
            return v;
        }

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

        void mem_write8(RecompContext* ctx, uint64_t addr, uint64_t val) {
            if (!ctx || !ctx->ram || addr + 1 > ctx->ram_size) return;
            ctx->ram[addr] = static_cast<uint8_t>(val);
        }

        void mem_write16(RecompContext* ctx, uint64_t addr, uint64_t val) {
            if (!ctx || !ctx->ram || addr + 2 > ctx->ram_size) return;
            uint16_t v = static_cast<uint16_t>(val);
            std::memcpy(ctx->ram + addr, &v, sizeof(v));
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
