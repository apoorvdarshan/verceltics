package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.ui.components.AppPullToRefresh
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedModalBottomSheet
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAdaptivePage
import com.apoorvdarshan.verceltics.ui.hosting.ProviderContentMetrics
import com.apoorvdarshan.verceltics.ui.hosting.ProviderLayout
import kotlinx.coroutines.launch

/** iOS `CloudflareStyle`. */
object CloudflareOpsColors {
    val Orange = Color(0xFFF56B1F)
    val Amber = Color(0xFFFFA633)
    val Green = Color(0xFF35C86F)
    val Yellow = Color(0xFFFFD83D)

    @Composable
    fun red(): Color = MaterialTheme.colorScheme.error

    /** iOS `statusColor` for zones, deployments and stages. */
    @Composable
    fun forStatus(status: String?): Color = when (status?.lowercase()) {
        "active", "ready", "success", "deployed", "available", "enabled", "on", "online", "healthy" -> Green
        "pending", "initializing", "building", "queued", "active_pending", "initializing_pending", "deploying",
        "in_progress", "provisioning",
        -> Amber
        "moved", "deactivated", "error", "failed", "failure", "canceled", "cancelled", "blocked", "disabled",
        "off", "deleted",
        -> red()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Width context of the operations page being composed, so shared components (metric grids,
 * two-pane sections) can adapt on wide windows without changing their phone layout.
 */
val LocalCloudflareOpsMetrics = staticCompositionLocalOf<ProviderContentMetrics?> { null }

/**
 * Standard scrolling page for an operations screen.
 *
 * Phones keep the 18 dp edge-to-edge page. On windows of 600 dp and wider the content is centered
 * at [maximumContentWidth] (iOS `appContentWidth` / `.frame(maxWidth:)`) with 24 dp minimum
 * padding. When [onRefresh] is set the page supports pull-to-refresh like iOS `.refreshable`.
 */
@Composable
fun CloudflareOpsScreen(
    testTag: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    maximumContentWidth: Dp = ProviderLayout.DetailMaxWidth,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.(ProviderContentMetrics) -> Unit,
) {
    ProviderAdaptivePage(maximumContentWidth = maximumContentWidth, modifier = modifier.fillMaxSize()) { metrics ->
        CompositionLocalProvider(LocalCloudflareOpsMetrics provides metrics) {
            AppPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = { onRefresh?.invoke() },
                enabled = onRefresh != null,
                modifier = Modifier.fillMaxSize(),
                testTag = if (onRefresh == null) null else "$testTag.pullToRefresh",
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(testTag),
                    state = state,
                    contentPadding = metrics.contentPadding(top = 6.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    content(metrics)
                }
            }
        }
    }
}

/** iOS `.cloudflarePanel(accentOpacity:)`. */
@Composable
fun CloudflareOpsPanel(
    modifier: Modifier = Modifier,
    accent: Float = 0f,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = if (accent > 0f) CloudflareOpsColors.Orange.copy(alpha = accent).compositeOver(surface) else surface,
        borderColor = if (accent > 0f) CloudflareOpsColors.Orange.copy(alpha = 0.22f) else MaterialTheme.colorScheme.outline,
        testTag = testTag,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun CloudflareOpsIconTile(icon: ImageVector, tint: Color = CloudflareOpsColors.Orange, size: Int = 34) {
    Box(
        Modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape((size / 3.4f).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.56f).dp))
    }
}

/** iOS `CloudflareSectionHeader`. */
@Composable
fun CloudflareOpsSectionHeader(
    title: String,
    icon: ImageVector,
    count: Int? = null,
    actionTitle: String? = null,
    actionTestTag: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CloudflareOpsIconTile(icon, size = 28)
        Text(
            title,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    count.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (actionTitle != null && onAction != null) {
            Text(
                actionTitle,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .then(if (actionTestTag == null) Modifier else Modifier.testTag(actionTestTag)),
                color = CloudflareOpsColors.Orange.copy(alpha = if (actionEnabled) 1f else 0.4f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun CloudflareOpsDivider(inset: Boolean = false) {
    HorizontalDivider(
        modifier = if (inset) Modifier.padding(start = 60.dp) else Modifier,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
    )
}

/** iOS `CloudflareDetailRow`: a labelled value that can be copied. */
@Composable
fun CloudflareOpsDetailRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    monospace: Boolean = false,
    copyable: Boolean = false,
    testTag: String? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            modifier = Modifier.widthIn(max = 160.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        SelectionContainer(Modifier.weight(2f, fill = false)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (copyable) {
            IconButton(
                onClick = {
                    scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) } }
                },
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** iOS `CloudflareResourceRow`: icon tile, title, subtitle and an optional trailing element. */
@Composable
fun CloudflareOpsResourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = CloudflareOpsColors.Orange,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onClick()
                    }
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CloudflareOpsIconTile(icon, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun CloudflareOpsStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    StatusPill(text, color, modifier)
}

/** iOS `CloudflareMetricCard`. */
@Composable
fun CloudflareOpsMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = CloudflareOpsColors.Orange,
) {
    OffsetPanel(modifier = modifier, color = MaterialTheme.colorScheme.surface, borderColor = MaterialTheme.colorScheme.outline) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

data class CloudflareOpsMetric(val title: String, val value: String, val icon: ImageVector, val accent: Color = CloudflareOpsColors.Orange)

/**
 * Grid of metric cards: two columns on phones; on wide windows as many 200 dp columns as fit (iOS
 * `metricColumns` = `adaptiveColumns(regularMinimum: 200, regularMaximum: 250)`).
 */
@Composable
fun CloudflareOpsMetricGrid(metrics: List<CloudflareOpsMetric>, modifier: Modifier = Modifier) {
    val page = LocalCloudflareOpsMetrics.current
    val columns = cloudflareOpsMetricColumns(page)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { metric ->
                    CloudflareOpsMetricCard(metric.title, metric.value, metric.icon, Modifier.weight(1f), metric.accent)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Metric columns: always two on compact windows (unchanged phone layout). */
internal fun cloudflareOpsMetricColumns(page: ProviderContentMetrics?): Int =
    if (page == null || !page.isRegular) 2 else page.columns(minimumCellWidth = 200.dp, spacing = 10.dp, maximumColumns = 6).coerceAtLeast(2)

/** iOS `CloudflareEmptySection`. */
@Composable
fun CloudflareOpsEmptySection(icon: ImageVector, title: String, message: String, testTag: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 26.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CloudflareOpsLoading(message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp)
            .semantics { contentDescription = message ?: "Loading" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(color = CloudflareOpsColors.Orange, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** iOS `CloudflareWriteNotice`. */
@Composable
fun CloudflareWriteNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.writeNotice"),
        shape = RoundedCornerShape(14.dp),
        color = CloudflareOpsColors.Orange.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, CloudflareOpsColors.Orange.copy(alpha = 0.16f)),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = CloudflareOpsColors.Orange, modifier = Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Write access is guarded", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Changes use the connected Cloudflare credential. Destructive actions always ask for confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** iOS `CloudflareActionResultBanner`. */
@Composable
fun CloudflareActionResultBanner(banner: CloudflareActionBanner, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val accent = if (banner.isError) MaterialTheme.colorScheme.error else CloudflareOpsColors.Green
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.actionBanner")
            .semantics { liveRegion = if (banner.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.11f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (banner.isError) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(banner.message, modifier = Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss message", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** iOS `CloudflareActionButton`: a capsule action, red when destructive. */
@Composable
fun CloudflareOpsActionButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    working: Boolean = false,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else CloudflareOpsColors.Orange
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        enabled = enabled && !working,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = tint.copy(alpha = if (enabled) 1f else 0.4f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The one confirmation surface for every Cloudflare mutation. It states exactly what will change and
 * styles destructive actions with the destructive tone.
 */
@Composable
fun CloudflareConfirmationDialog(
    prompt: CloudflareConfirmationPrompt?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    prompt ?: return
    val haptic = LocalHapticFeedback.current
    ThemedAlertDialog(
        title = prompt.title,
        message = prompt.message,
        confirmText = prompt.confirmLabel.uppercase(),
        confirmTone = if (prompt.destructive) ThemedActionTone.DESTRUCTIVE else ThemedActionTone.PRIMARY,
        dismissText = "CANCEL",
        onConfirm = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onConfirm()
        },
        onDismissRequest = onDismiss,
        testTag = "cloudflare.confirmation",
    )
}

/** Renders a [CloudflareOperationsViewModel]'s pending confirmation and result banner plumbing. */
@Composable
fun CloudflareConfirmationHost(viewModel: CloudflareOperationsViewModel) {
    val prompt by viewModel.confirmation.collectAsStateWithLifecycle()
    CloudflareConfirmationDialog(
        prompt = prompt,
        onConfirm = { viewModel.confirmPendingMutation() },
        onDismiss = viewModel::dismissPendingMutation,
    )
}

/** Themed text input used by every Cloudflare editor. */
@Composable
fun CloudflareOpsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 12,
    monospace: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontSize = if (monospace) 13.sp else MaterialTheme.typography.bodyMedium.fontSize,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CloudflareOpsColors.Orange,
            focusedLabelColor = CloudflareOpsColors.Orange,
            cursorColor = CloudflareOpsColors.Orange,
            unfocusedBorderColor = colors.outline,
        ),
    )
}

@Composable
fun CloudflareOpsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CloudflareOpsColors.Orange),
        )
    }
}

/** Horizontally scrolling single-choice capsules (iOS segmented pickers and range rails). */
@Composable
fun <T> CloudflareOpsChoiceRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTagPrefix: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val text = label(option)
            Surface(
                onClick = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                    .then(if (testTagPrefix == null) Modifier else Modifier.testTag("$testTagPrefix.$text")),
                shape = RoundedCornerShape(50),
                color = if (isSelected) CloudflareOpsColors.Orange else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** A labelled dropdown (iOS `Picker` in a form). */
@Composable
fun <T> CloudflareOpsDropdownField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = if (testTag == null) Modifier else Modifier.testTag("$testTag.${optionLabel(option)}"),
                )
            }
        }
    }
}

/** Full-height editor sheet with a title and close button (iOS editor sheets in a NavigationStack). */
@Composable
fun CloudflareOpsEditorSheet(
    title: String,
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ThemedModalBottomSheet(onDismissRequest = onDismiss, testTag = testTag) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("$testTag.close")) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }
        // The title row stays pinned; the form scrolls so long editors (presets plus a multi-line
        // JSON field, for example) keep their submit button reachable on short screens and with
        // large font scales, like iOS sheets.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Selectable monospaced block for JSON, logs, SQL results and source code. */
@Composable
fun CloudflareOpsMonospaceBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, testTag: String? = null) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** iOS edge header: icon, name, subtitle, status pill and quick actions. */
@Composable
fun CloudflareOpsHero(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    status: String?,
    statusColor: Color,
    testTag: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    CloudflareOpsPanel(accent = 0.08f, testTag = testTag) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsIconTile(icon, size = 46)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                }
                status?.let { CloudflareOpsStatusPill(it, statusColor) }
            }
            if (actions != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    content = actions,
                )
            }
        }
    }
}

/** Shown when the connection cannot run live operations (sample data, previews, tests). */
@Composable
fun CloudflareOperationsUnavailable(modifier: Modifier = Modifier) {
    CloudflareOpsPanel(modifier = modifier, testTag = "cloudflare.operationsUnavailable") {
        CloudflareOpsEmptySection(
            icon = Icons.Rounded.Info,
            title = "Live operations unavailable",
            message = "Connect Cloudflare with a Global API Key or scoped API token to load live details and make changes. Sample data is read-only.",
        )
    }
}
