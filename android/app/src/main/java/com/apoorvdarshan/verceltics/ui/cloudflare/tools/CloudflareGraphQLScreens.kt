package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDataset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDatasets
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsFormat
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.StatusPill

/** Port of iOS `CloudflareGraphQLDatasetCatalogView`. */
@Composable
internal fun CloudflareGraphQLDatasetScreen(
    state: CloudflareDatasetsUiState,
    zones: List<CloudflareToolsZone>,
    onSelectScope: (CloudflareGraphQLScope) -> Unit,
    onSelectZone: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenDataset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.datasets, query) { state.datasets.filter { it.matches(query) } }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("cloudflare.graphql"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item("scope") {
            ToolPanel(accentAlpha = 0.07f) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("LIVE DATASET DISCOVERY", style = MaterialTheme.typography.labelSmall, color = CloudflareToolsColors.Orange)
                            Text(
                                "Availability and limits come from your current Cloudflare plan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill("${state.datasets.size} datasets", CloudflareToolsColors.success())
                    }
                    ToolSegmentedChoice(
                        options = CloudflareGraphQLScope.entries,
                        selected = state.scope,
                        label = { it.label },
                        onSelect = onSelectScope,
                        enabled = { it != CloudflareGraphQLScope.ZONE || zones.isNotEmpty() },
                        testTagPrefix = "cloudflare.graphql.scope",
                    )
                    if (state.scope == CloudflareGraphQLScope.ZONE && zones.isNotEmpty()) {
                        ToolDropdownField(
                            label = "Zone",
                            value = zones.firstOrNull { it.id == state.zoneId }?.name ?: "Choose zone",
                            options = zones.sortedBy { it.name.lowercase() }.map { it.id to it.name },
                            selectedKey = state.zoneId,
                            onSelect = onSelectZone,
                            icon = Icons.Rounded.Public,
                            testTag = "cloudflare.graphql.zone",
                        )
                    }
                }
            }
        }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search datasets and fields",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.graphql.search",
            )
        }
        when {
            state.isLoading && state.datasets.isEmpty() -> item("loading") { ToolLoadingBlock("Discovering GraphQL datasets…") }
            state.error != null && state.datasets.isEmpty() -> item("error") {
                ToolBanner(state.error, isError = true, actionLabel = "Retry", onAction = onRetry, testTag = "cloudflare.graphql.error")
            }
            else -> {
                state.error?.let { error ->
                    item("refresh-error") {
                        ToolBanner(error, isError = true, title = "Dataset refresh failed", actionLabel = "Retry", onAction = onRetry)
                    }
                }
                item("heading") {
                    ToolPanel { ToolSectionHeader("Available Datasets", Icons.Rounded.QueryStats, filtered.size) }
                }
                if (filtered.isEmpty()) {
                    item("empty") {
                        ToolPanel {
                            ToolEmptyBlock(
                                "No datasets found",
                                if (query.isBlank()) "Cloudflare returned no datasets for this scope." else "No datasets or fields match “$query”.",
                                Icons.Rounded.Search,
                            )
                        }
                    }
                }
                items(filtered, key = { "dataset-${it.name}" }) { dataset ->
                    ToolPanel {
                        ToolNavigationRow(
                            title = dataset.name,
                            subtitle = if (dataset.isLocked) {
                                "Not enabled on this plan"
                            } else {
                                "${dataset.availableFields.size} fields · ${CloudflareGraphQLDatasets.durationLabel(dataset.maxDuration)} max window"
                            },
                            icon = if (dataset.isLocked) Icons.Rounded.Lock else Icons.Rounded.MonitorHeart,
                            tint = if (dataset.isLocked) MaterialTheme.colorScheme.onSurfaceVariant else CloudflareToolsColors.Orange,
                            onClick = { onOpenDataset(dataset.name) },
                            testTag = "cloudflare.graphql.dataset.${dataset.name}",
                        )
                    }
                }
            }
        }
    }
}

/** Port of iOS `CloudflareGraphQLDatasetDetailView`. */
@Composable
internal fun CloudflareGraphQLDatasetDetailScreen(
    dataset: CloudflareGraphQLDataset,
    onOpenQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("cloudflare.graphql.detail"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item("header") {
            ToolPanel(accentAlpha = 0.07f) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QueryStats, contentDescription = null, tint = CloudflareToolsColors.Orange)
                        Spacer(Modifier.width(9.dp))
                        Text(dataset.name, style = MonospaceSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), modifier = Modifier.weight(1f))
                        StatusPill(
                            if (dataset.isLocked) "Locked" else "Enabled",
                            if (dataset.isLocked) CloudflareToolsColors.warning() else CloudflareToolsColors.success(),
                        )
                    }
                    if (dataset.description.isNotEmpty()) {
                        Text(dataset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item("limits") {
            ToolPanel {
                ToolSectionHeader("Plan Limits", Icons.Rounded.Speed)
                ToolDetailRow("Maximum window", CloudflareGraphQLDatasets.durationLabel(dataset.maxDuration), Icons.Rounded.CalendarMonth)
                ToolDetailRow("Retention", CloudflareGraphQLDatasets.durationLabel(dataset.notOlderThan), Icons.Rounded.History)
                ToolDetailRow(
                    "Maximum records",
                    dataset.maxPageSize?.let { CloudflareToolsFormat.number(it.toBigDecimal()) } ?: "Not returned",
                    Icons.Rounded.FormatListNumbered,
                )
                ToolDetailRow(
                    "Fields per query",
                    dataset.maxNumberOfFields?.let { CloudflareToolsFormat.number(it.toBigDecimal()) } ?: "Not returned",
                    Icons.Rounded.GridView,
                )
            }
        }
        item("fields") {
            ToolPanel {
                ToolSectionHeader("Available Fields", Icons.AutoMirrored.Rounded.ViewList, dataset.availableFields.size)
                SelectionContainer {
                    Text(
                        dataset.availableFields.joinToString("\n").ifEmpty { "No fields returned" },
                        style = MonospaceSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(15.dp),
                    )
                }
            }
        }
        item("open") {
            ToolPrimaryButton(
                text = "Open generated query",
                onClick = onOpenQuery,
                enabled = !dataset.isLocked,
                testTag = "cloudflare.graphql.openQuery",
            )
        }
    }
}
