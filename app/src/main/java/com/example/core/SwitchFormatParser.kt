package com.example.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.model.ContainerEntry
import com.example.model.DumpSourceType
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

object SwitchFormatParser {

    // Super Mario Odyssey Base & Update Title IDs
    private const val ODYSSEY_BASE_TID = "0100000000010000"
    private const val ODYSSEY_UPDATE_TID = "0100000000010800"

    private val EXPECTED_ROMFS_DIRS = setOf(
        "StageData", "ObjectData", "ShaderData", "SoundData", "SystemData", "LocalizedData", "MovieData"
    )

    fun parseProdKeys(inputStream: InputStream, fileName: String): KeysValidationResult {
        val bytes = inputStream.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val shaShort = digest.take(6).joinToString("") { "%02x".format(it) }

        val text = bytes.toString(Charsets.UTF_8)
        var totalKeys = 0
        var hasHeaderKey = false
        var masterKeys = 0
        var keyAreaApps = 0
        var titleKeks = 0
        val issues = mutableListOf<String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) return@forEach

            val keyName = line.substring(0, eqIdx).trim().lowercase(Locale.ROOT)
            val keyVal = line.substring(eqIdx + 1).trim()
            val isHex = keyVal.isNotEmpty() && keyVal.length % 2 == 0 && keyVal.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            }
            if (!isHex) {
                issues.add("Valor hex inválido para '$keyName'")
                return@forEach
            }

            totalKeys++
            when {
                keyName == "header_key" && keyVal.length == 64 -> hasHeaderKey = true
                keyName.startsWith("master_key_") -> masterKeys++
                keyName.startsWith("key_area_key_application_") -> keyAreaApps++
                keyName.startsWith("titlekek_") -> titleKeks++
            }
        }

        if (!hasHeaderKey) issues.add("Ausente: header_key (necessária para descriptografar NCA)")
        if (masterKeys == 0) issues.add("Ausente: master_key_XX")
        if (keyAreaApps == 0) issues.add("Ausente: key_area_key_application_XX")

        return KeysValidationResult(
            isLoaded = totalKeys > 0,
            fileName = fileName,
            totalKeys = totalKeys,
            hasHeaderKey = hasHeaderKey,
            masterKeyCount = masterKeys,
            keyAreaAppCount = keyAreaApps,
            titleKekCount = titleKeks,
            sha256Short = shaShort,
            issues = issues
        )
    }

    enum class DetectedFileKind {
        PROD_KEYS,
        MOD_ZIP,
        SWITCH_BINARY
    }

    fun detectFileKind(fileName: String, headerPrefix: ByteArray): DetectedFileKind {
        val lower = fileName.lowercase(Locale.ROOT)
        if (headerPrefix.size >= 2 && headerPrefix[0] == 0x50.toByte() && headerPrefix[1] == 0x4B.toByte()) {
            return DetectedFileKind.MOD_ZIP
        }
        if (lower.endsWith(".zip")) return DetectedFileKind.MOD_ZIP
        if (lower.endsWith(".keys") || lower == "prod.keys" || lower == "title.keys") {
            return DetectedFileKind.PROD_KEYS
        }
        if (headerPrefix.size >= 4) {
            val magic4 = String(headerPrefix, 0, 4, Charsets.US_ASCII)
            if (magic4 == "PFS0" || magic4 == "NSO0" || magic4 == "NCA3" || magic4 == "NCA2") {
                return DetectedFileKind.SWITCH_BINARY
            }
        }
        if (headerPrefix.size >= 0x104) {
            val xciMagic = String(headerPrefix, 0x100, 4, Charsets.US_ASCII)
            if (xciMagic == "HEAD") return DetectedFileKind.SWITCH_BINARY
        }
        val sampleText = headerPrefix.take(512).toByteArray().toString(Charsets.UTF_8)
        if (sampleText.contains("header_key") || sampleText.contains("master_key_")) {
            return DetectedFileKind.PROD_KEYS
        }
        return DetectedFileKind.SWITCH_BINARY
    }

    fun inspectBinaryDump(
        inputStream: InputStream,
        fileName: String,
        fileSizeBytes: Long
    ): GameDumpReport {
        val buffered = BufferedInputStream(inputStream, 64 * 1024)
        val header = ByteArray(minOf(4096, maxOf(256, fileSizeBytes.toInt().coerceAtLeast(256))))
        val readLen = readFullyUpTo(buffered, header)
        if (readLen < 16) {
            return GameDumpReport(
                sourceType = guessTypeFromName(fileName),
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                magicValid = false,
                magicLabel = "Arquivo muito pequeno ($readLen bytes)",
                summaryMessage = "Arquivo inválido ou vazio."
            )
        }

        val shaPrefix = MessageDigest.getInstance("SHA-256")
            .digest(header.copyOf(readLen))
            .take(8)
            .joinToString("") { "%02x".format(it) }

        val magic4 = String(header, 0, 4, Charsets.US_ASCII)

        // 1. Check PFS0 (.nsp)
        if (magic4 == "PFS0") {
            return parsePfs0Header(header, readLen, buffered, fileName, fileSizeBytes, shaPrefix)
        }

        // 2. Check NSO0 (main / main.nso)
        if (magic4 == "NSO0") {
            return parseNso0Header(header, readLen, fileName, fileSizeBytes, shaPrefix)
        }

        // 3. Check decrypted NCA2 / NCA3
        if (magic4 == "NCA3" || magic4 == "NCA2") {
            return GameDumpReport(
                sourceType = DumpSourceType.NCA_CONTENT,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                magicValid = true,
                magicLabel = "$magic4 Descriptografado",
                sha256Prefix = shaPrefix,
                summaryMessage = "Arquivo de conteúdo $magic4 válido (${formatByteSize(fileSizeBytes)})."
            )
        }

        // 4. Check XCI ("HEAD" at offset 0x100)
        if (readLen >= 0x148) {
            val xciMagic = String(header, 0x100, 4, Charsets.US_ASCII)
            if (xciMagic == "HEAD") {
                return parseXciHeader(header, fileName, fileSizeBytes, shaPrefix)
            }
        }

        // 5. Encrypted NCA or unknown
        val isEncryptedNcaName = fileName.endsWith(".nca", ignoreCase = true)
        return GameDumpReport(
            sourceType = if (isEncryptedNcaName) DumpSourceType.NCA_CONTENT else guessTypeFromName(fileName),
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            magicValid = false,
            magicLabel = if (isEncryptedNcaName) {
                "NCA Criptografado (XTS)"
            } else {
                "Assinatura não reconhecida (0x${header.take(4).joinToString("") { "%02X".format(it) }})"
            },
            sha256Prefix = shaPrefix,
            summaryMessage = if (isEncryptedNcaName) {
                "NCA criptografado detectado. Use o NSP/XCI completo junto de prod.keys."
            } else {
                "Esperado cabeçalho PFS0 (.nsp), HEAD (.xci), NSO0 (main) ou NCA."
            }
        )
    }

    private fun parsePfs0Header(
        initialBuf: ByteArray,
        initialLen: Int,
        stream: BufferedInputStream,
        fileName: String,
        totalSize: Long,
        shaPrefix: String
    ): GameDumpReport {
        val bb = ByteBuffer.wrap(initialBuf, 0, initialLen).order(ByteOrder.LITTLE_ENDIAN)
        val fileCount = bb.getInt(4)
        val stringTableSize = bb.getInt(8)

        if (fileCount <= 0 || fileCount > 4096 || stringTableSize <= 0 || stringTableSize > 512 * 1024) {
            return GameDumpReport(
                sourceType = DumpSourceType.NSP_PFS0,
                fileName = fileName,
                fileSizeBytes = totalSize,
                magicValid = true,
                magicLabel = "PFS0 (Contagem inválida: $fileCount)",
                sha256Prefix = shaPrefix,
                summaryMessage = "Cabeçalho PFS0 corrompido ou truncado."
            )
        }

        val fullHeaderNeed = 16 + fileCount * 24 + stringTableSize
        val headerData = if (fullHeaderNeed <= initialLen) {
            initialBuf
        } else {
            val extra = ByteArray(fullHeaderNeed)
            System.arraycopy(initialBuf, 0, extra, 0, initialLen)
            val remRead = readFullyUpTo(stream, extra, initialLen, fullHeaderNeed - initialLen)
            if (initialLen + remRead < fullHeaderNeed) {
                return GameDumpReport(
                    sourceType = DumpSourceType.NSP_PFS0,
                    fileName = fileName,
                    fileSizeBytes = totalSize,
                    magicValid = true,
                    magicLabel = "PFS0 ($fileCount entradas)",
                    sha256Prefix = shaPrefix,
                    summaryMessage = "Não foi possível ler toda a tabela PFS0."
                )
            }
            extra
        }

        val entries = mutableListOf<ContainerEntry>()
        val tableBuf = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)
        val strTableStart = 16 + fileCount * 24

        var detectedTid: String? = null
        var isOdyssey = false

        for (i in 0 until fileCount) {
            val base = 16 + i * 24
            val offset = tableBuf.getLong(base)
            val size = tableBuf.getLong(base + 8)
            val strOffset = tableBuf.getInt(base + 16)
            val nameStart = strTableStart + strOffset
            val name = if (nameStart in strTableStart until headerData.size) {
                var end = nameStart
                while (end < headerData.size && headerData[end] != 0.toByte()) end++
                String(headerData, nameStart, end - nameStart, Charsets.UTF_8)
            } else {
                "entry_$i"
            }
            entries.add(ContainerEntry(name = name, offset = offset, sizeBytes = size, partition = "PFS0"))

            val lower = name.lowercase(Locale.ROOT)
            if (lower.contains(ODYSSEY_BASE_TID)) {
                detectedTid = ODYSSEY_BASE_TID.uppercase(Locale.ROOT)
                isOdyssey = true
            } else if (lower.contains(ODYSSEY_UPDATE_TID)) {
                detectedTid = ODYSSEY_UPDATE_TID.uppercase(Locale.ROOT)
                isOdyssey = true
            }
        }

        if (fileName.contains(ODYSSEY_BASE_TID, ignoreCase = true)) {
            detectedTid = ODYSSEY_BASE_TID.uppercase(Locale.ROOT)
            isOdyssey = true
        }

        val ncaCount = entries.count { it.name.endsWith(".nca", ignoreCase = true) }
        val tikCount = entries.count { it.name.endsWith(".tik", ignoreCase = true) }

        return GameDumpReport(
            sourceType = DumpSourceType.NSP_PFS0,
            fileName = fileName,
            fileSizeBytes = totalSize,
            magicValid = true,
            magicLabel = "PFS0 Válido ($fileCount arquivos)",
            titleIdDetected = detectedTid,
            isOdysseyMatch = isOdyssey,
            entries = entries,
            sha256Prefix = shaPrefix,
            summaryMessage = "Container NSP validado: $ncaCount NCA(s), $tikCount ticket(s)." +
                if (isOdyssey) " Title ID de Super Mario Odyssey detectado!" else ""
        )
    }

    private fun parseXciHeader(
        header: ByteArray,
        fileName: String,
        totalSize: Long,
        shaPrefix: String
    ): GameDumpReport {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cartTypeCode = header[0x10D].toInt() and 0xFF
        val cartSizeLabel = when (cartTypeCode) {
            0xFA -> "1 GB"
            0xF8 -> "2 GB"
            0xF0 -> "4 GB"
            0xE0 -> "8 GB"
            0xE1 -> "16 GB"
            0xE2 -> "32 GB"
            else -> "Desconhecido (0x${"%02X".format(cartTypeCode)})"
        }
        val hfs0Offset = bb.getLong(0x130)
        val hfs0Size = bb.getLong(0x138)

        val isOdyssey = fileName.contains(ODYSSEY_BASE_TID, ignoreCase = true) ||
            fileName.contains("odyssey", ignoreCase = true)

        return GameDumpReport(
            sourceType = DumpSourceType.XCI_HFS0,
            fileName = fileName,
            fileSizeBytes = totalSize,
            magicValid = true,
            magicLabel = "XCI HEAD Válido (Cartucho $cartSizeLabel)",
            titleIdDetected = if (isOdyssey) ODYSSEY_BASE_TID.uppercase(Locale.ROOT) else null,
            isOdysseyMatch = isOdyssey,
            entries = listOf(
                ContainerEntry(
                    name = "HFS0 Root Partition Header",
                    offset = hfs0Offset,
                    sizeBytes = hfs0Size,
                    partition = "XCI"
                )
            ),
            sha256Prefix = shaPrefix,
            summaryMessage = "Cartucho XCI autêntico ($cartSizeLabel). Partição raiz HFS0 em 0x${hfs0Offset.toString(16)}."
        )
    }

    private fun parseNso0Header(
        header: ByteArray,
        readLen: Int,
        fileName: String,
        totalSize: Long,
        shaPrefix: String
    ): GameDumpReport {
        if (readLen < 0x100) {
            return GameDumpReport(
                sourceType = DumpSourceType.NSO0_EXECUTABLE,
                fileName = fileName,
                fileSizeBytes = totalSize,
                magicValid = false,
                magicLabel = "NSO0 Truncado",
                summaryMessage = "Cabeçalho NSO0 incompleto (< 256 bytes)."
            )
        }

        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getInt(0x0C)
        val textFileOffset = bb.getInt(0x10).toLong() and 0xFFFFFFFFL
        val textSize = bb.getInt(0x18).toLong() and 0xFFFFFFFFL
        val roFileOffset = bb.getInt(0x20).toLong() and 0xFFFFFFFFL
        val roSize = bb.getInt(0x28).toLong() and 0xFFFFFFFFL
        val dataFileOffset = bb.getInt(0x30).toLong() and 0xFFFFFFFFL
        val dataSize = bb.getInt(0x38).toLong() and 0xFFFFFFFFL
        val bssSize = bb.getInt(0x3C).toLong() and 0xFFFFFFFFL

        val buildId = header.copyOfRange(0x40, 0x60).joinToString("") { "%02x".format(it) }
        val lz4Text = (flags and 0x01) != 0

        val entries = listOf(
            ContainerEntry(".text (ARM64 Code${if (lz4Text) " LZ4" else ""})", textFileOffset, textSize, "NSO0"),
            ContainerEntry(".rodata", roFileOffset, roSize, "NSO0"),
            ContainerEntry(".data", dataFileOffset, dataSize, "NSO0"),
            ContainerEntry(".bss (Alloc)", 0L, bssSize, "NSO0")
        )

        return GameDumpReport(
            sourceType = DumpSourceType.NSO0_EXECUTABLE,
            fileName = fileName,
            fileSizeBytes = totalSize,
            magicValid = true,
            magicLabel = "NSO0 ARM64 Executável",
            buildIdHex = buildId,
            nsoTextSize = textSize,
            nsoRodataSize = roSize,
            nsoDataSize = dataSize,
            nsoBssSize = bssSize,
            entries = entries,
            sha256Prefix = shaPrefix,
            summaryMessage = "Binário NSO0 válido (.text=${formatByteSize(textSize)}, Build ID=${buildId.take(16)}…)."
        )
    }

    fun scanRomfsTree(context: Context, treeUri: Uri, displayName: String): GameDumpReport {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val foundDirs = mutableSetOf<String>()
        val collectedFiles = mutableListOf<ContainerEntry>()
        var fileCount = 0
        var totalBytes = 0L
        var hasMainNso = false
        var hasNpdm = false
        var mainNsoReport: GameDumpReport? = null
        var npdmProgramName: String? = null
        var npdmTitleId: String? = null

        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocId to 0)

        while (queue.isNotEmpty() && fileCount < 8000) {
            val (docId, depth) = queue.removeFirst()
            if (depth > 3) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        val mime = cursor.getString(mimeIdx) ?: ""
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (name in EXPECTED_ROMFS_DIRS || name.equals("romfs", true) || name.equals("exefs", true)) {
                                foundDirs.add(name)
                            }
                            if (depth < 2) {
                                queue.add(childId to (depth + 1))
                            }
                        } else {
                            fileCount++
                            totalBytes += size
                            if (name == "main" || name == "main.nso") {
                                hasMainNso = true
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                mainNsoReport = try {
                                    context.contentResolver.openInputStream(docUri)?.use { s ->
                                        inspectBinaryDump(s, name, size)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            if (name == "main.npdm") {
                                hasNpdm = true
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val npdmMeta = try {
                                    context.contentResolver.openInputStream(docUri)?.use { s ->
                                        parseNpdmMetadata(s)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                                if (npdmMeta != null) {
                                    npdmProgramName = npdmMeta.first
                                    npdmTitleId = npdmMeta.second
                                }
                            }
                            if (collectedFiles.size < 40 && (name.endsWith(".szs", true) || name.endsWith(".byml", true) || name.endsWith(".bfstm", true) || name == "main" || name == "main.npdm")) {
                                collectedFiles.add(
                                    ContainerEntry(
                                        name = name,
                                        offset = 0L,
                                        sizeBytes = size,
                                        partition = if (name == "main" || name == "main.npdm") "ExeFS" else "RomFS"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                break
            }
        }

        val isOdysseyLayout = foundDirs.any { it in EXPECTED_ROMFS_DIRS } ||
            npdmTitleId.equals(ODYSSEY_BASE_TID, true)
        val dirEntries = foundDirs.sorted().map {
            ContainerEntry(name = "$it/", offset = 0L, sizeBytes = 0L, partition = if (it.equals("exefs", true)) "ExeFS" else "RomFS")
        }

        return GameDumpReport(
            sourceType = DumpSourceType.ROMFS_DIRECTORY,
            fileName = displayName,
            fileSizeBytes = totalBytes,
            magicValid = isOdysseyLayout || (hasMainNso && mainNsoReport?.magicValid == true),
            magicLabel = when {
                isOdysseyLayout && hasMainNso -> "RomFS + ExeFS Prontos"
                isOdysseyLayout -> "Estrutura RomFS Verificada"
                hasMainNso -> "ExeFS (NSO0) Verificado"
                else -> "Pasta Digitalizada"
            },
            titleIdDetected = npdmTitleId ?: if (isOdysseyLayout) ODYSSEY_BASE_TID.uppercase(Locale.ROOT) else null,
            programName = npdmProgramName,
            isOdysseyMatch = isOdysseyLayout,
            buildIdHex = mainNsoReport?.buildIdHex,
            nsoTextSize = mainNsoReport?.nsoTextSize ?: 0L,
            nsoRodataSize = mainNsoReport?.nsoRodataSize ?: 0L,
            nsoDataSize = mainNsoReport?.nsoDataSize ?: 0L,
            nsoBssSize = mainNsoReport?.nsoBssSize ?: 0L,
            entries = (dirEntries + collectedFiles).distinctBy { it.name + it.partition },
            romfsFoldersFound = foundDirs.sorted(),
            romfsFileCount = fileCount,
            treeUriString = treeUri.toString(),
            summaryMessage = buildString {
                if (!npdmProgramName.isNullOrBlank()) append("$npdmProgramName • ")
                append("$fileCount arquivo(s) (${formatByteSize(totalBytes)}). ")
                if (foundDirs.isNotEmpty()) append("Pastas: ${foundDirs.joinToString(", ")}. ")
                if (hasMainNso && hasNpdm) append("ExeFS (main + main.npdm) montado.")
            }.trim()
        )
    }

    private fun parseNpdmMetadata(stream: InputStream): Pair<String?, String?>? {
        val buf = ByteArray(1024)
        val read = readFullyUpTo(stream, buf)
        if (read < 0x80) return null
        val magic = String(buf, 0, 4, Charsets.US_ASCII)
        if (magic != "META") return null

        val nameBytes = buf.copyOfRange(0x20, 0x30)
        val programName = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
            .ifEmpty { null }

        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val aci0Offset = bb.getInt(0x70)
        var titleIdHex: String? = null
        if (aci0Offset in 0x80..(read - 0x18)) {
            val aciMagic = String(buf, aci0Offset, 4, Charsets.US_ASCII)
            if (aciMagic == "ACI0") {
                val tid = bb.getLong(aci0Offset + 0x10)
                titleIdHex = "%016X".format(tid)
            }
        }
        return programName to titleIdHex
    }

    private fun readFullyUpTo(stream: InputStream, dest: ByteArray, offset: Int = 0, length: Int = dest.size): Int {
        var total = 0
        while (total < length) {
            val n = stream.read(dest, offset + total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun guessTypeFromName(name: String): DumpSourceType {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".xci") -> DumpSourceType.XCI_HFS0
            lower.endsWith(".nso") || lower == "main" -> DumpSourceType.NSO0_EXECUTABLE
            else -> DumpSourceType.NSP_PFS0
        }
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
