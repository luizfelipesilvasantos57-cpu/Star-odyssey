package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.SwitchFormatParser
import com.example.model.GameDumpReport
import com.example.model.KeysValidationResult
import com.example.model.RomfsMod
import com.example.ui.components.MinimalGroupCard
import com.example.ui.components.SegmentedPill
import com.example.ui.components.StatusPill
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.CappyCyanSoft
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberSoft

private enum class LibrarySegment(val label: String) {
    DUMP("Dump & Chaves"),
    MODS("Mods RomFS")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    keysValidation: KeysValidationResult,
    dumpReport: GameDumpReport?,
    mods: List<RomfsMod>,
    isBusy: Boolean,
    onImportAutoFile: (android.net.Uri) -> Unit,
    onImportRomfsFolder: (android.net.Uri) -> Unit,
    onImportModFolder: (android.net.Uri) -> Unit,
    onClearDumps: () -> Unit,
    onToggleMod: (String) -> Unit,
    onMovePriority: (String, Int) -> Unit,
    onDeleteMod: (String) -> Unit
) {
    var segment by remember { mutableStateOf(LibrarySegment.DUMP) }
    var searchQuery by remember { mutableStateOf("") }

    // Single smart auto-detect picker for .keys, .nsp, .xci, .nca, main (NSO0), or .zip mods
    val smartFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportAutoFile)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            if (segment == LibrarySegment.DUMP) onImportRomfsFolder(it) else onImportModFolder(it)
        }
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
                    options = LibrarySegment.entries,
                    selected = segment,
                    label = { it.label },
                    testTagPrefix = "library_seg",
                    onSelect = { segment = it }
                )

                if (isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PowerMoonGold,
                        trackColor = ObsidianSurfaceElevated
                    )
                }

                // Minimalist 2-action bar: Smart File Auto-Import + Folder Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { smartFilePicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = OdysseyCrimson),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("smart_import_file_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (segment == LibrarySegment.DUMP) {
                                "Importar Arquivo"
                            } else {
                                "Importar Mod (.zip)"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("import_folder_button")
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CappyCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pasta", color = CappyCyan)
                    }
                }

                // Live filter across parsed entries or installed mods
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = if (segment == LibrarySegment.DUMP) {
                                "Filtrar entradas NCA, ExeFS/main ou RomFS…"
                            } else {
                                "Filtrar mods instalados…"
                            },
                            color = TextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PowerMoonGold,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedContainerColor = ObsidianSurface,
                        unfocusedContainerColor = ObsidianSurface,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_input")
                )
            }
        }

        if (segment == LibrarySegment.DUMP) {
            item {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MinimalGroupCard(title = "prod.keys") {
                        if (!keysValidation.isLoaded) {
                            Text(
                                text = "Importe seu prod.keys via 'Importar Arquivo' (detecção automática).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(keysValidation.fileName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                StatusPill(
                                    text = if (keysValidation.isSufficient) "${keysValidation.totalKeys} CHAVES" else "INCOMPLETO",
                                    color = if (keysValidation.isSufficient) ReadyEmerald else WarningAmber,
                                    backgroundColor = if (keysValidation.isSufficient) ReadyEmeraldSoft else WarningAmberSoft
                                )
                            }
                            Text(
                                text = "header_key: ${if (keysValidation.hasHeaderKey) "OK" else "Ausente"} • master_key: ${keysValidation.masterKeyCount} • SHA ${keysValidation.sha256Short}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    MinimalGroupCard(title = "Binário & RomFS") {
                        if (dumpReport == null) {
                            Text(
                                text = "Suporta .nsp, .xci, .nca, arquivo 'main' (ExeFS) ou pasta RomFS/ExeFS.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dumpReport.fileName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    Text(
                                        text = "${dumpReport.sourceType.label} • ${SwitchFormatParser.formatByteSize(dumpReport.fileSizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CappyCyan
                                    )
                                }
                                StatusPill(
                                    text = if (dumpReport.magicValid) "OK" else "AVISO",
                                    color = if (dumpReport.magicValid) ReadyEmerald else ErrorCoral,
                                    backgroundColor = if (dumpReport.magicValid) ReadyEmeraldSoft else WarningAmberSoft
                                )
                            }
                            Text(
                                text = dumpReport.summaryMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            if (!dumpReport.buildIdHex.isNullOrBlank()) {
                                Text(
                                    text = "Build ID: ${dumpReport.buildIdHex.take(24)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }

                            val filteredEntries = dumpReport.entries.filter {
                                searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
                            }
                            if (filteredEntries.isNotEmpty()) {
                                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                                filteredEntries.take(20).forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (entry.sizeBytes > 0L) {
                                            Text(
                                                text = SwitchFormatParser.formatByteSize(entry.sizeBytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CappyCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (keysValidation.isLoaded || dumpReport != null) {
                            Text(
                                text = "Limpar dados importados",
                                style = MaterialTheme.typography.labelLarge,
                                color = ErrorCoral,
                                modifier = Modifier
                                    .clickable(onClick = onClearDumps)
                                    .padding(vertical = 4.dp)
                                    .testTag("clear_dumps_action")
                            )
                        }
                    }
                }
            }
        } else {
            val filteredMods = mods.filter {
                searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
            }
            if (filteredMods.isEmpty()) {
                item {
                    MinimalGroupCard(
                        modifier = Modifier.widthIn(max = 720.dp),
                        title = "Mods RomFS"
                    ) {
                        Text(
                            text = if (mods.isEmpty()) {
                                "Nenhum mod instalado. Use 'Importar Mod (.zip)' ou 'Pasta' para adicionar substituições."
                            } else {
                                "Nenhum mod corresponde ao filtro '$searchQuery'."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                itemsIndexed(filteredMods, key = { _, m -> m.id }) { index, mod ->
                    Surface(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .testTag("mod_card_${mod.id}"),
                        shape = RoundedCornerShape(18.dp),
                        color = ObsidianSurface,
                        border = BorderStroke(
                            1.dp,
                            if (mod.enabled) PowerMoonGold.copy(alpha = 0.45f) else ObsidianBorder
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = "#${index + 1}  ${mod.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (mod.enabled) TextPrimary else TextMuted
                                    )
                                    Text(
                                        text = "${mod.fileCount} arq. • ${SwitchFormatParser.formatByteSize(mod.totalSizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = mod.enabled,
                                    onCheckedChange = { onToggleMod(mod.id) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = ObsidianBackground,
                                        checkedTrackColor = PowerMoonGold,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = ObsidianSurfaceHighest
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    mod.categories.forEach { cat ->
                                        StatusPill(text = cat, color = CappyCyan, backgroundColor = CappyCyanSoft)
                                    }
                                }
                                Row {
                                    IconButton(
                                        onClick = { onMovePriority(mod.id, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Subir", tint = TextPrimary)
                                    }
                                    IconButton(
                                        onClick = { onMovePriority(mod.id, 1) },
                                        enabled = index < filteredMods.lastIndex,
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descer", tint = TextPrimary)
                                    }
                                    IconButton(
                                        onClick = { onDeleteMod(mod.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Remover", tint = ErrorCoral)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
