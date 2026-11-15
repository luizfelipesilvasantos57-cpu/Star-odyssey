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
                haltReason = "PC fora de .text (0x${pc.toULong().toString(16)})"
                logs.add("[VM] Parada: $haltReason")
                break
            }

            val insn = textBuf.getInt(textOff)
            val op = Arm64StaticLifter.decodeArm64Instruction(insn, textOff)
            steps++
            if (logs.size < 64) {
                logs.add("0x%08X: %s".format(pc, op.asm))
            }

            // NOP
            if (insn == 0xD503201F.toInt()) {
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
                // Simulate ResultSuccess (0) in X0 only if we stopped at SVC
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
                val opc = (insn ushr 29) and 3
                val hw = (insn ushr 21) and 3
                val imm16 = ((insn ushr 5) and 0xFFFF).toLong()
                val rd = insn and 0x1F
                val shift = hw * 16
                if (rd < 31) {
                    x[rd] = when (opc) {
                        2 -> imm16 shl shift
                        3 -> (x[rd] and (0xFFFFL shl shift).inv()) or (imm16 shl shift)
                        else -> (imm16 shl shift).inv()
                    }
                }
                pc += 4
                continue
            }

            // ADD / SUB immediate
            if ((insn and 0x1F000000) == 0x11000000) {
                val isSub = ((insn ushr 30) and 1) == 1
                val setFlags = ((insn ushr 29) and 1) == 1
                val sh = (insn ushr 22) and 1
                val imm12 = (((insn ushr 10) and 0xFFF) shl (if (sh == 1) 12 else 0)).toLong()
                val rn = (insn ushr 5) and 0x1F
                val rd = insn and 0x1F
                val baseVal = if (rn == 31) sp else x[rn]
                val res = if (isSub) baseVal - imm12 else baseVal + imm12
                if (setFlags) {
                    nzcv = if (res == 0L) 0x4000_0000 else if (res < 0L) 0x8000_0000.toInt() else 0
                }
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

            // MOV register alias
            if ((insn and 0x7FE0FFE0) == 0x2A0003E0) {
                val rm = (insn ushr 16) and 0x1F
                val rd = insn and 0x1F
                if (rd < 31) x[rd] = if (rm == 31) 0L else x[rm]
                pc += 4
                continue
            }

            // LDR / STR unsigned offset
            if ((insn and 0x3B200000) == 0x39000000 && ((insn ushr 30) and 3) >= 2) {
                val sizeBits = (insn ushr 30) and 3
                val is64 = sizeBits == 3
                val isLoad = ((insn ushr 22) and 3) == 1
                val offset = ((insn ushr 10) and 0xFFF) shl sizeBits
                val rn = (insn ushr 5) and 0x1F
                val rt = insn and 0x1F
                val addr = (if (rn == 31) sp else x[rn]) + offset
                if (isLoad) {
                    if (rt < 31) x[rt] = if (is64) readMem64(addr) else (readMem32(addr).toLong() and 0xFFFF_FFFFL)
                } else {
                    val v = if (rt == 31) 0L else x[rt]
                    if (is64) writeMem64(addr, v) else writeMem32(addr, v)
                }
                pc += 4
                continue
            }

            // STP / LDP 64-bit pair
            if ((insn and 0x7E000000) == 0x28000000) {
                val isLoad = ((insn ushr 22) and 1) == 1
                val modeBits = (insn ushr 23) and 3
                val rt = insn and 0x1F
                val rn = (insn ushr 5) and 0x1F
                val rt2 = (insn ushr 10) and 0x1F
                val imm7 = (signExtend((insn ushr 15) and 0x7F, 7) * 8).toLong()
                val baseVal = if (rn == 31) sp else x[rn]
                val effectiveAddr = if (modeBits == 1) baseVal else baseVal + imm7
                if (isLoad) {
                    if (rt < 31) x[rt] = readMem64(effectiveAddr)
                    if (rt2 < 31) x[rt2] = readMem64(effectiveAddr + 8)
                } else {
                    writeMem64(effectiveAddr, if (rt == 31) 0L else x[rt])
                    writeMem64(effectiveAddr + 8, if (rt2 == 31) 0L else x[rt2])
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
