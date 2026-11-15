package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.SwitchFormatParser
import com.example.model.LiftedFunctionMeta
import com.example.model.RecompileSessionState
import com.example.model.SarcArchiveReport
import com.example.model.SarcEntry
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
import java.util.Locale

private enum class RecompilerSubTab(val label: String) {
    EXEC("Execução"),
    LIFTER("Lifter C++"),
    STRINGS("Strings"),
    ROMFS("RomFS")
}

@Composable
fun RecompilerScreen(
    state: RecompileSessionState,
    sarcReport: SarcArchiveReport?,
    isBusy: Boolean,
    onPickNsoFile: (Uri) -> Unit,
    onSelectFunction: (LiftedFunctionMeta) -> Unit,
    onExecuteFunction: (LiftedFunctionMeta) -> Unit,
    onExportZip: (Uri) -> Unit,
    onPickSzsFile: (Uri) -> Unit,
    onExportSarcEntry: (Uri, SarcEntry) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var activeSubTab by remember { mutableStateOf(RecompilerSubTab.EXEC) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingEntryExport by remember { mutableStateOf<SarcEntry?>(null) }

    // Switch to Landscape mode automatically when entering the recompilation execution screen
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
        }
    }

    val nsoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickNsoFile(uri)
    }
    val zipExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onExportZip(uri)
    }
    val szsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickSzsFile(uri)
    }
    val entryExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingEntryExport
        pendingEntryExport = null
        if (uri != null && entry != null) onExportSarcEntry(uri, entry)
    }

    BackHandler { onExit() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianBackground,
        topBar = {
            Surface(
                color = ObsidianSurface,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onExit,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("recompiler_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Column {
                            Text("Recompilador & Execução (Paisagem)", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                text = state.programName ?: state.sourceName.ifBlank { "Selecione ExeFS/main" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.isReady) ReadyEmerald else WarningAmber
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = PowerMoonGold,
                                strokeWidth = 2.dp
                            )
                        }
                        if (state.isReady) {
                            StatusPill(
                                text = "${state.functions.size} fn • ${state.svcCount} SVC",
                                color = ReadyEmerald,
                                backgroundColor = ReadyEmeraldSoft
                            )
                        }
                        StatusPill(
                            text = "PAISAGEM",
                            color = CappyCyan,
                            backgroundColor = CappyCyanSoft
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            val isLandscapeSplit = maxWidth >= 600.dp

            if (isLandscapeSplit) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Pane: Controls, Subtabs & Lists
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        item {
                            LeftControlsBlock(
                                state = state,
                                activeSubTab = activeSubTab,
                                searchQuery = searchQuery,
                                isBusy = isBusy,
                                onSelectTab = { activeSubTab = it },
                                onSearchChange = { searchQuery = it },
                                onPickNso = { nsoPicker.launch(arrayOf("*/*")) },
                                onExportZip = { zipExporter.launch("star_odyssey_recomp_cpp.zip") },
                                onPickSzs = { szsPicker.launch(arrayOf("*/*")) },
                                onExecuteSelected = {
                                    state.selectedFunction?.let(onExecuteFunction)
                                }
                            )
                        }

                        when (activeSubTab) {
                            RecompilerSubTab.EXEC, RecompilerSubTab.LIFTER -> {
                                val filtered = state.functions.filter {
                                    searchQuery.isBlank() ||
                                        it.name.contains(searchQuery, true) ||
                                        (searchQuery.equals("svc", true) && it.svcCalls.isNotEmpty())
                                }.take(80)
                                items(filtered, key = { it.name }) { fn ->
                                    FunctionRowCard(
                                        fn = fn,
                                        isSelected = state.selectedFunction?.name == fn.name,
                                        onClick = {
                                            if (activeSubTab == RecompilerSubTab.EXEC) {
                                                onExecuteFunction(fn)
                                            } else {
                                                onSelectFunction(fn)
                                            }
                                        }
                                    )
                                }
                            }
                            RecompilerSubTab.STRINGS -> {
                                val filtered = state.rodataStrings.filter {
                                    searchQuery.isBlank() || it.contains(searchQuery, true)
                                }.take(120)
                                items(filtered) { str ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = ObsidianSurface,
                                        border = BorderStroke(1.dp, ObsidianBorder.copy(alpha = 0.6f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = str,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            }
                            RecompilerSubTab.ROMFS -> {
                                if (sarcReport != null) {
                                    items(sarcReport.entries.take(120)) { entry ->
                                        SarcEntryCard(
                                            entry = entry,
                                            onExport = {
                                                pendingEntryExport = entry
                                                entryExporter.launch(entry.name.substringAfterLast('/'))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right Pane: Live Execution Output / Lifted C++17 Viewer
                    Column(
                        modifier = Modifier
                            .weight(0.56f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RightDetailPane(
                            activeSubTab = activeSubTab,
                            state = state,
                            sarcReport = sarcReport,
                            onExecuteSelected = {
                                state.selectedFunction?.let(onExecuteFunction)
                            }
                        )
                    }
                }
            } else {
                // Fallback portrait single-column layout
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        LeftControlsBlock(
                            state = state,
                            activeSubTab = activeSubTab,
                            searchQuery = searchQuery,
                            isBusy = isBusy,
                            onSelectTab = { activeSubTab = it },
                            onSearchChange = { searchQuery = it },
                            onPickNso = { nsoPicker.launch(arrayOf("*/*")) },
                            onExportZip = { zipExporter.launch("star_odyssey_recomp_cpp.zip") },
                            onPickSzs = { szsPicker.launch(arrayOf("*/*")) },
                            onExecuteSelected = {
                                state.selectedFunction?.let(onExecuteFunction)
                            }
                        )
                    }
                    item {
                        RightDetailPane(
                            activeSubTab = activeSubTab,
                            state = state,
                            sarcReport = sarcReport,
                            onExecuteSelected = {
                                state.selectedFunction?.let(onExecuteFunction)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftControlsBlock(
    state: RecompileSessionState,
    activeSubTab: RecompilerSubTab,
    searchQuery: String,
    isBusy: Boolean,
    onSelectTab: (RecompilerSubTab) -> Unit,
    onSearchChange: (String) -> Unit,
    onPickNso: () -> Unit,
    onExportZip: () -> Unit,
    onPickSzs: () -> Unit,
    onExecuteSelected: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedPill(
            options = RecompilerSubTab.entries,
            selected = activeSubTab,
            label = { it.label },
            testTagPrefix = "recomp_tab",
            onSelect = onSelectTab
        )

        MinimalGroupCard(title = "Controle NSO0 / RomFS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPickNso,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianSurfaceElevated, contentColor = TextPrimary),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("pick_nso_button")
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CappyCyan)
                    Spacer(Modifier.width(6.dp))
                    Text("ExeFS/main", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = onExportZip,
                    enabled = state.isReady && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = OdysseyCrimson, contentColor = TextPrimary),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("export_cpp_zip_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Exportar .zip", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (activeSubTab == RecompilerSubTab.ROMFS) {
                OutlinedButton(
                    onClick = onPickSzs,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pick_szs_button")
                ) {
                    Icon(Icons.Filled.Unarchive, contentDescription = null, tint = PowerMoonGold)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir .szs / .sarc (Yaz0)")
                }
            } else if (state.isReady) {
                Button(
                    onClick = onExecuteSelected,
                    enabled = state.selectedFunction != null && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = PowerMoonGold, contentColor = ObsidianBackground),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("execute_recomp_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Executar ${state.selectedFunction?.name ?: "Função"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (state.isReady && (activeSubTab == RecompilerSubTab.EXEC || activeSubTab == RecompilerSubTab.LIFTER || activeSubTab == RecompilerSubTab.STRINGS)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = CappyCyan) },
                label = {
                    Text(
                        if (activeSubTab == RecompilerSubTab.STRINGS) "Filtrar strings .rodata" else "Filtrar função (sub_ / svc)"
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("search_functions_field")
            )
        }
    }
}

@Composable
private fun RightDetailPane(
    activeSubTab: RecompilerSubTab,
    state: RecompileSessionState,
    sarcReport: SarcArchiveReport?,
    onExecuteSelected: () -> Unit
) {
    if (state.noticeMessage.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = ObsidianSurfaceElevated,
            border = BorderStroke(1.dp, if (state.isReady) CappyCyan.copy(alpha = 0.4f) else WarningAmber.copy(alpha = 0.5f))
        ) {
            Text(
                text = state.noticeMessage,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    when (activeSubTab) {
        RecompilerSubTab.EXEC -> {
            val exec = state.execResult
            if (exec != null) {
                MinimalGroupCard(title = "Execução Recompilada • ${exec.entryFunction}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            text = "${exec.stepsExecuted} passo(s)",
                            color = ReadyEmerald,
                            backgroundColor = ReadyEmeraldSoft
                        )
                        StatusPill(
                            text = exec.entryFunction,
                            color = PowerMoonGold,
                            backgroundColor = PowerMoonGoldSoft
                        )
                    }
                    Text(
                        text = exec.haltReason,
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningAmber
                    )

                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.5f))

                    // Register Grid
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        exec.registers.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { (reg, hex) ->
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        color = ObsidianBackground
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(reg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = CappyCyan)
                                            Text(hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.5f))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        exec.traceLogs.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    line.startsWith("[SVC]") -> WarningAmber
                                    line.startsWith("[RODATA]") -> ReadyEmerald
                                    line.startsWith("[VM]") -> CappyCyan
                                    else -> TextSecondary
                                }
                            )
                        }
                    }
                }
            } else {
                MinimalGroupCard(title = "Execução Recompilada") {
                    Text(
                        text = "Carregue um ExeFS/main (NSO0) para recompilar e executar as instruções ARM64 aqui em modo paisagem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        RecompilerSubTab.LIFTER, RecompilerSubTab.STRINGS -> {
            val sel = state.selectedFunction
            if (sel != null && state.isReady) {
                MinimalGroupCard(title = "Saída C++17 • ${sel.name}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coveragePct = if (state.totalInstructions > 0) {
                            (state.decodedInstructions * 100f / state.totalInstructions)
                        } else 0f
                        Text(
                            text = String.format(Locale.US, "%d instr. • %.0f%% ARM64 coberto", sel.insnCount, coveragePct),
                            style = MaterialTheme.typography.labelSmall,
                            color = PowerMoonGold
                        )
                        OutlinedButton(onClick = onExecuteSelected) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ReadyEmerald)
                            Spacer(Modifier.width(4.dp))
                            Text("Executar")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "void ${sel.name}(RecompContext* ctx) {",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = CappyCyan
                        )
                        state.previewLines.forEach { line ->
                            Text(
                                text = "  ${line.cpp}   // ${line.offsetHex}: ${line.asm}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = CappyCyan
                        )
                    }
                }
            }
        }

        RecompilerSubTab.ROMFS -> {
            MinimalGroupCard(title = "RomFS (Yaz0 / SARC)") {
                if (sarcReport != null) {
                    Text(
                        text = "${sarcReport.fileName} • ${if (sarcReport.wasYaz0) "Yaz0 descomprimido" else "SARC bruto"} (${SwitchFormatParser.formatByteSize(sarcReport.decompressedBytes.toLong())}) • ${sarcReport.entries.size} entradas",
                        style = MaterialTheme.typography.labelMedium,
                        color = ReadyEmerald
                    )
                } else {
                    Text(
                        text = "Use o botão 'Abrir .szs / .sarc (Yaz0)' à esquerda para descomprimir e extrair arquivos do RomFS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun FunctionRowCard(
    fn: LiftedFunctionMeta,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("fn_row_${fn.name}"),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ObsidianSurfaceElevated else ObsidianSurface,
        border = BorderStroke(1.dp, if (isSelected) PowerMoonGold else ObsidianBorder.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Code, contentDescription = null, tint = if (isSelected) PowerMoonGold else CappyCyan)
                Column {
                    Text(fn.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    Text("${fn.insnCount} instr.", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            if (fn.svcCalls.isNotEmpty()) {
                StatusPill(text = "SVC", color = WarningAmber, backgroundColor = PowerMoonGoldSoft)
            }
        }
    }
}

@Composable
private fun SarcEntryCard(
    entry: SarcEntry,
    onExport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.labelMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.formatTag} • ${SwitchFormatParser.formatByteSize(entry.sizeBytes.toLong())}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            IconButton(
                onClick = onExport,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Filled.Download, contentDescription = "Extrair arquivo", tint = CappyCyan)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
