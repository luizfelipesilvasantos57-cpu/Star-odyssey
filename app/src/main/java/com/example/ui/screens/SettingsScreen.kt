package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AspectRatioMode
import com.example.model.AudioBackend
import com.example.model.DeviceCapabilities
import com.example.model.GraphicsBackend
import com.example.model.LiveGamepadState
import com.example.model.RecompConfig
import com.example.model.ResolutionScale
import com.example.model.ShaderCacheReport
import com.example.model.TargetFramerate
import com.example.model.UpscalerMode
import com.example.ui.components.DropdownSettingRow
import com.example.ui.components.MinimalGroupCard
import com.example.ui.components.SegmentedPill
import com.example.ui.components.SettingSliderRow
import com.example.ui.components.SettingToggleRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsSegment(val label: String) {
    VIDEO("Vídeo"),
    AUDIO("Áudio"),
    CONTROLS("Controles")
}

@Composable
fun SettingsScreen(
    config: RecompConfig,
    capabilities: DeviceCapabilities,
    gamepadState: LiveGamepadState,
    shaderCache: ShaderCacheReport,
    configJsonPreview: String,
    onUpdateConfig: ((RecompConfig) -> RecompConfig) -> Unit,
    onCompileShaders: () -> Unit,
    onTestAudioChime: () -> Unit,
    onTestRumble: () -> Unit,
    onExportConfig: (android.net.Uri) -> Unit,
    onResetDefaults: () -> Unit
) {
    var segment by remember { mutableStateOf(SettingsSegment.VIDEO) }
    var showJsonPreview by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let(onExportConfig)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SegmentedPill(
                    options = SettingsSegment.entries,
                    selected = segment,
                    label = { it.label },
                    testTagPrefix = "settings_seg",
                    onSelect = { segment = it }
                )

                when (segment) {
                    SettingsSegment.VIDEO -> {
                        MinimalGroupCard(title = "Renderização") {
                            DropdownSettingRow(
                                title = "Backend Gráfico",
                                subtitle = capabilities.vulkanVersionLabel,
                                options = GraphicsBackend.entries,
                                selected = config.graphicsBackend,
                                optionLabel = { it.label },
                                testTag = "dd_backend",
                                onSelect = { b -> onUpdateConfig { it.copy(graphicsBackend = b) } }
                            )
                            DropdownSettingRow(
                                title = "Escala de Resolução",
                                options = ResolutionScale.entries,
                                selected = config.resolutionScale,
                                optionLabel = { it.label },
                                testTag = "dd_resolution",
                                onSelect = { r -> onUpdateConfig { it.copy(resolutionScale = r) } }
                            )
                            DropdownSettingRow(
                                title = "Alvo de FPS",
                                subtitle = "Painel ${capabilities.refreshRateHz.toInt()} Hz",
                                options = TargetFramerate.entries,
                                selected = config.targetFramerate,
                                optionLabel = { it.label },
                                testTag = "dd_fps",
                                onSelect = { f -> onUpdateConfig { it.copy(targetFramerate = f) } }
                            )
                            DropdownSettingRow(
                                title = "Proporção",
                                options = AspectRatioMode.entries,
                                selected = config.aspectRatio,
                                optionLabel = { it.label },
                                testTag = "dd_aspect",
                                onSelect = { a -> onUpdateConfig { it.copy(aspectRatio = a) } }
                            )
                        }

                        MinimalGroupCard(title = "Upscaling & Shaders") {
                            DropdownSettingRow(
                                title = "Upscaler / Anti-Aliasing",
                                options = UpscalerMode.entries,
                                selected = config.upscalerMode,
                                optionLabel = { it.label },
                                testTag = "dd_upscaler",
                                onSelect = { u -> onUpdateConfig { it.copy(upscalerMode = u) } }
                            )
                            if (config.upscalerMode == UpscalerMode.AMD_FSR_1) {
                                SettingSliderRow(
                                    title = "Nitidez AMD FSR",
                                    valueLabel = "${(config.fsrSharpness * 100).roundToInt()}%",
                                    value = config.fsrSharpness,
                                    range = 0f..1f,
                                    testTag = "slider_fsr_sharpness",
                                    onValueChange = { s -> onUpdateConfig { it.copy(fsrSharpness = s) } }
                                )
                            }
                            DropdownSettingRow(
                                title = "Filtro Anisotrópico",
                                options = listOf(1, 4, 8, 16),
                                selected = config.anisotropicLevel,
                                optionLabel = { "${it}x" },
                                testTag = "dd_aniso",
                                onSelect = { lvl -> onUpdateConfig { it.copy(anisotropicLevel = lvl) } }
                            )
                            SettingToggleRow(
                                title = "Interpolação 120 FPS",
                                checked = config.frameInterpolation,
                                testTag = "toggle_interpolation",
                                onCheckedChange = { v -> onUpdateConfig { it.copy(frameInterpolation = v) } }
                            )
                            SettingToggleRow(
                                title = "Correção de Banding (Dithering)",
                                checked = config.colorBandingFix,
                                testTag = "toggle_banding_fix",
                                onCheckedChange = { v -> onUpdateConfig { it.copy(colorBandingFix = v) } }
                            )
                            SettingToggleRow(
                                title = "Pré-compilação de Shaders & VSync",
                                checked = config.precompileShaders && config.vsyncEnabled,
                                testTag = "toggle_shaders_vsync",
                                onCheckedChange = { v ->
                                    onUpdateConfig { it.copy(precompileShaders = v, vsyncEnabled = v) }
                                }
                            )
                            HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onCompileShaders)
                                    .padding(vertical = 4.dp)
                                    .testTag("compile_shaders_action"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Compilar Cache de Shaders", style = MaterialTheme.typography.bodyLarge, color = PowerMoonGold)
                                    Text(shaderCache.statusNote, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                                StatusPill(
                                    text = if (shaderCache.isCompiled) "PRONTO" else "COMPILAR",
                                    color = if (shaderCache.isCompiled) ReadyEmerald else CappyCyan,
                                    backgroundColor = if (shaderCache.isCompiled) ReadyEmeraldSoft else ObsidianSurfaceElevated
                                )
                            }
                        }

                        MinimalGroupCard(title = "Configuração (recomp_config.json)") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (showJsonPreview) "Ocultar JSON" else "Visualizar JSON",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CappyCyan,
                                    modifier = Modifier
                                        .clickable { showJsonPreview = !showJsonPreview }
                                        .padding(vertical = 4.dp)
                                )
                                Text(
                                    text = "Exportar",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PowerMoonGold,
                                    modifier = Modifier
                                        .clickable { exportLauncher.launch("recomp_config.json") }
                                        .padding(vertical = 4.dp)
                                        .testTag("export_config_action")
                                )
                                Text(
                                    text = "Restaurar",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .clickable(onClick = onResetDefaults)
                                        .padding(vertical = 4.dp)
                                        .testTag("reset_config_action")
                                )
                            }
                            AnimatedVisibility(visible = showJsonPreview) {
                                Text(
                                    text = configJsonPreview,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    SettingsSegment.AUDIO -> {
                        MinimalGroupCard(title = "Motor de Áudio (Oboe / AudioTrack)") {
                            DropdownSettingRow(
                                title = "Driver de Áudio",
                                options = AudioBackend.entries,
                                selected = config.audioBackend,
                                optionLabel = { it.label },
                                testTag = "dd_audio_driver",
                                onSelect = { ab -> onUpdateConfig { it.copy(audioBackend = ab) } }
                            )
                            SettingSliderRow(
                                title = "Volume Geral",
                                valueLabel = "${(config.masterVolume * 100).roundToInt()}%",
                                value = config.masterVolume,
                                range = 0f..1f,
                                testTag = "slider_master_vol",
                                onValueChange = { v -> onUpdateConfig { it.copy(masterVolume = v) } }
                            )
                            SettingSliderRow(
                                title = "Música (BGM)",
                                valueLabel = "${(config.bgmVolume * 100).roundToInt()}%",
                                value = config.bgmVolume,
                                range = 0f..1f,
                                testTag = "slider_bgm_vol",
                                onValueChange = { v -> onUpdateConfig { it.copy(bgmVolume = v) } }
                            )
                            SettingSliderRow(
                                title = "Efeitos Sonoros (SFX)",
                                valueLabel = "${(config.sfxVolume * 100).roundToInt()}%",
                                value = config.sfxVolume,
                                range = 0f..1f,
                                testTag = "slider_sfx_vol",
                                onValueChange = { v -> onUpdateConfig { it.copy(sfxVolume = v) } }
                            )
                            HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                            Text(
                                text = "Testar Áudio Sintetizado (Power Moon Chime)",
                                style = MaterialTheme.typography.labelLarge,
                                color = PowerMoonGold,
                                modifier = Modifier
                                    .clickable(onClick = onTestAudioChime)
                                    .padding(vertical = 6.dp)
                                    .testTag("test_audio_action")
                            )
                        }
                    }

                    SettingsSegment.CONTROLS -> {
                        MinimalGroupCard(title = "Gamepad & Sensores ao Vivo") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(gamepadState.deviceName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    Text(
                                        text = if (capabilities.hasGyroscope) {
                                            String.format(Locale.US, "Gyro %.2f / %.2f / %.2f", gamepadState.gyroX, gamepadState.gyroY, gamepadState.gyroZ)
                                        } else {
                                            gamepadState.lastEventLabel
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CappyCyan
                                    )
                                }
                                StatusPill(
                                    text = if (gamepadState.isConnected) "ONLINE" else "TOUCH",
                                    color = if (gamepadState.isConnected) ReadyEmerald else PowerMoonGold,
                                    backgroundColor = ObsidianSurfaceElevated
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                MinimalStickDot("E", gamepadState.leftX, gamepadState.leftY)
                                MinimalStickDot("D", gamepadState.rightX, gamepadState.rightY)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { gamepadState.triggerL },
                                    modifier = Modifier.weight(1f),
                                    color = CappyCyan,
                                    trackColor = ObsidianSurfaceElevated
                                )
                                LinearProgressIndicator(
                                    progress = { gamepadState.triggerR },
                                    modifier = Modifier.weight(1f),
                                    color = OdysseyCrimson,
                                    trackColor = ObsidianSurfaceElevated
                                )
                            }

                            if (capabilities.hasVibrator && config.rumbleEnabled) {
                                Text(
                                    text = "Testar Vibração Háptica",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PowerMoonGold,
                                    modifier = Modifier
                                        .clickable(onClick = onTestRumble)
                                        .padding(vertical = 4.dp)
                                        .testTag("test_rumble_action")
                                )
                            }
                        }

                        MinimalGroupCard(title = "Calibração & Touch") {
                            SettingToggleRow(
                                title = "Giroscópio (Motion Cap)",
                                checked = config.gyroMotionCapEnabled,
                                testTag = "toggle_gyro",
                                onCheckedChange = { v -> onUpdateConfig { it.copy(gyroMotionCapEnabled = v) } }
                            )
                            SettingSliderRow(
                                title = "Sensibilidade Gyro",
                                valueLabel = String.format(Locale.US, "%.1fx", config.gyroSensitivity),
                                value = config.gyroSensitivity,
                                range = 0.2f..2.5f,
                                testTag = "slider_gyro_sens",
                                onValueChange = { s -> onUpdateConfig { it.copy(gyroSensitivity = s) } }
                            )
                            SettingSliderRow(
                                title = "Zona Morta (Anti-Drift)",
                                valueLabel = "${(config.stickDeadzone * 100).roundToInt()}%",
                                value = config.stickDeadzone,
                                range = 0f..0.35f,
                                testTag = "slider_deadzone",
                                onValueChange = { dz -> onUpdateConfig { it.copy(stickDeadzone = dz) } }
                            )
                            SettingToggleRow(
                                title = "Vibração (Rumble)",
                                checked = config.rumbleEnabled,
                                testTag = "toggle_rumble",
                                onCheckedChange = { v -> onUpdateConfig { it.copy(rumbleEnabled = v) } }
                            )
                            SettingToggleRow(
                                title = "Controles Virtuais na Tela",
                                checked = config.showTouchOverlay,
                                testTag = "toggle_touch_overlay",
                                onCheckedChange = { v -> onUpdateConfig { it.copy(showTouchOverlay = v) } }
                            )
                            if (config.showTouchOverlay) {
                                SettingSliderRow(
                                    title = "Escala Touch",
                                    valueLabel = "${(config.touchOverlayScale * 100).roundToInt()}%",
                                    value = config.touchOverlayScale,
                                    range = 0.7f..1.35f,
                                    testTag = "slider_touch_scale",
                                    onValueChange = { s -> onUpdateConfig { it.copy(touchOverlayScale = s) } }
                                )
                                SettingSliderRow(
                                    title = "Opacidade Touch",
                                    valueLabel = "${(config.touchOverlayOpacity * 100).roundToInt()}%",
                                    value = config.touchOverlayOpacity,
                                    range = 0.25f..1.0f,
                                    testTag = "slider_touch_opacity",
                                    onValueChange = { o -> onUpdateConfig { it.copy(touchOverlayOpacity = o) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalStickDot(label: String, x: Float, y: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(68.dp)) {
            val r = size.minDimension / 2f - 4f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = ObsidianSurfaceElevated, radius = r, center = center)
            drawCircle(color = ObsidianBorder, radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(
                color = PowerMoonGold,
                radius = 7.dp.toPx(),
                center = Offset(
                    center.x + x.coerceIn(-1f, 1f) * (r * 0.7f),
                    center.y + y.coerceIn(-1f, 1f) * (r * 0.7f)
                )
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}
