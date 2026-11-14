package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.SwitchFormatParser
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Star Odyssey", appName)
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
    fun `default recomp config has expected defaults`() {
        val cfg = RecompConfig()
        assertEquals(ResolutionScale.NATIVE_1080P, cfg.resolutionScale)
        assertEquals(TargetFramerate.FPS_60, cfg.targetFramerate)
        assertFalse(cfg.frameInterpolation)
        assertTrue(cfg.colorBandingFix)
        assertTrue(cfg.showTouchOverlay)
    }
}
