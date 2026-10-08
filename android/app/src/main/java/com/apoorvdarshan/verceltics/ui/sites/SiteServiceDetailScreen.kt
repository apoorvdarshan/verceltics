package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.ClarityDimensionOptions
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSection
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeries
import com.apoorvdarshan.verceltics.data.sites.SiteDetailTable
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val TABLE_ROW_BATCH = 200

/** Providers whose detail workspace is driven by a date range (iOS `usesDateRange`). */
internal fun usesDateRange(providerId: String): Boolean =
    providerId in setOf("googleAnalytics", "plausible", "umami", "uptimeRobot", "betterStack")

@Composable
internal fun SiteServiceDetailContent(
    service: SiteServiceState,
    detail: SiteServiceDetailState,
    providerId: String,
    actions: SiteServiceScreenActions,
    modifier: Modifier,
) {
    val accent = siteAccent(providerId)
    val dashboard = service.dashboard
    val resources = dashboard?.resources.orEmpty()
    val resource = resources.firstOrNull { it.id == detail.resourceId }
    val payload = detail.payload
    val tableSearch = remember(payload) { mutableStateMapOf<String, String>() }
    val tableSort = remember(payload) { mutableStateMapOf<String, Pair<String, Boolean>>() }
    val tableLimit = remember(payload) { mutableStateMapOf<String, Int>() }
    val searchSnapshot = tableSearch.toMap()
    val sortSnapshot = tableSort.toMap()
    val limitSnapshot = tableLimit.toMap()
    val tableViews = remember(payload, searchSnapshot, sortSnapshot, limitSnapshot) {
        payload?.tables.orEmpty().associate { table ->
            table.id to tableView(
                table,
                searchSnapshot[table.id].orEmpty(),
                sortSnapshot[table.id],
                limitSnapshot[table.id] ?: TABLE_ROW_BATCH,
            )
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("siteService.detail"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { DetailHeader(detail, resource, dashboard, providerId) }
        item("controls") { ReportControls(detail, resources, providerId, actions) }
        item("open-provider") {
            ThemedActionButton(
                "OPEN IN ${siteCatalogProvider(providerId).displayName.uppercase()}",
                onClick = { actions.onOpenExternal(externalUrl(providerId, resource)) },
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "siteService.detail.openProvider",
            )
        }
        detail.error?.let { message ->
            item("error") {
                SiteErrorPanel("Couldn’t load provider details", message, "siteService.detail.error") {
                    ThemedActionButton(
                        "TRY AGAIN",
                        onClick = actions.onRefreshDetail,
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        when {
            detail.isLoading && payload == null -> item("loading") {
                SiteLoadingState(
                    if (providerId == "googleAnalytics") "Loading the Analytics overview…" else "Loading the provider report…",
                    accent,
                    Modifier.testTag("siteService.detail.loading"),
                )
            }
            payload != null -> payloadItems(payload, providerId, tableViews, tableSearch, tableSort, tableLimit, actions)
            detail.error == null -> item("empty") {
                SiteFeedbackPanel(
                    "No detail data",
                    "The provider did not return a detailed report for this resource.",
                    accent,
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    detail: SiteServiceDetailState,
    resource: SiteResourceUi?,
    dashboard: SiteServiceDashboardUi?,
    providerId: String,
) {
    val provider = siteCatalogProvider(providerId)
    val accent = siteAccent(providerId)
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, borderColor = accent.copy(alpha = 0.24f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(provider, size = 52.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    detail.payload?.title ?: resource?.name ?: dashboard?.accountName ?: provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    SiteServiceCopy.subtitle(providerId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            val payload = detail.payload
            when {
                detail.isRefreshing -> CircularProgressIndicator(
                    Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Refreshing" },
                    strokeWidth = 2.dp,
                    color = accent,
                )
                payload != null -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    StatusPill(if (payload.isPartial) "Loading" else "Current", if (payload.isPartial) accent else MaterialTheme.colorScheme.tertiary)
                    Text(
                        formatSiteTimestamp(payload.fetchedAtMillis),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportControls(
    detail: SiteServiceDetailState,
    resources: List<SiteResourceUi>,
    providerId: String,
    actions: SiteServiceScreenActions,
) {
    val accent = siteAccent(providerId)
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, testTag = "siteService.detail.controls") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteIconTile(Icons.Rounded.Tune, accent, size = 34)
                Spacer(Modifier.width(10.dp))
                Text("Report controls", style = MaterialTheme.typography.titleSmall)
            }
            if (resources.size > 1) {
                ResourcePicker(detail.resourceId, resources, accent, actions.onSelectResource)
            }
            when {
                providerId == "clarity" -> ClarityControls(detail.query, accent, actions)
                usesDateRange(providerId) -> DateRangeControls(detail.query, accent, actions)
                else -> Text(
                    "Bing reports its full available history for the selected site.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ResourcePicker(
    selectedId: String?,
    resources: List<SiteResourceUi>,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = resources.firstOrNull { it.id == selectedId } ?: resources.first()
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .testTag("siteService.detail.resourcePicker"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RESOURCE", style = MaterialTheme.typography.labelSmall, color = accent)
                    Text(selected.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Choose a resource")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            resources.forEach { resource ->
                DropdownMenuItem(
                    text = { Text(resource.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (resource.id == selected.id) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(resource.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeControls(query: SiteServiceDetailQueryUi, accent: Color, actions: SiteServiceScreenActions) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SiteDetailRangePresetUi.entries.forEach { preset ->
                SiteChoiceChip(
                    label = preset.label,
                    selected = query.preset == preset,
                    accent = accent,
                    onClick = { actions.onSelectRange(preset) },
                    modifier = Modifier.weight(1f),
                    testTag = "siteService.detail.range.${preset.name}",
                )
            }
        }
        if (query.preset == SiteDetailRangePresetUi.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("From", query.customStartDate, Modifier.weight(1f), "siteService.detail.customStart") { editing = "start" }
                DateField("To", query.customEndDate, Modifier.weight(1f), "siteService.detail.customEnd") { editing = "end" }
            }
        }
    }
    editing?.let { which ->
        val start = LocalDate.parse(query.customStartDate)
        val end = LocalDate.parse(query.customEndDate)
        val initial = if (which == "start") start else end
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
            },
        )
        DatePickerDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "start") actions.onCustomRange(picked, maxOf(picked, end)) else actions.onCustomRange(minOf(start, picked), picked)
                    }
                    editing = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DateField(label: String, value: String, modifier: Modifier, testTag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 46.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ClarityControls(query: SiteServiceDetailQueryUi, accent: Color, actions: SiteServiceScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "24H", 2 to "48H", 3 to "72H").forEach { (days, label) ->
                SiteChoiceChip(
                    label = label,
                    selected = query.clarityDays == days,
                    accent = accent,
                    onClick = { actions.onClarityDays(days) },
                    modifier = Modifier.weight(1f),
                    testTag = "siteService.detail.clarityDays.$days",
                )
            }
        }
        Text(
            if (query.clarityDimensions.isEmpty()) "Add dimensions (up to 3)" else query.clarityDimensions.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClarityDimensionOptions.forEach { dimension ->
                val selected = dimension in query.clarityDimensions
                SiteChoiceChip(
                    label = dimension,
                    selected = selected,
                    accent = accent,
                    enabled = selected || query.clarityDimensions.size < 3,
                    onClick = { actions.onToggleClarityDimension(dimension) },
                    testTag = "siteService.detail.clarityDimension.$dimension",
                )
            }
        }
    }
}

private fun LazyListScope.payloadItems(
    payload: SiteServiceDetailUi,
    providerId: String,
    tableViews: Map<String, TableView>,
    tableSearch: MutableMap<String, String>,
    tableSort: MutableMap<String, Pair<String, Boolean>>,
    tableLimit: MutableMap<String, Int>,
    actions: SiteServiceScreenActions,
) {
    payload.warnings.forEachIndexed { index, warning ->
        item("warning-$index") { SiteWarningPanel("Partial provider response", warning) }
    }
    if (payload.sections.isNotEmpty()) {
        item("sections-heading") { SiteSectionHeader("Overview", payload.sections.size, siteAccent(providerId)) }
        items(payload.sections, key = { "section-${it.id}" }) { section -> SectionCard(section, providerId) }
    }
    if (payload.series.isNotEmpty()) {
        item("series-heading") { SiteSectionHeader("Timeline", payload.series.size, siteAccent(providerId)) }
        items(payload.series, key = { "series-${it.id}" }) { series -> SeriesCard(series, providerId) }
    }
    if (payload.tables.isNotEmpty()) {
        item("tables-heading") {
            SiteSectionHeader("Provider records", payload.tables.sumOf { it.rows.size }, siteAccent(providerId))
        }
        payload.tables.forEach { table ->
            val view = tableViews[table.id] ?: return@forEach
            item("table-${table.id}") {
                TableHeader(
                    table = table,
                    view = view,
                    providerId = providerId,
                    search = tableSearch[table.id].orEmpty(),
                    onSearch = {
                        tableSearch[table.id] = it
                        tableLimit[table.id] = TABLE_ROW_BATCH
                    },
                    onSort = { column ->
                        tableLimit[table.id] = TABLE_ROW_BATCH
                        if (column == null) {
                            tableSort.remove(table.id)
                        } else {
                            val current = tableSort[table.id]
                            tableSort[table.id] = column to if (current?.first == column) !current.second else true
                        }
                    },
                )
            }
            items(view.rowIndices.size, key = { "table-${table.id}-row-${view.rowIndices[it]}" }) { position ->
                TableRowCard(table.rows[view.rowIndices[position]], view.columns, position, providerId)
            }
            if (view.hasMore) {
                item("table-${table.id}-more") {
                    ThemedActionButton(
                        "SHOW $TABLE_ROW_BATCH MORE",
                        onClick = { tableLimit[table.id] = (tableLimit[table.id] ?: TABLE_ROW_BATCH) + TABLE_ROW_BATCH },
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            table.nextCursor?.takeIf(String::isNotBlank)?.let { cursor ->
                item("table-${table.id}-cursor") {
                    Text(cursor, color = SiteWarningColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (payload.rawResponses.isNotEmpty() && !payload.isPartial) {
        item("raw") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                onClick = { actions.onShowRawResponses(true) },
                testTag = "siteService.detail.raw",
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteIconTile(Icons.Rounded.DataObject, siteAccent(providerId), size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Complete API response", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Browse every returned non-secret field across ${payload.rawResponses.size} endpoints",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: SiteDetailSection, providerId: String) {
    val accent = siteAccent(providerId)
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, borderColor = accent.copy(alpha = 0.18f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 17.dp).background(accent, RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(8.dp))
                Text(section.title, style = MaterialTheme.typography.titleSmall)
            }
            if (section.fields.isEmpty()) {
                Text("No fields were returned.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.fields.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { field ->
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 64.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        field.label.uppercase(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        SiteServiceFormat.display(field.value),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(series: SiteDetailSeries, providerId: String) {
    val accent = siteAccent(providerId)
    val metricKeys = remember(series) {
        (series.metricLabels.keys + series.points.flatMap { it.values.keys }).toSortedSet().toList()
    }
    var chosen by rememberSaveable(series.id) { mutableStateOf(metricKeys.firstOrNull().orEmpty()) }
    val selected = chosen.takeIf { it in metricKeys } ?: metricKeys.firstOrNull().orEmpty()
    val points = series.points.filter { it.values[selected] != null }
    val label = series.metricLabels[selected] ?: SiteServiceFormat.humanized(selected)
    OffsetPanel(
        Modifier.fillMaxWidth(),
        MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.18f),
        testTag = "siteService.detail.series.${series.id}",
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(series.title, style = MaterialTheme.typography.titleSmall)
            if (metricKeys.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    metricKeys.forEach { key ->
                        SiteChoiceChip(
                            label = series.metricLabels[key] ?: SiteServiceFormat.humanized(key),
                            selected = key == selected,
                            accent = accent,
                            onClick = { chosen = key },
                        )
                    }
                }
            }
            if (points.isEmpty()) {
                Text(
                    "No points were returned for this metric.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(min = 80.dp),
                )
            } else {
                val chartPoints = points.mapNotNull { point ->
                    point.values[selected]?.let { TimelineChartPoint(point.x, it) }
                }
                var scrubbed by remember(series.id, selected) { mutableStateOf<Int?>(null) }
                scrubbed?.let { index ->
                    chartPoints.getOrNull(index)?.let { point ->
                        Text(
                            "${point.label} · ${SiteServiceFormat.display(ProviderJsonValue.Num.of(point.value))}",
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                SiteTimelineChart(
                    points = chartPoints,
                    accent = accent,
                    formatValue = ::formatCompactAxisValue,
                    description = "${series.title}: $label",
                    modifier = Modifier.fillMaxWidth(),
                    chartHeight = 180.dp,
                    selectedIndex = scrubbed,
                    onSelectedIndexChange = { scrubbed = it },
                    testTag = "siteService.detail.series.${series.id}.chart",
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                val max = points.mapNotNull { it.values[selected] }.maxOrNull()
                Text(
                    buildString {
                        append("${points.size} points")
                        max?.let { append(" · max ${SiteServiceFormat.display(ProviderJsonValue.Num.of(it))}") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal class TableView(
    val columns: List<String>,
    val rowIndices: List<Int>,
    val hasMore: Boolean,
)

/** Searches and sorts by row index only, so large tables are never copied (iOS `visibleRowSlice`). */
internal fun tableView(
    table: SiteDetailTable,
    search: String,
    sort: Pair<String, Boolean>?,
    limit: Int,
): TableView {
    val returned = table.rows.flatMapTo(HashSet()) { it.keys }
    val columns = table.columns + (returned - table.columns.toSet()).sorted()
    val needle = search.trim().lowercase()
    val scanLimit = if (sort == null) limit + 1 else Int.MAX_VALUE
    val indices = ArrayList<Int>()
    for (index in table.rows.indices) {
        val row = table.rows[index]
        val matches = needle.isEmpty() || row.any { (key, value) ->
            key.lowercase().contains(needle) || SiteServiceFormat.display(value).lowercase().contains(needle)
        }
        if (matches) indices += index
        if (indices.size >= scanLimit) break
    }
    if (sort != null) {
        val (column, ascending) = sort
        val collator = Collator.getInstance()
        indices.sortWith { left, right ->
            val comparison = SiteServiceFormat.compare(
                table.rows[left][column] ?: ProviderJsonValue.Null,
                table.rows[right][column] ?: ProviderJsonValue.Null,
                collator,
            )
            when {
                comparison == 0 -> left.compareTo(right)
                ascending -> comparison
                else -> -comparison
            }
        }
    }
    return TableView(columns, indices.take(limit), indices.size > limit)
}

@Composable
private fun TableHeader(
    table: SiteDetailTable,
    view: TableView,
    providerId: String,
    search: String,
    onSearch: (String) -> Unit,
    onSort: (String?) -> Unit,
) {
    val accent = siteAccent(providerId)
    var sortMenu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag("siteService.detail.table.${table.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(table.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            LabelChip(table.rows.size.toString(), contentColor = accent)
            Spacer(Modifier.width(6.dp))
            Box {
                Surface(
                    onClick = { sortMenu = true },
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort ${table.title}", modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DropdownMenuItem(text = { Text("Provider order") }, onClick = {
                        sortMenu = false
                        onSort(null)
                    })
                    view.columns.forEach { column ->
                        DropdownMenuItem(
                            text = { Text(SiteServiceFormat.humanized(column), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                sortMenu = false
                                onSort(column)
                            },
                        )
                    }
                }
            }
        }
        if (table.rows.size > 1) {
            ControlSearchField(
                value = search,
                onValueChange = onSearch,
                placeholder = "Search all returned rows",
                modifier = Modifier.fillMaxWidth(),
                testTag = "siteService.detail.tableSearch.${table.id}",
            )
        }
        if (view.rowIndices.isEmpty()) {
            Text(
                if (search.isBlank()) "No rows returned." else "No rows match your search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TableRowCard(row: Map<String, ProviderJsonValue>, columns: List<String>, position: Int, providerId: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        SelectionContainer {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ROW ${position + 1}", color = siteAccent(providerId), style = MaterialTheme.typography.labelSmall)
                columns.forEach { column ->
                    val value = row[column] ?: return@forEach
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            SiteServiceFormat.humanized(column),
                            modifier = Modifier.width(118.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(SiteServiceFormat.display(value), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// MARK: - Raw response explorer

internal data class RawLeaf(val path: String, val value: ProviderJsonValue)

internal fun flattenRaw(value: ProviderJsonValue, path: String = "$", output: MutableList<RawLeaf> = ArrayList()): List<RawLeaf> {
    when (value) {
        is ProviderJsonValue.Obj -> {
            if (value.fields.isEmpty()) output += RawLeaf(path, value)
            value.fields.keys.sorted().forEach { key -> flattenRaw(value.fields.getValue(key), "$path.$key", output) }
        }
        is ProviderJsonValue.Arr -> {
            if (value.items.isEmpty()) output += RawLeaf(path, value)
            value.items.forEachIndexed { index, child -> flattenRaw(child, "$path[$index]", output) }
        }
        else -> output += RawLeaf(path, value)
    }
    return output
}

@Composable
internal fun SiteRawResponseExplorer(payload: SiteServiceDetailUi, providerId: String, modifier: Modifier) {
    val accent = siteAccent(providerId)
    val endpoints = remember(payload) { payload.rawResponses.keys.sorted() }
    var chosenEndpoint by rememberSaveable(payload.resourceId) { mutableStateOf(endpoints.firstOrNull().orEmpty()) }
    var search by rememberSaveable(payload.resourceId) { mutableStateOf("") }
    val endpoint = chosenEndpoint.takeIf { it in endpoints } ?: endpoints.firstOrNull().orEmpty()
    val leaves = remember(payload, endpoint, search) {
        val all = payload.rawResponses[endpoint]?.let { flattenRaw(it) }.orEmpty()
        val needle = search.trim().lowercase()
        if (needle.isEmpty()) {
            all
        } else {
            all.filter { it.path.lowercase().contains(needle) || SiteServiceFormat.display(it.value).lowercase().contains(needle) }
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("siteService.raw"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            SiteFeedbackPanel(
                "Sanitized provider payload",
                "Secrets are redacted; all other returned leaf fields are shown.",
                accent,
            )
        }
        item("endpoints") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                endpoints.forEach { key ->
                    SiteChoiceChip(key, selected = key == endpoint, accent = accent, onClick = { chosenEndpoint = key }, testTag = "siteService.raw.endpoint.$key")
                }
            }
        }
        item("search") {
            ControlSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search field paths and values",
                modifier = Modifier.fillMaxWidth(),
                testTag = "siteService.raw.search",
            )
        }
        item("count") { SiteSectionHeader("Fields", leaves.size, accent) }
        // Paths are not guaranteed unique (a key may itself contain "." or "["), so key by position.
        items(leaves.size, key = { "leaf-$endpoint-$it" }) { index ->
            val leaf = leaves[index]
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(leaf.path, color = accent, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                        Text(
                            SiteServiceFormat.display(leaf.value),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
        item("fetched") {
            Text(
                "Fetched ${formatSiteTimestamp(payload.fetchedAtMillis)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
