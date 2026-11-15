package com.example.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AspectRatioMode
import com.example.model.AudioBackend
import com.example.model.BuildStage
import com.example.model.ContainerEntry
import com.example.model.DumpSourceType
import com.example.model.GameDumpReport
import com.example.model.GraphicsBackend
import com.example.model.KeysValidationResult
import com.example.model.OdysseyGameVersion
import com.example.model.OnDeviceBuildState
import com.example.model.RecompConfig
import com.example.model.ResolutionScale
import com.example.model.RomfsMod
import com.example.model.SarcArchiveReport
import com.example.model.SarcEntry
import com.example.model.TargetFramerate
import com.example.model.UpscalerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

private val Context.recompDataStore: DataStore<Preferences> by preferencesDataStore(name = "powermoon_recomp_prefs")

class RecompRepository(private val context: Context) {

    private object Keys {
        val GRAPHICS_BACKEND = stringPreferencesKey("graphics_backend")
        val RESOLUTION_SCALE = stringPreferencesKey("resolution_scale")
        val TARGET_FPS = stringPreferencesKey("target_fps")
        val FRAME_INTERPOLATION = booleanPreferencesKey("frame_interpolation")
        val UPSCALER_MODE = stringPreferencesKey("upscaler_mode")
        val FSR_SHARPNESS = floatPreferencesKey("fsr_sharpness")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val COLOR_BANDING_FIX = booleanPreferencesKey("color_banding_fix")
        val PRECOMPILE_SHADERS = booleanPreferencesKey("precompile_shaders")
        val VSYNC_ENABLED = booleanPreferencesKey("vsync_enabled")
        val ANISOTROPIC_LEVEL = intPreferencesKey("anisotropic_level")
        val ADRENO_NOLRZ_FIX = booleanPreferencesKey("adreno_nolrz_fix")
        val RECYCLE_DESCRIPTORS = booleanPreferencesKey("recycle_descriptors")
        val PREFERRED_GAME_VERSION = stringPreferencesKey("preferred_game_version")
        val AUDIO_BACKEND = stringPreferencesKey("audio_backend")
        val MASTER_VOLUME = floatPreferencesKey("master_volume")
        val BGM_VOLUME = floatPreferencesKey("bgm_volume")
        val SFX_VOLUME = floatPreferencesKey("sfx_volume")
        val STICK_DEADZONE = floatPreferencesKey("stick_deadzone")
        val GYRO_ENABLED = booleanPreferencesKey("gyro_enabled")
        val GYRO_SENSITIVITY = floatPreferencesKey("gyro_sensitivity")
        val RUMBLE_ENABLED = booleanPreferencesKey("rumble_enabled")
        val TOUCH_SCALE = floatPreferencesKey("touch_scale")
        val TOUCH_OPACITY = floatPreferencesKey("touch_opacity")
        val SHOW_TOUCH_OVERLAY = booleanPreferencesKey("show_touch_overlay")
        val KEYS_JSON = stringPreferencesKey("keys_validation_json")
        val DUMP_JSON = stringPreferencesKey("dump_report_json")
        val MODS_JSON = stringPreferencesKey("mods_manifest_json")
    }

    private val recompRootDir: File by lazy {
        File(context.filesDir, "recomp").apply { mkdirs() }
    }
    private val keysDir: File by lazy { File(recompRootDir, "keys").apply { mkdirs() } }
    private val modsDir: File by lazy { File(recompRootDir, "mods").apply { mkdirs() } }
    private val odysseyBuildDir: File by lazy { File(recompRootDir, "odyssey_build").apply { mkdirs() } }
    private val configFile: File by lazy { File(recompRootDir, "recomp_config.json") }

    val configFlow: Flow<RecompConfig> = context.recompDataStore.data.map { prefs ->
        RecompConfig(
            graphicsBackend = enumValueOrDefault(prefs[Keys.GRAPHICS_BACKEND], GraphicsBackend.VULKAN_NATIVE),
            resolutionScale = enumValueOrDefault(prefs[Keys.RESOLUTION_SCALE], ResolutionScale.NATIVE_1080P),
            targetFramerate = enumValueOrDefault(prefs[Keys.TARGET_FPS], TargetFramerate.FPS_60),
            frameInterpolation = prefs[Keys.FRAME_INTERPOLATION] ?: false,
            upscalerMode = enumValueOrDefault(prefs[Keys.UPSCALER_MODE], UpscalerMode.AMD_FSR_1),
            fsrSharpness = prefs[Keys.FSR_SHARPNESS] ?: 0.65f,
            aspectRatio = enumValueOrDefault(prefs[Keys.ASPECT_RATIO], AspectRatioMode.RATIO_16_9),
            colorBandingFix = prefs[Keys.COLOR_BANDING_FIX] ?: true,
            precompileShaders = prefs[Keys.PRECOMPILE_SHADERS] ?: true,
            vsyncEnabled = prefs[Keys.VSYNC_ENABLED] ?: true,
            anisotropicLevel = prefs[Keys.ANISOTROPIC_LEVEL] ?: 8,
            adrenoNoLrzFix = prefs[Keys.ADRENO_NOLRZ_FIX] ?: false,
            recycleVulkanDescriptors = prefs[Keys.RECYCLE_DESCRIPTORS] ?: true,
            preferredGameVersion = enumValueOrDefault(prefs[Keys.PREFERRED_GAME_VERSION], OdysseyGameVersion.V1_3_0),
            audioBackend = enumValueOrDefault(prefs[Keys.AUDIO_BACKEND], AudioBackend.AAUDIO_LOW_LATENCY),
            masterVolume = prefs[Keys.MASTER_VOLUME] ?: 0.85f,
            bgmVolume = prefs[Keys.BGM_VOLUME] ?: 0.80f,
            sfxVolume = prefs[Keys.SFX_VOLUME] ?: 0.90f,
            stickDeadzone = prefs[Keys.STICK_DEADZONE] ?: 0.12f,
            gyroMotionCapEnabled = prefs[Keys.GYRO_ENABLED] ?: true,
            gyroSensitivity = prefs[Keys.GYRO_SENSITIVITY] ?: 1.0f,
            rumbleEnabled = prefs[Keys.RUMBLE_ENABLED] ?: true,
            touchOverlayScale = prefs[Keys.TOUCH_SCALE] ?: 1.0f,
            touchOverlayOpacity = prefs[Keys.TOUCH_OPACITY] ?: 0.78f,
            showTouchOverlay = prefs[Keys.SHOW_TOUCH_OVERLAY] ?: true
        )
    }

    val keysFlow: Flow<KeysValidationResult> = context.recompDataStore.data.map { prefs ->
        val raw = prefs[Keys.KEYS_JSON] ?: return@map KeysValidationResult()
        deserializeKeys(raw)
    }

    val dumpReportFlow: Flow<GameDumpReport?> = context.recompDataStore.data.map { prefs ->
        val raw = prefs[Keys.DUMP_JSON] ?: return@map null
        deserializeDump(raw)
    }

    val modsFlow: Flow<List<RomfsMod>> = context.recompDataStore.data.map { prefs ->
        val raw = prefs[Keys.MODS_JSON] ?: return@map emptyList()
        deserializeMods(raw).sortedBy { it.priority }
    }

    suspend fun saveConfig(config: RecompConfig) {
        context.recompDataStore.edit { prefs ->
            prefs[Keys.GRAPHICS_BACKEND] = config.graphicsBackend.name
            prefs[Keys.RESOLUTION_SCALE] = config.resolutionScale.name
            prefs[Keys.TARGET_FPS] = config.targetFramerate.name
            prefs[Keys.FRAME_INTERPOLATION] = config.frameInterpolation
            prefs[Keys.UPSCALER_MODE] = config.upscalerMode.name
            prefs[Keys.FSR_SHARPNESS] = config.fsrSharpness
            prefs[Keys.ASPECT_RATIO] = config.aspectRatio.name
            prefs[Keys.COLOR_BANDING_FIX] = config.colorBandingFix
            prefs[Keys.PRECOMPILE_SHADERS] = config.precompileShaders
            prefs[Keys.VSYNC_ENABLED] = config.vsyncEnabled
            prefs[Keys.ANISOTROPIC_LEVEL] = config.anisotropicLevel
            prefs[Keys.ADRENO_NOLRZ_FIX] = config.adrenoNoLrzFix
            prefs[Keys.RECYCLE_DESCRIPTORS] = config.recycleVulkanDescriptors
            prefs[Keys.PREFERRED_GAME_VERSION] = config.preferredGameVersion.name
            prefs[Keys.AUDIO_BACKEND] = config.audioBackend.name
            prefs[Keys.MASTER_VOLUME] = config.masterVolume
            prefs[Keys.BGM_VOLUME] = config.bgmVolume
            prefs[Keys.SFX_VOLUME] = config.sfxVolume
            prefs[Keys.STICK_DEADZONE] = config.stickDeadzone
            prefs[Keys.GYRO_ENABLED] = config.gyroMotionCapEnabled
            prefs[Keys.GYRO_SENSITIVITY] = config.gyroSensitivity
            prefs[Keys.RUMBLE_ENABLED] = config.rumbleEnabled
            prefs[Keys.TOUCH_SCALE] = config.touchOverlayScale
            prefs[Keys.TOUCH_OPACITY] = config.touchOverlayOpacity
            prefs[Keys.SHOW_TOUCH_OVERLAY] = config.showTouchOverlay
        }
        withContext(Dispatchers.IO) {
            configFile.writeText(buildConfigJson(config).toString(2))
        }
    }

    suspend fun importAutoDetectedFile(uri: Uri, currentMods: List<RomfsMod>): String = withContext(Dispatchers.IO) {
        val (name, _) = resolveNameAndSize(uri)
        val fileName = name ?: "arquivo"
        val prefix = ByteArray(1024)
        val readCount = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.read(prefix)
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
        val actualPrefix = if (readCount > 0) prefix.copyOf(readCount) else ByteArray(0)

        return@withContext when (SwitchFormatParser.detectFileKind(fileName, actualPrefix)) {
            SwitchFormatParser.DetectedFileKind.PROD_KEYS -> {
                val res = importProdKeys(uri)
                if (res.isSufficient) {
                    "Chaves validadas: ${res.totalKeys} entradas (${res.fileName})."
                } else {
                    "Chaves importadas com avisos (${res.totalKeys} entradas)."
                }
            }
            SwitchFormatParser.DetectedFileKind.MOD_ZIP -> {
                val mod = importModZip(uri, currentMods)
                "Mod RomFS '${mod.name}' instalado (${mod.fileCount} arquivos)."
            }
            SwitchFormatParser.DetectedFileKind.SWITCH_BINARY -> {
                val report = importBinaryDump(uri)
                report.summaryMessage.ifEmpty { report.magicLabel }
            }
        }
    }

    suspend fun saveScreenshotToUri(uri: Uri, bitmap: android.graphics.Bitmap) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IllegalStateException("Não foi possível salvar a captura PNG.")
    }

    fun buildConfigJsonPretty(config: RecompConfig): String = buildConfigJson(config).toString(2)

    suspend fun importProdKeys(uri: Uri): KeysValidationResult = withContext(Dispatchers.IO) {
        val fileName = resolveDisplayName(uri) ?: "prod.keys"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")

        val result = SwitchFormatParser.parseProdKeys(bytes.inputStream(), fileName)
        if (result.isLoaded) {
            File(keysDir, "prod.keys").writeBytes(bytes)
        }
        context.recompDataStore.edit { prefs ->
            prefs[Keys.KEYS_JSON] = serializeKeys(result)
        }
        result
    }

    suspend fun importBinaryDump(uri: Uri): GameDumpReport = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Temporary provider access only.
        }
        val (fileName, size) = resolveNameAndSize(uri)
        val rawReport = context.contentResolver.openInputStream(uri)?.use { stream ->
            SwitchFormatParser.inspectBinaryDump(stream, fileName ?: "dump.bin", size)
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo.")

        val report = if (rawReport.sourceType == DumpSourceType.NSO0_EXECUTABLE && rawReport.magicValid) {
            rawReport.copy(mainNsoUriString = uri.toString())
        } else {
            rawReport
        }

        context.recompDataStore.edit { prefs ->
            prefs[Keys.DUMP_JSON] = serializeDump(report)
        }
        report
    }

    suspend fun loadNsoForRecompile(uri: Uri): Arm64StaticLifter.LoadedNsoSession = withContext(Dispatchers.IO) {
        val (fileName, _) = resolveNameAndSize(uri)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            Arm64StaticLifter.loadAndAnalyzeNso0(stream, fileName ?: "main")
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo 'main' do ExeFS.")
    }

    suspend fun exportRecompiledZip(
        uri: Uri,
        session: Arm64StaticLifter.LoadedNsoSession,
        titleId: String?,
        programName: String?
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            Arm64StaticLifter.exportRecompiledProjectZip(session, titleId, programName, out)
        } ?: throw IllegalStateException("Não foi possível gravar o arquivo .zip recompilado.")
    }

    suspend fun persistOnDeviceBuild(
        session: Arm64StaticLifter.LoadedNsoSession,
        titleId: String?,
        programName: String?
    ): OnDeviceBuildState = withContext(Dispatchers.IO) {
        val archiveFile = File(odysseyBuildDir, "odyssey_recomp_cpp.zip")
        FileOutputStream(archiveFile).use { fos ->
            Arm64StaticLifter.exportRecompiledProjectZip(
                session = session,
                titleId = titleId,
                programName = programName,
                outStream = fos,
                maxFunctions = 1200
            )
        }
        val builtAt = System.currentTimeMillis()
        val manifestFile = File(odysseyBuildDir, "build_state.json")
        val json = JSONObject().apply {
            put("isBuilt", true)
            put("buildIdHex", session.buildIdHex)
            put("discoveredFunctions", session.functions.size)
            put("textBytes", session.textBytes.size)
            put("builtAtMillis", builtAt)
            put("programName", programName ?: "Super Mario Odyssey")
            put("titleId", titleId ?: "0100000000010000")
            put("detailMessage", "${session.functions.size} funções ARM64 → C++17 prontas (${SwitchFormatParser.formatByteSize(session.textBytes.size.toLong())})")
        }
        manifestFile.writeText(json.toString(2))
        OnDeviceBuildState(
            stage = BuildStage.READY,
            progress = 1f,
            detailMessage = json.getString("detailMessage"),
            isBuilt = true,
            buildDirPath = odysseyBuildDir.absolutePath,
            buildIdHex = session.buildIdHex,
            discoveredFunctions = session.functions.size,
            textBytes = session.textBytes.size,
            builtAtMillis = builtAt
        )
    }

    fun loadPersistedBuildState(): OnDeviceBuildState {
        val manifestFile = File(odysseyBuildDir, "build_state.json")
        if (!manifestFile.exists()) return OnDeviceBuildState()
        return try {
            val o = JSONObject(manifestFile.readText())
            OnDeviceBuildState(
                stage = if (o.optBoolean("isBuilt")) BuildStage.READY else BuildStage.IDLE,
                progress = if (o.optBoolean("isBuilt")) 1f else 0f,
                detailMessage = o.optString("detailMessage"),
                isBuilt = o.optBoolean("isBuilt"),
                buildDirPath = odysseyBuildDir.absolutePath,
                buildIdHex = o.optString("buildIdHex"),
                discoveredFunctions = o.optInt("discoveredFunctions"),
                textBytes = o.optInt("textBytes"),
                builtAtMillis = o.optLong("builtAtMillis")
            )
        } catch (_: Exception) {
            OnDeviceBuildState()
        }
    }

    suspend fun inspectSzsArchive(uri: Uri): Pair<SarcArchiveReport, ByteArray> = withContext(Dispatchers.IO) {
        val (fileName, _) = resolveNameAndSize(uri)
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Não foi possível ler o arquivo RomFS.")
        Yaz0SarcParser.inspectArchive(raw, fileName ?: "archive.szs")
    }

    suspend fun exportSarcEntry(uri: Uri, payload: ByteArray, entry: SarcEntry) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            val end = (entry.offset + entry.sizeBytes).coerceAtMost(payload.size)
            if (entry.offset in 0..end) {
                out.write(payload, entry.offset, end - entry.offset)
            }
        } ?: throw IllegalStateException("Não foi possível exportar a entrada SARC.")
    }

    suspend fun importRomfsTree(treeUri: Uri): GameDumpReport = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Document provider may only grant temporary access.
        }
        val name = resolveTreeName(treeUri)
        val report = SwitchFormatParser.scanRomfsTree(context, treeUri, name)
        context.recompDataStore.edit { prefs ->
            prefs[Keys.DUMP_JSON] = serializeDump(report)
        }
        report
    }

    suspend fun rescanTreeIfAccessible(treeUriString: String): GameDumpReport? = withContext(Dispatchers.IO) {
        return@withContext try {
            val uri = Uri.parse(treeUriString)
            val name = resolveTreeName(uri)
            val report = SwitchFormatParser.scanRomfsTree(context, uri, name)
            if (report.romfsFileCount > 0 || report.magicValid) {
                context.recompDataStore.edit { prefs ->
                    prefs[Keys.DUMP_JSON] = serializeDump(report)
                }
                report
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clearImportedDumps() = withContext(Dispatchers.IO) {
        File(keysDir, "prod.keys").delete()
        odysseyBuildDir.deleteRecursively()
        odysseyBuildDir.mkdirs()
        context.recompDataStore.edit { prefs ->
            prefs.remove(Keys.KEYS_JSON)
            prefs.remove(Keys.DUMP_JSON)
        }
    }

    suspend fun importModZip(uri: Uri, currentMods: List<RomfsMod>): RomfsMod = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "mod_${System.currentTimeMillis()}.zip"
        val modId = UUID.randomUUID().toString().take(8)
        val cleanName = displayName.removeSuffix(".zip").removeSuffix(".ZIP")
        val targetFolder = File(modsDir, modId).apply { mkdirs() }

        var count = 0
        var totalSize = 0L
        val categories = mutableSetOf<String>()

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zis ->
                val canonicalRoot = targetFolder.canonicalPath + File.separator
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(targetFolder, entry.name)
                        if (outFile.canonicalPath.startsWith(canonicalRoot)) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val copied = zis.copyTo(fos)
                                totalSize += copied
                            }
                            count++
                            detectModCategory(entry.name)?.let { categories.add(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalStateException("Falha ao abrir arquivo .zip.")

        if (count == 0) {
            targetFolder.deleteRecursively()
            throw IllegalStateException("O arquivo .zip está vazio ou não contém arquivos válidos.")
        }

        val mod = RomfsMod(
            id = modId,
            name = cleanName,
            folderName = modId,
            fileCount = count,
            totalSizeBytes = totalSize,
            categories = categories.ifEmpty { setOf("RomFS") }.toList().sorted(),
            enabled = true,
            priority = currentMods.size
        )
        saveModsList(currentMods + mod)
        mod
    }

    suspend fun importModFolder(treeUri: Uri, currentMods: List<RomfsMod>): RomfsMod = withContext(Dispatchers.IO) {
        val folderName = resolveTreeName(treeUri)
        val modId = UUID.randomUUID().toString().take(8)
        val targetFolder = File(modsDir, modId).apply { mkdirs() }

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var count = 0
        var totalSize = 0L
        val categories = mutableSetOf<String>()

        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootDocId to "")

        while (queue.isNotEmpty() && count < 1500) {
            val (docId, relPath) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val nextRel = if (relPath.isEmpty()) name else "$relPath/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        detectModCategory(nextRel)?.let { categories.add(it) }
                        queue.add(childId to nextRel)
                    } else {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val outFile = File(targetFolder, nextRel)
                        outFile.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(fileUri)?.use { input ->
                            FileOutputStream(outFile).use { output ->
                                totalSize += input.copyTo(output)
                            }
                        }
                        count++
                        detectModCategory(nextRel)?.let { categories.add(it) }
                    }
                }
            }
        }

        if (count == 0) {
            targetFolder.deleteRecursively()
            throw IllegalStateException("A pasta selecionada não possui arquivos de mod.")
        }

        val mod = RomfsMod(
            id = modId,
            name = folderName,
            folderName = modId,
            fileCount = count,
            totalSizeBytes = totalSize,
            categories = categories.ifEmpty { setOf("RomFS") }.toList().sorted(),
            enabled = true,
            priority = currentMods.size
        )
        saveModsList(currentMods + mod)
        mod
    }

    suspend fun toggleMod(modId: String, currentMods: List<RomfsMod>) {
        val updated = currentMods.map {
            if (it.id == modId) it.copy(enabled = !it.enabled) else it
        }
        saveModsList(updated)
    }

    suspend fun moveModPriority(modId: String, delta: Int, currentMods: List<RomfsMod>) {
        val idx = currentMods.indexOfFirst { it.id == modId }
        val target = idx + delta
        if (idx < 0 || target !in currentMods.indices) return
        val mutable = currentMods.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(target, item)
        saveModsList(mutable.mapIndexed { i, m -> m.copy(priority = i) })
    }

    suspend fun deleteMod(modId: String, currentMods: List<RomfsMod>) = withContext(Dispatchers.IO) {
        val target = currentMods.firstOrNull { it.id == modId }
        if (target != null) {
            File(modsDir, target.folderName).deleteRecursively()
        }
        val remaining = currentMods.filterNot { it.id == modId }
            .mapIndexed { i, m -> m.copy(priority = i) }
        saveModsList(remaining)
    }

    suspend fun exportConfigToUri(uri: Uri, config: RecompConfig) = withContext(Dispatchers.IO) {
        val json = buildConfigJson(config).toString(2)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Não foi possível gravar recomp_config.json.")
    }

    private suspend fun saveModsList(mods: List<RomfsMod>) {
        context.recompDataStore.edit { prefs ->
            prefs[Keys.MODS_JSON] = serializeMods(mods)
        }
    }

    private fun detectModCategory(path: String): String? {
        return when {
            path.contains("StageData", true) -> "StageData"
            path.contains("ObjectData", true) -> "ObjectData"
            path.contains("SoundData", true) -> "SoundData"
            path.contains("ShaderData", true) -> "ShaderData"
            path.contains("LocalizedData", true) -> "Texto/UI"
            path.contains("Mario", true) || path.contains("Cap", true) -> "Trajes"
            else -> null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun resolveNameAndSize(uri: Uri): Pair<String?, Long> {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                val size = if (!c.isNull(1)) c.getLong(1) else 0L
                return name to size
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') to 0L
    }

    private fun resolveTreeName(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "RomFS" }
    }

    private fun buildConfigJson(config: RecompConfig): JSONObject = JSONObject().apply {
        put("project", "star_odyssey_recomp_android")
        put("game_version", config.preferredGameVersion.badge)
        put("graphics", JSONObject().apply {
            put("backend", config.graphicsBackend.name)
            put("resolution_scale", config.resolutionScale.factor.toDouble())
            put("target_fps", config.targetFramerate.fps)
            put("frame_interpolation", config.frameInterpolation)
            put("upscaler", config.upscalerMode.name)
            put("fsr_sharpness", config.fsrSharpness.toDouble())
            put("aspect_ratio", config.aspectRatio.shortTag)
            put("color_banding_fix", config.colorBandingFix)
            put("precompile_shaders", config.precompileShaders)
            put("vsync", config.vsyncEnabled)
            put("anisotropic_filtering", config.anisotropicLevel)
            put("adreno_tu_debug_nolrz", config.adrenoNoLrzFix)
            put("recycle_vulkan_descriptors", config.recycleVulkanDescriptors)
        })
        put("audio", JSONObject().apply {
            put("backend", config.audioBackend.name)
            put("master_volume", config.masterVolume.toDouble())
            put("bgm_volume", config.bgmVolume.toDouble())
            put("sfx_volume", config.sfxVolume.toDouble())
        })
        put("controls", JSONObject().apply {
            put("stick_deadzone", config.stickDeadzone.toDouble())
            put("gyro_motion_cap", config.gyroMotionCapEnabled)
            put("gyro_sensitivity", config.gyroSensitivity.toDouble())
            put("rumble", config.rumbleEnabled)
            put("touch_scale", config.touchOverlayScale.toDouble())
            put("touch_opacity", config.touchOverlayOpacity.toDouble())
        })
    }

    private fun serializeKeys(k: KeysValidationResult): String = JSONObject().apply {
        put("isLoaded", k.isLoaded)
        put("fileName", k.fileName)
        put("totalKeys", k.totalKeys)
        put("hasHeaderKey", k.hasHeaderKey)
        put("masterKeyCount", k.masterKeyCount)
        put("keyAreaAppCount", k.keyAreaAppCount)
        put("titleKekCount", k.titleKekCount)
        put("sha256Short", k.sha256Short)
        put("issues", JSONArray(k.issues))
    }.toString()

    private fun deserializeKeys(raw: String): KeysValidationResult = try {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("issues") ?: JSONArray()
        val issues = (0 until arr.length()).map { arr.getString(it) }
        KeysValidationResult(
            isLoaded = o.optBoolean("isLoaded"),
            fileName = o.optString("fileName"),
            totalKeys = o.optInt("totalKeys"),
            hasHeaderKey = o.optBoolean("hasHeaderKey"),
            masterKeyCount = o.optInt("masterKeyCount"),
            keyAreaAppCount = o.optInt("keyAreaAppCount"),
            titleKekCount = o.optInt("titleKekCount"),
            sha256Short = o.optString("sha256Short"),
            issues = issues
        )
    } catch (_: Exception) {
        KeysValidationResult()
    }

    private fun serializeDump(d: GameDumpReport): String = JSONObject().apply {
        put("sourceType", d.sourceType.name)
        put("fileName", d.fileName)
        put("fileSizeBytes", d.fileSizeBytes)
        put("magicValid", d.magicValid)
        put("magicLabel", d.magicLabel)
        put("titleIdDetected", d.titleIdDetected ?: "")
        put("programName", d.programName ?: "")
        put("isOdysseyMatch", d.isOdysseyMatch)
        put("buildIdHex", d.buildIdHex ?: "")
        put("nsoTextSize", d.nsoTextSize)
        put("nsoRodataSize", d.nsoRodataSize)
        put("nsoDataSize", d.nsoDataSize)
        put("nsoBssSize", d.nsoBssSize)
        put("romfsFileCount", d.romfsFileCount)
        put("sha256Prefix", d.sha256Prefix)
        put("treeUriString", d.treeUriString ?: "")
        put("mainNsoUriString", d.mainNsoUriString ?: "")
        put("verifiedAtMillis", d.verifiedAtMillis)
        put("summaryMessage", d.summaryMessage)
        put("romfsFoldersFound", JSONArray(d.romfsFoldersFound))
        put("entries", JSONArray().apply {
            d.entries.take(40).forEach { e ->
                put(JSONObject().apply {
                    put("name", e.name)
                    put("offset", e.offset)
                    put("sizeBytes", e.sizeBytes)
                    put("partition", e.partition)
                })
            }
        })
    }.toString()

    private fun deserializeDump(raw: String): GameDumpReport? = try {
        val o = JSONObject(raw)
        val dirsArr = o.optJSONArray("romfsFoldersFound") ?: JSONArray()
        val dirs = (0 until dirsArr.length()).map { dirsArr.getString(it) }
        val entriesArr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until entriesArr.length()).map { i ->
            val eo = entriesArr.getJSONObject(i)
            ContainerEntry(
                name = eo.optString("name"),
                offset = eo.optLong("offset"),
                sizeBytes = eo.optLong("sizeBytes"),
                partition = eo.optString("partition", "root")
            )
        }
        GameDumpReport(
            sourceType = enumValueOrDefault(o.optString("sourceType"), DumpSourceType.NSP_PFS0),
            fileName = o.optString("fileName"),
            fileSizeBytes = o.optLong("fileSizeBytes"),
            magicValid = o.optBoolean("magicValid"),
            magicLabel = o.optString("magicLabel"),
            titleIdDetected = o.optString("titleIdDetected").ifEmpty { null },
            programName = o.optString("programName").ifEmpty { null },
            isOdysseyMatch = o.optBoolean("isOdysseyMatch"),
            buildIdHex = o.optString("buildIdHex").ifEmpty { null },
            nsoTextSize = o.optLong("nsoTextSize"),
            nsoRodataSize = o.optLong("nsoRodataSize"),
            nsoDataSize = o.optLong("nsoDataSize"),
            nsoBssSize = o.optLong("nsoBssSize"),
            entries = entries,
            romfsFoldersFound = dirs,
            romfsFileCount = o.optInt("romfsFileCount"),
            sha256Prefix = o.optString("sha256Prefix"),
            treeUriString = o.optString("treeUriString").ifEmpty { null },
            mainNsoUriString = o.optString("mainNsoUriString").ifEmpty { null },
            verifiedAtMillis = o.optLong("verifiedAtMillis"),
            summaryMessage = o.optString("summaryMessage")
        )
    } catch (_: Exception) {
        null
    }

    private fun serializeMods(mods: List<RomfsMod>): String = JSONArray().apply {
        mods.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("folderName", m.folderName)
                put("fileCount", m.fileCount)
                put("totalSizeBytes", m.totalSizeBytes)
                put("categories", JSONArray(m.categories))
                put("enabled", m.enabled)
                put("priority", m.priority)
                put("importedAtMillis", m.importedAtMillis)
            })
        }
    }.toString()

    private fun deserializeMods(raw: String): List<RomfsMod> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cats = o.optJSONArray("categories") ?: JSONArray()
            RomfsMod(
                id = o.optString("id"),
                name = o.optString("name"),
                folderName = o.optString("folderName"),
                fileCount = o.optInt("fileCount"),
                totalSizeBytes = o.optLong("totalSizeBytes"),
                categories = (0 until cats.length()).map { cats.getString(it) },
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", i),
                importedAtMillis = o.optLong("importedAtMillis")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (_: Exception) {
            default
        }
    }
}
