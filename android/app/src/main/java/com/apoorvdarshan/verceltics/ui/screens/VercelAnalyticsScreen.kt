package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsBreakdownUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsDataUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsEnvironment
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsRange
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsUiState
import com.apoorvdarshan.verceltics.ui.VercelDeploymentUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.AppPullToRefresh
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.vercel.VercelAnalyticsChart
import com.apoorvdarshan.verceltics.ui.vercel.VercelAnalyticsSkeleton
import com.apoorvdarshan.verceltics.ui.vercel.VercelDeltaTone
import com.apoorvdarshan.verceltics.ui.vercel.VercelDetailRow
import com.apoorvdarshan.verceltics.ui.vercel.VercelEmptyState
import com.apoorvdarshan.verceltics.ui.vercel.VercelFeedbackBanner
import com.apoorvdarshan.verceltics.ui.vercel.VercelInfoPanel
import com.apoorvdarshan.verceltics.ui.vercel.VercelLayout
import com.apoorvdarshan.verceltics.ui.vercel.vercelColumnsDescription
import com.apoorvdarshan.verceltics.ui.vercel.vercelWindowWidthDp
import com.apoorvdarshan.verceltics.ui.vercel.VercelProjectIcon
import com.apoorvdarshan.verceltics.ui.vercel.VercelStatusBadge
import com.apoorvdarshan.verceltics.ui.vercel.VercelStatusDot
import com.apoorvdarshan.verceltics.ui.vercel.capitalizedVercelState
import com.apoorvdarshan.verceltics.ui.vercel.formatVercelBounceRate
import com.apoorvdarshan.verceltics.ui.vercel.formatVercelDelta
import com.apoorvdarshan.verceltics.ui.vercel.formatVercelMetric
import com.apoorvdarshan.verceltics.ui.vercel.isVercelAliasDomain
import com.apoorvdarshan.verceltics.ui.vercel.prettyVercelFrameworkName
import com.apoorvdarshan.verceltics.ui.vercel.shortVercelSha
import com.apoorvdarshan.verceltics.ui.vercel.vercelBounceChange
import com.apoorvdarshan.verceltics.ui.vercel.vercelBreakdownLabel
import com.apoorvdarshan.verceltics.ui.vercel.vercelCountryFlag
import com.apoorvdarshan.verceltics.ui.vercel.vercelDeltaTone
import com.apoorvdarshan.verceltics.ui.vercel.vercelPercentChange
import com.apoorvdarshan.verceltics.ui.vercel.vercelRelativeTime
import com.apoorvdarshan.verceltics.ui.vercel.vercelStaleAnalyticsMessage
import com.apoorvdarshan.verceltics.ui.vercel.vercelStatusTone
import com.apoorvdarshan.verceltics.ui.vercel.vercelToneColor
import com.apoorvdarshan.verceltics.ui.vercel.vercelUtmLockedTitle
import com.apoorvdarshan.verceltics.ui.vercel.vercelWarningColor
import com.apoorvdarshan.verceltics.ui.vercel.vercelWebsiteUrl
import java.util.Locale

/**
 * Project analytics (iOS `AnalyticsView`): favicon and domain header, range and environment
 * pickers that stay usable while loading, shimmer skeleton, stats with inverted bounce deltas,
 * the interactive chart, project details, recent deployments, domains and breakdowns.
 *
 * Like iOS `.refreshable`, pulling down reloads the report and project context. Regular-width
 * windows (600dp and wider) center the page at 1100dp and lay the panels out in two or three
 * columns; phones keep one column.
 */
@Composable
internal fun VercelAnalyticsScreen(
    project: VercelProjectUi,
    state: VercelAnalyticsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRangeSelected: (VercelAnalyticsRange) -> Unit,
    onEnvironmentSelected: (VercelAnalyticsEnvironment) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeployment: (VercelDeploymentUi) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.analytics"),
    ) {
        AnalyticsTopBar(project = project, isWorking = state.isWorking, onBack = onBack, onRefresh = onRefresh)
        AppPullToRefresh(
            isRefreshing = state.isWorking,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            testTag = "workspace.hosting.analytics.pullToRefresh",
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fontScale = LocalDensity.current.fontScale
                val metrics = VercelLayout.page(
                    availableWidthDp = maxWidth.value,
                    windowWidthDp = vercelWindowWidthDp(),
                    compactPaddingDp = 16f,
                    maxContentWidthDp = VercelLayout.ANALYTICS_MAX_WIDTH_DP,
                )
                val chartHeight = if (metrics.isRegular || fontScale >= 1.3f) 340.dp else 260.dp
                val stackedStats = shouldUseStackedVercelLayout(metrics.contentWidthDp, fontScale)
                val columns = VercelLayout.analyticsPanelColumns(metrics)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("workspace.hosting.analytics.grid")
                        .semantics { stateDescription = vercelColumnsDescription(columns) },
                    contentPadding = PaddingValues(
                        start = metrics.horizontalPadding,
                        top = 6.dp,
                        end = metrics.horizontalPadding,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    fullWidth("header") {
                        AnalyticsHeader(
                            project = project,
                            state = state,
                            onRangeSelected = onRangeSelected,
                            onEnvironmentSelected = onEnvironmentSelected,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                    when {
                        state.isLoading && !state.hasVisibleContent -> fullWidth("loading") {
                            VercelAnalyticsSkeleton(chartHeight = chartHeight)
                        }

                        state.error != null && !state.hasVisibleContent -> fullWidth("error-state") {
                            VercelEmptyState(
                                icon = Icons.Rounded.Warning,
                                title = "Couldn’t load data",
                                message = state.error,
                                actionTitle = "Try again",
                                onAction = onRefresh,
                                testTag = "workspace.hosting.analytics.errorState",
                            )
                        }

                        else -> analyticsContent(
                            project = project,
                            state = state,
                            chartHeight = chartHeight,
                            stackedStats = stackedStats,
                            onRefresh = onRefresh,
                            onOpenDeployment = onOpenDeployment,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.analyticsContent(
    project: VercelProjectUi,
    state: VercelAnalyticsUiState,
    chartHeight: Dp,
    stackedStats: Boolean,
    onRefresh: () -> Unit,
    onOpenDeployment: (VercelDeploymentUi) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    when (analyticsFeedbackPresentation(state.error, state.unavailableMessage)) {
        AnalyticsFeedbackPresentation.ERROR -> fullWidth("error") {
            VercelFeedbackBanner(
                title = "Analytics refresh failed",
                message = vercelStaleAnalyticsMessage(
                    error = checkNotNull(state.error),
                    rangeLabel = state.displayedRange?.shortLabel,
                    environmentLabel = state.displayedEnvironment?.controlLabel,
                ),
                tint = vercelWarningColor(),
                actionTitle = "Retry",
                onAction = onRefresh,
                testTag = "workspace.hosting.analytics.error",
            )
        }

        AnalyticsFeedbackPresentation.UNAVAILABLE -> fullWidth("unavailable") {
            VercelFeedbackBanner(
                title = "Analytics unavailable",
                message = checkNotNull(state.unavailableMessage),
                tint = MaterialTheme.colorScheme.primary,
                icon = Icons.Rounded.BarChart,
                testTag = "workspace.hosting.analytics.unavailable",
            )
        }

        null -> Unit
    }

    val data = state.data
    if (state.unavailableMessage == null && data != null) {
        fullWidth("stats") { AnalyticsStats(data, stacked = stackedStats) }
        fullWidth("chart") {
            OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                VercelAnalyticsChart(
                    points = data.timeseries,
                    chartHeight = chartHeight,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
    }

    item(key = "project-overview") { ProjectSnapshotPanel(project, state) }
    item(key = "deployments") { DeploymentsPanel(state, onOpenDeployment) }
    item(key = "domains") { DomainsPanel(state.domains, onOpenUrl) }

    if (state.unavailableMessage == null && data != null) {
        analyticsBreakdownSections(data, state.hasLongAnalyticsHistory).forEach { section ->
            item(key = "breakdown-${section.title}") { AnalyticsBreakdownPanel(section) }
        }
    }
}

@Composable
private fun AnalyticsTopBar(
    project: VercelProjectUi,
    isWorking: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
            testTag = "workspace.hosting.analytics.back",
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Back to Vercel projects"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(25.dp))
            }
        }
        Text(
            text = project.name,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = !isWorking,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onRefresh()
            },
            testTag = "workspace.hosting.analytics.refresh",
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = if (isWorking) "Refreshing project analytics" else "Refresh project analytics"
                        role = Role.Button
                        if (isWorking) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(21.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsHeader(
    project: VercelProjectUi,
    state: VercelAnalyticsUiState,
    onRangeSelected: (VercelAnalyticsRange) -> Unit,
    onEnvironmentSelected: (VercelAnalyticsEnvironment) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VercelProjectIcon(domain = project.primaryDomain, name = project.name)
            val domain = project.primaryDomain
            val url = vercelWebsiteUrl(domain)
            if (domain != null && url != null) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onOpenUrl(url)
                    },
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 40.dp)
                        .testTag("workspace.hosting.analytics.domainLink")
                        .semantics { contentDescription = "Open $domain" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            domain,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Box(
                            modifier = Modifier
                                .size(17.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.NorthEast,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
            }
        }
        AnalyticsFilters(state = state, onRangeSelected = onRangeSelected, onEnvironmentSelected = onEnvironmentSelected)
        state.lastUpdatedMillis?.let { updatedAt ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("workspace.hosting.analytics.updated"),
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Updated ${vercelRelativeTime(updatedAt).replaceFirstChar { it.lowercase(Locale.getDefault()) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnalyticsFilters(
    state: VercelAnalyticsUiState,
    onRangeSelected: (VercelAnalyticsRange) -> Unit,
    onEnvironmentSelected: (VercelAnalyticsEnvironment) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = shouldUseStackedVercelLayout(maxWidth.value, LocalDensity.current.fontScale)
        val rangeMenu: @Composable (Modifier) -> Unit = { menuModifier ->
            AnalyticsMenu(
                modifier = menuModifier,
                value = state.selectedRange.controlLabel,
                contentDescription = "Analytics range, ${state.selectedRange.controlLabel}",
                icon = Icons.Rounded.CalendarMonth,
                testTag = "workspace.hosting.analytics.range",
                entries = VercelAnalyticsRange.entries.map { range ->
                    AnalyticsMenuEntry(
                        label = range.menuLabel,
                        isSelected = range == state.selectedRange,
                        isLocked = range.requiresLongHistory && !state.hasLongAnalyticsHistory,
                        testTag = "workspace.hosting.analytics.range.${range.shortLabel}",
                        onSelect = { onRangeSelected(range) },
                    )
                },
            )
        }
        val environmentMenu: @Composable (Modifier) -> Unit = { menuModifier ->
            AnalyticsMenu(
                modifier = menuModifier,
                value = state.selectedEnvironment.controlLabel,
                contentDescription = "Deployment environment, ${state.selectedEnvironment.controlLabel}",
                icon = Icons.Rounded.Inventory2,
                testTag = "workspace.hosting.analytics.environment",
                entries = VercelAnalyticsEnvironment.entries.map { environment ->
                    AnalyticsMenuEntry(
                        label = environment.menuLabel,
                        isSelected = environment == state.selectedEnvironment,
                        isLocked = false,
                        testTag = "workspace.hosting.analytics.environment.${environment.name.lowercase(Locale.ROOT)}",
                        onSelect = { onEnvironmentSelected(environment) },
                    )
                },
            )
        }
        val progress: @Composable () -> Unit = {
            if (state.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = "Refreshing analytics" },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rangeMenu(Modifier.fillMaxWidth())
                environmentMenu(Modifier.fillMaxWidth())
                progress()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                rangeMenu(Modifier.weight(1f))
                environmentMenu(Modifier.weight(1f))
                progress()
            }
        }
    }
}

private data class AnalyticsMenuEntry(
    val label: String,
    val isSelected: Boolean,
    val isLocked: Boolean,
    val testTag: String,
    val onSelect: () -> Unit,
)

@Composable
private fun AnalyticsMenu(
    value: String,
    contentDescription: String,
    icon: ImageVector,
    testTag: String,
    entries: List<AnalyticsMenuEntry>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(modifier) {
        ThemedGlassControl(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                expanded = true
            },
            testTag = testTag,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .semantics {
                        this.contentDescription = contentDescription
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(
                            if (entry.isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .testTag(entry.testTag)
                        .semantics {
                            selected = entry.isSelected
                            stateDescription = listOfNotNull(
                                "Selected".takeIf { entry.isSelected },
                                "May need a Vercel plan with longer history".takeIf { entry.isLocked },
                            ).joinToString(", ")
                        },
                    text = {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (entry.isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(28.dp)
                                .background(
                                    if (entry.isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                    },
                    trailingIcon = if (entry.isSelected || entry.isLocked) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (entry.isLocked) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .testTag("${entry.testTag}.lock"),
                                    )
                                }
                                if (entry.isSelected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.primary,
                        trailingIconColor = MaterialTheme.colorScheme.tertiary,
                    ),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        entry.onSelect()
                    },
                )
            }
        }
    }
}

private data class AnalyticsStat(
    val label: String,
    val value: String,
    val change: Double?,
    val invert: Boolean,
    val icon: ImageVector,
)

@Composable
private fun AnalyticsStats(data: VercelAnalyticsDataUi, stacked: Boolean) {
    val stats = listOf(
        AnalyticsStat(
            "Visitors",
            formatVercelMetric(data.overview.visitors),
            vercelPercentChange(data.overview.visitors, data.previousOverview?.visitors),
            invert = false,
            icon = Icons.Rounded.Group,
        ),
        AnalyticsStat(
            "Page Views",
            formatVercelMetric(data.overview.pageViews),
            vercelPercentChange(data.overview.pageViews, data.previousOverview?.pageViews),
            invert = false,
            icon = Icons.Rounded.Visibility,
        ),
        AnalyticsStat(
            "Bounce Rate",
            formatVercelBounceRate(data.overview.bounceRate),
            vercelBounceChange(data.overview.bounceRate, data.previousOverview?.bounceRate),
            invert = true,
            icon = Icons.AutoMirrored.Rounded.Undo,
        ),
    )
    Box(Modifier.testTag("workspace.hosting.analytics.stats")) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.forEach { AnalyticsStatCard(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.forEach { AnalyticsStatCard(it, Modifier.weight(1f)) }
            }
        }
    }
}

/** iOS `StatCard`: bounce rate uses inverted colors, so a rising bounce rate reads as bad. */
@Composable
private fun AnalyticsStatCard(stat: AnalyticsStat, modifier: Modifier) {
    OffsetPanel(
        modifier = modifier.heightIn(min = 112.dp),
        color = MaterialTheme.colorScheme.surface,
        testTag = "workspace.hosting.analytics.stat.${stat.label.lowercase(Locale.ROOT).replace(' ', '-')}",
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    stat.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    stat.label.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stat.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            DeltaChip(change = stat.change, invert = stat.invert)
        }
    }
}

@Composable
private fun DeltaChip(change: Double?, invert: Boolean) {
    val text = formatVercelDelta(change)
    val tone = vercelDeltaTone(change, invert)
    if (text == null || tone == VercelDeltaTone.NONE || change == null) {
        Text("—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val color = if (tone == VercelDeltaTone.GOOD) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.18f)),
        contentColor = color,
        modifier = Modifier.semantics {
            stateDescription = if (tone == VercelDeltaTone.GOOD) "Improved" else "Worsened"
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (change >= 0) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProjectSnapshotPanel(project: VercelProjectUi, state: VercelAnalyticsUiState) {
    val details = state.projectDetails ?: project
    VercelInfoPanel(
        title = "Project",
        icon = Icons.Rounded.Folder,
        testTag = "workspace.hosting.analytics.overview",
    ) {
        VercelDetailRow(
            icon = Icons.Rounded.Layers,
            title = "Scope",
            value = (details.scope ?: project.scope)?.name ?: "Personal",
        )
        VercelDetailRow(
            icon = Icons.Rounded.Inventory2,
            title = "Framework",
            value = (details.framework ?: project.framework)?.let(::prettyVercelFrameworkName) ?: "Not set",
        )
        (details.repository ?: project.repository)?.let { repository ->
            VercelDetailRow(icon = Icons.Rounded.Code, title = "Repository", value = repository)
        }
        (details.lastDeployment ?: project.lastDeployment)?.let { deployment ->
            VercelDetailRow(
                icon = Icons.Rounded.ChatBubble,
                title = "Last Commit",
                value = deployment.commitMessage ?: "No commit message",
            )
            deployment.createdAtMillis?.let { createdAt ->
                VercelDetailRow(icon = Icons.Rounded.Schedule, title = "Last Deploy", value = vercelRelativeTime(createdAt))
            }
        }
    }
}

@Composable
private fun DeploymentsPanel(state: VercelAnalyticsUiState, onOpenDeployment: (VercelDeploymentUi) -> Unit) {
    VercelInfoPanel(
        title = "Recent Deployments",
        icon = Icons.Rounded.Inventory2,
        testTag = "workspace.hosting.analytics.deployments",
    ) {
        when {
            state.recentDeployments.isNotEmpty() -> state.recentDeployments.take(5).forEach { deployment ->
                DeploymentRow(deployment, onOpenDeployment)
            }

            state.isContextLoading && !state.hasLoadedContext -> InfoPanelMessage("Loading deployments")
            else -> InfoPanelMessage("No deployments returned")
        }
    }
}

@Composable
private fun DeploymentRow(deployment: VercelDeploymentUi, onOpenDeployment: (VercelDeploymentUi) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val tone = vercelStatusTone(deployment.state)
    val subtitle = listOfNotNull(
        deployment.target,
        deployment.branch,
        shortVercelSha(deployment.commitSha),
        deployment.createdAtMillis?.let { vercelRelativeTime(it) },
    ).joinToString(" · ")
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onOpenDeployment(deployment)
        },
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace.hosting.analytics.deployment.${deployment.id}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VercelStatusDot(color = vercelToneColor(tone), modifier = Modifier.padding(top = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        deployment.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    VercelStatusBadge(text = capitalizedVercelState(deployment.state), tone = tone)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (deployment.branch != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.CallSplit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DomainsPanel(domains: List<String>, onOpenUrl: (String) -> Unit) {
    VercelInfoPanel(
        title = "Domains",
        icon = Icons.Rounded.Language,
        testTag = "workspace.hosting.analytics.domains",
    ) {
        if (domains.isEmpty()) {
            InfoPanelMessage("No verified domains returned")
        } else {
            domains.take(8).forEach { domain ->
                val isVercel = isVercelAliasDomain(domain)
                val url = vercelWebsiteUrl(domain)
                VercelDetailRow(
                    icon = if (isVercel) Icons.Rounded.Public else Icons.Rounded.Verified,
                    title = if (isVercel) "Vercel Alias" else "Custom Domain",
                    value = domain,
                    onClick = url?.let { { onOpenUrl(it) } },
                    testTag = "workspace.hosting.analytics.domain.$domain",
                )
            }
        }
    }
}

@Composable
private fun InfoPanelMessage(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

internal enum class AnalyticsFeedbackPresentation {
    ERROR,
    UNAVAILABLE,
}

/** A refresh failure takes precedence over a retained unavailable result, so only one card renders. */
internal fun analyticsFeedbackPresentation(
    error: String?,
    unavailableMessage: String?,
): AnalyticsFeedbackPresentation? = when {
    error != null -> AnalyticsFeedbackPresentation.ERROR
    unavailableMessage != null -> AnalyticsFeedbackPresentation.UNAVAILABLE
    else -> null
}

internal data class AnalyticsBreakdownSection(
    val title: String,
    val items: List<VercelAnalyticsBreakdownUi>,
    val emptyLabel: String = "",
    val isCountry: Boolean = false,
    val lockedTitle: String? = null,
    val lockedSubtitle: String? = null,
)

internal fun analyticsBreakdownSections(
    data: VercelAnalyticsDataUi,
    hasLongAnalyticsHistory: Boolean,
): List<AnalyticsBreakdownSection> = listOf(
    AnalyticsBreakdownSection("Pages", data.pages),
    AnalyticsBreakdownSection("Routes", data.routes),
    AnalyticsBreakdownSection("Hostnames", data.hostnames),
    AnalyticsBreakdownSection("Referrers", data.referrers, emptyLabel = "Direct"),
    AnalyticsBreakdownSection(
        "UTM Parameters",
        data.utmSources,
        lockedTitle = vercelUtmLockedTitle(hasLongAnalyticsHistory),
        lockedSubtitle = "to access this feature",
    ),
    AnalyticsBreakdownSection("Countries", data.countries, isCountry = true),
    AnalyticsBreakdownSection("Devices", data.devices),
    AnalyticsBreakdownSection("Browsers", data.browsers),
    AnalyticsBreakdownSection("Operating Systems", data.operatingSystems),
    AnalyticsBreakdownSection(
        "Events",
        data.events,
        lockedTitle = "Requires Pro",
        lockedSubtitle = "Upgrade your Vercel plan",
    ),
    AnalyticsBreakdownSection("Flags", data.flags),
    AnalyticsBreakdownSection("Query Parameters", data.queryParameters),
)

private fun breakdownIcon(title: String): ImageVector = when (title) {
    "Pages" -> Icons.Rounded.Description
    "Routes" -> Icons.AutoMirrored.Rounded.CallSplit
    "Hostnames" -> Icons.Rounded.Dns
    "Referrers" -> Icons.Rounded.Link
    "UTM Parameters" -> Icons.Rounded.Sell
    "Countries" -> Icons.Rounded.Public
    "Devices" -> Icons.Rounded.Devices
    "Browsers" -> Icons.Rounded.Explore
    "Operating Systems" -> Icons.Rounded.Laptop
    "Events" -> Icons.Rounded.Bolt
    "Flags" -> Icons.Rounded.Flag
    else -> Icons.Rounded.QuestionMark
}

@Composable
private fun AnalyticsBreakdownPanel(section: AnalyticsBreakdownSection) {
    VercelInfoPanel(
        title = section.title,
        icon = breakdownIcon(section.title),
        testTag = "workspace.hosting.analytics.breakdown.${section.title.lowercase(Locale.ROOT).replace(' ', '-')}",
        trailing = {
            Row {
                Text(
                    "VIEWS",
                    modifier = Modifier.width(54.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
                Text(
                    "VISITORS",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        },
    ) {
        if (section.items.isEmpty()) {
            val lockedTitle = section.lockedTitle
            if (lockedTitle != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        lockedTitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        section.lockedSubtitle ?: "Upgrade your Vercel plan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                InfoPanelMessage("No data available")
            }
        } else {
            val maximumVisitors = section.items.maxOf(VercelAnalyticsBreakdownUi::visitors).coerceAtLeast(1L)
            Column(Modifier.padding(bottom = 4.dp)) {
                section.items.take(8).forEach { item ->
                    BreakdownRow(item, section, maximumVisitors)
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(item: VercelAnalyticsBreakdownUi, section: AnalyticsBreakdownSection, maximumVisitors: Long) {
    val fraction = (item.visitors.toFloat() / maximumVisitors.toFloat()).coerceIn(0f, 1f)
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    val label = vercelBreakdownLabel(item.key, section.emptyLabel, section.isCountry)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, ${formatVercelMetric(item.pageViews)} views, " +
                    "${formatVercelMetric(item.visitors)} visitors"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 36.dp)
                .drawBehind {
                    drawRoundRect(
                        color = barColor,
                        size = Size(width = size.width * fraction, height = size.height),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (section.isCountry) {
                    val flag = vercelCountryFlag(item.key)
                    if (flag.isNotEmpty()) Text(flag, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatVercelMetric(item.pageViews),
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Text(
            formatVercelMetric(item.visitors),
            modifier = Modifier
                .width(64.dp)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
