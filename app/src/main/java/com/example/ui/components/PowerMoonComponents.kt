package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.RecompTab
import com.example.ui.theme.CappyCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.OdysseyCrimson
import com.example.ui.theme.PowerMoonGold
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PowerMoonTopBar(
    isDumpReady: Boolean,
    vulkanSupported: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    Surface(
        color = ObsidianBackground.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(OdysseyCrimson, PowerMoonGold))
                            )
                    )
                    Text(
                        text = "Star Odyssey",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill(
                        text = if (isDumpReady) "DUMP OK" else if (vulkanSupported) "VULKAN" else "GLES",
                        color = if (isDumpReady) ReadyEmerald else CappyCyan,
                        backgroundColor = ObsidianSurface
                    )
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = "Telemetria e Cache de Shaders",
                            tint = TextSecondary
                        )
                    }
                }
            }
            HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.55f))
        }
    }
}

private data class NavItemSpec(
    val tab: RecompTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

private val navItems = listOf(
    NavItemSpec(RecompTab.HOME, "Jogar", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    NavItemSpec(RecompTab.LIBRARY, "Biblioteca", Icons.Filled.FolderZip, Icons.Outlined.FolderZip, "nav_library"),
    NavItemSpec(RecompTab.SETTINGS, "Sistema", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_settings")
)

@Composable
fun PowerMoonBottomBar(
    currentTab: RecompTab,
    onSelectTab: (RecompTab) -> Unit
) {
    NavigationBar(
        containerColor = ObsidianSurface,
        contentColor = TextPrimary,
        windowInsets = WindowInsets.navigationBars,
        tonalElevation = 4.dp
    ) {
        navItems.forEach { item ->
            val selected = currentTab == item.tab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(item.tab) },
                modifier = Modifier.testTag(item.tag),
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PowerMoonGold,
                    selectedTextColor = PowerMoonGold,
                    indicatorColor = OdysseyCrimson.copy(alpha = 0.24f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}

@Composable
fun PowerMoonNavRail(
    currentTab: RecompTab,
    onSelectTab: (RecompTab) -> Unit
) {
    NavigationRail(
        containerColor = ObsidianSurface,
        contentColor = TextPrimary
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        navItems.forEach { item ->
            val selected = currentTab == item.tab
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(item.tab) },
                modifier = Modifier.testTag("${item.tag}_rail"),
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label
                    )
                },
                label = {
                    Text(text = item.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = PowerMoonGold,
                    selectedTextColor = PowerMoonGold,
                    indicatorColor = OdysseyCrimson.copy(alpha = 0.24f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}

@Composable
fun <T> SegmentedPill(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    testTagPrefix: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) ObsidianSurfaceElevated else Color.Transparent
                        )
                        .clickable { onSelect(option) }
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                        .testTag("${testTagPrefix}_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) PowerMoonGold else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun MinimalGroupCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder.copy(alpha = 0.75f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            content()
        }
    }
}

@Composable
fun <T> DropdownSettingRow(
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    testTag: String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(vertical = 6.dp)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                if (!subtitle.isNullOrBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
            Text(
                text = optionLabel(selected),
                style = MaterialTheme.typography.labelLarge,
                color = PowerMoonGold
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(ObsidianSurfaceElevated)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel(opt),
                            color = if (opt == selected) PowerMoonGold else TextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    }
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBackground,
                checkedTrackColor = PowerMoonGold,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ObsidianSurfaceHighest
            )
        )
    }
}

@Composable
fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = CappyCyan
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = PowerMoonGold,
                activeTrackColor = OdysseyCrimson,
                inactiveTrackColor = ObsidianSurfaceHighest
            )
        )
    }
}
