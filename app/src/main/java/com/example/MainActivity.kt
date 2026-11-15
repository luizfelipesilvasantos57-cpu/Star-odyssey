package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.RecompTab
import com.example.ui.RecompViewModel
import com.example.ui.components.PowerMoonBottomBar
import com.example.ui.components.PowerMoonNavRail
import com.example.ui.components.PowerMoonTopBar
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.RecompilerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.PowerMoonTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: RecompViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PowerMoonTheme {
                PowerMoonApp(viewModel = viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.handleHardwareKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.handleHardwareMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerMoonApp(viewModel: RecompViewModel) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val keysValidation by viewModel.keysValidation.collectAsStateWithLifecycle()
    val dumpReport by viewModel.dumpReport.collectAsStateWithLifecycle()
    val mods by viewModel.mods.collectAsStateWithLifecycle()
    val shaderCache by viewModel.shaderCacheReport.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val gamepadState by viewModel.gamepadState.collectAsStateWithLifecycle()
    val recompileState by viewModel.recompileState.collectAsStateWithLifecycle()
    val sarcReport by viewModel.sarcReport.collectAsStateWithLifecycle()
    val showMissingDumpDialog by viewModel.showMissingDumpDialog.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.feedbackMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    if (currentTab == RecompTab.RUNTIME) {
        RecompilerScreen(
            state = recompileState,
            sarcReport = sarcReport,
            isBusy = isBusy,
            onPickNsoFile = viewModel::recompileFromNsoUri,
            onSelectFunction = viewModel::selectLiftedFunction,
            onExecuteFunction = viewModel::executeRecompiledFunction,
            onExportZip = viewModel::exportRecompiledProjectZip,
            onPickSzsFile = viewModel::inspectRomfsSzs,
            onExportSarcEntry = viewModel::exportSarcEntry,
            onExit = { viewModel.selectTab(RecompTab.HOME) }
        )
        return
    }

    BackHandler(enabled = currentTab != RecompTab.HOME) {
        viewModel.selectTab(RecompTab.HOME)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        val useNavRail = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBackground,
            topBar = {
                PowerMoonTopBar(
                    isDumpReady = dumpReport?.magicValid == true,
                    vulkanSupported = capabilities.vulkanSupported,
                    onOpenDiagnostics = {
                        viewModel.refreshHardware()
                        showDiagnosticsSheet = true
                    }
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    PowerMoonBottomBar(
                        currentTab = currentTab,
                        onSelectTab = viewModel::selectTab
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    PowerMoonNavRail(
                        currentTab = currentTab,
                        onSelectTab = viewModel::selectTab
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        RecompTab.HOME -> HomeScreen(
                            capabilities = capabilities,
                            config = config,
                            keysValidation = keysValidation,
                            dumpReport = dumpReport,
                            mods = mods,
                            shaderCache = shaderCache,
                            isBusy = isBusy,
                            showMissingDumpDialog = showMissingDumpDialog,
                            onApplyPreset = viewModel::applyQualityPreset,
                            onLaunchRecompiler = viewModel::launchRecompiler,
                            onSelectRomfsAndRecompile = viewModel::importRomfsAndRecompile,
                            onSelectNsoAndRecompile = viewModel::recompileFromNsoUri,
                            onDismissMissingDumpDialog = viewModel::dismissMissingDumpDialog,
                            onNavigate = viewModel::selectTab
                        )
                        RecompTab.LIBRARY -> LibraryScreen(
                            keysValidation = keysValidation,
                            dumpReport = dumpReport,
                            mods = mods,
                            isBusy = isBusy,
                            onImportAutoFile = viewModel::importAutoFile,
                            onImportRomfsFolder = viewModel::importRomfsDirectory,
                            onImportModFolder = viewModel::importModFolder,
                            onClearDumps = viewModel::clearImportedDumps,
                            onToggleMod = viewModel::toggleMod,
                            onMovePriority = viewModel::moveModPriority,
                            onDeleteMod = viewModel::deleteMod
                        )
                        RecompTab.SETTINGS -> SettingsScreen(
                            config = config,
                            capabilities = capabilities,
                            gamepadState = gamepadState,
                            shaderCache = shaderCache,
                            configJsonPreview = viewModel.buildConfigPreviewJson(),
                            onUpdateConfig = viewModel::updateConfig,
                            onCompileShaders = viewModel::compileShaderCache,
                            onTestAudioChime = { viewModel.playSfxForButton("X") },
                            onTestRumble = { viewModel.triggerRumblePulse(90L) },
                            onExportConfig = viewModel::exportConfigJson,
                            onResetDefaults = viewModel::resetConfigToDefaults
                        )
                        RecompTab.RUNTIME -> Unit
                    }
                }
            }
        }
    }

    if (showDiagnosticsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDiagnosticsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Hardware & Cache de Shaders", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                DiagnosticRow("Dispositivo", "${capabilities.deviceModel} (${capabilities.androidVersion})")
                DiagnosticRow("CPU ABI", capabilities.primaryAbi)
                DiagnosticRow("GPU", capabilities.gpuRenderer)
                DiagnosticRow("Vulkan", capabilities.vulkanVersionLabel)
                DiagnosticRow("Tela", "${capabilities.refreshRateHz.toInt()} Hz")
                DiagnosticRow(
                    "RAM / Armazenamento",
                    String.format(Locale.US, "%.1f GB livre • %.1f GB disco", capabilities.availableRamGb, capabilities.freeStorageGb)
                )
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                DiagnosticRow("Shaders GLSL", shaderCache.statusNote)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compilar Shaders Agora",
                        style = MaterialTheme.typography.labelLarge,
                        color = PowerMoonGold,
                        modifier = Modifier
                            .clickable { viewModel.compileShaderCache() }
                            .padding(vertical = 8.dp)
                    )
                    if (shaderCache.isCompiled) {
                        Text(
                            text = "Limpar Cache",
                            style = MaterialTheme.typography.labelLarge,
                            color = CappyCyan,
                            modifier = Modifier
                                .clickable { viewModel.clearShaderCache() }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}
