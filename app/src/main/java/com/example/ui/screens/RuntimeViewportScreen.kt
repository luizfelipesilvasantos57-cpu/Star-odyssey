package com.example.ui.screens

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.PowerMoonGlRenderer
import com.example.core.SwitchFormatParser
import com.example.model.AspectRatioMode
import com.example.model.BootSessionState
import com.example.model.GameDumpReport
import com.example.model.LiveGamepadState
import com.example.model.RecompConfig
import com.example.model.ResolutionScale
import com.example.model.RomfsMod
import com.example.model.RuntimeRenderMode
import com.example.model.RuntimeTelemetry
import com.example.model.TargetFramerate
import com.example.model.UpscalerMode
import com.example.ui.components.DropdownSettingRow
import com.example.ui.components.SegmentedPill
import com.example.ui.components.SettingToggleRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.CappyCyanSoft
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldSoft
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import java.util.Locale
import kotlin.math.hypot

@Composable
fun RuntimeViewportScreen(
    config: RecompConfig,
    dumpReport: GameDumpReport?,
    bootSession: BootSessionState,
    mods: List<RomfsMod>,
    gamepadState: LiveGamepadState,
    telemetry: RuntimeTelemetry,
    renderMode: RuntimeRenderMode,
    isBgmActive: Boolean,
    onTelemetry: (RuntimeTelemetry) -> Unit,
    onUpdateConfig: ((RecompConfig) -> RecompConfig) -> Unit,
    onSetRenderMode: (RuntimeRenderMode) -> Unit,
    onToggleBgm: (Boolean) -> Unit,
    onToggleMod: (String) -> Unit,
    onVirtualSticks: (Float?, Float?, Float?, Float?) -> Unit,
    onVirtualButton: (String, Boolean) -> Unit,
    onSaveScreenshot: (android.net.Uri, Bitmap) -> Unit,
    onExitRuntime: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showInGameMenu by remember { mutableStateOf(false) }
    var showBootConsole by remember(bootSession.isMounted) { mutableStateOf(bootSession.isMounted) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = capturedBitmap
        capturedBitmap = null
        if (uri != null && bmp != null) {
            onSaveScreenshot(uri, bmp)
        } else {
            bmp?.recycle()
        }
    }

    val renderer = remember {
        PowerMoonGlRenderer(onTelemetryUpdate = onTelemetry)
    }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            this.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    LaunchedEffect(config) { renderer.config = config }
    LaunchedEffect(renderMode) { renderer.renderMode = renderMode }

    LaunchedEffect(gamepadState) {
        renderer.updateInputs(
            lx = gamepadState.leftX,
            ly = gamepadState.leftY,
            rx = gamepadState.rightX,
            ry = gamepadState.rightY,
            gx = gamepadState.gyroX,
            gy = gamepadState.gyroY
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
                Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceView.onPause()
        }
    }

    BackHandler {
        when {
            showBootConsole -> showBootConsole = false
            showInGameMenu -> showInGameMenu = false
            else -> onExitRuntime()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("gl_runtime_surface")
        )

        // Minimalist Top Floating HUD
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onExitRuntime,
                shape = CircleShape,
                color = ObsidianSurface.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("exit_runtime_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = TextPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Surface(
                onClick = { showBootConsole = !showBootConsole },
                shape = RoundedCornerShape(999.dp),
                color = ObsidianSurface.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, if (bootSession.isMounted) ReadyEmerald.copy(alpha = 0.7f) else ObsidianBorder),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toggle_boot_console_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "%.0f FPS", telemetry.fps),
                        style = MaterialTheme.typography.labelMedium,
                        color = ReadyEmerald,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${telemetry.renderWidth}x${telemetry.renderHeight}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PowerMoonGold
                    )
                    Text(
                        text = if (bootSession.isMounted) "ExeFS/RomFS" else config.upscalerMode.shortTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = CappyCyan
                    )
                }
            }

            Surface(
                onClick = { showInGameMenu = !showInGameMenu },
                shape = CircleShape,
                color = OdysseyCrimson.copy(alpha = 0.88f),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("ingame_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Menu In-Game",
                    tint = TextPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Virtual Switch Pro Controller Overlay (can be hidden cleanly)
        if (config.showTouchOverlay) {
            val padScale = config.touchOverlayScale.coerceIn(0.7f, 1.35f)
            val padAlpha = config.touchOverlayOpacity.coerceIn(0.25f, 1.0f)

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .alpha(padAlpha),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ShoulderButton("ZL", CappyCyan) { pressed ->
                        onVirtualButton("ZL", pressed)
                        if (pressed) renderer.triggerShockwave()
                    }
                    VirtualStickPad(
                        size = (114 * padScale).dp,
                        accent = CappyCyan,
                        onStickMove = { x, y -> onVirtualSticks(x, y, null, null) }
                    )
                }

                VirtualStickPad(
                    size = (88 * padScale).dp,
                    accent = PowerMoonGold,
                    onStickMove = { x, y -> onVirtualSticks(null, null, x, y) }
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ShoulderButton("ZR", OdysseyCrimson) { pressed ->
                        onVirtualButton("ZR", pressed)
                        if (pressed) renderer.triggerJumpBoost()
                    }
                    Box(
                        modifier = Modifier.size((130 * padScale).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FaceButton("X", CappyCyan, Modifier.align(Alignment.TopCenter),
                            onPress = {
                                onVirtualButton("X", true)
                                renderer.triggerJumpBoost()
                            },
                            onRelease = { onVirtualButton("X", false) }
                        )
                        FaceButton("B", WarningAmber, Modifier.align(Alignment.BottomCenter),
                            onPress = {
                                onVirtualButton("B", true)
                                renderer.resetCamera()
                            },
                            onRelease = { onVirtualButton("B", false) }
                        )
                        FaceButton("Y", ReadyEmerald, Modifier.align(Alignment.CenterStart),
                            onPress = {
                                onVirtualButton("Y", true)
                                renderer.triggerShockwave()
                            },
                            onRelease = { onVirtualButton("Y", false) }
                        )
                        FaceButton("A", OdysseyCrimson, Modifier.align(Alignment.CenterEnd),
                            onPress = {
                                onVirtualButton("A", true)
                                renderer.triggerJumpBoost()
                            },
                            onRelease = { onVirtualButton("A", false) }
                        )
                    }
                }
            }
        }

        // Slide-over In-Game Recomp Menu
        AnimatedVisibility(
            visible = showInGameMenu,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 350.dp)
                    .fillMaxWidth(0.86f)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .testTag("ingame_menu_panel"),
                shape = RoundedCornerShape(22.dp),
                color = ObsidianSurface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Menu Recomp", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                            Text(
                                text = dumpReport?.fileName ?: "Pipeline 3D Interativo",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dumpReport?.magicValid == true) ReadyEmerald else CappyCyan
                            )
                        }
                        IconButton(
                            onClick = { showInGameMenu = false },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondary)
                        }
                    }

                    SegmentedPill(
                        options = RuntimeRenderMode.entries,
                        selected = renderMode,
                        label = { it.label },
                        testTagPrefix = "render_mode",
                        onSelect = onSetRenderMode
                    )

                    DropdownSettingRow(
                        title = "Resolução",
                        options = ResolutionScale.entries,
                        selected = config.resolutionScale,
                        optionLabel = { it.badge },
                        testTag = "overlay_res_dd",
                        onSelect = { r -> onUpdateConfig { it.copy(resolutionScale = r) } }
                    )

                    DropdownSettingRow(
                        title = "FPS Alvo",
                        options = TargetFramerate.entries,
                        selected = config.targetFramerate,
                        optionLabel = { it.label },
                        testTag = "overlay_fps_dd",
                        onSelect = { f -> onUpdateConfig { it.copy(targetFramerate = f) } }
                    )

                    DropdownSettingRow(
                        title = "Upscaler",
                        options = UpscalerMode.entries,
                        selected = config.upscalerMode,
                        optionLabel = { it.label },
                        testTag = "overlay_upscaler_dd",
                        onSelect = { u -> onUpdateConfig { it.copy(upscalerMode = u) } }
                    )

                    DropdownSettingRow(
                        title = "Proporção",
                        options = AspectRatioMode.entries,
                        selected = config.aspectRatio,
                        optionLabel = { it.label },
                        testTag = "overlay_aspect_dd",
                        onSelect = { a -> onUpdateConfig { it.copy(aspectRatio = a) } }
                    )

                    SettingToggleRow(
                        title = "Áudio Ambiente (Synth)",
                        checked = isBgmActive,
                        testTag = "overlay_bgm_toggle",
                        onCheckedChange = onToggleBgm
                    )

                    SettingToggleRow(
                        title = "Controles Virtuais",
                        checked = config.showTouchOverlay,
                        testTag = "overlay_touch_toggle",
                        onCheckedChange = { v -> onUpdateConfig { it.copy(showTouchOverlay = v) } }
                    )

                    SettingToggleRow(
                        title = "Correção de Banding",
                        checked = config.colorBandingFix,
                        testTag = "overlay_banding",
                        onCheckedChange = { v -> onUpdateConfig { it.copy(colorBandingFix = v) } }
                    )

                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showInGameMenu = false
                                showBootConsole = true
                            }
                            .padding(vertical = 8.dp)
                            .testTag("open_boot_console_action"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = CappyCyan)
                        Text("Console RomFS / ExeFS", style = MaterialTheme.typography.labelLarge, color = CappyCyan)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showInGameMenu = false
                                renderer.requestFrameCapture { bmp ->
                                    capturedBitmap = bmp
                                    savePngLauncher.launch("powermoon_frame_${System.currentTimeMillis()}.png")
                                }
                            }
                            .padding(vertical = 8.dp)
                            .testTag("capture_frame_action"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = PowerMoonGold)
                        Text("Capturar Frameboard (PNG)", style = MaterialTheme.typography.labelLarge, color = PowerMoonGold)
                    }

                    if (mods.isNotEmpty()) {
                        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                        Text("Mods RomFS (${mods.size})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        mods.forEach { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ObsidianSurfaceElevated)
                                    .clickable { onToggleMod(m.id) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                StatusPill(
                                    text = if (m.enabled) "ON" else "OFF",
                                    color = if (m.enabled) ReadyEmerald else TextSecondary,
                                    backgroundColor = if (m.enabled) ReadyEmeraldSoft else CappyCyanSoft
                                )
                            }
                        }
                    }
                }
            }
        }

        // Expandable RomFS / ExeFS Boot Console & Mounted Assets Overlay
        AnimatedVisibility(
            visible = showBootConsole,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp, vertical = 76.dp)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .testTag("boot_console_panel"),
                shape = RoundedCornerShape(20.dp),
                color = ObsidianSurface.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Execução RomFS / ExeFS",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = bootSession.sourceLabel.ifBlank { dumpReport?.fileName ?: "Runtime GLES 3.0" },
                                style = MaterialTheme.typography.labelSmall,
                                color = ReadyEmerald
                            )
                        }
                        TextButton(
                            onClick = { showBootConsole = false },
                            modifier = Modifier.testTag("close_boot_console_button")
                        ) {
                            Text("Ocultar", color = PowerMoonGold)
                        }
                    }

                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))

                    if (bootSession.bootLogs.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianBackground.copy(alpha = 0.8f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            bootSession.bootLogs.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (line.startsWith("[Core]")) WarningAmber else TextSecondary
                                )
                            }
                        }
                    }

                    if (bootSession.mountedAssets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Entradas Montadas (${bootSession.mountedAssets.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = CappyCyan
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            bootSession.mountedAssets.take(16).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ObsidianSurfaceElevated)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "[${entry.partition}] ${entry.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (entry.sizeBytes > 0L) {
                                        Text(
                                            text = SwitchFormatParser.formatByteSize(entry.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
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

@Composable
private fun VirtualStickPad(
    size: Dp,
    accent: Color,
    onStickMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onStickMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onStickMove(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val maxRadius = (this.size.width / 2f) * 0.65f
                    val next = knobOffset + dragAmount
                    val dist = hypot(next.x, next.y)
                    val clamped = if (dist > maxRadius && dist > 0f) next * (maxRadius / dist) else next
                    knobOffset = clamped
                    onStickMove(
                        (clamped.x / maxRadius).coerceIn(-1f, 1f),
                        (clamped.y / maxRadius).coerceIn(-1f, 1f)
                    )
                }
            }
    ) {
        val outerR = this.size.minDimension / 2f - 4f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = ObsidianSurface.copy(alpha = 0.55f), radius = outerR, center = center)
        drawCircle(color = accent.copy(alpha = 0.5f), radius = outerR, center = center, style = Stroke(width = 1.8.dp.toPx()))
        drawCircle(color = accent.copy(alpha = 0.9f), radius = outerR * 0.4f, center = center + knobOffset)
    }
}

@Composable
private fun FaceButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = ObsidianSurface.copy(alpha = 0.72f),
        border = BorderStroke(1.5.dp, color),
        modifier = modifier
            .size(48.dp)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
            .testTag("virtual_btn_$label")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShoulderButton(
    label: String,
    color: Color,
    onPressChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ObsidianSurface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPressChange(true)
                        tryAwaitRelease()
                        onPressChange(false)
                    }
                )
            }
            .testTag("virtual_btn_$label")
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
