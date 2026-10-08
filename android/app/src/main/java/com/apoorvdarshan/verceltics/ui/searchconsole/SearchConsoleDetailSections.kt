package com.apoorvdarshan.verceltics.ui.searchconsole

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.sites.SiteChoiceChip
import com.apoorvdarshan.verceltics.ui.sites.SiteTimelineChart
import com.apoorvdarshan.verceltics.ui.sites.SiteLayout
import com.apoorvdarshan.verceltics.ui.sites.SiteWidthClass
import com.apoorvdarshan.verceltics.ui.sites.TimelineChartPoint
import com.apoorvdarshan.verceltics.ui.sites.adaptiveGridItems
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SearchConsolePropertyDetail(
    state: SearchConsoleUiState,
    onRequestPropertySwitcher: () -> Unit,
    onSelectSection: (SearchConsoleDetailSection) -> Unit,
    onPerformanceQueryChange: (SearchConsolePerformanceQueryUi) -> Unit,
    onSelectPerformanceMetric: (SearchConsoleMetricUi) -> Unit,
    onPreviousPerformancePage: () -> Unit,
    onNextPerformancePage: () -> Unit,
    onInspectionUrlChange: (String) -> Unit,
    onInspect: () -> Unit,
    performanceActions: SearchConsolePerformanceActions,
    modifier: Modifier = Modifier,
) {
    val property = state.dashboard?.properties?.firstOrNull {
        it.siteUrl == state.selectedPropertyUrl
    }
    val haptic = LocalHapticFeedback.current
    var showReportBuilder by rememberSaveable { mutableStateOf(false) }
    var showFilterEditor by rememberSaveable { mutableStateOf(false) }
    if (showReportBuilder) {
        PerformanceControlsDialog(
            query = state.performanceQuery,
            onApply = {
                showReportBuilder = false
                onPerformanceQueryChange(it)
            },
            onDismiss = { showReportBuilder = false },
        )
    }
    if (showFilterEditor) {
        FilterEditorDialog(
            filters = state.performanceQuery.filters,
            onApply = {
                showFilterEditor = false
                performanceActions.onApplyFilters(it)
            },
            onDismiss = { showFilterEditor = false },
        )
    }
    val performance = state.performance
    val query = state.performanceQuery
    val sortedRows = remember(performance?.breakdownRows, query.dimensions, query.sortField, query.sortAscending) {
        sortSearchConsoleBreakdown(
            performance?.breakdownRows.orEmpty(),
            query.dimensions,
            query.sortField,
            query.sortAscending,
        )
    }
    val breakdownPage = searchConsoleBreakdownPage(sortedRows, query.page, query.pageSize)
    var scrubbedIndex by remember(performance?.timeline, state.selectedPerformanceMetric) { mutableStateOf<Int?>(null) }
    val wideTable = LocalConfiguration.current.screenWidthDp >= 600 && LocalDensity.current.fontScale < 1.3f

    // Phones keep one 20 dp-padded column. Regular windows cap the report (iOS
    // `dashboardMaxWidth`), widen metrics and charts, grid the sitemaps, and split inspection
    // findings into two panes once both fit (iOS `AppAdaptiveTwoPane(430, 340)`).
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val regular = SiteWidthClass.of(maxWidth).isRegular
    val contentWidth = SiteLayout.contentWidth(maxWidth, 20.dp, SiteLayout.DashboardMaxWidth)
    val sitemapColumns = SiteLayout.columns(maxWidth, contentWidth, minimum = 380.dp, spacing = 14.dp, maximumColumns = 3)
    val twoPaneInspection = regular && contentWidth >= 430.dp + 340.dp + 16.dp
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("searchConsole.detail"),
        contentPadding = SiteLayout.padding(maxWidth, 20.dp, SiteLayout.DashboardMaxWidth, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OffsetPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp),
                color = SearchConsoleAccent.copy(alpha = 0.14f)
                    .compositeOver(MaterialTheme.colorScheme.surface),
                borderColor = MaterialTheme.colorScheme.outline,
            ) {
                PropertyHeroContent(property, state.selectedPropertyUrl, onRequestPropertySwitcher)
            }
        }
        item {
            DetailSectionPicker(
                selectedSection = state.selectedSection,
                onSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSelectSection(it)
                },
            )
        }
        state.propertyError?.let { message ->
            item { FeedbackPanel("Property unavailable", message, MaterialTheme.colorScheme.error) }
        }
        if (state.isLoadingProperty && state.propertyWorkspace == null) {
            item {
                LoadingPanel("Loading ${state.selectedSection.displayLabel.lowercase()}…")
            }
        } else {
            when (state.selectedSection) {
                SearchConsoleDetailSection.PERFORMANCE -> performanceItems(
                    state = state,
                    breakdownPage = breakdownPage,
                    wideTable = wideTable,
                    scrubbedIndex = scrubbedIndex,
                    onScrub = { scrubbedIndex = it },
                    onOpenReportBuilder = { showReportBuilder = true },
                    onOpenFilterEditor = { showFilterEditor = true },
                    actions = performanceActions,
                    onSelectMetric = onSelectPerformanceMetric,
                    onPreviousPage = onPreviousPerformancePage,
                    onNextPage = onNextPerformancePage,
                    regular = regular,
                )
                SearchConsoleDetailSection.SITEMAPS -> sitemapItems(state.propertyWorkspace?.sitemaps, sitemapColumns)
                SearchConsoleDetailSection.INSPECT -> inspectionItems(
                    state = state,
                    onInspectionUrlChange = onInspectionUrlChange,
                    onInspect = onInspect,
                    twoPane = twoPaneInspection,
                )
            }
        }
    }
    }
}

@Composable
private fun PropertyHeroContent(property: SearchConsolePropertyUi?, selectedPropertyUrl: String?, onSwitch: () -> Unit) {
    val siteUrl = property?.siteUrl ?: selectedPropertyUrl.orEmpty()
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderMark(searchConsoleProvider(), size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(
                        property?.displayName ?: selectedPropertyUrl.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (siteUrl.startsWith("sc-domain:")) "Domain property" else "URL-prefix property",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    property?.permission?.let { LabelChip(it) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Read-only Google data", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemedActionButton(text = "Switch property", onClick = onSwitch, tone = ThemedActionTone.NEUTRAL, testTag = "searchConsole.switchProperty")
        }
    }
}

@Composable
private fun DetailSectionPicker(
    selectedSection: SearchConsoleDetailSection,
    onSelect: (SearchConsoleDetailSection) -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.35f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchConsoleDetailSection.entries.forEach { section ->
                DetailSectionTab(section, section == selectedSection, onSelect, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchConsoleDetailSection.entries.forEach { section ->
                DetailSectionTab(section, section == selectedSection, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailSectionTab(
    section: SearchConsoleDetailSection,
    selected: Boolean,
    onSelect: (SearchConsoleDetailSection) -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = { onSelect(section) },
        modifier = modifier
            .heightIn(min = 54.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .testTag("searchConsole.section.${section.name.lowercase()}"),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(section.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(section.displayLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val SearchConsoleDetailSection.icon: ImageVector
    get() = when (this) {
        SearchConsoleDetailSection.PERFORMANCE -> Icons.Rounded.Analytics
        SearchConsoleDetailSection.SITEMAPS -> Icons.AutoMirrored.Rounded.Rule
        SearchConsoleDetailSection.INSPECT -> Icons.AutoMirrored.Rounded.FactCheck
    }

// MARK: - Performance

private fun LazyListScope.performanceItems(
    state: SearchConsoleUiState,
    breakdownPage: SearchConsoleBreakdownPageUi,
    wideTable: Boolean,
    scrubbedIndex: Int?,
    onScrub: (Int?) -> Unit,
    onOpenReportBuilder: () -> Unit,
    onOpenFilterEditor: () -> Unit,
    actions: SearchConsolePerformanceActions,
    onSelectMetric: (SearchConsoleMetricUi) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    regular: Boolean = false,
) {
    val resource = state.propertyWorkspace?.performance
    val query = state.performanceQuery
    item(key = "performance.controls") {
        PerformanceControlsPanel(
            query = query,
            returnedAggregation = state.performance?.returnedAggregationType,
            isLoading = state.isLoadingPerformance,
            actions = actions,
            onOpenReportBuilder = onOpenReportBuilder,
        )
    }
    state.performanceError?.let { message ->
        item { FeedbackPanel("Performance refresh failed", message, MaterialTheme.colorScheme.error) }
    }
    when (resource) {
        null -> item { EmptyPanel("Performance has not loaded yet.") }
        is SearchConsoleResourceUi.Unavailable -> item {
            FeedbackPanel("Performance unavailable", resource.message, MaterialTheme.colorScheme.error)
        }
        is SearchConsoleResourceUi.Available -> {
            val performance = resource.value
            performance.timelineError?.let { message ->
                item { FeedbackPanel("Performance timeline unavailable", message, MaterialTheme.colorScheme.error) }
            }
            item(key = "performance.metrics") {
                AdaptiveMetrics(performance, state.selectedPerformanceMetric, onSelectMetric, regular)
            }
            item(key = "performance.chart") {
                PerformanceChartCard(
                    performance = performance,
                    metric = state.selectedPerformanceMetric,
                    isUpdating = state.isLoadingPerformance,
                    scrubbedIndex = scrubbedIndex,
                    onScrub = onScrub,
                    chartHeight = if (regular) 310.dp else 220.dp,
                )
            }
            performance.firstIncompleteHour?.let { hour ->
                item {
                    FeedbackPanel(
                        "Recent hourly data is still settling",
                        "Google marks results from ${formatSearchConsoleTimestamp(hour)} onward as incomplete. " +
                            "They can change as processing finishes.",
                        SearchConsoleWarning,
                    )
                }
            } ?: performance.firstIncompleteDate?.let { date ->
                item {
                    FeedbackPanel(
                        "Fresh data is still settling",
                        "Google marks results from $date onward as incomplete. They can change as processing finishes.",
                        SearchConsoleWarning,
                    )
                }
            }
            item(key = "breakdown.header") {
                BreakdownHeader(
                    query = query,
                    rowCount = performance.breakdownRows.size,
                    onOpenFilterEditor = onOpenFilterEditor,
                    onToggleDimension = actions.onToggleDimension,
                    onSelectDimension = actions.onSelectDimension,
                    onRemoveFilter = actions.onRemoveFilter,
                )
            }
            performance.breakdownError?.let { message ->
                item { FeedbackPanel("Breakdown unavailable", message, MaterialTheme.colorScheme.error) }
            }
            if (performance.breakdownLimitReached) {
                item {
                    FeedbackPanel(
                        "Showing the first 100,000 rows",
                        "Narrow the date window or add a filter to inspect a more specific result set.",
                        SearchConsoleWarning,
                    )
                }
            }
            if (performance.breakdownRows.isEmpty()) {
                item {
                    EmptyPanel(
                        title = "No breakdown rows",
                        message = "Google returned no ${query.dimensions.first().displayLabel.lowercase()} rows for this query.",
                    )
                }
            } else {
                item(key = "breakdown.sort") {
                    BreakdownSortHeader(query, wideTable, actions.onToggleSort)
                }
                itemsIndexed(
                    breakdownPage.rows,
                    key = { index, row -> "row-${breakdownPage.page}-$index-${row.keys.joinToString("\u0000")}" },
                ) { index, row ->
                    BreakdownRow(row, query.dimensions, wideTable, index)
                }
                item(key = "breakdown.pagination") {
                    PerformancePagination(
                        page = breakdownPage,
                        isLoading = state.isLoadingPerformance,
                        onPrevious = onPreviousPage,
                        onNext = onNextPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceControlsPanel(
    query: SearchConsolePerformanceQueryUi,
    returnedAggregation: String?,
    isLoading: Boolean,
    actions: SearchConsolePerformanceActions,
    onOpenReportBuilder: () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        borderColor = SearchConsoleAccent.copy(alpha = 0.20f),
        testTag = "searchConsole.performance.query",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SectionEyebrow("PERFORMANCE WINDOW")
                    Text(
                        dateRangeLabel(query),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Updating performance" },
                        strokeWidth = 2.dp,
                        color = SearchConsoleAccent,
                    )
                }
            }
            ChipRow {
                SearchConsoleDatePresetUi.entries.forEach { preset ->
                    SiteChoiceChip(
                        label = preset.displayLabel,
                        selected = query.preset == preset,
                        accent = SearchConsoleAccent,
                        onClick = {
                            if (preset == SearchConsoleDatePresetUi.CUSTOM) onOpenReportBuilder() else actions.onSelectDatePreset(preset)
                        },
                        testTag = "searchConsole.performance.preset.${preset.name}",
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionEyebrow("SEARCH SURFACE")
                ChipRow {
                    SearchConsoleSearchTypeUi.entries.forEach { type ->
                        SiteChoiceChip(
                            label = type.displayLabel,
                            selected = query.searchType == type,
                            accent = SearchConsoleAccent,
                            onClick = { actions.onSelectSearchType(type) },
                            testTag = "searchConsole.performance.searchType.${type.name}",
                        )
                    }
                }
            }
            Surface(
                onClick = { advanced = !advanced },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { stateDescription = if (advanced) "Expanded" else "Collapsed" }
                    .testTag("searchConsole.performance.advanced"),
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Advanced query", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${query.dataState.displayLabel} · ${query.aggregation.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
            if (advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionEyebrow("DATA FRESHNESS")
                    Text(
                        query.dataState.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow {
                        SearchConsoleDataStateUi.entries.forEach { dataState ->
                            SiteChoiceChip(
                                label = dataState.displayLabel,
                                selected = query.dataState == dataState,
                                accent = SearchConsoleAccent,
                                onClick = { actions.onSelectDataState(dataState) },
                                testTag = "searchConsole.performance.dataState.${dataState.name}",
                            )
                        }
                    }
                    if (query.dataState == SearchConsoleDataStateUi.HOURLY_ALL) {
                        Text(
                            "Hourly data adds the Hour dimension; Google keeps hourly rows for recent days only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionEyebrow("AGGREGATION")
                    Text(
                        "Choose how Google groups result rows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow {
                        SearchConsoleAggregationUi.entries.forEach { aggregation ->
                            SiteChoiceChip(
                                label = aggregation.displayLabel,
                                selected = query.aggregation == aggregation,
                                accent = SearchConsoleAccent,
                                enabled = query.canSelectAggregation(aggregation),
                                onClick = { actions.onSelectAggregation(aggregation) },
                                testTag = "searchConsole.performance.aggregation.${aggregation.name}",
                            )
                        }
                    }
                    SearchConsoleAggregationUi.entries
                        .mapNotNull(query::aggregationRestriction)
                        .distinct()
                        .forEach { restriction ->
                            Text(
                                restriction,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    returnedAggregation?.let { returned ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("searchConsole.performance.returnedAggregation"),
                        ) {
                            Icon(Icons.Rounded.Verified, contentDescription = null, tint = SearchConsoleSuccess, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Google returned ${searchConsoleHumanized(returned)} aggregation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ThemedActionButton(
                    text = "OPEN REPORT BUILDER",
                    onClick = onOpenReportBuilder,
                    modifier = Modifier.fillMaxWidth(),
                    tone = ThemedActionTone.NEUTRAL,
                    testTag = "searchConsole.performance.reportBuilder",
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private fun dateRangeLabel(query: SearchConsolePerformanceQueryUi, locale: Locale = Locale.getDefault()): String {
    val start = runCatching { LocalDate.parse(query.startDate) }.getOrNull() ?: return "${query.startDate} – ${query.endDate}"
    val end = runCatching { LocalDate.parse(query.endDate) }.getOrNull() ?: return "${query.startDate} – ${query.endDate}"
    return "${DateTimeFormatter.ofPattern("MMM d", locale).format(start)} – " +
        DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(end)
}

@Composable
private fun AdaptiveMetrics(
    performance: SearchConsolePerformanceUi,
    selectedMetric: SearchConsoleMetricUi,
    onSelectMetric: (SearchConsoleMetricUi) -> Unit,
    regular: Boolean = false,
) {
    val values = listOf(
        SearchConsoleMetricUi.CLICKS to performance.clicks,
        SearchConsoleMetricUi.IMPRESSIONS to performance.impressions,
        SearchConsoleMetricUi.CTR to performance.ctr,
        SearchConsoleMetricUi.POSITION to performance.position,
    )
    // iOS uses four metric columns in regular width and two in compact width.
    val columns = when {
        LocalDensity.current.fontScale >= 1.35f -> 1
        regular -> 4
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (metric, value) ->
                    MetricCard(
                        label = if (metric == SearchConsoleMetricUi.POSITION) "AVERAGE POSITION" else metric.displayLabel.uppercase(),
                        value = metric.format(value),
                        modifier = Modifier.weight(1f),
                        selected = selectedMetric == metric,
                        tint = metric.color,
                        testTag = "searchConsole.performance.metric.${metric.name}",
                    ) { onSelectMetric(metric) }
                }
            }
        }
    }
}

@Composable
internal fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = SearchConsoleAccent,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    OffsetPanel(
        modifier = modifier.heightIn(min = 102.dp),
        color = (if (selected) tint else MaterialTheme.colorScheme.primary)
            .copy(alpha = if (selected) 0.16f else 0.06f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = if (selected) tint else MaterialTheme.colorScheme.outline,
        shadowColor = if (selected) tint else MaterialTheme.colorScheme.outline,
        onClick = onClick,
        testTag = testTag,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .semantics {
                    stateDescription = "$label, $value${if (selected) ", selected chart metric" else ""}"
                    if (onClick != null) this.selected = selected
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (selected) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(tint, RoundedCornerShape(50)),
                    )
                }
            }
            Text(value, style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun PerformanceChartCard(
    performance: SearchConsolePerformanceUi,
    metric: SearchConsoleMetricUi,
    isUpdating: Boolean,
    scrubbedIndex: Int?,
    onScrub: (Int?) -> Unit,
    chartHeight: Dp = 220.dp,
) {
    val hourly = performance.timelineIsHourly ||
        performance.timeline.firstOrNull()?.label?.contains('T') == true
    val cadence = if (hourly) "Hourly" else "Daily"
    val points = remember(performance.timeline, metric) {
        performance.timeline.map { point ->
            TimelineChartPoint(formatSearchConsoleTimelineLabel(point.label), point.metricValue(metric))
        }
    }
    val selected = scrubbedIndex?.let(points::getOrNull)
    val headline = selected?.value ?: searchConsoleHeadlineValue(performance.timeline, metric)
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        borderColor = metric.color.copy(alpha = 0.35f),
        testTag = "searchConsole.performance.chartCard",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SectionEyebrow("SEARCH PULSE")
                    Text("$cadence ${metric.displayLabel.lowercase()}", style = MaterialTheme.typography.titleMedium)
                }
                if (isUpdating && points.isNotEmpty()) {
                    Text("Updating", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (points.isEmpty()) {
                Text(
                    if (isUpdating) {
                        "Loading ${cadence.lowercase()} performance…"
                    } else {
                        "Google returned no ${cadence.lowercase()} rows for this property, surface and date range."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 120.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(metric.format(headline), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected != null) metric.color.copy(alpha = 0.12f) else Color.Transparent,
                    ) {
                        Text(
                            selected?.label ?: "Drag across the chart",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected != null) metric.color else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SiteTimelineChart(
                    points = points,
                    accent = metric.color,
                    formatValue = metric::format,
                    description = "$cadence ${metric.displayLabel} chart. ${points.size} ${cadence.lowercase()} points. " +
                        "Total ${metric.format(searchConsoleHeadlineValue(performance.timeline, metric))}.",
                    modifier = Modifier.fillMaxWidth(),
                    chartHeight = chartHeight,
                    showArea = metric == SearchConsoleMetricUi.CLICKS || metric == SearchConsoleMetricUi.IMPRESSIONS,
                    includeZero = metric != SearchConsoleMetricUi.POSITION,
                    selectedIndex = scrubbedIndex,
                    onSelectedIndexChange = onScrub,
                    testTag = "searchConsole.performance.chart",
                )
                if (metric == SearchConsoleMetricUi.POSITION) {
                    Text(
                        "A lower average position is better.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownHeader(
    query: SearchConsolePerformanceQueryUi,
    rowCount: Int,
    onOpenFilterEditor: () -> Unit,
    onToggleDimension: (SearchConsoleDimensionUi) -> Unit,
    onSelectDimension: (SearchConsoleDimensionUi) -> Unit,
    onRemoveFilter: (Int) -> Unit,
) {
    var dimensionsMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionEyebrow("BREAKDOWN", Modifier.weight(1f))
            LabelChip(compactWholeNumber(rowCount.toLong()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = if (query.filters.isEmpty()) "Filter" else "Filters ${query.filters.size}",
                icon = Icons.Rounded.FilterList,
                onClick = onOpenFilterEditor,
                testTag = "searchConsole.breakdown.filter",
            )
            Box {
                PillButton(
                    text = "Dimensions ${query.dimensions.size}",
                    icon = Icons.Rounded.GridView,
                    onClick = { dimensionsMenu = true },
                    testTag = "searchConsole.breakdown.dimensionsMenu",
                )
                DropdownMenu(expanded = dimensionsMenu, onDismissRequest = { dimensionsMenu = false }) {
                    SearchConsoleDimensionUi.entries.forEach { dimension ->
                        val checked = dimension in query.dimensions
                        DropdownMenuItem(
                            text = { Text(dimension.displayLabel) },
                            leadingIcon = {
                                Icon(
                                    if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (checked) SearchConsoleAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { onToggleDimension(dimension) },
                            modifier = Modifier
                                .semantics { selected = checked }
                                .testTag("searchConsole.breakdown.dimensionToggle.${dimension.name}"),
                        )
                    }
                }
            }
        }
        ChipRow {
            SearchConsoleDimensionUi.entries.forEach { dimension ->
                SiteChoiceChip(
                    label = dimension.displayLabel,
                    selected = query.dimensions == listOf(dimension),
                    accent = SearchConsoleAccent,
                    onClick = { onSelectDimension(dimension) },
                    testTag = "searchConsole.breakdown.dimension.${dimension.name}",
                )
            }
        }
        if (query.dimensions.size > 1) {
            Text(
                "Grouped by ${query.dimensions.joinToString(" + ") { it.displayLabel }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (query.filters.isNotEmpty()) {
            ChipRow {
                query.filters.forEachIndexed { index, filter ->
                    AppliedFilterChip(filter, index) { onRemoveFilter(index) }
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit, testTag: String) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AppliedFilterChip(filter: SearchConsoleFilterUi, index: Int, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = SearchConsoleAccent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, SearchConsoleAccent.copy(alpha = 0.5f)),
        modifier = Modifier.testTag("searchConsole.breakdown.filterChip.$index"),
    ) {
        Row(Modifier.padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${filter.dimension.displayLabel} ${filter.operator.displayLabel.lowercase()} “${filter.expression}”",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = filterChipTextWidth(filter)),
            )
            Surface(onClick = onRemove, color = Color.Transparent, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cancel, contentDescription = "Remove filter", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Keeps long regex filters from stretching the chip row; the full text is in the filter editor. */
private fun filterChipTextWidth(filter: SearchConsoleFilterUi): Dp {
    val characters = filter.dimension.displayLabel.length + filter.operator.displayLabel.length + filter.expression.length + 4
    return (characters * 7).coerceIn(80, 260).dp
}

@Composable
private fun BreakdownSortHeader(
    query: SearchConsolePerformanceQueryUi,
    wideTable: Boolean,
    onToggleSort: (SearchConsoleSortFieldUi) -> Unit,
) {
    if (wideTable) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SortHeaderCell(SearchConsoleSortFieldUi.DIMENSION, query, onToggleSort, Modifier.weight(1f), TextAlign.Start)
                SortHeaderCell(SearchConsoleSortFieldUi.CLICKS, query, onToggleSort, Modifier.width(90.dp))
                SortHeaderCell(SearchConsoleSortFieldUi.IMPRESSIONS, query, onToggleSort, Modifier.width(110.dp))
                SortHeaderCell(SearchConsoleSortFieldUi.CTR, query, onToggleSort, Modifier.width(78.dp))
                SortHeaderCell(SearchConsoleSortFieldUi.POSITION, query, onToggleSort, Modifier.width(86.dp))
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "SORT BY · ${if (query.sortAscending) "ASCENDING" else "DESCENDING"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow {
                SearchConsoleSortFieldUi.entries.forEach { field ->
                    SortHeaderCell(field, query, onToggleSort, Modifier, TextAlign.Center, chip = true)
                }
            }
        }
    }
}

@Composable
private fun SortHeaderCell(
    field: SearchConsoleSortFieldUi,
    query: SearchConsolePerformanceQueryUi,
    onToggleSort: (SearchConsoleSortFieldUi) -> Unit,
    modifier: Modifier,
    align: TextAlign = TextAlign.End,
    chip: Boolean = false,
) {
    val active = query.sortField == field
    val label = field.displayLabel(query.dimensions)
    Surface(
        onClick = { onToggleSort(field) },
        modifier = modifier
            .heightIn(min = 40.dp)
            .semantics {
                role = Role.Button
                selected = active
                stateDescription = when {
                    !active -> "Not sorted"
                    query.sortAscending -> "Sorted ascending"
                    else -> "Sorted descending"
                }
            }
            .testTag("searchConsole.breakdown.sort.${field.name}"),
        shape = RoundedCornerShape(if (chip) 50 else 8),
        color = when {
            chip && active -> SearchConsoleAccent.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)
            chip -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
        border = if (chip) BorderStroke(0.5.dp, if (active) SearchConsoleAccent else MaterialTheme.colorScheme.outline) else null,
    ) {
        Row(
            Modifier.padding(horizontal = if (chip) 12.dp else 2.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = when (align) {
                TextAlign.Start -> Arrangement.Start
                TextAlign.Center -> Arrangement.Center
                else -> Arrangement.End
            },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (active) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    if (query.sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    row: SearchConsoleBreakdownRowUi,
    dimensions: List<SearchConsoleDimensionUi>,
    wideTable: Boolean,
    index: Int,
) {
    val value = remember(row, dimensions) { searchConsoleDimensionValue(row.keys, dimensions) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("searchConsole.breakdown.row.$index"),
        shape = RoundedCornerShape(12.dp),
        color = if (index % 2 == 0) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f).compositeOver(MaterialTheme.colorScheme.surface)
        },
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (wideTable) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MetricCell(SearchConsoleMetricUi.CLICKS.format(row.clicks), 90.dp)
                MetricCell(SearchConsoleMetricUi.IMPRESSIONS.format(row.impressions), 110.dp)
                MetricCell(SearchConsoleMetricUi.CTR.format(row.ctr), 78.dp)
                MetricCell(SearchConsoleMetricUi.POSITION.format(row.position), 86.dp)
            }
        } else {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth()) {
                    RowMetric("Clicks", SearchConsoleMetricUi.CLICKS.format(row.clicks), Modifier.weight(1f))
                    RowMetric("Impressions", SearchConsoleMetricUi.IMPRESSIONS.format(row.impressions), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    RowMetric("CTR", SearchConsoleMetricUi.CTR.format(row.ctr), Modifier.weight(1f))
                    RowMetric("Position", SearchConsoleMetricUi.POSITION.format(row.position), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(text: String, width: Dp) {
    Text(
        text,
        modifier = Modifier.width(width),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun RowMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PerformancePagination(
    page: SearchConsoleBreakdownPageUi,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    OffsetPanel(modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemedGlassControl(
                modifier = Modifier.size(48.dp),
                enabled = page.hasPrevious && !isLoading,
                onClick = onPrevious,
                testTag = "searchConsole.performance.previousPage",
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous report page")
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${compactWholeNumber(page.firstRow.toLong())}–${compactWholeNumber(page.lastRow.toLong())} of ${compactWholeNumber(page.totalRows.toLong())}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "Page ${page.page + 1} of ${page.totalPages.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThemedGlassControl(
                modifier = Modifier.size(48.dp),
                enabled = page.hasNext && !isLoading,
                onClick = onNext,
                testTag = "searchConsole.performance.nextPage",
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next report page")
                }
            }
        }
    }
}

// MARK: - Sitemaps

private fun LazyListScope.sitemapItems(
    resource: SearchConsoleResourceUi<List<SearchConsoleSitemapUi>>?,
    columns: Int = 1,
) {
    when (resource) {
        null -> item { EmptyPanel("Sitemaps have not loaded yet.") }
        is SearchConsoleResourceUi.Unavailable -> item {
            FeedbackPanel("Couldn’t load sitemaps", resource.message, MaterialTheme.colorScheme.error)
        }
        is SearchConsoleResourceUi.Available -> {
            resource.warning?.let { warning ->
                item { FeedbackPanel("Partial sitemaps", warning, SearchConsoleWarning) }
            }
            val sitemaps = resource.value.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
            item { SitemapAggregateSummary(sitemaps) }
            if (sitemaps.isEmpty()) {
                item {
                    EmptyPanel(
                        title = "No submitted sitemaps",
                        message = "Search Console did not return any submitted sitemaps for this property.",
                    )
                }
            } else {
                adaptiveGridItems(sitemaps, columns, key = SearchConsoleSitemapUi::path, spacing = 14.dp) { sitemap ->
                    SitemapCard(sitemap)
                }
            }
        }
    }
}

@Composable
private fun SitemapAggregateSummary(sitemaps: List<SearchConsoleSitemapUi>) {
    val submitted = sitemaps.sumOf { sitemap -> sitemap.contents.sumOf(SearchConsoleSitemapContentUi::submitted) }
    val indexed = sitemaps.sumOf { sitemap -> sitemap.contents.sumOf { it.indexed ?: 0L } }
    val issues = sitemaps.sumOf { it.errors + it.warnings }
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        borderColor = SearchConsoleAccent.copy(alpha = 0.20f),
        testTag = "searchConsole.sitemaps.summary",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionEyebrow("SITEMAP COVERAGE")
                Text("Submitted discovery files", style = MaterialTheme.typography.titleMedium)
            }
            val stats = listOf(
                Triple("Sitemaps", compactWholeNumber(sitemaps.size.toLong()), SearchConsoleAccent),
                Triple("Submitted URLs", compactNumber(submitted.toDouble()), SearchConsoleAccent),
                Triple("Indexed URLs", compactNumber(indexed.toDouble()), SearchConsoleAccent),
                Triple("Issues", compactWholeNumber(issues), if (issues > 0) SearchConsoleWarning else SearchConsoleSuccess),
            )
            stats.chunked(if (LocalDensity.current.fontScale >= 1.35f) 1 else 2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { (label, value, tint) -> CompactStat(label, value, Modifier.weight(1f), tint) }
                }
            }
        }
    }
}

@Composable
private fun SitemapCard(sitemap: SearchConsoleSitemapUi) {
    var expanded by rememberSaveable(sitemap.path) { mutableStateOf(false) }
    val issueCount = sitemap.errors + sitemap.warnings
    val (statusText, statusColor) = when {
        sitemap.isPending -> "Pending" to SearchConsoleAccent
        sitemap.errors > 0 -> "Errors" to SearchConsoleDanger
        sitemap.warnings > 0 -> "Warnings" to SearchConsoleWarning
        else -> "Processed" to SearchConsoleSuccess
    }
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp),
        color = if (sitemap.errors > 0) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface)
        } else {
            MaterialTheme.colorScheme.surface
        },
        testTag = "searchConsole.sitemap.${sitemap.path}",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(Icons.AutoMirrored.Rounded.Rule, SearchConsoleAccent, size = 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Text(
                            sitemap.path,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (sitemap.isIndex) "Sitemap index" else searchConsoleHumanized(sitemap.type ?: "Sitemap"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(6.dp))
                StatusPill(statusText, statusColor)
            }
            Row(Modifier.fillMaxWidth()) {
                SitemapStat("Contents", sitemap.contents.size.toLong(), Modifier.weight(1f))
                SitemapStat("Warnings", sitemap.warnings, Modifier.weight(1f))
                SitemapStat("Errors", sitemap.errors, Modifier.weight(1f))
            }
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .testTag("searchConsole.sitemap.details.${sitemap.path}"),
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (expanded) "Hide complete details" else "Show complete details",
                        style = MaterialTheme.typography.labelLarge,
                        color = SearchConsoleAccent,
                        modifier = Modifier.weight(1f),
                    )
                    if (issueCount > 0) {
                        Text("${compactWholeNumber(issueCount)} issues", style = MaterialTheme.typography.labelMedium, color = statusColor)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse sitemap" else "Expand sitemap",
                    )
                }
            }
            if (expanded) {
                LabeledValue("Pending", if (sitemap.isPending) "Yes" else "No")
                LabeledValue("Sitemap index", if (sitemap.isIndex) "Yes" else "No")
                LabeledValue("Type", searchConsoleHumanized(sitemap.type ?: "Not reported"))
                LabeledValue("Last submitted", formatSearchConsoleTimestamp(sitemap.lastSubmitted))
                LabeledValue("Last downloaded", formatSearchConsoleTimestamp(sitemap.lastDownloaded))
                LabeledValue("Warnings", compactWholeNumber(sitemap.warnings))
                LabeledValue("Errors", compactWholeNumber(sitemap.errors))
                SectionEyebrow("CONTENT COUNTS")
                if (sitemap.contents.isEmpty()) {
                    Text(
                        "Google did not include content counts for this sitemap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sitemap.contents.forEach { content ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(searchConsoleHumanized(content.type), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(compactWholeNumber(content.submitted), style = MaterialTheme.typography.labelLarge)
                            Text("Submitted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(content.indexed?.let(::compactWholeNumber) ?: "—", style = MaterialTheme.typography.labelLarge)
                            Text("Indexed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        sitemap.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SitemapStat(label: String, value: Long, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(compactWholeNumber(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - URL inspection

private fun LazyListScope.inspectionItems(
    state: SearchConsoleUiState,
    onInspectionUrlChange: (String) -> Unit,
    onInspect: () -> Unit,
    twoPane: Boolean = false,
) {
    item {
        OffsetPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 178.dp),
            borderColor = SearchConsoleAccent.copy(alpha = 0.20f),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SectionEyebrow("URL INSPECTION")
                    Text("Check Google’s latest indexed view", style = MaterialTheme.typography.titleMedium)
                }
                ThemedAuthTextField(
                    value = state.inspectionUrl,
                    onValueChange = onInspectionUrlChange,
                    label = "Fully-qualified URL",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("searchConsole.inspectionUrl"),
                    enabled = !state.isInspecting,
                )
                ThemedActionButton(
                    text = if (state.isInspecting) "INSPECTING…" else "INSPECT URL",
                    onClick = onInspect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.inspectionUrl.isNotBlank() && !state.isInspecting,
                    isBusy = state.isInspecting,
                    testTag = "searchConsole.inspect",
                )
            }
        }
    }
    state.inspectionError?.let { message ->
        item { FeedbackPanel("URL inspection failed", message, MaterialTheme.colorScheme.error) }
    }
    val inspection = state.inspection
    if (inspection == null) {
        if (!state.isInspecting) {
            item {
                EmptyPanel(
                    title = "Inspect any URL",
                    message = "Enter a URL inside the selected property to see Google’s index, AMP, mobile usability and rich-result findings.",
                )
            }
        }
        return
    }
    item { InspectionSummaryCard(inspection, state.inspectionUrl) }
    if (twoPane) {
        item(key = "inspection.panes") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("searchConsole.inspection.twoPane"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (inspection.hasIndexStatus) {
                        IndexStatusCard(inspection)
                    } else {
                        EmptyPanel(title = "Index status", message = "Google did not return an index-status result.")
                    }
                    inspection.amp?.let { amp -> AmpCard(amp) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (inspection.hasMobileResult) {
                        InspectionCard(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Mobile usability",
                            verdict = inspection.mobileVerdict,
                            testTag = "searchConsole.inspection.mobile",
                        ) {
                            IssueList("Mobile issues", inspection.issues.filter { it.area == SearchConsoleInspectionAreaUi.MOBILE })
                        }
                    }
                    if (inspection.hasRichResults) RichResultsCard(inspection)
                    if (inspection.amp == null && !inspection.hasMobileResult && !inspection.hasRichResults) {
                        EmptyPanel(
                            title = "Enhancements",
                            message = "Google did not return AMP, mobile-usability or rich-result findings for this URL.",
                        )
                    }
                }
            }
        }
        return
    }
    item {
        if (inspection.hasIndexStatus) {
            IndexStatusCard(inspection)
        } else {
            EmptyPanel(title = "Index status", message = "Google did not return an index-status result.")
        }
    }
    inspection.amp?.let { amp -> item { AmpCard(amp) } }
    if (inspection.hasMobileResult) {
        item {
            InspectionCard(
                icon = Icons.Rounded.PhoneAndroid,
                title = "Mobile usability",
                verdict = inspection.mobileVerdict,
                testTag = "searchConsole.inspection.mobile",
            ) {
                IssueList("Mobile issues", inspection.issues.filter { it.area == SearchConsoleInspectionAreaUi.MOBILE })
            }
        }
    }
    if (inspection.hasRichResults) {
        item { RichResultsCard(inspection) }
    }
    if (inspection.amp == null && !inspection.hasMobileResult && !inspection.hasRichResults) {
        item {
            EmptyPanel(
                title = "Enhancements",
                message = "Google did not return AMP, mobile-usability or rich-result findings for this URL.",
            )
        }
    }
}

@Composable
private fun InspectionSummaryCard(inspection: SearchConsoleInspectionUi, fallbackUrl: String) {
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = SearchConsoleAccent.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = SearchConsoleAccent.copy(alpha = 0.25f),
        testTag = "searchConsole.inspection.summary",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(Icons.AutoMirrored.Rounded.FactCheck, SearchConsoleAccent, size = 42)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionEyebrow("INSPECTED URL")
                    SelectionContainer {
                        Text(
                            inspection.inspectedUrl ?: fallbackUrl,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ChipRow {
                inspection.verdict?.let { StatusPill("Index ${searchConsoleHumanized(it)}", searchConsoleVerdictColor(it)) }
                inspection.ampVerdict?.let { StatusPill("AMP ${searchConsoleHumanized(it)}", searchConsoleVerdictColor(it)) }
                inspection.richResultsVerdict?.let {
                    StatusPill("Rich results ${searchConsoleHumanized(it)}", searchConsoleVerdictColor(it))
                }
            }
            inspection.inspectionResultLink?.let { link ->
                ThemedActionButton(
                    text = "OPEN THIS RESULT IN SEARCH CONSOLE",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        uriHandler.openUri(link)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = ThemedActionTone.NEUTRAL,
                    testTag = "searchConsole.inspection.openResult",
                )
            }
        }
    }
}

@Composable
private fun IndexStatusCard(inspection: SearchConsoleInspectionUi) {
    InspectionCard(
        icon = Icons.Rounded.Verified,
        title = "Index status",
        verdict = inspection.verdict,
        testTag = "searchConsole.inspection.index",
    ) {
        LabeledValue("Coverage", inspection.coverageState ?: "Not reported")
        LabeledValue("Indexing", searchConsoleHumanized(inspection.indexingState ?: "Not reported"))
        LabeledValue("Robots.txt", searchConsoleHumanized(inspection.robotsTxtState ?: "Not reported"))
        LabeledValue("Page fetch", searchConsoleHumanized(inspection.pageFetchState ?: "Not reported"))
        LabeledValue("Crawled as", searchConsoleHumanized(inspection.crawledAs ?: "Not reported"))
        LabeledValue("Last crawl", formatSearchConsoleTimestamp(inspection.lastCrawlTime))
        LabeledValue("Google canonical", inspection.googleCanonical ?: "Not reported", monospace = inspection.googleCanonical != null)
        LabeledValue("User canonical", inspection.userCanonical ?: "Not reported", monospace = inspection.userCanonical != null)
        StringList("Sitemaps", inspection.sitemaps)
        StringList("Referring URLs", inspection.referringUrls)
    }
}

@Composable
private fun AmpCard(amp: SearchConsoleAmpInspectionUi) {
    InspectionCard(
        icon = Icons.Rounded.Bolt,
        title = "AMP",
        verdict = amp.verdict,
        testTag = "searchConsole.inspection.amp",
    ) {
        LabeledValue("AMP URL", amp.ampUrl ?: "Not reported", monospace = amp.ampUrl != null)
        LabeledValue("Index status", searchConsoleHumanized(amp.indexStatusVerdict ?: "Not reported"))
        LabeledValue("Indexing", searchConsoleHumanized(amp.indexingState ?: "Not reported"))
        LabeledValue("Robots.txt", searchConsoleHumanized(amp.robotsTxtState ?: "Not reported"))
        LabeledValue("Page fetch", searchConsoleHumanized(amp.pageFetchState ?: "Not reported"))
        LabeledValue("Last crawl", formatSearchConsoleTimestamp(amp.lastCrawlTime))
        IssueList("AMP issues", amp.issues)
    }
}

@Composable
private fun RichResultsCard(inspection: SearchConsoleInspectionUi) {
    InspectionCard(
        icon = Icons.Rounded.AutoAwesome,
        title = "Rich results",
        verdict = inspection.richResultsVerdict,
        testTag = "searchConsole.inspection.richResults",
    ) {
        if (inspection.richResultTypes.isEmpty()) {
            Text(
                "Google did not report any detected rich-result types.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        inspection.richResultTypes.forEach { type ->
            Column(
                Modifier.testTag("searchConsole.inspection.richType.${type.type}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(searchConsoleHumanized(type.type), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${compactWholeNumber(type.items.size.toLong())} item${if (type.items.size == 1) "" else "s"} · " +
                            "${type.issueCount} issue${if (type.issueCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (type.issueCount > 0) SearchConsoleWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                type.items.forEachIndexed { index, item ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectionContainer {
                                Text(item.name ?: "Detected item ${index + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                            IssueList("Item issues", item.issues)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionCard(
    icon: ImageVector,
    title: String,
    verdict: String?,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val tone = searchConsoleVerdictColor(verdict)
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (verdict == null) MaterialTheme.colorScheme.outline else tone.copy(alpha = 0.55f),
        testTag = testTag,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, if (verdict == null) MaterialTheme.colorScheme.outline else tone, size = 36)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                verdict?.let { StatusPill(searchConsoleHumanized(it), tone) }
            }
            content()
        }
    }
}

@Composable
private fun StringList(title: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (values.isEmpty()) {
            Text("None reported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    values.forEach { value ->
                        Text(
                            "• $value",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueList(title: String, issues: List<SearchConsoleInspectionIssueUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (issues.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SearchConsoleSuccess, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("No issues reported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        issues.forEach { issue ->
            val severe = issue.severity?.lowercase(Locale.ROOT)?.let { severity ->
                listOf("error", "fail", "critical", "invalid").any(severity::contains)
            } == true
            val tint = if (severe) SearchConsoleDanger else SearchConsoleWarning
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (severe) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(issue.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        issue.detail?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        issue.severity?.let {
                            Text(searchConsoleHumanized(it), style = MaterialTheme.typography.labelSmall, color = tint)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Dialog helpers shared by the report builder and filter editor

@Composable
internal fun DialogHeader(title: String, subtitle: String, icon: ImageVector, onDismiss: () -> Unit, closeTag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, SearchConsoleAccent)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ThemedGlassControl(
            modifier = Modifier.size(48.dp),
            onClick = onDismiss,
            testTag = closeTag,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cancel, contentDescription = "Close")
            }
        }
    }
}

@Composable
internal fun ControlGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionEyebrow(title)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
internal fun <T> ChoiceGrid(
    values: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    val singleColumn = LocalDensity.current.fontScale >= 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.chunked(if (singleColumn) 1 else 2).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowValues.forEach { value ->
                    val isSelected = selected(value)
                    val isEnabled = enabled(value)
                    Surface(
                        onClick = { onSelect(value) },
                        enabled = isEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                        shape = RoundedCornerShape(13.dp),
                        color = if (isSelected) SearchConsoleAccent else MaterialTheme.colorScheme.surface,
                        contentColor = when {
                            !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            isSelected -> Color.Black
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(label(value), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!singleColumn && rowValues.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun FilterSummaryRow(filter: SearchConsoleFilterUi, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = SearchConsoleAccent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, SearchConsoleAccent),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(filter.dimension.displayLabel, style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        "${filter.operator.displayLabel}: ${filter.expression}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(onClick = onRemove, color = Color.Transparent) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Remove filter",
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
