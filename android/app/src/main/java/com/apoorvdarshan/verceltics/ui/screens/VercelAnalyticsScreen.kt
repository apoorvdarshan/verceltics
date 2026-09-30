package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.ui.text.style.TextAlign
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsBreakdownUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsDataUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsEnvironment
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsPointUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsRange
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsUiState
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun VercelAnalyticsScreen(
    project: VercelProjectUi,
    account: VercelAccountUi,
    state: VercelAnalyticsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRangeSelected: (VercelAnalyticsRange) -> Unit,
    onEnvironmentSelected: (VercelAnalyticsEnvironment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.analytics"),
    ) {
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
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(25.dp),
                    )
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
                enabled = !state.isLoading,
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
                            contentDescription = if (state.isLoading) {
                                "Refreshing project analytics"
                            } else {
                                "Refresh project analytics"
                            }
                            role = Role.Button
                            if (state.isLoading) {
                                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(21.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "identity") {
                ProjectIdentityPanel(project, account)
            }
            item(key = "filters") {
                AnalyticsFilters(
                    range = state.selectedRange,
                    environment = state.selectedEnvironment,
                    enabled = !state.isLoading,
                    onRangeSelected = onRangeSelected,
                    onEnvironmentSelected = onEnvironmentSelected,
                )
            }

            if (state.isLoading && !state.hasVisibleContent) {
                item(key = "loading") { AnalyticsLoadingPanel() }
            }

            when (analyticsFeedbackPresentation(state.error, state.unavailableMessage)) {
                AnalyticsFeedbackPresentation.ERROR -> {
                    val error = checkNotNull(state.error)
                    item(key = "error") {
                        AnalyticsFeedbackPanel(
                            title = "Analytics refresh failed",
                            message = if (state.data != null) {
                                "$error Showing the last successful " +
                                    "${state.displayedRange?.shortLabel} · " +
                                    "${state.displayedEnvironment?.controlLabel} result."
                            } else {
                                error
                            },
                            isError = true,
                        )
                    }
                }

                AnalyticsFeedbackPresentation.UNAVAILABLE -> {
                    val message = checkNotNull(state.unavailableMessage)
                    item(key = "unavailable") {
                        AnalyticsFeedbackPanel(
                            title = "Analytics unavailable",
                            message = message,
                            isError = false,
                        )
                    }
                }

                null -> Unit
            }

            state.data?.let { data ->
                item(key = "stats") { AnalyticsStats(data) }
                item(key = "chart") { AnalyticsChartPanel(data.timeseries) }
                analyticsBreakdownSections(data).forEach { section ->
                    item(key = "breakdown-${section.title}") {
                        AnalyticsBreakdownPanel(
                            title = section.title,
                            items = section.items,
                            emptyLabel = section.emptyLabel,
                            lockedMessage = section.lockedMessage,
                        )
                    }
                }
            }

            item(key = "project-overview") {
                ProjectOverviewPanel(project, account)
            }

            state.lastUpdatedMillis?.let { updatedAt ->
                item(key = "updated") {
                    Text(
                        text = "Updated ${formatAnalyticsTimestamp(updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectIdentityPanel(project: VercelProjectUi, account: VercelAccountUi) {
    ProviderSummaryCard(
        provider = requireNotNull(com.apoorvdarshan.verceltics.domain.IntegrationCatalog.provider("vercel")),
        title = project.name,
        subtitle = listOfNotNull(project.framework, account.displayName).joinToString(" · "),
        status = "Connected",
        detail = "Web Analytics",
        testTag = "workspace.hosting.analytics.identity",
    )
}

@Composable
private fun ProjectIdentityText(
    project: VercelProjectUi,
    account: VercelAccountUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = project.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(project.framework, account.displayName).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AnalyticsFilters(
    range: VercelAnalyticsRange,
    environment: VercelAnalyticsEnvironment,
    enabled: Boolean,
    onRangeSelected: (VercelAnalyticsRange) -> Unit,
    onEnvironmentSelected: (VercelAnalyticsEnvironment) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useStackedLayout = shouldUseStackedVercelLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (useStackedLayout) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnalyticsRangeMenu(range, enabled, onRangeSelected, Modifier.fillMaxWidth())
                AnalyticsEnvironmentMenu(environment, enabled, onEnvironmentSelected, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnalyticsRangeMenu(range, enabled, onRangeSelected, Modifier.weight(1f))
                AnalyticsEnvironmentMenu(environment, enabled, onEnvironmentSelected, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnalyticsRangeMenu(
    selected: VercelAnalyticsRange,
    enabled: Boolean,
    onSelected: (VercelAnalyticsRange) -> Unit,
    modifier: Modifier,
) {
    AnalyticsMenu(
        modifier = modifier,
        value = selected.controlLabel,
        contentDescription = "Analytics range, ${selected.controlLabel}",
        icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
        enabled = enabled,
        entries = VercelAnalyticsRange.entries.map { it.controlLabel to { onSelected(it) } },
    )
}

@Composable
private fun AnalyticsEnvironmentMenu(
    selected: VercelAnalyticsEnvironment,
    enabled: Boolean,
    onSelected: (VercelAnalyticsEnvironment) -> Unit,
    modifier: Modifier,
) {
    AnalyticsMenu(
        modifier = modifier,
        value = selected.controlLabel,
        contentDescription = "Deployment environment, ${selected.controlLabel}",
        icon = { Icon(Icons.Rounded.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp)) },
        enabled = enabled,
        entries = VercelAnalyticsEnvironment.entries.map { it.controlLabel to { onSelected(it) } },
    )
}

@Composable
private fun AnalyticsMenu(
    value: String,
    contentDescription: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    entries: List<Pair<String, () -> Unit>>,
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
            enabled = enabled,
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
                icon()
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            entries.forEach { (label, select) ->
                val isSelected = label == value
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .semantics {
                            selected = isSelected
                            if (isSelected) stateDescription = "Selected"
                        },
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(28.dp)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
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
                        select()
                    },
                )
            }
        }
    }
}

@Composable
private fun AnalyticsLoadingPanel() {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
        color = MaterialTheme.colorScheme.surface,
        testTag = "workspace.hosting.analytics.loading",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text("Loading Vercel Web Analytics", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AnalyticsFeedbackPanel(title: String, message: String, isError: Boolean) {
    val tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = tint.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = tint,
        shadowColor = tint.copy(alpha = 0.65f),
        testTag = if (isError) {
            "workspace.hosting.analytics.error"
        } else {
            "workspace.hosting.analytics.unavailable"
        },
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalyticsStats(data: VercelAnalyticsDataUi) {
    val stats = listOf(
        AnalyticsStat(
            "Visitors",
            formatMetric(data.overview.visitors),
            percentChange(data.overview.visitors, data.previousOverview?.visitors),
        ),
        AnalyticsStat(
            "Page views",
            formatMetric(data.overview.pageViews),
            percentChange(data.overview.pageViews, data.previousOverview?.pageViews),
        ),
        AnalyticsStat(
            "Bounce rate",
            data.overview.bounceRate?.let { "${formatPercent(it)}%" } ?: "—",
            bounceChange(data.overview.bounceRate, data.previousOverview?.bounceRate),
        ),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useStackedLayout = shouldUseStackedVercelLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (useStackedLayout) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.forEach { AnalyticsStatPanel(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.forEach { AnalyticsStatPanel(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AnalyticsStatPanel(stat: AnalyticsStat, modifier: Modifier) {
    OffsetPanel(
        modifier = modifier.heightIn(min = 100.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stat.label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stat.value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            stat.change?.let {
                Text(
                    text = if (it > 0) "+${formatPercent(it)}%" else "${formatPercent(it)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AnalyticsChartPanel(points: List<VercelAnalyticsPointUi>) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp),
        color = MaterialTheme.colorScheme.surface,
        testTag = "workspace.hosting.analytics.chart",
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Traffic trend",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                    Text("No timeseries data available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val signal = MaterialTheme.colorScheme.primary
                val grid = MaterialTheme.colorScheme.outlineVariant
                val maxValue = points.maxOf { it.pageViews }.coerceAtLeast(1L)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .semantics {
                            contentDescription = "Traffic trend, ${formatMetric(points.sumOf { it.pageViews })} page views"
                        },
                ) {
                    val left = 4.dp.toPx()
                    val right = size.width - 4.dp.toPx()
                    val top = 8.dp.toPx()
                    val bottom = size.height - 8.dp.toPx()
                    repeat(3) { index ->
                        val y = top + (bottom - top) * index / 2f
                        drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                    }
                    val coordinates = points.mapIndexed { index, point ->
                        val fraction = if (points.size == 1) 0.5f else index.toFloat() / (points.size - 1)
                        Offset(
                            x = left + (right - left) * fraction,
                            y = bottom - (bottom - top) * point.pageViews.toFloat() / maxValue.toFloat(),
                        )
                    }
                    val fillPath = Path().apply {
                        moveTo(coordinates.first().x, bottom)
                        coordinates.forEachIndexed { index, point ->
                            if (index == 0) lineTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        lineTo(coordinates.last().x, bottom)
                        close()
                    }
                    drawPath(fillPath, signal.copy(alpha = 0.16f))
                    val linePath = Path().apply {
                        moveTo(coordinates.first().x, coordinates.first().y)
                        coordinates.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(linePath, signal, style = Stroke(width = 3.dp.toPx()))
                    coordinates.forEach { drawCircle(signal, radius = 3.dp.toPx(), center = it) }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsBreakdownPanel(
    title: String,
    items: List<VercelAnalyticsBreakdownUi>,
    emptyLabel: String,
    lockedMessage: String?,
) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val useStackedLayout = shouldUseStackedVercelLayout(
                availableWidthDp = maxWidth.value,
                fontScale = LocalDensity.current.fontScale,
            )
            Column {
                if (useStackedLayout) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .semantics { heading() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("VIEWS", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(12.dp))
                        Text("VISITORS", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (items.isEmpty()) {
                    Text(
                        text = lockedMessage ?: "No data available",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val maximumVisitors = items.maxOf { it.visitors }.coerceAtLeast(1L)
                    items.take(8).forEach { item ->
                        AnalyticsBreakdownRow(
                            item = item,
                            emptyLabel = emptyLabel,
                            maximumVisitors = maximumVisitors,
                            stacked = useStackedLayout,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsBreakdownRow(
    item: VercelAnalyticsBreakdownUi,
    emptyLabel: String,
    maximumVisitors: Long,
    stacked: Boolean,
) {
    val fillFraction = item.visitors.toFloat() / maximumVisitors.toFloat()
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (stacked) 76.dp else 48.dp)
            .drawBehind {
                drawRect(
                    color = fillColor,
                    size = Size(width = size.width * fillFraction, height = size.height),
                )
            },
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = item.key.ifBlank { emptyLabel.ifBlank { "Unknown" } },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BreakdownMetric("Views", item.pageViews)
                    BreakdownMetric("Visitors", item.visitors)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.key.ifBlank { emptyLabel.ifBlank { "Unknown" } },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatMetric(item.pageViews),
                    modifier = Modifier.widthIn(min = 54.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    formatMetric(item.visitors),
                    modifier = Modifier.widthIn(min = 58.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun BreakdownMetric(label: String, value: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatMetric(value), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ProjectOverviewPanel(project: VercelProjectUi, account: VercelAccountUi) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "workspace.hosting.analytics.overview",
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Project", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            AnalyticsProjectValue("Project ID", project.id)
            AnalyticsProjectValue("Framework", project.framework ?: "Not reported")
            AnalyticsProjectValue(
                "Last updated",
                project.updatedAtMillis?.let(::formatAnalyticsTimestamp) ?: "Not reported",
            )
            AnalyticsProjectValue("Account", account.displayName)
        }
    }
}

@Composable
private fun AnalyticsProjectValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class AnalyticsStat(val label: String, val value: String, val change: Double?)

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

private data class AnalyticsBreakdownSection(
    val title: String,
    val items: List<VercelAnalyticsBreakdownUi>,
    val emptyLabel: String = "",
    val lockedMessage: String? = null,
)

private fun analyticsBreakdownSections(data: VercelAnalyticsDataUi): List<AnalyticsBreakdownSection> =
    listOf(
        AnalyticsBreakdownSection("Pages", data.pages),
        AnalyticsBreakdownSection("Routes", data.routes),
        AnalyticsBreakdownSection("Hostnames", data.hostnames),
        AnalyticsBreakdownSection("Referrers", data.referrers, emptyLabel = "Direct"),
        AnalyticsBreakdownSection(
            "UTM Parameters",
            data.utmSources,
            lockedMessage = "Requires Pro + Web Analytics Plus",
        ),
        AnalyticsBreakdownSection("Countries", data.countries),
        AnalyticsBreakdownSection("Devices", data.devices),
        AnalyticsBreakdownSection("Browsers", data.browsers),
        AnalyticsBreakdownSection("Operating Systems", data.operatingSystems),
        AnalyticsBreakdownSection("Events", data.events, lockedMessage = "Requires Pro"),
        AnalyticsBreakdownSection("Flags", data.flags),
        AnalyticsBreakdownSection("Query Parameters", data.queryParameters),
    )

private fun percentChange(current: Long, previous: Long?): Double? =
    previous?.takeIf { it != 0L }?.let { (current - it).toDouble() / it.toDouble() * 100.0 }

private fun bounceChange(current: Double?, previous: Double?): Double? =
    if (current == null || previous == null || previous == 0.0) null else current - previous

private fun formatMetric(value: Long): String = when {
    abs(value) >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    abs(value) >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatAnalyticsTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
