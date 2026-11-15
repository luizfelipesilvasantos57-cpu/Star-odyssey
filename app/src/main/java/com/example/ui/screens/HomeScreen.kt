package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.SwitchFormatParser
import com.example.model.BuildStage
import com.example.model.DeviceCapabilities
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
import com.example.model.OdysseyGameVersion
import com.example.model.OnDeviceBuildState
import com.example.model.QualityPreset
import com.example.model.RecompConfig
import com.example.model.RecompTab
import com.example.model.ResolutionScale
import com.example.model.RomfsMod
import com.example.model.ShaderCacheReport
import com.example.model.TargetFramerate
import com.example.ui.components.MinimalGroupCard
import com.example.ui.components.SegmentedPill
import com.example.ui.components.StatusPill
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.CappyCyanSoft
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.PowerMoonGoldSoft
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun HomeScreen(
    capabilities: DeviceCapabilities,
    config: RecompConfig,
    keysValidation: KeysValidationResult,
    dumpReport: GameDumpReport?,
    mods: List<RomfsMod>,
    shaderCache: ShaderCacheReport,
    buildState: OnDeviceBuildState,
    isBusy: Boolean,
    showMissingDumpDialog: Boolean,
    onApplyPreset: (QualityPreset) -> Unit,
    onSelectOdysseyVersion: (OdysseyGameVersion) -> Unit,
    onRunBuildWizard: () -> Unit,
    onLaunchRecompiler: () -> Unit,
    onSelectRomfsAndRecompile: (Uri) -> Unit,
    onSelectNsoAndRecompile: (Uri) -> Unit,
    onDismissMissingDumpDialog: () -> Unit,
    onNavigate: (RecompTab) -> Unit
) {
    val romfsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onSelectRomfsAndRecompile(uri)
        }
    }
    val nsoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onSelectNsoAndRecompile(uri)
        }
    }

    val activePreset = when {
        config.targetFramerate == TargetFramerate.FPS_30 -> QualityPreset.BATTERY
        config.resolutionScale == ResolutionScale.ULTRA_4K && config.targetFramerate == TargetFramerate.FPS_120_UNLOCKED -> QualityPreset.ULTRA
        else -> QualityPreset.BALANCED
    }

    val hasExecutableReady = dumpReport?.magicValid == true && !dumpReport.mainNsoUriString.isNullOrBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Card inspired by LASRecomp-Android-Plus + Odyssey cosmic theme
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_hero_powermoon_1794693853861),
                            contentDescription = stringResource(R.string.cd_hero_banner),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(24.dp))
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            ObsidianBackground.copy(alpha = 0.55f),
                                            ObsidianBackground.copy(alpha = 0.94f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusPill(
                                    text = "Odyssey ${config.preferredGameVersion.badge}",
                                    color = PowerMoonGold,
                                    backgroundColor = PowerMoonGoldSoft
                                )
                                StatusPill(
                                    text = "${config.resolutionScale.badge} • ${config.targetFramerate.fps} FPS" +
                                        if (config.frameInterpolation) " FG" else "",
                                    color = CappyCyan,
                                    backgroundColor = ObsidianSurfaceElevated.copy(alpha = 0.85f)
                                )
                                StatusPill(
                                    text = if (buildState.isBuilt) "BUILD PRONTA" else config.upscalerMode.shortTag,
                                    color = if (buildState.isBuilt) ReadyEmerald else WarningAmber,
                                    backgroundColor = if (buildState.isBuilt) ReadyEmeraldSoft else ObsidianSurfaceElevated.copy(alpha = 0.85f)
                                )
                            }

                            Text(
                                text = "Star Odyssey",
                                style = MaterialTheme.typography.displaySmall,
                                color = TextPrimary
                            )

                            Text(
                                text = when {
                                    buildState.isBuilt ->
                                        "Build On-Device pronta (${buildState.discoveredFunctions} funções • ${SwitchFormatParser.formatByteSize(buildState.textBytes.toLong())}). Inicie o modo paisagem com HUD Touch ou exporte C++17."
                                    dumpReport?.magicValid == true ->
                                        "${dumpReport.programName ?: dumpReport.fileName} • ${dumpReport.magicLabel}"
                                    else ->
                                        "Port nativo estilo LASRecomp-Android-Plus para Super Mario Odyssey: assistente on-device, shader cache Vulkan/SGSR1 e HUD touch em paisagem."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = onLaunchRecompiler,
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OdysseyCrimson,
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("launch_game_button")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = TextPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = buildState.stage.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                } else {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.btn_launch_runtime),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }

                // Tappable 3-column BYOD Status Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactStatusCard(
                        title = "Chaves",
                        value = if (keysValidation.isSufficient) "${keysValidation.totalKeys} ok" else "Opcional",
                        dotColor = if (keysValidation.isSufficient) ReadyEmerald else WarningAmber,
                        modifier = Modifier.weight(1f),
                        testTag = "status_keys_tile",
                        onClick = { onNavigate(RecompTab.LIBRARY) }
                    )
                    CompactStatusCard(
                        title = "Dump",
                        value = if (dumpReport?.magicValid == true) dumpReport.sourceType.label else "Pendente",
                        dotColor = if (dumpReport?.magicValid == true) ReadyEmerald else WarningAmber,
                        modifier = Modifier.weight(1f),
                        testTag = "status_dump_tile",
                        onClick = { onNavigate(RecompTab.LIBRARY) }
                    )
                    CompactStatusCard(
                        title = "Mods",
                        value = "${mods.count { it.enabled }} ativo(s)",
                        dotColor = if (mods.any { it.enabled }) CappyCyan else TextMuted,
                        modifier = Modifier.weight(1f),
                        testTag = "status_mods_tile",
                        onClick = { onNavigate(RecompTab.LIBRARY) }
                    )
                }

                // LASRecomp-Android-Plus Style 4-Step On-Device Setup Wizard
                MinimalGroupCard(title = "Assistente On-Device • Super Mario Odyssey") {
                    Text(
                        text = "Prepare e salve a recompilação diretamente no armazenamento privado do app (estilo LASRecomp-Android).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    SegmentedPill(
                        options = OdysseyGameVersion.entries,
                        selected = config.preferredGameVersion,
                        label = { it.badge },
                        testTagPrefix = "wizard_ver",
                        onSelect = onSelectOdysseyVersion
                    )

                    WizardStepRow(
                        stepNumber = "1",
                        title = "Chaves Switch (prod.keys)",
                        detail = if (keysValidation.isSufficient) {
                            "${keysValidation.totalKeys} chaves validadas (${keysValidation.fileName})"
                        } else {
                            "Obrigatória apenas para .nsp/.xci cifrados; opcional se ExeFS/main já estiver extraído"
                        },
                        isComplete = keysValidation.isSufficient || hasExecutableReady
                    )

                    WizardStepRow(
                        stepNumber = "2",
                        title = "Dump Legal (ExeFS/main + RomFS)",
                        detail = when {
                            hasExecutableReady -> "${dumpReport?.fileName} • ${dumpReport?.magicLabel}"
                            dumpReport?.magicValid == true -> "${dumpReport.fileName} importado (selecione o arquivo 'main' do ExeFS)"
                            else -> "Nenhum dump carregado — toque abaixo para selecionar"
                        },
                        isComplete = hasExecutableReady
                    )

                    if (!hasExecutableReady) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { nsoLauncher.launch(arrayOf("*/*")) },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("wizard_pick_nso_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CappyCyan)
                                Spacer(Modifier.width(6.dp))
                                Text("ExeFS/main", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { romfsLauncher.launch(null) },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("wizard_pick_romfs_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = PowerMoonGold)
                                Spacer(Modifier.width(6.dp))
                                Text("Pasta RomFS", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    WizardStepRow(
                        stepNumber = "3",
                        title = "Build ARM64 → C++17 & Cache Vulkan",
                        detail = when {
                            buildState.stage != BuildStage.IDLE && buildState.stage != BuildStage.READY ->
                                buildState.detailMessage.ifBlank { buildState.stage.label }
                            buildState.isBuilt ->
                                buildState.detailMessage.ifBlank { "${buildState.discoveredFunctions} funções salvas no armazenamento privado" }
                            else ->
                                "Gera C++17 + CMake no aparelho e pré-compila shaders (${config.upscalerMode.shortTag})"
                        },
                        isComplete = buildState.isBuilt
                    )

                    if (buildState.stage != BuildStage.IDLE && !buildState.isBuilt && buildState.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { buildState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PowerMoonGold,
                            trackColor = ObsidianSurfaceElevated
                        )
                    }

                    WizardStepRow(
                        stepNumber = "4",
                        title = "HUD Touch em Paisagem & Gamepad",
                        detail = if (config.showTouchOverlay) {
                            "Controles virtuais Odyssey ativos (Cappy Y/X, Pulo A/B, Gyro)"
                        } else {
                            "Modo tela limpa (controle físico Bluetooth/USB)"
                        },
                        isComplete = buildState.isBuilt
                    )

                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRunBuildWizard,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObsidianSurfaceElevated,
                                contentColor = PowerMoonGold
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("wizard_build_button")
                        ) {
                            Icon(Icons.Filled.Build, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (buildState.isBuilt) "Recompilar Build" else "Compilar Build",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (buildState.isBuilt) {
                            Button(
                                onClick = onLaunchRecompiler,
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ReadyEmerald,
                                    contentColor = ObsidianBackground
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("wizard_open_hud_button")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Abrir HUD", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // 1-Tap Performance Profile Selector
                MinimalGroupCard(title = "Perfil de Execução") {
                    SegmentedPill(
                        options = QualityPreset.entries,
                        selected = activePreset,
                        label = { it.label },
                        testTagPrefix = "home_preset",
                        onSelect = onApplyPreset
                    )
                    Text(
                        text = "${capabilities.primaryAbi} • ${capabilities.gpuRenderer} • ${capabilities.refreshRateHz.toInt()} Hz" +
                            (if (shaderCache.isCompiled) " • Shaders em cache" else "") +
                            (if (config.adrenoNoLrzFix) " • TU_DEBUG=nolrz" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showMissingDumpDialog) {
        AlertDialog(
            onDismissRequest = onDismissMissingDumpDialog,
            containerColor = ObsidianSurface,
            title = {
                Text(
                    text = "Selecionar ExeFS / RomFS",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Para recompilar Super Mario Odyssey (${config.preferredGameVersion.badge}) para C++17, selecione sua pasta com RomFS/ExeFS ou escolha diretamente o arquivo 'main' dentro da pasta ExeFS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissMissingDumpDialog()
                        nsoLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OdysseyCrimson,
                        contentColor = TextPrimary
                    ),
                    modifier = Modifier.testTag("dialog_select_nso_button")
                ) {
                    Text("Arquivo 'main' (ExeFS)")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismissMissingDumpDialog()
                        romfsLauncher.launch(null)
                    },
                    modifier = Modifier.testTag("dialog_select_romfs_button")
                ) {
                    Text("Pasta RomFS/ExeFS", color = CappyCyan)
                }
            }
        )
    }
}

@Composable
private fun WizardStepRow(
    stepNumber: String,
    title: String,
    detail: String,
    isComplete: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = if (isComplete) ReadyEmeraldSoft else ObsidianSurfaceElevated,
            border = BorderStroke(1.dp, if (isComplete) ReadyEmerald else ObsidianBorder)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isComplete) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = ReadyEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = stepNumber,
                        style = MaterialTheme.typography.labelSmall,
                        color = PowerMoonGold
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            Text(text = detail, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun CompactStatusCard(
    title: String,
    value: String,
    dotColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder.copy(alpha = 0.75f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
