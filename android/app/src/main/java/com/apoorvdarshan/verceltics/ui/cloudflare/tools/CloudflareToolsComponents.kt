package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.contrastingContentColor
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme

internal object CloudflareToolsColors {
    val Orange = Color(0xFFF26B14)
    private val LightAmber = Color(0xFFA65C05)
    private val DarkAmber = Color(0xFFF5A63D)

    @Composable
    fun success(): Color = MaterialTheme.colorScheme.tertiary

    @Composable
    fun warning(): Color = if (LocalVercelticsDarkTheme.current) DarkAmber else LightAmber

    @Composable
    fun danger(): Color = MaterialTheme.colorScheme.error

    /** iOS method colors: GET green, POST orange, PUT/PATCH amber, DELETE red. */
    @Composable
    fun method(method: CloudflareHttpMethod): Color = when (method) {
        CloudflareHttpMethod.GET -> success()
        CloudflareHttpMethod.POST -> Orange
        CloudflareHttpMethod.PUT, CloudflareHttpMethod.PATCH -> warning()
        CloudflareHttpMethod.DELETE -> danger()
    }
}

internal val MonospaceSmall: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
internal fun CloudflareToolsTopBar(
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
            testTag = "cloudflare.tools.back",
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { heading() }
                .testTag("cloudflare.tools.title"),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
            trailing?.invoke()
        }
    }
}

@Composable
internal fun ToolsRefreshAction(isLoading: Boolean, onRefresh: () -> Unit, label: String) {
    AppToolbarAction(
        modifier = Modifier.size(48.dp),
        enabled = !isLoading,
        onClick = onRefresh,
        testTag = "cloudflare.tools.refresh",
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CloudflareToolsColors.Orange)
        } else {
            Icon(Icons.Rounded.Refresh, contentDescription = label)
        }
    }
}

/** iOS `cloudflarePanel(accentOpacity:)`. */
@Composable
internal fun ToolPanel(
    modifier: Modifier = Modifier,
    accentAlpha: Float = 0f,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = if (accentAlpha > 0f) {
            CloudflareToolsColors.Orange.copy(alpha = accentAlpha).compositeOver(MaterialTheme.colorScheme.surface)
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = CloudflareToolsColors.Orange.copy(alpha = 0.20f),
        testTag = testTag,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** iOS `CloudflareSectionHeader`. */
@Composable
internal fun ToolSectionHeader(title: String, icon: ImageVector, count: Int? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = CloudflareToolsColors.Orange, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ToolDivider()
}

@Composable
internal fun ToolDivider(start: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** iOS `CloudflareDetailRow`. Values are selectable for copying IDs. */
@Composable
internal fun ToolDetailRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = CloudflareToolsColors.Orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(0.58f)) {
            Text(
                value,
                color = valueColor,
                style = if (monospace) MonospaceSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A tappable list row with icon, title, subtitle and chevron (iOS `CloudflareResourceRow`). */
@Composable
internal fun ToolNavigationRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CloudflareToolsColors.Orange,
    enabled: Boolean = true,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.48f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIconTile(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            trailing?.invoke()
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ToolIconTile(icon: ImageVector, tint: Color = CloudflareToolsColors.Orange, size: Dp = 38.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.11f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = tint,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f)) }
    }
}

@Composable
internal fun MethodBadge(method: CloudflareHttpMethod, modifier: Modifier = Modifier, width: Dp = 52.dp) {
    val color = CloudflareToolsColors.method(method)
    Box(
        modifier = modifier
            .width(width)
            .heightIn(min = 28.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
            .semantics { contentDescription = "${method.name} request" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            method.name,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
    }
}

/** iOS `CloudflareActionResultBanner`. */
@Composable
internal fun ToolBanner(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val accent = if (isError) CloudflareToolsColors.danger() else CloudflareToolsColors.Orange
    OffsetPanel(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        color = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent.copy(alpha = 0.45f),
        testTag = testTag,
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(actionLabel.uppercase(), color = accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToolLoadingBlock(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = CloudflareToolsColors.Orange)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** iOS `CloudflareEmptySection`. */
@Composable
internal fun ToolEmptyBlock(title: String, message: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ToolMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Segmented single-choice row with 48 dp targets (iOS filter rails and scope toggles). */
@Composable
internal fun <T> ToolSegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    selectedColor: (T) -> Color? = { null },
    testTagPrefix: String,
) {
    val haptic = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val accent = selectedColor(option) ?: CloudflareToolsColors.Orange
            val isEnabled = enabled(option)
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(option)
                },
                enabled = isEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("$testTagPrefix.${label(option)}")
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                    },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) accent else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) {
                    contrastingContentColor(accent)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isEnabled) 1f else 0.38f)
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label(option).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A labelled dropdown picker (iOS `Menu`). */
@Composable
internal fun ToolDropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(testTag),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CloudflareToolsColors.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Choose $label")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (key == selectedKey) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/** Monospace editor used for paths, query strings, headers and request bodies (iOS `TextEditor`). */
@Composable
internal fun ToolTextEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minHeight: Dp = 48.dp,
    maxHeight: Dp = 320.dp,
    prefix: String? = null,
    enabled: Boolean = true,
    testTag: String,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = colors.onSurfaceVariant,
            )
            if (value.isNotEmpty() && !singleLine && enabled) {
                TextButton(onClick = { onValueChange("") }, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("CLEAR", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceVariant,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                if (prefix != null) {
                    Text(prefix, style = MonospaceSmall, color = colors.onSurfaceVariant)
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MonospaceSmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = MonospaceSmall.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(CloudflareToolsColors.Orange),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHeight - 24.dp, max = maxHeight)
                            .testTag(testTag)
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBusy: Boolean = false,
    testTag: String,
    tone: ThemedActionTone = ThemedActionTone.PRIMARY,
) {
    val haptic = LocalHapticFeedback.current
    ThemedActionButton(
        text = text,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        enabled = enabled,
        isBusy = isBusy,
        tone = tone,
        modifier = modifier.fillMaxWidth(),
        testTag = testTag,
    )
}

/** Full-height body helper so every tools screen shares the same background. */
@Composable
internal fun ToolScreenSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

internal fun formatBytes(bytes: Int): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
    else -> "$bytes bytes"
}
