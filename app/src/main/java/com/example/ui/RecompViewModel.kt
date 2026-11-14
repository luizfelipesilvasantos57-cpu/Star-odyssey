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
import com.example.core.AudioSynthEngine
import com.example.core.HardwareProbe
import com.example.core.RecompRepository
import com.example.core.ShaderCacheManager
import com.example.model.AspectRatioMode
import com.example.model.DeviceCapabilities
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
import com.example.model.LiveGamepadState
import com.example.model.QualityPreset
import com.example.model.RecompConfig
import com.example.model.RecompTab
import com.example.model.ResolutionScale
import com.example.model.RomfsMod
import com.example.model.RuntimeRenderMode
import com.example.model.RuntimeTelemetry
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

    private val _runtimeTelemetry = MutableStateFlow(RuntimeTelemetry())
    val runtimeTelemetry: StateFlow<RuntimeTelemetry> = _runtimeTelemetry.asStateFlow()

    private val _runtimeRenderMode = MutableStateFlow(RuntimeRenderMode.SHADED)
    val runtimeRenderMode: StateFlow<RuntimeRenderMode> = _runtimeRenderMode.asStateFlow()

    private val _isBgmActive = MutableStateFlow(false)
    val isBgmActive: StateFlow<Boolean> = _isBgmActive.asStateFlow()

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
                    upscalerMode = UpscalerMode.AMD_FSR_1,
                    fsrSharpness = 0.7f,
                    anisotropicLevel = 4
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
            _feedbackMessage.value = "Dados de dump e chaves removidos."
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

    fun saveScreenshot(uri: Uri, bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                repo.saveScreenshotToUri(uri, bitmap)
                bitmap.recycle()
                _feedbackMessage.value = "Captura 3D salva em PNG."
            } catch (e: Exception) {
                _feedbackMessage.value = "Erro ao salvar captura: ${e.message}"
            }
        }
    }

    fun setRuntimeRenderMode(mode: RuntimeRenderMode) {
        _runtimeRenderMode.value = mode
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

    fun updateRuntimeTelemetry(telemetry: RuntimeTelemetry) {
        _runtimeTelemetry.value = telemetry
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
