package com.example.core

import com.example.model.RecompExecResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sandboxed AArch64 recompiled-IR execution engine.
 * Maps the user's actual decompressed NSO0 (.text and .rodata) segments plus a
 * 256 KB stack and executes decoded instructions step-by-step, logging real
 * register updates, memory accesses, .rodata string pointers, and Switch SVC traps.
 */
object RecompExecutionEngine {

    private const val STACK_BASE = 0x7FF0_0000L
    private const val STACK_SIZE = 256 * 1024
    private const val SENTINEL_RETURN_PC = 0xDEAD_BEEF_0000L

    fun execute(
        session: Arm64StaticLifter.LoadedNsoSession,
        entryOffset: Int,
        maxSteps: Int = 256
    ): RecompExecResult {
        val textBase = session.textMemOffset.toLong()
        val roBase = session.roMemOffset.toLong()
        val textBytes = session.textBytes
        val roBytes = session.rodataBytes
        val stack = ByteArray(STACK_SIZE)

        val x = LongArray(31)
        var sp = STACK_BASE + STACK_SIZE - 16L
        var pc = textBase + entryOffset.toLong()
        var nzcv = 0
        x[30] = SENTINEL_RETURN_PC

        val textBuf = ByteBuffer.wrap(textBytes).order(ByteOrder.LITTLE_ENDIAN)
        val logs = ArrayList<String>()
        var steps = 0
        var haltReason = "Limite de $maxSteps passos atingido"

        logs.add("[VM] Iniciando sub_%08x (PC=0x%08X, SP=0x%08X)".format(entryOffset, pc, sp))
        logs.add("[VM] .text=0x%08X (%d B) • .rodata=0x%08X (%d B)".format(textBase, textBytes.size, roBase, roBytes.size))

        fun readMem8(addr: Long): Int {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff in stack.indices) {
                return stack[stackOff].toInt() and 0xFF
            }
            val roOff = (addr - roBase).toInt()
            if (addr >= roBase && roOff in roBytes.indices) {
                return roBytes[roOff].toInt() and 0xFF
            }
            val textOff = (addr - textBase).toInt()
            if (addr >= textBase && textOff in textBytes.indices) {
                return textBytes[textOff].toInt() and 0xFF
            }
            return 0
        }

        fun readMem16(addr: Long): Int {
            val b0 = readMem8(addr)
            val b1 = readMem8(addr + 1)
            return b0 or (b1 shl 8)
        }

        fun readMem32(addr: Long): Int {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff >= 0 && stackOff + 4 <= stack.size) {
                return ByteBuffer.wrap(stack, stackOff, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val roOff = (addr - roBase).toInt()
            if (addr >= roBase && roOff >= 0 && roOff + 4 <= roBytes.size) {
                return ByteBuffer.wrap(roBytes, roOff, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val textOff = (addr - textBase).toInt()
            if (addr >= textBase && textOff >= 0 && textOff + 4 <= textBytes.size) {
                return textBuf.getInt(textOff)
            }
            return 0
        }

        fun readMem64(addr: Long): Long {
            val lo = readMem32(addr).toLong() and 0xFFFF_FFFFL
            val hi = readMem32(addr + 4).toLong() and 0xFFFF_FFFFL
            return lo or (hi shl 32)
        }

        fun writeMem8(addr: Long, value: Long) {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff in stack.indices) {
                stack[stackOff] = value.toByte()
            }
        }

        fun writeMem16(addr: Long, value: Long) {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff >= 0 && stackOff + 2 <= stack.size) {
                stack[stackOff] = value.toByte()
                stack[stackOff + 1] = (value ushr 8).toByte()
            }
        }

        fun writeMem32(addr: Long, value: Long) {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff >= 0 && stackOff + 4 <= stack.size) {
                ByteBuffer.wrap(stack, stackOff, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
            }
        }

        fun writeMem64(addr: Long, value: Long) {
            val stackOff = (addr - STACK_BASE).toInt()
            if (addr >= STACK_BASE && stackOff >= 0 && stackOff + 8 <= stack.size) {
                ByteBuffer.wrap(stack, stackOff, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
            }
        }

        fun probeRodataString(addr: Long): String? {
            val roOff = (addr - roBase).toInt()
            if (addr < roBase || roOff < 0 || roOff >= roBytes.size) return null
            val sb = StringBuilder()
            var i = roOff
            while (i < roBytes.size && sb.length < 48) {
                val c = roBytes[i].toInt() and 0xFF
                if (c == 0) break
                if (c !in 0x20..0x7E) return null
                sb.append(c.toChar())
                i++
            }
            return if (sb.length >= 3) sb.toString() else null
        }

        while (steps < maxSteps) {
            if (pc == SENTINEL_RETURN_PC) {
                haltReason = "Retorno normal (RET para endereço sentinela) • X0=0x${x[0].toULong().toString(16)}"
                logs.add("[VM] $haltReason")
                break
            }

            val textOff = (pc - textBase).toInt()
            if (pc < textBase || textOff < 0 || textOff + 4 > textBytes.size) {
                haltReason = "Salto para módulo externo / stub fora de .text (0x${pc.toULong().toString(16)} — requer sdk/subsdk)"
                logs.add("[VM] Parada: $haltReason")
                break
            }

            val insn = textBuf.getInt(textOff)
            val op = Arm64StaticLifter.decodeArm64Instruction(insn, textOff)
            steps++
            if (logs.size < 64) {
                logs.add("0x%08X: %s".format(pc, op.asm))
            }

            // NOP / HINT
            if ((insn and 0xFFFFF01F.toInt()) == 0xD503201F.toInt()) {
                pc += 4
                continue
            }

            // RET
            if ((insn and 0xFFFFFC1F.toInt()) == 0xD65F0000.toInt()) {
                val rn = (insn ushr 5) and 0x1F
                pc = if (rn < 31) x[rn] else SENTINEL_RETURN_PC
                continue
            }

            // SVC #imm16
            if ((insn and 0xFFE0001F.toInt()) == 0xD4000001.toInt()) {
                val imm16 = (insn ushr 5) and 0xFFFF
                val svcName = SWITCH_SVC_NAMES[imm16] ?: "svcUnknown"
                logs.add("[SVC] Trap Switch Horizon OS: $svcName (0x${imm16.toString(16)}) • X0=0x${x[0].toULong().toString(16)} X1=0x${x[1].toULong().toString(16)}")
                probeRodataString(x[0])?.let { s -> logs.add("[RODATA] X0 -> \"$s\"") }
                probeRodataString(x[1])?.let { s -> logs.add("[RODATA] X1 -> \"$s\"") }
                haltReason = "Aguardando serviço Horizon OS: $svcName (0x${imm16.toString(16)})"
                break
            }

            // BRK / HLT
            if ((insn and 0xFFE0001F.toInt()) == 0xD4200000.toInt()) {
                val imm16 = (insn ushr 5) and 0xFFFF
                haltReason = "Trap BRK #0x${imm16.toString(16)}"
                logs.add("[VM] Parada: $haltReason")
                break
            }

            // B / BL imm26
            val top6 = (insn ushr 26) and 0x3F
            if (top6 == 0x05 || top6 == 0x25) {
                val imm26 = signExtend(insn and 0x03FFFFFF, 26) shl 2
                if (top6 == 0x25) {
                    x[30] = pc + 4
                }
                pc += imm26
                continue
            }

            // B.cond imm19
            if ((insn and 0xFF000010.toInt()) == 0x54000000) {
                val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
                val cond = insn and 0xF
                pc = if (evalCond(cond, nzcv)) pc + imm19 else pc + 4
                continue
            }

            // CBZ / CBNZ
            if ((insn and 0x7E000000) == 0x34000000) {
                val sf = (insn ushr 31) and 1
                val isNz = ((insn ushr 24) and 1) == 1
                val rt = insn and 0x1F
                val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
                val raw = if (rt == 31) 0L else x[rt]
                val v = if (sf == 1) raw else (raw and 0xFFFF_FFFFL)
                val take = if (isNz) v != 0L else v == 0L
                pc = if (take) pc + imm19 else pc + 4
                continue
            }

            // TBZ / TBNZ
            if ((insn and 0x7E000000) == 0x36000000) {
                val bitPos = (((insn ushr 31) and 1) shl 5) or ((insn ushr 19) and 0x1F)
                val isNz = ((insn ushr 24) and 1) == 1
                val rt = insn and 0x1F
                val imm14 = signExtend((insn ushr 5) and 0x3FFF, 14) shl 2
                val raw = if (rt == 31) 0L else x[rt]
                val bitSet = ((raw ushr bitPos) and 1L) != 0L
                val take = if (isNz) bitSet else !bitSet
                pc = if (take) pc + imm14 else pc + 4
                continue
            }

            // ADRP / ADR
            if ((insn and 0x1F000000) == 0x10000000) {
                val isAdrp = ((insn ushr 31) and 1) == 1
                val rd = insn and 0x1F
                val immlo = (insn ushr 29) and 0x3
                val immhi = (insn ushr 5) and 0x7FFFF
                val imm = signExtend((immhi shl 2) or immlo, 21).toLong()
                val target = if (isAdrp) (pc and -4096L) + (imm shl 12) else pc + imm
                if (rd < 31) x[rd] = target
                probeRodataString(target)?.let { s ->
                    if (logs.size < 64) logs.add("[RODATA] X$rd -> \"$s\"")
                }
                pc += 4
                continue
            }

            // MOVZ / MOVK / MOVN
            if ((insn and 0x1F800000) == 0x12800000) {
                val sf = (insn ushr 31) and 1
                val opc = (insn ushr 29) and 3
                val hw = (insn ushr 21) and 3
                val imm16 = ((insn ushr 5) and 0xFFFF).toLong()
                val rd = insn and 0x1F
                val shift = hw * 16
                if (rd < 31) {
                    val v = when (opc) {
                        2 -> imm16 shl shift
                        3 -> (x[rd] and (0xFFFFL shl shift).inv()) or (imm16 shl shift)
                        else -> (imm16 shl shift).inv()
                    }
                    x[rd] = if (sf == 1) v else (v and 0xFFFF_FFFFL)
                }
                pc += 4
                continue
            }

            // ADD / ADDS / SUB / SUBS immediate
            if ((insn and 0x1F000000) == 0x11000000) {
                val sf = (insn ushr 31) and 1
                val isSub = ((insn ushr 30) and 1) == 1
                val setFlags = ((insn ushr 29) and 1) == 1
                val sh = (insn ushr 22) and 1
                val imm12 = (((insn ushr 10) and 0xFFF) shl (if (sh == 1) 12 else 0)).toLong()
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val baseVal = if (rn == 31) sp else x[rn]
                val (res, newFlags) = computeAddSub(baseVal, imm12, isSub, sf == 1)
                if (setFlags) nzcv = newFlags
                if (rd == 31 && !setFlags) {
                    sp = res
                } else if (rd < 31) {
                    x[rd] = res
                    probeRodataString(res)?.let { s ->
                        if (logs.size < 64) logs.add("[RODATA] X$rd -> \"$s\"")
                    }
                }
                pc += 4
                continue
            }

            // ADD / ADDS / SUB / SUBS (shifted register)
            if ((insn and 0x1F200000) == 0x0B000000) {
                val sf = (insn ushr 31) and 1
                val isSub = ((insn ushr 30) and 1) == 1
                val setFlags = ((insn ushr 29) and 1) == 1
                val shiftType = (insn ushr 22) and 3
                val rm = (insn ushr 16) and 0x1F
                val imm6 = (insn ushr 10) and 0x3F
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val lhs = if (rn == 31) 0L else x[rn]
                val rawRm = if (rm == 31) 0L else x[rm]
                val rhs = applyShift(rawRm, shiftType, imm6, sf == 1)
                val (res, newFlags) = computeAddSub(lhs, rhs, isSub, sf == 1)
                if (setFlags) nzcv = newFlags
                if (rd < 31) {
                    x[rd] = res
                    probeRodataString(res)?.let { s ->
                        if (logs.size < 64) logs.add("[RODATA] X$rd -> \"$s\"")
                    }
                }
                pc += 4
                continue
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
                val lhs = if (rn == 31) sp else x[rn]
                val rhs = applyExtend(if (rm == 31) 0L else x[rm], option) shl imm3
                val (res, newFlags) = computeAddSub(lhs, rhs, isSub, sf == 1)
                if (setFlags) nzcv = newFlags
                if (rd == 31 && !setFlags) {
                    sp = res
                } else if (rd < 31) {
                    x[rd] = res
                }
                pc += 4
                continue
            }

            // Logical bitmask immediate (AND / ORR / EOR / ANDS)
            if ((insn and 0x1F800000) == 0x12000000) {
                val sf = (insn ushr 31) and 1
                val opc = (insn ushr 29) and 3
                val nBit = (insn ushr 22) and 1
                val immr = (insn ushr 16) and 0x3F
                val imms = (insn ushr 10) and 0x3F
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val immVal = Arm64StaticLifter.decodeBitmaskImmediate(nBit, immr, imms, sf == 1)
                if (immVal != null) {
                    val rawRn = if (rn == 31) 0L else x[rn]
                    val lhs = if (sf == 1) rawRn else (rawRn and 0xFFFF_FFFFL)
                    val res = when (opc) {
                        1 -> lhs or immVal
                        2 -> lhs xor immVal
                        else -> lhs and immVal
                    }.let { if (sf == 1) it else (it and 0xFFFF_FFFFL) }
                    if (opc == 3) {
                        val nFlag = if (sf == 1) (if (res < 0) 1 else 0) else (if (res.toInt() < 0) 1 else 0)
                        val zFlag = if (res == 0L) 1 else 0
                        nzcv = (nFlag shl 31) or (zFlag shl 30)
                    }
                    if (rd == 31 && opc != 3) {
                        sp = res
                    } else if (rd < 31) {
                        x[rd] = res
                    }
                    pc += 4
                    continue
                }
            }

            // Logical shifted register (AND / ORR / EOR / ANDS / BIC / ORN / EON / BICS)
            if ((insn and 0x1F000000) == 0x0A000000) {
                val sf = (insn ushr 31) and 1
                val opc = (insn ushr 29) and 3
                val shiftType = (insn ushr 22) and 3
                val invertRm = ((insn ushr 21) and 1) == 1
                val rm = (insn ushr 16) and 0x1F
                val imm6 = (insn ushr 10) and 0x3F
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val lhs = (if (rn == 31) 0L else x[rn]).let { if (sf == 1) it else (it and 0xFFFF_FFFFL) }
                var rhs = applyShift(if (rm == 31) 0L else x[rm], shiftType, imm6, sf == 1)
                if (invertRm) rhs = rhs.inv()
                val res = when (opc) {
                    1 -> lhs or rhs
                    2 -> lhs xor rhs
                    else -> lhs and rhs
                }.let { if (sf == 1) it else (it and 0xFFFF_FFFFL) }
                if (opc == 3) {
                    val nFlag = if (sf == 1) (if (res < 0) 1 else 0) else (if (res.toInt() < 0) 1 else 0)
                    val zFlag = if (res == 0L) 1 else 0
                    nzcv = (nFlag shl 31) or (zFlag shl 30)
                }
                if (rd < 31) x[rd] = res
                pc += 4
                continue
            }

            // Bitfield (SBFM / BFM / UBFM)
            if ((insn and 0x1F800000) == 0x13000000 && ((insn ushr 29) and 3) != 3) {
                val sf = (insn ushr 31) and 1
                val opc = (insn ushr 29) and 3
                val immr = (insn ushr 16) and 0x3F
                val imms = (insn ushr 10) and 0x3F
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val width = if (sf == 1) 64 else 32
                val src = (if (rn == 31) 0L else x[rn]).let { if (sf == 1) it else (it and 0xFFFF_FFFFL) }
                val res = if (opc == 1) {
                    // BFM preserves destination bits outside field
                    val dst = if (rd == 31) 0L else x[rd]
                    evalBitfieldInsert(dst, src, immr, imms, width)
                } else {
                    evalSbfmUbfm(src, opc == 0, immr, imms, width)
                }
                if (rd < 31) x[rd] = if (sf == 1) res else (res and 0xFFFF_FFFFL)
                pc += 4
                continue
            }

            // CSEL / CSINC / CSINV / CSNEG
            if ((insn and 0x3FE00800) == 0x1A800000) {
                val sf = (insn ushr 31) and 1
                val opCode = (insn ushr 30) and 1
                val rm = (insn ushr 16) and 0x1F
                val cond = (insn ushr 12) and 0xF
                val op2 = (insn ushr 10) and 1
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val vRn = if (rn == 31) 0L else x[rn]
                val vRm = if (rm == 31) 0L else x[rm]
                val res = if (evalCond(cond, nzcv)) {
                    vRn
                } else {
                    when ((opCode shl 1) or op2) {
                        0 -> vRm
                        1 -> vRm + 1L
                        2 -> vRm.inv()
                        else -> -vRm
                    }
                }
                if (rd < 31) x[rd] = if (sf == 1) res else (res and 0xFFFF_FFFFL)
                pc += 4
                continue
            }

            // MADD / MSUB / MUL
            if ((insn and 0x7FE00000) == 0x1B000000) {
                val sf = (insn ushr 31) and 1
                val rm = (insn ushr 16) and 0x1F
                val o0 = (insn ushr 15) and 1
                val ra = (insn ushr 10) and 0x1F
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val vRn = if (rn == 31) 0L else x[rn]
                val vRm = if (rm == 31) 0L else x[rm]
                val vRa = if (ra == 31) 0L else x[ra]
                val res = if (o0 == 0) vRa + (vRn * vRm) else vRa - (vRn * vRm)
                if (rd < 31) x[rd] = if (sf == 1) res else (res and 0xFFFF_FFFFL)
                pc += 4
                continue
            }

            // LDR (literal) 32-bit / 64-bit / LDRSW
            if ((insn and 0x3F000000) == 0x18000000 && ((insn ushr 30) and 3) != 3) {
                val opc = (insn ushr 30) and 3
                val rt = insn and 0x1F
                val imm19 = signExtend((insn ushr 5) and 0x7FFFF, 19) shl 2
                val targetAddr = pc + imm19
                if (rt < 31) {
                    x[rt] = when (opc) {
                        0 -> readMem32(targetAddr).toLong() and 0xFFFF_FFFFL
                        2 -> readMem32(targetAddr).toLong() // sign-extended
                        else -> readMem64(targetAddr)
                    }
                }
                pc += 4
                continue
            }

            // LDR / STR unsigned immediate (8 / 16 / 32 / 64-bit)
            if ((insn and 0x3B000000) == 0x39000000) {
                val sizeBits = (insn ushr 30) and 3
                val opc = (insn ushr 22) and 3
                if (!(sizeBits == 3 && opc >= 2)) {
                    val isLoad = opc != 0
                    val offset = ((insn ushr 10) and 0xFFF) shl sizeBits
                    val rn = (insn ushr 5) and 0x1F
                    val rt = insn and 0x1F
                    val addr = (if (rn == 31) sp else x[rn]) + offset
                    if (isLoad) {
                        if (rt < 31) {
                            x[rt] = loadBySizeOpc(addr, sizeBits, opc, ::readMem8, ::readMem16, ::readMem32, ::readMem64)
                        }
                    } else {
                        val v = if (rt == 31) 0L else x[rt]
                        when (sizeBits) {
                            0 -> writeMem8(addr, v)
                            1 -> writeMem16(addr, v)
                            2 -> writeMem32(addr, v)
                            else -> writeMem64(addr, v)
                        }
                    }
                    pc += 4
                    continue
                }
            }

            // LDUR / STUR, pre-index & post-index LDR / STR
            if ((insn and 0x3B200000) == 0x38000000 && ((insn ushr 10) and 3) != 2) {
                val sizeBits = (insn ushr 30) and 3
                val opc = (insn ushr 22) and 3
                val mode = (insn ushr 10) and 3
                val imm9 = signExtend((insn ushr 12) and 0x1FF, 9).toLong()
                val rn = (insn ushr 5) and 0x1F
                val rt = insn and 0x1F
                val isLoad = opc != 0
                val baseVal = if (rn == 31) sp else x[rn]
                val effectiveAddr = if (mode == 1) baseVal else baseVal + imm9
                if (isLoad) {
                    if (rt < 31) {
                        x[rt] = loadBySizeOpc(effectiveAddr, sizeBits, opc, ::readMem8, ::readMem16, ::readMem32, ::readMem64)
                    }
                } else {
                    val v = if (rt == 31) 0L else x[rt]
                    when (sizeBits) {
                        0 -> writeMem8(effectiveAddr, v)
                        1 -> writeMem16(effectiveAddr, v)
                        2 -> writeMem32(effectiveAddr, v)
                        else -> writeMem64(effectiveAddr, v)
                    }
                }
                if (mode == 1 || mode == 3) {
                    val updated = baseVal + imm9
                    if (rn == 31) sp = updated else x[rn] = updated
                }
                pc += 4
                continue
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
                val shift = if (s == 1) sizeBits else 0
                val idx = applyExtend(if (rm == 31) 0L else x[rm], option) shl shift
                val addr = (if (rn == 31) sp else x[rn]) + idx
                if (isLoad) {
                    if (rt < 31) {
                        x[rt] = loadBySizeOpc(addr, sizeBits, opc, ::readMem8, ::readMem16, ::readMem32, ::readMem64)
                    }
                } else {
                    val v = if (rt == 31) 0L else x[rt]
                    when (sizeBits) {
                        0 -> writeMem8(addr, v)
                        1 -> writeMem16(addr, v)
                        2 -> writeMem32(addr, v)
                        else -> writeMem64(addr, v)
                    }
                }
                pc += 4
                continue
            }

            // STP / LDP (32-bit or 64-bit)
            if ((insn and 0x7E000000) == 0x28000000) {
                val opc = (insn ushr 30) and 3
                val is64 = opc >= 2
                val scale = if (is64) 8L else 4L
                val isLoad = ((insn ushr 22) and 1) == 1
                val modeBits = (insn ushr 23) and 3
                val rt = insn and 0x1F
                val rn = (insn ushr 5) and 0x1F
                val rt2 = (insn ushr 10) and 0x1F
                val imm7 = signExtend((insn ushr 15) and 0x7F, 7) * scale
                val baseVal = if (rn == 31) sp else x[rn]
                val effectiveAddr = if (modeBits == 1) baseVal else baseVal + imm7
                if (isLoad) {
                    if (is64) {
                        if (rt < 31) x[rt] = readMem64(effectiveAddr)
                        if (rt2 < 31) x[rt2] = readMem64(effectiveAddr + 8)
                    } else {
                        if (rt < 31) x[rt] = readMem32(effectiveAddr).toLong() and 0xFFFF_FFFFL
                        if (rt2 < 31) x[rt2] = readMem32(effectiveAddr + 4).toLong() and 0xFFFF_FFFFL
                    }
                } else {
                    val v1 = if (rt == 31) 0L else x[rt]
                    val v2 = if (rt2 == 31) 0L else x[rt2]
                    if (is64) {
                        writeMem64(effectiveAddr, v1)
                        writeMem64(effectiveAddr + 8, v2)
                    } else {
                        writeMem32(effectiveAddr, v1)
                        writeMem32(effectiveAddr + 4, v2)
                    }
                }
                if (modeBits == 1 || modeBits == 3) {
                    val updatedBase = baseVal + imm7
                    if (rn == 31) sp = updatedBase else x[rn] = updatedBase
                }
                pc += 4
                continue
            }

            // BLR / BR indirect dispatch
            if ((insn and 0xFFFFFC1F.toInt()) == 0xD63F0000.toInt() ||
                (insn and 0xFFFFFC1F.toInt()) == 0xD61F0000.toInt()
            ) {
                val rn = (insn ushr 5) and 0x1F
                val target = if (rn < 31) x[rn] else 0L
                haltReason = "Salto indireto ${op.asm} (destino 0x${target.toULong().toString(16)})"
                logs.add("[VM] Parada: $haltReason")
                break
            }

            // Other instruction not implemented in sandbox VM
            haltReason = "Instrução não mapeada na VM (${op.asm} • 0x%08X)".format(insn)
            logs.add("[VM] Parada: $haltReason")
            break
        }

        val regsSnapshot = listOf(
            "X0" to "0x%016X".format(x[0]),
            "X1" to "0x%016X".format(x[1]),
            "X2" to "0x%016X".format(x[2]),
            "X3" to "0x%016X".format(x[3]),
            "SP" to "0x%016X".format(sp),
            "LR" to "0x%016X".format(x[30]),
            "PC" to "0x%016X".format(pc)
        )

        return RecompExecResult(
            entryFunction = "sub_%08x".format(entryOffset),
            stepsExecuted = steps,
            haltReason = haltReason,
            registers = regsSnapshot,
            traceLogs = logs
        )
    }

    private inline fun loadBySizeOpc(
        addr: Long,
        sizeBits: Int,
        opc: Int,
        read8: (Long) -> Int,
        read16: (Long) -> Int,
        read32: (Long) -> Int,
        read64: (Long) -> Long
    ): Long = when (sizeBits) {
        0 -> {
            val b = read8(addr)
            when (opc) {
                2 -> b.toByte().toLong()
                3 -> b.toByte().toLong() and 0xFFFF_FFFFL
                else -> b.toLong()
            }
        }
        1 -> {
            val h = read16(addr)
            when (opc) {
                2 -> h.toShort().toLong()
                3 -> h.toShort().toLong() and 0xFFFF_FFFFL
                else -> h.toLong()
            }
        }
        2 -> {
            val w = read32(addr)
            if (opc == 2) w.toLong() else (w.toLong() and 0xFFFF_FFFFL)
        }
        else -> read64(addr)
    }

    private fun computeAddSub(a: Long, b: Long, isSub: Boolean, is64: Boolean): Pair<Long, Int> {
        if (is64) {
            val res = if (isSub) a - b else a + b
            val n = if (res < 0L) 1 else 0
            val z = if (res == 0L) 1 else 0
            val c = if (isSub) (if (a.toULong() >= b.toULong()) 1 else 0) else (if (res.toULong() < a.toULong()) 1 else 0)
            val v = if (isSub) {
                if (((a xor b) and (a xor res)) < 0L) 1 else 0
            } else {
                if (((a xor b).inv() and (a xor res)) < 0L) 1 else 0
            }
            return res to ((n shl 31) or (z shl 30) or (c shl 29) or (v shl 28))
        } else {
            val a32 = a.toInt()
            val b32 = b.toInt()
            val res32 = if (isSub) a32 - b32 else a32 + b32
            val n = if (res32 < 0) 1 else 0
            val z = if (res32 == 0) 1 else 0
            val c = if (isSub) (if (a32.toUInt() >= b32.toUInt()) 1 else 0) else (if (res32.toUInt() < a32.toUInt()) 1 else 0)
            val v = if (isSub) {
                if (((a32 xor b32) and (a32 xor res32)) < 0) 1 else 0
            } else {
                if (((a32 xor b32).inv() and (a32 xor res32)) < 0) 1 else 0
            }
            return (res32.toLong() and 0xFFFF_FFFFL) to ((n shl 31) or (z shl 30) or (c shl 29) or (v shl 28))
        }
    }

    private fun applyShift(value: Long, shiftType: Int, amount: Int, is64: Boolean): Long {
        if (amount == 0) return if (is64) value else (value and 0xFFFF_FFFFL)
        return if (is64) {
            val amt = amount and 63
            when (shiftType) {
                0 -> value shl amt
                1 -> value ushr amt
                2 -> value shr amt
                else -> (value ushr amt) or (value shl (64 - amt))
            }
        } else {
            val v32 = value.toInt()
            val amt = amount and 31
            val r32 = when (shiftType) {
                0 -> v32 shl amt
                1 -> v32 ushr amt
                2 -> v32 shr amt
                else -> (v32 ushr amt) or (v32 shl (32 - amt))
            }
            r32.toLong() and 0xFFFF_FFFFL
        }
    }

    private fun applyExtend(value: Long, option: Int): Long = when (option) {
        0 -> (value and 0xFFL)
        1 -> (value and 0xFFFFL)
        2 -> (value and 0xFFFF_FFFFL)
        3 -> value
        4 -> value.toByte().toLong()
        5 -> value.toShort().toLong()
        6 -> value.toInt().toLong()
        else -> value
    }

    private fun evalSbfmUbfm(src: Long, isSigned: Boolean, immr: Int, imms: Int, width: Int): Long {
        val maskedSrc = if (width == 64) src else (src and 0xFFFF_FFFFL)
        return if (imms >= immr) {
            val bits = imms - immr + 1
            val mask = if (bits >= 64) -1L else (1L shl bits) - 1L
            val extracted = (maskedSrc ushr immr) and mask
            if (isSigned && bits < 64 && ((extracted ushr (bits - 1)) and 1L) == 1L) {
                extracted or mask.inv()
            } else {
                extracted
            }
        } else {
            val shift = width - immr
            val bits = imms + 1
            val mask = if (bits >= 64) -1L else (1L shl bits) - 1L
            var field = maskedSrc and mask
            if (isSigned && bits < 64 && ((field ushr (bits - 1)) and 1L) == 1L) {
                field = field or mask.inv()
            }
            field shl shift
        }
    }

    private fun evalBitfieldInsert(dst: Long, src: Long, immr: Int, imms: Int, width: Int): Long {
        return if (imms >= immr) {
            val bits = imms - immr + 1
            val mask = if (bits >= 64) -1L else (1L shl bits) - 1L
            val insertVal = (src ushr immr) and mask
            (dst and mask.inv()) or insertVal
        } else {
            val shift = width - immr
            val bits = imms + 1
            val mask = (if (bits >= 64) -1L else (1L shl bits) - 1L) shl shift
            val insertVal = (src shl shift) and mask
            (dst and mask.inv()) or insertVal
        }
    }

    private fun evalCond(cond: Int, nzcv: Int): Boolean {
        val n = (nzcv ushr 31) and 1 == 1
        val z = (nzcv ushr 30) and 1 == 1
        val c = (nzcv ushr 29) and 1 == 1
        val v = (nzcv ushr 28) and 1 == 1
        return when (cond) {
            0 -> z
            1 -> !z
            2 -> c
            3 -> !c
            4 -> n
            5 -> !n
            6 -> v
            7 -> !v
            8 -> c && !z
            9 -> !(c && !z)
            10 -> n == v
            11 -> n != v
            12 -> !z && (n == v)
            13 -> z || (n != v)
            14 -> true
            else -> false
        }
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    private val SWITCH_SVC_NAMES = mapOf(
        0x01 to "svcSetHeapSize",
        0x03 to "svcSetMemoryAttribute",
        0x06 to "svcQueryMemory",
        0x07 to "svcExitProcess",
        0x08 to "svcCreateThread",
        0x0B to "svcSleepThread",
        0x13 to "svcCreateTransferMemory",
        0x18 to "svcWaitSynchronization",
        0x1F to "svcConnectToNamedPort",
        0x21 to "svcSendSyncRequest",
        0x26 to "svcBreak",
        0x27 to "svcOutputDebugString",
        0x29 to "svcGetInfo"
    )
}
