package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PowerMoonDarkColorScheme = darkColorScheme(
    primary = OdysseyCrimson,
    onPrimary = TextPrimary,
    primaryContainer = OdysseyCrimsonDark,
    onPrimaryContainer = TextPrimary,
    secondary = PowerMoonGold,
    onSecondary = ObsidianBackground,
    secondaryContainer = PowerMoonGoldDark,
    onSecondaryContainer = TextPrimary,
    tertiary = CappyCyan,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianBorder,
    error = ErrorCoral,
    onError = TextPrimary
)

private val PowerMoonShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PowerMoonTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PowerMoonDarkColorScheme,
        typography = PowerMoonTypography,
        shapes = PowerMoonShapes,
        content = content
    )
}
