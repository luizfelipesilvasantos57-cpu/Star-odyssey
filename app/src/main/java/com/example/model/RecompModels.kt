package com.example.model

enum class RecompTab {
    HOME,
    LIBRARY,
    SETTINGS,
    RUNTIME
}

enum class GraphicsBackend(val label: String) {
    VULKAN_NATIVE("Vulkan 1.3 (Nativo)"),
    GLES_COMPAT("OpenGL ES 3.2")
}

enum class ResolutionScale(val factor: Float, val label: String, val badge: String) {
    HALF_540P(0.5f, "0.5x • 540p", "540p"),
    THREE_QUARTER_720P(0.75f, "0.75x • 720p", "720p"),
    NATIVE_1080P(1.0f, "1.0x • 1080p", "1080p"),
    HIGH_1440P(1.5f, "1.5x • 1440p", "1440p"),
    ULTRA_4K(2.0f, "2.0x • 4K", "4K")
}

enum class TargetFramerate(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_120_UNLOCKED(120, "120 FPS")
}

enum class UpscalerMode(val label: String, val shortTag: String) {
    NONE("Nativo", "OFF"),
    SMAA("SMAA", "SMAA"),
    AMD_FSR_1("AMD FSR 1.0", "FSR1")
}

enum class AspectRatioMode(val ratio: Float, val label: String, val shortTag: String) {
    RATIO_16_9(16f / 9f, "16:9 Padrão", "16:9"),
    RATIO_16_10(16f / 10f, "16:10 Tablet", "16:10"),
    RATIO_ULTRAWIDE_21_9(21f / 9f, "21:9 Ultrawide", "21:9")
}

enum class AudioBackend(val label: String) {
    AAUDIO_LOW_LATENCY("AAudio (Baixa Latência)"),
    OPENSL_ES("OpenSL ES")
}

enum class QualityPreset(val label: String) {
    BATTERY("Portátil"),
    BALANCED("Nativo 60"),
    ULTRA("4K 120Hz")
}

enum class DumpSourceType(val label: String) {
    NSP_PFS0("NSP (PFS0)"),
    XCI_HFS0("XCI (HFS0)"),
    NCA_CONTENT("NCA"),
    NSO0_EXECUTABLE("NSO0 (main)"),
    ROMFS_DIRECTORY("RomFS/ExeFS")
}

enum class RuntimeRenderMode(val label: String) {
    SHADED("Padrão"),
    WIREFRAME("Wireframe"),
    NIGHT_AURA("Aura Lunar")
}

data class DeviceCapabilities(
    val deviceModel: String = "",
    val androidVersion: String = "",
    val primaryAbi: String = "",
    val isArm64: Boolean = false,
    val gpuRenderer: String = "Consultando GPU...",
    val gpuVendor: String = "",
    val glesVersion: String = "",
    val vulkanSupported: Boolean = false,
    val vulkanVersionLabel: String = "Indisponível",
    val vulkanHardwareLevel: Int = -1,
    val refreshRateHz: Float = 60f,
    val supports120Hz: Boolean = false,
    val totalRamGb: Float = 0f,
    val availableRamGb: Float = 0f,
    val freeStorageGb: Float = 0f,
    val hasGyroscope: Boolean = false,
    val hasVibrator: Boolean = false,
    val connectedGamepads: List<String> = emptyList()
)

data class KeysValidationResult(
    val isLoaded: Boolean = false,
    val fileName: String = "",
    val totalKeys: Int = 0,
    val hasHeaderKey: Boolean = false,
    val masterKeyCount: Int = 0,
    val keyAreaAppCount: Int = 0,
    val titleKekCount: Int = 0,
    val sha256Short: String = "",
    val issues: List<String> = emptyList()
) {
    val isSufficient: Boolean
        get() = isLoaded && hasHeaderKey && (masterKeyCount > 0 || keyAreaAppCount > 0)
}

data class ContainerEntry(
    val name: String,
    val offset: Long,
    val sizeBytes: Long,
    val partition: String = "root"
)

data class GameDumpReport(
    val sourceType: DumpSourceType,
    val fileName: String,
    val fileSizeBytes: Long,
    val magicValid: Boolean,
    val magicLabel: String,
    val titleIdDetected: String? = null,
    val isOdysseyMatch: Boolean = false,
    val buildIdHex: String? = null,
    val nsoTextSize: Long = 0L,
    val nsoRodataSize: Long = 0L,
    val nsoDataSize: Long = 0L,
    val nsoBssSize: Long = 0L,
    val entries: List<ContainerEntry> = emptyList(),
    val romfsFoldersFound: List<String> = emptyList(),
    val romfsFileCount: Int = 0,
    val sha256Prefix: String = "",
    val verifiedAtMillis: Long = System.currentTimeMillis(),
    val summaryMessage: String = ""
)

data class RomfsMod(
    val id: String,
    val name: String,
    val folderName: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val categories: List<String>,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val importedAtMillis: Long = System.currentTimeMillis()
)

data class ShaderCacheReport(
    val isCompiled: Boolean = false,
    val shaderCount: Int = 0,
    val compileDurationMs: Long = 0L,
    val cacheSizeBytes: Long = 0L,
    val lastCompiledMillis: Long = 0L,
    val statusNote: String = "Cache não compilado"
)

data class RecompConfig(
    val graphicsBackend: GraphicsBackend = GraphicsBackend.VULKAN_NATIVE,
    val resolutionScale: ResolutionScale = ResolutionScale.NATIVE_1080P,
    val targetFramerate: TargetFramerate = TargetFramerate.FPS_60,
    val frameInterpolation: Boolean = false,
    val upscalerMode: UpscalerMode = UpscalerMode.AMD_FSR_1,
    val fsrSharpness: Float = 0.65f,
    val aspectRatio: AspectRatioMode = AspectRatioMode.RATIO_16_9,
    val colorBandingFix: Boolean = true,
    val precompileShaders: Boolean = true,
    val vsyncEnabled: Boolean = true,
    val anisotropicLevel: Int = 8,
    val audioBackend: AudioBackend = AudioBackend.AAUDIO_LOW_LATENCY,
    val masterVolume: Float = 0.85f,
    val bgmVolume: Float = 0.70f,
    val sfxVolume: Float = 0.90f,
    val stickDeadzone: Float = 0.12f,
    val gyroMotionCapEnabled: Boolean = true,
    val gyroSensitivity: Float = 1.0f,
    val rumbleEnabled: Boolean = true,
    val touchOverlayScale: Float = 1.0f,
    val touchOverlayOpacity: Float = 0.78f,
    val showTouchOverlay: Boolean = true
)

data class LiveGamepadState(
    val deviceName: String = "Nenhum controle físico",
    val isConnected: Boolean = false,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val triggerL: Float = 0f,
    val triggerR: Float = 0f,
    val pressedButtons: Set<String> = emptySet(),
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val lastEventLabel: String = "Aguardando entrada"
)

data class RuntimeTelemetry(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val drawCalls: Int = 0
)
