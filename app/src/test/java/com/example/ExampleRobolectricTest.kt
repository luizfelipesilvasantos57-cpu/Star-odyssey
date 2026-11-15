package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.Arm64StaticLifter
import com.example.core.Lz4BlockDecoder
import com.example.core.RecompExecutionEngine
import com.example.core.SwitchFormatParser
import com.example.core.Yaz0SarcParser
import com.example.model.DumpSourceType
import com.example.model.RecompConfig
import com.example.model.ResolutionScale
import com.example.model.TargetFramerate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Star Odyssey", appName)
        assertEquals("Recompilar Jogo", context.getString(R.string.btn_launch_runtime))
    }

    @Test
    fun `parse prod keys validates header and master keys`() {
        val sampleKeys = """
            # Switch keys
            header_key = 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            master_key_00 = 00112233445566778899aabbccddeeff
            key_area_key_application_00 = ffeeddccbbaa99887766554433221100
            titlekek_00 = 11223344556677889900aabbccddeeff
        """.trimIndent()

        val result = SwitchFormatParser.parseProdKeys(sampleKeys.byteInputStream(), "prod.keys")
        assertTrue(result.isLoaded)
        assertTrue(result.isSufficient)
        assertTrue(result.hasHeaderKey)
        assertEquals(4, result.totalKeys)
        assertEquals(1, result.masterKeyCount)
        assertEquals(1, result.keyAreaAppCount)
        assertEquals(1, result.titleKekCount)
    }

    @Test
    fun `parse synthetic pfs0 nsp header extracts entries and odyssey title id`() {
        val fileNameBytes = "0100000000010000.nca\u0000".toByteArray(Charsets.UTF_8)
        val totalHeader = 16 + 24 + fileNameBytes.size
        val buf = ByteBuffer.allocate(totalHeader).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PFS0".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(fileNameBytes.size)
        buf.putInt(0)
        // File entry
        buf.putLong(0L)
        buf.putLong(1024L * 1024L)
        buf.putInt(0)
        buf.putInt(0)
        buf.put(fileNameBytes)

        val report = SwitchFormatParser.inspectBinaryDump(
            buf.array().inputStream(),
            "odyssey.nsp",
            totalHeader.toLong() + 1024L * 1024L
        )

        assertTrue(report.magicValid)
        assertEquals(DumpSourceType.NSP_PFS0, report.sourceType)
        assertTrue(report.isOdysseyMatch)
        assertEquals("0100000000010000", report.titleIdDetected)
        assertEquals(1, report.entries.size)
        assertEquals("0100000000010000.nca", report.entries[0].name)
    }

    @Test
    fun `parse synthetic nso0 header extracts segments and build id`() {
        val buf = ByteBuffer.allocate(0x100).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("NSO0".toByteArray(Charsets.US_ASCII))
        buf.position(0x0C)
        buf.putInt(1) // LZ4 text flag
        buf.putInt(0x100) // text file offset
        buf.position(0x18)
        buf.putInt(4096) // text size
        buf.position(0x28)
        buf.putInt(1024) // rodata size
        buf.position(0x38)
        buf.putInt(512) // data size
        buf.putInt(256) // bss size
        buf.position(0x40)
        buf.put(ByteArray(32) { 0xAB.toByte() })

        val report = SwitchFormatParser.inspectBinaryDump(buf.array().inputStream(), "main", 8192L)
        assertTrue(report.magicValid)
        assertEquals(DumpSourceType.NSO0_EXECUTABLE, report.sourceType)
        assertEquals(4096L, report.nsoTextSize)
        assertEquals(1024L, report.nsoRodataSize)
        assertEquals(512L, report.nsoDataSize)
        assertEquals(256L, report.nsoBssSize)
        assertTrue(report.buildIdHex?.startsWith("abababab") == true)
    }

    @Test
    fun `auto detect file kind identifies zip mod prod keys and pfs0`() {
        val zipPrefix = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(
            SwitchFormatParser.DetectedFileKind.MOD_ZIP,
            SwitchFormatParser.detectFileKind("costume_mod.zip", zipPrefix)
        )

        val keysPrefix = "header_key = 00112233\n".toByteArray()
        assertEquals(
            SwitchFormatParser.DetectedFileKind.PROD_KEYS,
            SwitchFormatParser.detectFileKind("keys.txt", keysPrefix)
        )

        val pfsPrefix = "PFS0\u0001\u0000\u0000\u0000".toByteArray()
        assertEquals(
            SwitchFormatParser.DetectedFileKind.SWITCH_BINARY,
            SwitchFormatParser.detectFileKind("game.nsp", pfsPrefix)
        )
    }

    @Test
    fun `parse decrypted nca3 header reports valid nca content`() {
        val ncaBuf = ByteArray(512)
        "NCA3".toByteArray(Charsets.US_ASCII).copyInto(ncaBuf, 0)
        val report = SwitchFormatParser.inspectBinaryDump(ncaBuf.inputStream(), "content.nca", 4096L)
        assertTrue(report.magicValid)
        assertEquals(DumpSourceType.NCA_CONTENT, report.sourceType)
    }

    @Test
    fun `default recomp config has expected defaults and lasrecomp options`() {
        val cfg = RecompConfig()
        assertEquals(ResolutionScale.NATIVE_1080P, cfg.resolutionScale)
        assertEquals(TargetFramerate.FPS_60, cfg.targetFramerate)
        assertFalse(cfg.frameInterpolation)
        assertTrue(cfg.colorBandingFix)
        assertTrue(cfg.showTouchOverlay)
        assertTrue(cfg.recycleVulkanDescriptors)
        assertFalse(cfg.adrenoNoLrzFix)
        assertEquals(com.example.model.OdysseyGameVersion.V1_3_0, cfg.preferredGameVersion)
        assertTrue(com.example.model.UpscalerMode.entries.contains(com.example.model.UpscalerMode.SNAPDRAGON_SGSR_1))
    }

    @Test
    fun `persist on device build writes cpp zip and reloads build state`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.RecompRepository(context)
        val rawText = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0xD2800840.toInt())
            putInt(0xD65F03C0.toInt())
        }.array()
        val nsoHeader = ByteBuffer.allocate(0x100 + rawText.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NSO0".toByteArray(Charsets.US_ASCII))
            position(0x0C)
            putInt(0)
            putInt(0x100)
            putInt(0)
            putInt(rawText.size)
            position(0x100)
            put(rawText)
        }.array()
        val session = Arm64StaticLifter.loadAndAnalyzeNso0(nsoHeader.inputStream(), "main")
        val built = repo.persistOnDeviceBuild(session, "0100000000010000", "Super Mario Odyssey 1.3.0")
        assertTrue(built.isBuilt)
        assertTrue(java.io.File(built.buildDirPath, "odyssey_recomp_cpp.zip").exists())

        val reloaded = repo.loadPersistedBuildState()
        assertTrue(reloaded.isBuilt)
        assertEquals(session.functions.size, reloaded.discoveredFunctions)
    }

    @Test
    fun `arm64 static lifter decompresses lz4 nso0 and emits compilable cpp zip`() {
        // Real ARM64 instructions:
        // 0x00: MOVZ X0, #0x42   -> 0xD2800840
        // 0x04: BL +0x8 (0x0C)   -> 0x94000002
        // 0x08: RET              -> 0xD65F03C0
        // 0x0C: SVC #0x21        -> 0xD4000421
        // 0x10: RET              -> 0xD65F03C0
        val insns = intArrayOf(
            0xD2800840.toInt(),
            0x94000002.toInt(),
            0xD65F03C0.toInt(),
            0xD4000421.toInt(),
            0xD65F03C0.toInt()
        )
        val rawText = ByteBuffer.allocate(insns.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            insns.forEach { putInt(it) }
        }.array()
        val lz4Text = Lz4BlockDecoder.compressSimple(rawText)

        val nsoHeader = ByteBuffer.allocate(0x100 + lz4Text.size).order(ByteOrder.LITTLE_ENDIAN)
        nsoHeader.put("NSO0".toByteArray(Charsets.US_ASCII))
        nsoHeader.position(0x0C)
        nsoHeader.putInt(0x01) // .text is LZ4 compressed
        nsoHeader.putInt(0x100) // text file offset
        nsoHeader.putInt(0) // mem offset
        nsoHeader.putInt(rawText.size) // decompressed text size
        nsoHeader.position(0x60)
        nsoHeader.putInt(lz4Text.size) // compressed text size
        nsoHeader.position(0x100)
        nsoHeader.put(lz4Text)

        val session = Arm64StaticLifter.loadAndAnalyzeNso0(nsoHeader.array().inputStream(), "main")
        assertEquals(5, session.totalInstructions)
        assertEquals(5, session.decodedInstructions)
        assertEquals(1, session.svcCount)
        assertTrue(session.functions.any { it.name == "sub_00000000" })
        assertTrue(session.functions.any { it.name == "sub_0000000c" })

        val preview = Arm64StaticLifter.liftFunctionPreview(session.textBytes, session.functions[0])
        assertTrue(preview.any { it.asm.startsWith("MOVZ X0") && it.cpp.contains("0x42ULL") })
        assertTrue(preview.any { it.asm.contains("BL sub_0000000c") })

        val zipOut = ByteArrayOutputStream()
        Arm64StaticLifter.exportRecompiledProjectZip(session, "0100000000010000", "Odyssey", zipOut)
        val entries = mutableListOf<String>()
        ZipInputStream(zipOut.toByteArray().inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries.add(e.name)
                e = zis.nextEntry
            }
        }
        assertTrue("CMakeLists.txt" in entries)
        assertTrue("src/recomp_functions.cpp" in entries)
        assertTrue("include/recomp_runtime.h" in entries)

        val exec = RecompExecutionEngine.execute(session, 0)
        assertEquals("sub_00000000", exec.entryFunction)
        assertEquals(3, exec.stepsExecuted) // MOVZ -> BL -> SVC
        assertTrue(exec.haltReason.contains("svcSendSyncRequest"))
        assertTrue(exec.registers.any { it.first == "X0" && it.second == "0x0000000000000042" })
    }

    @Test
    fun `yaz0 sarc parser inspects synthetic sarc archive entries`() {
        val fileBytes = "YB\u0000\u0000demo".toByteArray(Charsets.US_ASCII)
        val nameBytes = "Stage.byml\u0000\u0000".toByteArray(Charsets.US_ASCII) // 12 bytes (4-aligned)
        val dataOffset = 0x14 + 0x0C + 16 + 0x08 + nameBytes.size
        val buf = ByteBuffer.allocate(dataOffset + fileBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("SARC".toByteArray(Charsets.US_ASCII))
        buf.putShort(0x14)
        buf.putShort(0xFEFF.toShort()) // LE BOM in little-endian short -> 0xFF 0xFE
        buf.putInt(dataOffset + fileBytes.size)
        buf.putInt(dataOffset)
        buf.putShort(0x0100)
        buf.putShort(0)

        // SFAT
        buf.put("SFAT".toByteArray(Charsets.US_ASCII))
        buf.putShort(0x0C)
        buf.putShort(1)
        buf.putInt(0x65)
        buf.putInt(0x12345678)
        buf.putInt(0x01000000) // has filename at word offset 0
        buf.putInt(0)
        buf.putInt(fileBytes.size)

        // SFNT
        buf.put("SFNT".toByteArray(Charsets.US_ASCII))
        buf.putShort(0x08)
        buf.putShort(0)
        buf.put(nameBytes)
        buf.put(fileBytes)

        val (report, _) = Yaz0SarcParser.inspectArchive(buf.array(), "Demo.szs")
        assertFalse(report.wasYaz0)
        assertEquals(1, report.entries.size)
        assertEquals("Stage.byml", report.entries[0].name)
        assertEquals("BYML", report.entries[0].formatTag)
    }

    @Test
    fun `arm64 lifter skips leading zero mod0 padding and executes extended opcodes`() {
        // 0x00: 0x00000000 (MOD0 padding - must be skipped!)
        // 0x04: B +0x4 -> jumps to 0x08 (0x14000001)
        // 0x08: MOVZ X0, #6 (0xD28000C0)
        // 0x0C: MOVZ X1, #7 (0xD28000E1)
        // 0x10: MUL X2, X0, X1 (0x9B017C02 -> X2 = 42)
        // 0x14: CMP X2, #42 (0xF100A85F -> Z=1)
        // 0x18: CSET X3, EQ (0x9A9F17E3 -> cond=NE(1), CSET X3, EQ -> X3 = 1)
        // 0x1C: ADD X0, X2, X3 (0x8B030040 -> X0 = 43)
        // 0x20: STRB W0, [SP] (0x390003E0)
        // 0x24: LDRB W4, [SP] (0x394003E4 -> X4 = 43)
        // 0x28: TBNZ W0, #0, +8 -> jumps to 0x30 (0x36000040? Wait TBNZ is 0x37000040)
        // 0x2C: MOVZ X0, #0 (skipped!)
        // 0x30: RET (0xD65F03C0)
        val insns = intArrayOf(
            0x00000000,
            0x14000001,
            0xD28000C0.toInt(),
            0xD28000E1.toInt(),
            0x9B017C02.toInt(),
            0xF100A85F.toInt(),
            0x9A9F17E3.toInt(),
            0x8B030040.toInt(),
            0x390003E0,
            0x394003E4,
            0x37000040,
            0xD2800000.toInt(),
            0xD65F03C0.toInt()
        )
        val rawText = ByteBuffer.allocate(insns.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            insns.forEach { putInt(it) }
        }.array()
        // Add a BL 0x08 at end or let 0x04 be unrecognized? Actually 0x00 is 0, so let's add a prologue at 0x08 via STP X29, X30, [SP, #-16]! or BL
        // Even better: add BL 0x08 at 0x34 so entry 0x08 is discovered cleanly!
        val fullText = ByteBuffer.allocate(rawText.size + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(rawText)
            // at 0x34: BL -0x2C -> offset 0x34 + (-0x2C) = 0x08 -> imm26 = -11 = 0x03FFFFF5 -> 0x97FFFFF5
            putInt(0x97FFFFF5.toInt())
            putInt(0xD65F03C0.toInt())
        }.array()

        val nsoHeader = ByteBuffer.allocate(0x100 + fullText.size).order(ByteOrder.LITTLE_ENDIAN)
        nsoHeader.put("NSO0".toByteArray(Charsets.US_ASCII))
        nsoHeader.position(0x0C)
        nsoHeader.putInt(0) // uncompressed
        nsoHeader.putInt(0x100) // text file offset
        nsoHeader.putInt(0) // mem offset
        nsoHeader.putInt(fullText.size)
        nsoHeader.position(0x100)
        nsoHeader.put(fullText)

        val session = Arm64StaticLifter.loadAndAnalyzeNso0(nsoHeader.array().inputStream(), "main")
        assertFalse("sub_00000000 must not exist when offset 0 is 0x00000000", session.functions.any { it.name == "sub_00000000" })
        assertTrue(session.functions.any { it.name == "sub_00000008" })

        val exec = RecompExecutionEngine.execute(session, 0x08)
        assertTrue(exec.haltReason.contains("Retorno normal"))
        assertTrue(exec.registers.any { it.first == "X0" && it.second == "0x000000000000002B" }) // 43 = 0x2B
        assertTrue(exec.registers.any { it.first == "X2" && it.second == "0x000000000000002A" }) // 42 = 0x2A
        assertTrue(exec.registers.any { it.first == "X3" && it.second == "0x0000000000000001" })
    }
}
