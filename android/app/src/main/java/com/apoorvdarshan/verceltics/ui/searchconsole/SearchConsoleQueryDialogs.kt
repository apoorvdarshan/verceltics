package com.apoorvdarshan.verceltics.ui.searchconsole

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import java.time.LocalDate

/**
 * Advanced report builder: custom dates, page size and every control in one place. It applies the
 * same Google rules as the on-page chips, so choosing Hour or hourly data stays consistent.
 */
@Composable
internal fun PerformanceControlsDialog(
    query: SearchConsolePerformanceQueryUi,
    onApply: (SearchConsolePerformanceQueryUi) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(query) { mutableStateOf(query) }
    var startDate by remember(query) { mutableStateOf(query.startDate) }
    var endDate by remember(query) { mutableStateOf(query.endDate) }
    var validationError by remember(query) { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 680.dp)
                .heightIn(max = 780.dp)
                .testTag("searchConsole.performanceControls"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogHeader(
                    title = "BUILD PERFORMANCE REPORT",
                    subtitle = "Google Search Console · read only",
                    icon = Icons.Rounded.Tune,
                    onDismiss = onDismiss,
                    closeTag = "searchConsole.performanceControls.close",
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 6.dp),
                ) {
                    item {
                        ControlGroup("DATE RANGE") {
                            ChoiceGrid(
                                values = SearchConsoleDatePresetUi.entries,
                                selected = { it == draft.preset },
                                label = { it.displayLabel },
                                onSelect = { preset ->
                                    draft = if (preset == SearchConsoleDatePresetUi.CUSTOM) {
                                        draft.copy(preset = preset, page = 0)
                                    } else {
                                        draft.withPreset(preset)
                                    }
                                    startDate = draft.startDate
                                    endDate = draft.endDate
                                },
                            )
                            if (draft.preset == SearchConsoleDatePresetUi.CUSTOM) {
                                Spacer(Modifier.height(10.dp))
                                ThemedAuthTextField(
                                    value = startDate,
                                    onValueChange = { startDate = it.take(10); validationError = null },
                                    label = "Start date · YYYY-MM-DD",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("searchConsole.performanceControls.startDate"),
                                )
                                Spacer(Modifier.height(8.dp))
                                ThemedAuthTextField(
                                    value = endDate,
                                    onValueChange = { endDate = it.take(10); validationError = null },
                                    label = "End date · YYYY-MM-DD",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("searchConsole.performanceControls.endDate"),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Search Console reports dates in Pacific Time. Both boundary dates are included.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        ControlGroup("SEARCH SURFACE") {
                            ChoiceGrid(
                                SearchConsoleSearchTypeUi.entries,
                                selected = { it == draft.searchType },
                                label = { it.displayLabel },
                                onSelect = { draft = draft.withSearchType(it) },
                            )
                        }
                    }
                    item {
                        ControlGroup("DATA FRESHNESS") {
                            ChoiceGrid(
                                SearchConsoleDataStateUi.entries,
                                selected = { it == draft.dataState },
                                label = { it.displayLabel },
                                onSelect = { draft = draft.withDataState(it) },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                draft.dataState.explanation,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        ControlGroup("AGGREGATION") {
                            ChoiceGrid(
                                SearchConsoleAggregationUi.entries,
                                selected = { it == draft.aggregation },
                                label = { it.displayLabel },
                                onSelect = { draft = draft.withAggregation(it) },
                                enabled = draft::canSelectAggregation,
                            )
                            SearchConsoleAggregationUi.entries
                                .mapNotNull(draft::aggregationRestriction)
                                .distinct()
                                .forEach { restriction ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        restriction,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                        }
                    }
                    item {
                        ControlGroup("BREAKDOWN DIMENSIONS") {
                            ChoiceGrid(
                                SearchConsoleDimensionUi.entries,
                                selected = { it in draft.dimensions },
                                label = { it.displayLabel },
                                onSelect = { draft = draft.withToggledDimension(it) },
                            )
                        }
                    }
                    item {
                        ControlGroup("AND FILTERS") {
                            FilterComposer(
                                filters = draft.filters,
                                onChange = { draft = draft.withFilters(it) },
                            )
                        }
                    }
                    item {
                        ControlGroup("SORT AND PAGE SIZE") {
                            ChoiceGrid(
                                SearchConsoleSortFieldUi.entries,
                                selected = { it == draft.sortField },
                                label = { it.displayLabel(draft.dimensions) },
                                onSelect = { draft = draft.copy(sortField = it, page = 0) },
                            )
                            Spacer(Modifier.height(8.dp))
                            ChoiceGrid(
                                listOf(false, true),
                                selected = { it == draft.sortAscending },
                                label = { if (it) "Ascending" else "Descending" },
                                onSelect = { draft = draft.copy(sortAscending = it, page = 0) },
                            )
                            Spacer(Modifier.height(8.dp))
                            ChoiceGrid(
                                listOf(25, 50, 100),
                                selected = { it == draft.pageSize },
                                label = { "$it rows" },
                                onSelect = { draft = draft.copy(pageSize = it, page = 0) },
                            )
                        }
                    }
                    validationError?.let { message ->
                        item { FeedbackPanel("Check date range", message, MaterialTheme.colorScheme.error) }
                    }
                }
                ThemedActionButton(
                    text = "RUN REPORT",
                    onClick = {
                        val candidate = runCatching {
                            val start = LocalDate.parse(startDate.trim())
                            val end = LocalDate.parse(endDate.trim())
                            require(!start.isAfter(end))
                            draft.copy(
                                startDate = start.toString(),
                                endDate = end.toString(),
                                page = 0,
                            ).normalizedForGoogle()
                        }.getOrNull()
                        if (candidate == null) {
                            validationError = "Use valid YYYY-MM-DD dates with the start on or before the end."
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onApply(candidate)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "searchConsole.performanceControls.apply",
                )
            }
        }
    }
}

/** iOS `SearchConsoleFilterEditor`: AND filters that load only after Apply. */
@Composable
internal fun FilterEditorDialog(
    filters: List<SearchConsoleFilterUi>,
    onApply: (List<SearchConsoleFilterUi>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp)
                .testTag("searchConsole.filterEditor"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogHeader(
                    title = "PERFORMANCE FILTERS",
                    subtitle = "Search Console combines these filters with AND.",
                    icon = Icons.Rounded.FilterList,
                    onDismiss = onDismiss,
                    closeTag = "searchConsole.filterEditor.close",
                )
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { FilterComposer(draft, onChange = { draft = it }) }
                    item {
                        Text(
                            "Changes load only after you tap Apply.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (draft.isNotEmpty()) {
                    ThemedActionButton(
                        text = "CLEAR ALL",
                        onClick = { draft = emptyList() },
                        modifier = Modifier.fillMaxWidth(),
                        tone = ThemedActionTone.DESTRUCTIVE,
                        testTag = "searchConsole.filterEditor.clear",
                    )
                }
                ThemedActionButton(
                    text = "APPLY",
                    onClick = { onApply(draft) },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "searchConsole.filterEditor.apply",
                )
            }
        }
    }
}

@Composable
private fun FilterComposer(
    filters: List<SearchConsoleFilterUi>,
    onChange: (List<SearchConsoleFilterUi>) -> Unit,
) {
    var dimension by remember { mutableStateOf(SearchConsoleDimensionUi.QUERY) }
    var operator by remember { mutableStateOf(SearchConsoleFilterOperatorUi.CONTAINS) }
    var expression by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filters.isEmpty()) {
            Text(
                "No filters. Results include every matching row.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        filters.forEachIndexed { index, filter ->
            FilterSummaryRow(filter) {
                onChange(filters.filterIndexed { itemIndex, _ -> itemIndex != index })
            }
        }
        Spacer(Modifier.height(4.dp))
        ChoiceGrid(
            SearchConsoleDimensionUi.entries.filter(SearchConsoleDimensionUi::isFilterable),
            selected = { it == dimension },
            label = { it.displayLabel },
            onSelect = { dimension = it },
        )
        ChoiceGrid(
            SearchConsoleFilterOperatorUi.entries,
            selected = { it == operator },
            label = { it.displayLabel },
            onSelect = { operator = it },
        )
        ThemedAuthTextField(
            value = expression,
            onValueChange = { expression = it.take(4_096) },
            label = "Value or regular expression",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("searchConsole.filterEditor.expression"),
        )
        ThemedActionButton(
            text = "ADD FILTER",
            onClick = {
                val trimmed = expression.trim()
                if (trimmed.isNotEmpty() && filters.size < 32) {
                    onChange(filters + SearchConsoleFilterUi(dimension, operator, trimmed))
                    expression = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = expression.isNotBlank() && filters.size < 32,
            tone = ThemedActionTone.NEUTRAL,
            testTag = "searchConsole.filterEditor.add",
        )
    }
}
