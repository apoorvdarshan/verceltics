package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone

/** iOS read/write method badge colors (green reads, orange writes). */
internal val ApiReadColor = Color(0xFF2F9B55)
internal val ApiWriteColor = Color(0xFFE07B1A)
internal val ApiWarningColor = Color(0xFFE3A008)

@Composable
internal fun ProviderApiTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBack()
            },
            testTag = "providerApi.back",
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .testTag("providerApi.title")
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

@Composable
internal fun ProviderApiRefreshAction(isRefreshing: Boolean, onRefresh: () -> Unit) {
    AppToolbarAction(
        modifier = Modifier.size(48.dp),
        enabled = !isRefreshing,
        onClick = onRefresh,
        testTag = "providerApi.refresh",
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh operations")
        }
    }
}

/** Small caption above an editor (iOS `editorLabel`). */
@Composable
internal fun EditorLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A raised panel used for every explorer section (iOS `appSurface`). */
@Composable
internal fun ApiPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = accent?.copy(alpha = 0.05f)?.compositeOver(MaterialTheme.colorScheme.surface) ?: MaterialTheme.colorScheme.surface,
        borderColor = accent?.copy(alpha = 0.22f) ?: MaterialTheme.colorScheme.outline,
        testTag = testTag,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Monospaced, autocorrect-free editor for paths, JSON bodies and header objects. */
@Composable
internal fun ApiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    accessibilityLabel: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 14,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    enabled: Boolean = true,
    testTag: String,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { contentDescription = accessibilityLabel },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, style = monospaceStyle()) }),
        textStyle = monospaceStyle(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
        ),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            focusedContainerColor = colors.surfaceVariant,
            unfocusedContainerColor = colors.surfaceVariant,
            disabledContainerColor = colors.surfaceVariant.copy(alpha = 0.6f),
            cursorColor = colors.primary,
        ),
    )
}

@Composable
internal fun monospaceStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = MaterialTheme.colorScheme.onSurface,
)

/** A selectable chip in a horizontal row (tags, methods, content types). */
@Composable
internal fun ApiChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    monospace: Boolean = false,
    testTag: String,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .testTag(testTag)
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) selectedColor else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) selectedColor.copy(alpha = 0.4f) else colors.outline),
    ) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = if (monospace) FontFamily.Monospace else null),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ApiChipRow(
    items: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    monospace: Boolean = false,
    color: (String) -> Color? = { null },
) {
    // A plain scrolling row: even catalogs with ~100 tags stay cheap, and every chip stays
    // reachable by accessibility services and UI tests.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item) {
                ApiChip(
                    text = item,
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    enabled = enabled,
                    monospace = monospace,
                    selectedColor = color(item) ?: MaterialTheme.colorScheme.primary,
                    testTag = "$testTagPrefix.$item",
                )
            }
        }
    }
}

/** An equal-width segmented control (iOS `.pickerStyle(.segmented)`). */
@Composable
internal fun <T> ApiSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    testTag: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Row(Modifier.padding(3.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag(testTag(option))
                        .semantics {
                            this.selected = isSelected
                            role = Role.Tab
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) colors.surface else Color.Transparent,
                    contentColor = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(label(option), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** A menu picker for enum parameters and content types (iOS `.pickerStyle(.menu)`). */
@Composable
internal fun ApiDropdown(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    accessibilityLabel: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .testTag(testTag)
                .semantics { contentDescription = "$accessibilityLabel: $value" },
            shape = RoundedCornerShape(13.dp),
            color = colors.surfaceVariant,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value.ifEmpty { "Select" }, modifier = Modifier.weight(1f), style = monospaceStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = monospaceStyle()) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.testTag("$testTag.option.$option"),
                )
            }
        }
    }
}

/** iOS `AppFeedbackBanner`. */
@Composable
internal fun ApiFeedbackBanner(title: String?, message: String, isError: Boolean, testTag: String? = null) {
    val accent = if (isError) MaterialTheme.colorScheme.error else ApiReadColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .background(accent.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface), RoundedCornerShape(14.dp))
            .semantics(mergeDescendants = true) {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                contentDescription = listOfNotNull(title, message).joinToString(". ")
            }
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, contentDescription = null, tint = accent)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A tappable row card with a leading icon tile and a chevron (iOS NavigationLink labels). */
@Composable
internal fun ApiNavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.20f),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = testTag,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(11.dp),
                color = accent.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface),
                contentColor = accent,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The iOS "Complete API" entry card used on resource and domain details: "Search official
 * operations or send a manual raw request".
 */
@Composable
fun CompleteApiEntryCard(
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    title: String = "Complete API",
    subtitle: String = "Search official operations or send a manual raw request",
) {
    ApiNavigationCard(
        title = title,
        subtitle = subtitle,
        icon = Icons.Rounded.Terminal,
        accent = accent,
        onClick = onClick,
        testTag = testTag,
        modifier = modifier,
    )
}

/**
 * The iOS dashboard action pair: "Dashboard" and "Complete API" side by side, stacked when the
 * screen is narrow or the font is large.
 */
@Composable
fun DashboardAndCompleteApiActions(
    onOpenDashboard: (() -> Unit)?,
    onOpenCompleteApi: () -> Unit,
    dashboardTestTag: String,
    completeApiTestTag: String,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = LocalDensity.current.fontScale >= 1.3f || maxWidth < 300.dp
        val dashboard: @Composable (Modifier) -> Unit = { buttonModifier ->
            onOpenDashboard?.let { open ->
                ThemedActionButton(
                    "DASHBOARD",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        open()
                    },
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = buttonModifier,
                    testTag = dashboardTestTag,
                )
            }
        }
        val completeApi: @Composable (Modifier) -> Unit = { buttonModifier ->
            ThemedActionButton(
                "COMPLETE API",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onOpenCompleteApi()
                },
                tone = ThemedActionTone.NEUTRAL,
                modifier = buttonModifier,
                testTag = completeApiTestTag,
            )
        }
        if (stacked || onOpenDashboard == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dashboard(Modifier.fillMaxWidth())
                completeApi(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dashboard(Modifier.weight(1f))
                completeApi(Modifier.weight(1f))
            }
        }
    }
}
