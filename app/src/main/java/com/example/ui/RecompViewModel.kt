package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.Arm64StaticLifter
import com.example.core.AudioSynthEngine
import com.example.core.HardwareProbe
import com.example.core.RecompExecutionEngine
import com.example.core.RecompRepository
import com.example.core.ShaderCacheManager
import com.example.model.AspectRatioMode
import com.example.model.BuildStage
import com.example.model.DeviceCapabilities
import com.example.model.DumpSourceType
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
import com.example.model.LiftedFunctionMeta
import com.example.model.LiveGamepadState
import com.example.model.OdysseyGameVersion
import com.example.model.OnDeviceBuildState
import com.example.model.QualityPreset
import com.example.model.RecompConfig
import com.example.model.RecompTab
import com.example.model.RecompileSessionState
import com.example.model.ResolutionScale
import com.example.model.RomfsMod
import com.example.model.SarcArchiveReport
import com.example.model.SarcEntry
import com.example.model.ShaderCacheReport
import com.example.model.TargetFramerate
import com.example.model.UpscalerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class RecompViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val repo = RecompRepository(application)
    private val audioEngine = AudioSynthEngine()
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val inputManager = application.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val _currentTab = MutableStateFlow(RecompTab.HOME)
    val currentTab: StateFlow<RecompTab> = _currentTab.asStateFlow()

    private val _capabilities = MutableStateFlow(HardwareProbe.probe(application))
    val capabilities: StateFlow<DeviceCapabilities> = _capabilities.asStateFlow()

    private val _shaderCacheReport = MutableStateFlow(ShaderCacheManager.loadExistingReport(application))
    val shaderCacheReport: StateFlow<ShaderCacheReport> = _shaderCacheReport.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    private val _gamepadState = MutableStateFlow(LiveGamepadState())
    val gamepadState: StateFlow<LiveGamepadState> = _gamepadState.asStateFlow()

    private val _isBgmActive = MutableStateFlow(false)
    val isBgmActive: StateFlow<Boolean> = _isBgmActive.asStateFlow()

    private var loadedNsoSession: Arm64StaticLifter.LoadedNsoSession? = null
    private var loadedSarcPayload: ByteArray? = null

    private val _recompileState = MutableStateFlow(RecompileSessionState())
    val recompileState: StateFlow<RecompileSessionState> = _recompileState.asStateFlow()

    private val _sarcReport = MutableStateFlow<SarcArchiveReport?>(null)
    val sarcReport: StateFlow<SarcArchiveReport?> = _sarcReport.asStateFlow()

    private val _buildState = MutableStateFlow(repo.loadPersistedBuildState())
    val buildState: StateFlow<OnDeviceBuildState> = _buildState.asStateFlow()

    private val _showMissingDumpDialog = MutableStateFlow(false)
    val showMissingDumpDialog: StateFlow<Boolean> = _showMissingDumpDialog.asStateFlow()

    val config: StateFlow<RecompConfig> = repo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecompConfig())

    val keysValidation: StateFlow<KeysValidationResult> = repo.keysFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KeysValidationResult())

    val dumpReport: StateFlow<GameDumpReport?> = repo.dumpReportFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mods: StateFlow<List<RomfsMod>> = repo.modsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshHardware()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshHardware()
        override fun onInputDeviceChanged(deviceId: Int) = refreshHardware()
    }

    init {
        refreshHardware()
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { gyro ->
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        viewModelScope.launch {
            config.collect { cfg ->
                audioEngine.masterVolume = cfg.masterVolume
                audioEngine.bgmVolume = cfg.bgmVolume
                audioEngine.sfxVolume = cfg.sfxVolume
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        sensorManager?.unregisterListener(this)
        audioEngine.release()
    }

    fun selectTab(tab: RecompTab) {
        if (tab != RecompTab.RUNTIME && _isBgmActive.value) {
            toggleAmbientBgm(false)
        }
        _currentTab.value = tab
    }

    fun dismissMissingDumpDialog() {
        _showMissingDumpDialog.value = false
    }

    fun launchRecompiler() {
        val currentDump = dumpReport.value
        if (currentDump == null || !currentDump.magicValid) {
            _showMissingDumpDialog.value = true
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val treeStr = currentDump.treeUriString
                val effectiveDump: GameDumpReport =
                    if (currentDump.sourceType == DumpSourceType.ROMFS_DIRECTORY && !treeStr.isNullOrBlank()) {
                        repo.rescanTreeIfAccessible(treeStr) ?: currentDump
                    } else {
                        currentDump
                    }

                val nsoUriStr = effectiveDump.mainNsoUriString
                if (!nsoUriStr.isNullOrBlank()) {
                    try {
                        runNsoLifter(Uri.parse(nsoUriStr), effectiveDump)
                        _currentTab.value = RecompTab.RUNTIME
                        return@launch
                    } catch (e: Exception) {
                        _recompileState.value = RecompileSessionState(
                            isReady = false,
                            sourceName = effectiveDump.fileName,
                            titleId = effectiveDump.titleIdDetected,
                            programName = effectiveDump.programName,
                            noticeMessage = "Não foi possível reabrir ${effectiveDump.fileName}: ${e.message}. Selecione seu ExeFS/main abaixo."
                        )
                        _currentTab.value = RecompTab.RUNTIME
                        return@launch
                    }
                }

                _recompileState.value = RecompileSessionState(
                    isReady = false,
                    sourceName = effectiveDump.fileName,
                    titleId = effectiveDump.titleIdDetected,
                    programName = effectiveDump.programName,
                    noticeMessage = "Dump atual (${effectiveDump.sourceType.label}) não contém o arquivo 'main' dentro da pasta ExeFS descompactado. Selecione o arquivo 'main' do ExeFS para gerar o código C++."
                )
                _currentTab.value = RecompTab.RUNTIME
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun runOnDeviceBuildWizard() {
        val currentDump = dumpReport.value
        if (currentDump == null || !currentDump.magicValid) {
            _showMissingDumpDialog.value = true
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                _buildState.value = _buildState.value.copy(
                    stage = BuildStage.DECOMPRESSING_NSO,
                    progress = 0.2f,
                    detailMessage = "Lendo e descomprimindo ${currentDump.fileName}…"
                )
                val treeStr = currentDump.treeUriString
                val effectiveDump: GameDumpReport =
                    if (currentDump.sourceType == DumpSourceType.ROMFS_DIRECTORY && !treeStr.isNullOrBlank()) {
                        repo.rescanTreeIfAccessible(treeStr) ?: currentDump
                    } else {
                        currentDump
                    }
                val nsoUriStr = effectiveDump.mainNsoUriString
                if (nsoUriStr.isNullOrBlank()) {
                    _buildState.value = _buildState.value.copy(
                        stage = BuildStage.ERROR,
                        progress = 0f,
                        detailMessage = "Selecione o arquivo 'main' do ExeFS (descompactado do NSP/XCI) para compilar."
                    )
                    _showMissingDumpDialog.value = true
                    return@launch
                }

                _buildState.value = _buildState.value.copy(
                    stage = BuildStage.LIFTING_ARM64,
                    progress = 0.52f,
                    detailMessage = "Analisando funções ARM64 e traduzindo para C++17…"
                )
                val session = runNsoLifter(Uri.parse(nsoUriStr), effectiveDump, persistBuild = false)

                _buildState.value = _buildState.value.copy(
                    stage = BuildStage.INDEXING_ROMFS_SHADERS,
                    progress = 0.78f,
                    detailMessage = "Pré-preparando shaders Vulkan & SGSR/FSR1…"
                )
                val shaderReport = withContext(Dispatchers.Default) {
                    ShaderCacheManager.precompilePipelineShaders(getApplication())
                }
                _shaderCacheReport.value = shaderReport

                _buildState.value = _buildState.value.copy(
                    stage = BuildStage.FINALIZING_BUILD,
                    progress = 0.92f,
                    detailMessage = "Salvando build recompilada no armazenamento privado…"
                )
                val persisted = repo.persistOnDeviceBuild(
                    session = session,
                    titleId = effectiveDump.titleIdDetected,
                    programName = effectiveDump.programName ?: "Super Mario Odyssey ${config.value.preferredGameVersion.badge}"
                )
                _buildState.value = persisted
                _feedbackMessage.value = "Build On-Device concluída: ${persisted.discoveredFunctions} funções prontas."
            } catch (e: Exception) {
                _buildState.value = _buildState.value.copy(
                    stage = BuildStage.ERROR,
                    progress = 0f,
                    detailMessage = "Falha na compilação: ${e.message ?: "erro desconhecido"}"
                )
                _feedbackMessage.value = "Erro no assistente On-Device: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importRomfsAndRecompile(treeUri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _showMissingDumpDialog.value = false
            try {
                val report = repo.importRomfsTree(treeUri)
                if (report.mainNsoUriString != null) {
                    runNsoLifter(Uri.parse(report.mainNsoUriString), report)
                    _currentTab.value = RecompTab.RUNTIME
                } else if (report.magicValid) {
                    _recompileState.value = RecompileSessionState(
                        isReady = false,
                        sourceName = report.fileName,
                        titleId = report.titleIdDetected,
                        programName = report.programName,
                        noticeMessage = "RomFS montado (${report.romfsFileCount} arquivos), mas o executável ExeFS/'main' não estava nesta pasta. Selecione o arquivo 'main' abaixo ou inspecione um .szs na aba RomFS."
                    )
                    _currentTab.value = RecompTab.RUNTIME
                } else {
                    _feedbackMessage.value = "Nenhum RomFS ou ExeFS (main) válido encontrado nesta pasta."
                }
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao abrir pasta RomFS/ExeFS: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun recompileFromNsoUri(nsoUri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _showMissingDumpDialog.value = false
            try {
                val dump = repo.importBinaryDump(nsoUri)
                runNsoLifter(nsoUri, dump)
                _currentTab.value = RecompTab.RUNTIME
            } catch (e: Exception) {
                _feedbackMessage.value = "Falha ao recompilar ExeFS/main: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun selectLiftedFunction(fn: LiftedFunctionMeta) {
        val session = loadedNsoSession ?: return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) {
                Arm64StaticLifter.liftFunctionPreview(session.textBytes, fn)
            }
            _recompileState.value = _recompileState.value.copy(
                selectedFunction = fn,
                previewLines = preview
            )
        }
    }

    fun executeRecompiledFunction(fn: LiftedFunctionMeta) {
        val session = loadedNsoSession ?: return
        viewModelScope.launch {
            val exec = withContext(Dispatchers.Default) {
                RecompExecutionEngine.execute(session, fn.startOffset)
            }
            val preview = withContext(Dispatchers.Default) {
                Arm64StaticLifter.liftFunctionPreview(session.textBytes, fn)
            }
            _recompileState.value = _recompileState.value.copy(
                selectedFunction = fn,
                previewLines = preview,
                execResult = exec
            )
            _feedbackMessage.value = "Executado ${fn.name}: ${exec.stepsExecuted} passo(s)."
        }
    }

    fun exportRecompiledProjectZip(targetUri: Uri) {
        val session = loadedNsoSession
        if (session == null) {
            _feedbackMessage.value = "Carregue o arquivo 'main' do ExeFS antes de exportar."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val dump = dumpReport.value
                repo.exportRecompiledZip(
                    uri = targetUri,
                    session = session,
                    titleId = dump?.titleIdDetected,
                    programName = dump?.programName
                )
                _feedbackMessage.value = "Projeto C++/CMake exportado (${session.functions.size} funções analisadas)."
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao exportar projeto C++: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun inspectRomfsSzs(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val (report, payload) = repo.inspectSzsArchive(uri)
                loadedSarcPayload = payload
                _sarcReport.value = report
                _feedbackMessage.value = "RomFS '${report.fileName}' descomprimido (${report.entries.size} entradas)."
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao descomprimir .szs/SARC: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exportSarcEntry(targetUri: Uri, entry: SarcEntry) {
        val payload = loadedSarcPayload ?: return
        viewModelScope.launch {
            try {
                repo.exportSarcEntry(targetUri, payload, entry)
                _feedbackMessage.value = "Arquivo '${entry.name}' extraído."
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao extrair arquivo SARC: ${e.message}"
            }
        }
    }

    private suspend fun runNsoLifter(
        nsoUri: Uri,
        dump: GameDumpReport?,
        persistBuild: Boolean = true
    ): Arm64StaticLifter.LoadedNsoSession {
        val session = withContext(Dispatchers.Default) {
            repo.loadNsoForRecompile(nsoUri)
        }
        loadedNsoSession = session
        val firstFn = session.functions.firstOrNull { fn ->
            if (fn.startOffset + 4 <= session.textBytes.size) {
                val w = (session.textBytes[fn.startOffset].toInt() and 0xFF) or
                    ((session.textBytes[fn.startOffset + 1].toInt() and 0xFF) shl 8) or
                    ((session.textBytes[fn.startOffset + 2].toInt() and 0xFF) shl 16) or
                    ((session.textBytes[fn.startOffset + 3].toInt() and 0xFF) shl 24)
                w != 0 && Arm64StaticLifter.decodeArm64Instruction(w, fn.startOffset).isDecoded
            } else {
                false
            }
        } ?: session.functions.firstOrNull()
        val preview = if (firstFn != null) {
            withContext(Dispatchers.Default) {
                Arm64StaticLifter.liftFunctionPreview(session.textBytes, firstFn)
            }
        } else {
            emptyList()
        }
        val exec = if (firstFn != null) {
            withContext(Dispatchers.Default) {
                RecompExecutionEngine.execute(session, firstFn.startOffset)
            }
        } else {
            null
        }

        _recompileState.value = RecompileSessionState(
            isReady = true,
            sourceName = session.fileName,
            buildIdHex = session.buildIdHex,
            titleId = dump?.titleIdDetected,
            programName = dump?.programName,
            textCompressedBytes = session.textCompressedBytes,
            textDecompressedBytes = session.textBytes.size,
            rodataDecompressedBytes = session.rodataBytes.size,
            totalInstructions = session.totalInstructions,
            decodedInstructions = session.decodedInstructions,
            svcCount = session.svcCount,
            functions = session.functions,
            rodataStrings = session.rodataStrings,
            selectedFunction = firstFn,
            previewLines = preview,
            execResult = exec,
            noticeMessage = "ExeFS/'${session.fileName}' recompilado para C++17 e testado na VM ARM64 em modo paisagem. Como o jogo completo depende de módulos externos (sdk/subsdk) e serviços Horizon OS + NVN→Vulkan, esta VM executa funções isoladas em sandbox."
        )

        if (persistBuild) {
            _buildState.value = repo.persistOnDeviceBuild(
                session = session,
                titleId = dump?.titleIdDetected,
                programName = dump?.programName
            )
        }
        return session
    }

    fun refreshHardware() {
        val caps = HardwareProbe.probe(getApplication())
        _capabilities.value = caps
        _gamepadState.value = _gamepadState.value.copy(
            isConnected = caps.connectedGamepads.isNotEmpty(),
            deviceName = caps.connectedGamepads.firstOrNull() ?: "Nenhum controle físico"
        )
    }

    fun updateConfig(transform: (RecompConfig) -> RecompConfig) {
        val updated = transform(config.value)
        viewModelScope.launch {
            repo.saveConfig(updated)
        }
    }

    fun applyQualityPreset(preset: QualityPreset) {
        updateConfig { current ->
            when (preset) {
                QualityPreset.BATTERY -> current.copy(
                    resolutionScale = ResolutionScale.THREE_QUARTER_720P,
                    targetFramerate = TargetFramerate.FPS_30,
                    frameInterpolation = false,
                    upscalerMode = UpscalerMode.SNAPDRAGON_SGSR_1,
                    fsrSharpness = 0.7f,
                    anisotropicLevel = 4,
                    adrenoNoLrzFix = true
                )
                QualityPreset.BALANCED -> current.copy(
                    resolutionScale = ResolutionScale.NATIVE_1080P,
                    targetFramerate = TargetFramerate.FPS_60,
                    frameInterpolation = false,
                    upscalerMode = UpscalerMode.AMD_FSR_1,
                    fsrSharpness = 0.65f,
                    anisotropicLevel = 8
                )
                QualityPreset.ULTRA -> current.copy(
                    resolutionScale = ResolutionScale.ULTRA_4K,
                    targetFramerate = TargetFramerate.FPS_120_UNLOCKED,
                    frameInterpolation = true,
                    upscalerMode = UpscalerMode.SMAA,
                    aspectRatio = AspectRatioMode.RATIO_ULTRAWIDE_21_9,
                    anisotropicLevel = 16
                )
            }
        }
        _feedbackMessage.value = "Perfil '${preset.label}' aplicado."
    }

    fun resetConfigToDefaults() {
        viewModelScope.launch {
            repo.saveConfig(RecompConfig())
            _feedbackMessage.value = "Configurações restauradas."
        }
    }

    fun buildConfigPreviewJson(): String = repo.buildConfigJsonPretty(config.value)

    fun compileShaderCache() {
        viewModelScope.launch {
            _isBusy.value = true
            val report = withContext(Dispatchers.Default) {
                ShaderCacheManager.precompilePipelineShaders(getApplication())
            }
            _shaderCacheReport.value = report
            _isBusy.value = false
            _feedbackMessage.value = report.statusNote
        }
    }

    fun clearShaderCache() {
        _shaderCacheReport.value = ShaderCacheManager.clearCache(getApplication())
        _feedbackMessage.value = "Cache de shaders limpo."
    }

    fun importAutoFile(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                _feedbackMessage.value = repo.importAutoDetectedFile(uri, mods.value)
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao importar arquivo: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importRomfsDirectory(treeUri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val report = repo.importRomfsTree(treeUri)
                _feedbackMessage.value = report.summaryMessage
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao escanear pasta: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearImportedDumps() {
        viewModelScope.launch {
            repo.clearImportedDumps()
            loadedNsoSession = null
            loadedSarcPayload = null
            _recompileState.value = RecompileSessionState()
            _sarcReport.value = null
            _buildState.value = OnDeviceBuildState()
            _feedbackMessage.value = "Dados de dump, build e chaves removidos."
        }
    }

    fun importModFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val mod = repo.importModFolder(treeUri, mods.value)
                _feedbackMessage.value = "Mod '${mod.name}' importado (${mod.fileCount} arquivos)."
            } catch (e: Exception) {
                _feedbackMessage.value = "Falha ao importar pasta do mod: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleMod(modId: String) {
        viewModelScope.launch { repo.toggleMod(modId, mods.value) }
    }

    fun moveModPriority(modId: String, delta: Int) {
        viewModelScope.launch { repo.moveModPriority(modId, delta, mods.value) }
    }

    fun deleteMod(modId: String) {
        viewModelScope.launch {
            repo.deleteMod(modId, mods.value)
            _feedbackMessage.value = "Mod removido."
        }
    }

    fun exportConfigJson(uri: Uri) {
        viewModelScope.launch {
            try {
                repo.exportConfigToUri(uri, config.value)
                _feedbackMessage.value = "recomp_config.json exportado."
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao exportar JSON: ${e.message}"
            }
        }
    }

    fun toggleAmbientBgm(enabled: Boolean = !_isBgmActive.value) {
        _isBgmActive.value = enabled
        audioEngine.setBgmEnabled(enabled)
    }

    fun playSfxForButton(label: String) {
        when (label) {
            "A", "ZR" -> audioEngine.playJumpChime()
            "Y", "ZL" -> audioEngine.playCappyThrowSfx()
            "X" -> audioEngine.playPowerMoonChord()
        }
    }

    fun triggerRumblePulse(durationMs: Long = 55L) {
        if (!config.value.rumbleEnabled) return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(durationMs)
            }
        }
    }

    fun updateVirtualSticks(lx: Float? = null, ly: Float? = null, rx: Float? = null, ry: Float? = null) {
        val cur = _gamepadState.value
        _gamepadState.value = cur.copy(
            leftX = lx ?: cur.leftX,
            leftY = ly ?: cur.leftY,
            rightX = rx ?: cur.rightX,
            rightY = ry ?: cur.rightY
        )
    }

    fun setVirtualButton(label: String, pressed: Boolean) {
        val cur = _gamepadState.value
        val nextSet = if (pressed) cur.pressedButtons + label else cur.pressedButtons - label
        _gamepadState.value = cur.copy(
            pressedButtons = nextSet,
            lastEventLabel = if (pressed) "Botão $label" else cur.lastEventLabel
        )
        if (pressed) {
            triggerRumblePulse(24L)
            playSfxForButton(label)
        }
    }

    fun handleHardwareKeyEvent(event: KeyEvent): Boolean {
        val sources = event.device?.sources ?: 0
        val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isGamepad) return false

        val label = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "B"
            KeyEvent.KEYCODE_BUTTON_B -> "A"
            KeyEvent.KEYCODE_BUTTON_X -> "Y"
            KeyEvent.KEYCODE_BUTTON_Y -> "X"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R"
            KeyEvent.KEYCODE_BUTTON_L2 -> "ZL"
            KeyEvent.KEYCODE_BUTTON_R2 -> "ZR"
            KeyEvent.KEYCODE_BUTTON_START -> "+"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "-"
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> return false
        }

        val cur = _gamepadState.value
        val nextButtons = if (event.action == KeyEvent.ACTION_DOWN) {
            cur.pressedButtons + label
        } else {
            cur.pressedButtons - label
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && _currentTab.value == RecompTab.RUNTIME) {
            playSfxForButton(label)
        }

        _gamepadState.value = cur.copy(
            isConnected = true,
            deviceName = event.device?.name ?: cur.deviceName,
            pressedButtons = nextButtons,
            lastEventLabel = "${event.device?.name ?: "Gamepad"}: $label"
        )
        return true
    }

    fun handleHardwareMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) return false

        val dz = config.value.stickDeadzone
        fun applyDeadzone(v: Float): Float = if (abs(v) < dz) 0f else v

        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        val rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f)

        _gamepadState.value = _gamepadState.value.copy(
            isConnected = true,
            deviceName = event.device?.name ?: _gamepadState.value.deviceName,
            leftX = lx,
            leftY = ly,
            rightX = rx,
            rightY = ry,
            triggerL = lt,
            triggerR = rt,
            lastEventLabel = "Eixos analógicos ativos"
        )
        return true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return
        _gamepadState.value = _gamepadState.value.copy(
            gyroX = event.values[0],
            gyroY = event.values[1],
            gyroZ = event.values[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun clearFeedback() {
        _feedbackMessage.value = null
    }
}
