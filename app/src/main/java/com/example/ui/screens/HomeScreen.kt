package com.example.ui.screens

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.model.DeviceCapabilities
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
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
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.PowerMoonGoldSoft
import com.example.ui.theme.ReadyEmerald
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
    onApplyPreset: (QualityPreset) -> Unit,
    onNavigate: (RecompTab) -> Unit
) {
    val activePreset = when {
        config.targetFramerate == TargetFramerate.FPS_30 -> QualityPreset.BATTERY
        config.resolutionScale == ResolutionScale.ULTRA_4K && config.targetFramerate == TargetFramerate.FPS_120_UNLOCKED -> QualityPreset.ULTRA
        else -> QualityPreset.BALANCED
    }

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
                // Minimalist Hero Card with a single clear Launch CTA
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(
                                    text = "${config.resolutionScale.badge} • ${config.targetFramerate.fps} FPS",
                                    color = PowerMoonGold,
                                    backgroundColor = PowerMoonGoldSoft
                                )
                                StatusPill(
                                    text = config.upscalerMode.shortTag,
                                    color = CappyCyan,
                                    backgroundColor = ObsidianSurfaceElevated.copy(alpha = 0.85f)
                                )
                            }

                            Text(
                                text = "Star Odyssey",
                                style = MaterialTheme.typography.displaySmall,
                                color = TextPrimary
                            )

                            Text(
                                text = if (dumpReport?.magicValid == true) {
                                    "${dumpReport.fileName} • ${dumpReport.magicLabel}"
                                } else {
                                    "Runtime nativo BYOD, analisador NSP/XCI/NSO0, mods RomFS e pipeline 3D."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = { onNavigate(RecompTab.RUNTIME) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OdysseyCrimson,
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("launch_runtime_button")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Iniciar Runtime 3D",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                // Tappable 3-column BYOD Status Strip (replaces multiple setup buttons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactStatusCard(
                        title = "Chaves",
                        value = if (keysValidation.isSufficient) "${keysValidation.totalKeys} ok" else "Pendente",
                        dotColor = if (keysValidation.isSufficient) ReadyEmerald else WarningAmber,
                        modifier = Modifier.weight(1f),
                        testTag = "status_keys_tile",
                        onClick = { onNavigate(RecompTab.LIBRARY) }
                    )
                    CompactStatusCard(
                        title = "Dump",
                        value = if (dumpReport?.magicValid == true) dumpReport.sourceType.label else "Nenhum",
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
                            if (shaderCache.isCompiled) " • Shaders em cache" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
