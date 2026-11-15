package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.LiveGamepadState
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlin.math.hypot

@Composable
fun OdysseyTouchOverlay(
    gamepadState: LiveGamepadState,
    scale: Float,
    opacity: Float,
    onVirtualStick: (Float?, Float?, Float?, Float?) -> Unit,
    onVirtualButton: (String, Boolean) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clampedScale = scale.coerceIn(0.75f, 1.3f)
    val clampedAlpha = opacity.coerceIn(0.25f, 1.0f)
    val stickDiameter = (118f * clampedScale).dp
    val faceDiameter = (50f * clampedScale).coerceAtLeast(48f).dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(clampedAlpha)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Left cluster: ZL / L shoulders + Left Analog Stick + (-) Map
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderTouchButton(
                    label = "ZL",
                    isPressed = "ZL" in gamepadState.pressedButtons,
                    accent = CappyCyan,
                    onPressChanged = { onVirtualButton("ZL", it) }
                )
                ShoulderTouchButton(
                    label = "L",
                    isPressed = "L" in gamepadState.pressedButtons,
                    accent = TextPrimary,
                    onPressChanged = { onVirtualButton("L", it) }
                )
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VirtualAnalogStick(
                    diameter = stickDiameter,
                    stickX = gamepadState.leftX,
                    stickY = gamepadState.leftY,
                    onMove = { nx, ny -> onVirtualStick(nx, ny, null, null) }
                )
                UtilityRoundButton(
                    label = "-",
                    description = "Mapa do Reino",
                    isPressed = "-" in gamepadState.pressedButtons,
                    onClick = {
                        onVirtualButton("-", true)
                        onVirtualButton("-", false)
                    }
                )
            }
        }

        // Right cluster: R / ZR shoulders + (+) Menu + Odyssey Action Diamond (X, Y, B, A)
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderTouchButton(
                    label = "R",
                    isPressed = "R" in gamepadState.pressedButtons,
                    accent = TextPrimary,
                    onPressChanged = { onVirtualButton("R", it) }
                )
                ShoulderTouchButton(
                    label = "ZR",
                    isPressed = "ZR" in gamepadState.pressedButtons,
                    accent = OdysseyCrimson,
                    onPressChanged = { onVirtualButton("ZR", it) }
                )
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UtilityRoundButton(
                    label = "+",
                    description = "Abrir Menu In-Game",
                    isPressed = false,
                    accent = PowerMoonGold,
                    testTag = "hud_plus_menu_button",
                    onClick = onOpenMenu
                )

                // Nintendo Switch diamond: Top=X(Girar), Left=Y(Cappy), Right=A(Pular), Bottom=B(Mergulhar)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FaceTouchButton(
                        label = "X",
                        hint = "Girar",
                        size = faceDiameter,
                        accent = PowerMoonGold,
                        isPressed = "X" in gamepadState.pressedButtons,
                        onPressChanged = { onVirtualButton("X", it) }
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((34f * clampedScale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FaceTouchButton(
                            label = "Y",
                            hint = "Cappy",
                            size = faceDiameter,
                            accent = CappyCyan,
                            isPressed = "Y" in gamepadState.pressedButtons,
                            onPressChanged = { onVirtualButton("Y", it) }
                        )
                        FaceTouchButton(
                            label = "A",
                            hint = "Pular",
                            size = faceDiameter,
                            accent = OdysseyCrimson,
                            isPressed = "A" in gamepadState.pressedButtons,
                            onPressChanged = { onVirtualButton("A", it) }
                        )
                    }
                    FaceTouchButton(
                        label = "B",
                        hint = "Abaixar",
                        size = faceDiameter,
                        accent = ReadyEmerald,
                        isPressed = "B" in gamepadState.pressedButtons,
                        onPressChanged = { onVirtualButton("B", it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualAnalogStick(
    diameter: Dp,
    stickX: Float,
    stickY: Float,
    onMove: (Float, Float) -> Unit
) {
    var activeOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .size(diameter)
            .semantics { contentDescription = "Analógico Esquerdo Odyssey" }
            .testTag("hud_left_stick")
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxR = size.width * 0.38f
                        val delta = start - center
                        val dist = hypot(delta.x, delta.y).coerceAtLeast(1f)
                        val clamped = if (dist > maxR) delta * (maxR / dist) else delta
                        activeOffset = clamped
                        onMove((clamped.x / maxR).coerceIn(-1f, 1f), (clamped.y / maxR).coerceIn(-1f, 1f))
                    },
                    onDragEnd = {
                        activeOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        activeOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val maxR = size.width * 0.38f
                        val next = activeOffset + dragAmount
                        val dist = hypot(next.x, next.y).coerceAtLeast(1f)
                        val clamped = if (dist > maxR) next * (maxR / dist) else next
                        activeOffset = clamped
                        onMove((clamped.x / maxR).coerceIn(-1f, 1f), (clamped.y / maxR).coerceIn(-1f, 1f))
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 4.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = ObsidianSurface.copy(alpha = 0.72f),
                radius = radius,
                center = center
            )
            drawCircle(
                color = ObsidianBorder,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            val thumbCenter = if (activeOffset != Offset.Zero) {
                center + activeOffset
            } else {
                Offset(
                    center.x + stickX.coerceIn(-1f, 1f) * (radius * 0.65f),
                    center.y + stickY.coerceIn(-1f, 1f) * (radius * 0.65f)
                )
            }
            drawCircle(
                color = if (activeOffset != Offset.Zero) PowerMoonGold else CappyCyan,
                radius = radius * 0.36f,
                center = thumbCenter
            )
        }
    }
}

@Composable
private fun FaceTouchButton(
    label: String,
    hint: String,
    size: Dp,
    accent: Color,
    isPressed: Boolean,
    onPressChanged: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .size(size)
            .testTag("hud_btn_$label")
            .semantics { contentDescription = "$label $hint" }
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> onPressChanged(true)
                            PointerEventType.Release, PointerEventType.Exit -> onPressChanged(false)
                        }
                    }
                }
            },
        shape = CircleShape,
        color = if (isPressed) accent.copy(alpha = 0.85f) else ObsidianSurface.copy(alpha = 0.82f),
        border = BorderStroke(1.5.dp, if (isPressed) TextPrimary else accent)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isPressed) ObsidianBackground else TextPrimary
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (isPressed) ObsidianBackground else accent
            )
        }
    }
}

@Composable
private fun ShoulderTouchButton(
    label: String,
    isPressed: Boolean,
    accent: Color,
    onPressChanged: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .height(48.dp)
            .testTag("hud_btn_$label")
            .semantics { contentDescription = "Botão $label" }
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> onPressChanged(true)
                            PointerEventType.Release, PointerEventType.Exit -> onPressChanged(false)
                        }
                    }
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = if (isPressed) accent.copy(alpha = 0.85f) else ObsidianSurfaceElevated.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, if (isPressed) TextPrimary else accent.copy(alpha = 0.7f))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (isPressed) ObsidianBackground else TextPrimary
            )
        }
    }
}

@Composable
private fun UtilityRoundButton(
    label: String,
    description: String,
    isPressed: Boolean,
    accent: Color = TextMuted,
    testTag: String = "hud_btn_$label",
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(testTag)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = if (isPressed) accent else ObsidianSurface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.8f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isPressed) ObsidianBackground else accent
            )
        }
    }
}
