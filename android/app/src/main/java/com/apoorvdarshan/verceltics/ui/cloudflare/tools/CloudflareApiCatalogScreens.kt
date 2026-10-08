package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareApiTagSummary
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiOperation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiParameter
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOperationFilter
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsFormat
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.StatusPill

/** Wraps catalog screens with loading and retry states (iOS `CloudflareLoadingView`/`ErrorView`). */
@Composable
internal fun CatalogLoadGate(
    load: CloudflareToolLoad<CloudflareOpenApiCatalog>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (CloudflareOpenApiCatalog) -> Unit,
) {
    when (load) {
        is CloudflareToolLoad.Loaded -> content(load.value)
        is CloudflareToolLoad.Failed -> Column(modifier.padding(18.dp)) {
            ToolBanner(load.message, isError = true, actionLabel = "Retry", onAction = onRetry, testTag = "cloudflare.catalog.error")
        }
        CloudflareToolLoad.Idle, CloudflareToolLoad.Loading -> ToolLoadingBlock(
            "Loading Cloudflare’s official API catalog…",
            modifier.fillMaxSize(),
        )
    }
}

/** Port of iOS `CloudflareFullAPICatalogView`. */
@Composable
internal fun CloudflareApiCatalogScreen(
    catalog: CloudflareOpenApiCatalog,
    onOpenTag: (String) -> Unit,
    onOpenOperation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CloudflareOperationFilter.ALL) }
    val results = remember(catalog, query, filter) {
        if (query.isBlank()) emptyList() else catalog.search(query, filter)
    }
    val tags = remember(catalog, filter) { catalog.visibleTags(filter) }
    CloudflareToolsPage(
        "cloudflare.catalog",
        modifier,
        maximumContentWidth = 980.dp,
        spacing = 14.dp,
    ) { _ ->
        item("header") { CatalogHeader(catalog) }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search official operations",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.catalog.search",
            )
        }
        item("filter") {
            ToolSegmentedChoice(
                options = CloudflareOperationFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
                testTagPrefix = "cloudflare.catalog.filter",
            )
        }
        if (query.isBlank()) {
            item("tags-heading") {
                ToolPanel {
                    ToolSectionHeader("Product Directory", Icons.Rounded.Apps, tags.size)
                    if (tags.isEmpty()) {
                        ToolEmptyBlock(
                            "No product groups",
                            "No Cloudflare product groups match this read/write filter.",
                            Icons.Rounded.FilterAlt,
                        )
                    }
                }
            }
            items(tags, key = { "tag-${it.name}" }) { tag -> CatalogTagRow(tag, onOpenTag) }
        } else if (results.isEmpty()) {
            item("no-results") {
                ToolPanel {
                    ToolEmptyBlock(
                        "No API operations found",
                        "Search by product, endpoint, permission, method or path.",
                        Icons.Rounded.Search,
                    )
                }
            }
        } else {
            item("results-heading") {
                ToolPanel { ToolSectionHeader("Matching Operations", Icons.Rounded.Terminal, results.size) }
            }
            operationRows(results, onOpenOperation)
        }
    }
}

@Composable
private fun CatalogHeader(catalog: CloudflareOpenApiCatalog) {
    ToolPanel(accentAlpha = 0.09f, testTag = "cloudflare.catalog.header") {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ToolIconTile(Icons.Rounded.Hub, size = 48.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Official API directory", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Generated from Cloudflare’s OpenAPI schema",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill("Complete", CloudflareToolsColors.success())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMetric("OPERATIONS", CloudflareToolsFormat.count(catalog.operationCount), Modifier.weight(1f))
                ToolMetric("PRODUCT GROUPS", CloudflareToolsFormat.count(catalog.tagCount), Modifier.weight(1f))
                ToolMetric("PATHS", CloudflareToolsFormat.count(catalog.pathCount), Modifier.weight(1f))
            }
            Text(
                "Schema ${catalog.sourceCommit.take(8)} · OpenAPI ${catalog.openApiVersion} · BSD-3-Clause © Cloudflare",
                style = MonospaceSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogTagRow(tag: CloudflareApiTagSummary, onOpenTag: (String) -> Unit) {
    ToolPanel {
        ToolNavigationRow(
            title = tag.name,
            subtitle = "${tag.operationCount} operations · ${tag.writeCount} writes",
            icon = Icons.Rounded.Inventory2,
            onClick = { onOpenTag(tag.name) },
            testTag = "cloudflare.catalog.tag.${tag.name}",
        )
    }
}

private fun LazyListScope.operationRows(operations: List<CloudflareOpenApiOperation>, onOpenOperation: (String) -> Unit) {
    items(operations, key = { "operation-${it.id}" }) { operation ->
        ToolPanel { CatalogOperationRow(operation) { onOpenOperation(operation.id) } }
    }
}

/** iOS `CloudflareOpenAPIOperationRow`. */
@Composable
private fun CatalogOperationRow(operation: CloudflareOpenApiOperation, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .testTag("cloudflare.catalog.operation.${operation.id}"),
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(operation.method)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    operation.summary,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (operation.deprecated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    operation.path,
                    style = MonospaceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (operation.deprecated) {
                Spacer(Modifier.width(6.dp))
                Text("OLD", style = MaterialTheme.typography.labelSmall, color = CloudflareToolsColors.warning())
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Port of iOS `CloudflareAPITagView`. */
@Composable
internal fun CloudflareApiTagScreen(
    catalog: CloudflareOpenApiCatalog,
    tag: String,
    onOpenOperation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable(tag) { mutableStateOf("") }
    var filter by rememberSaveable(tag) { mutableStateOf(CloudflareOperationFilter.ALL) }
    val operations = remember(catalog, tag) { catalog.operationsForTag(tag) }
    val visible = remember(operations, query, filter) { operations.filter { filter.includes(it) && it.matches(query) } }
    CloudflareToolsPage(
        "cloudflare.catalog.tagScreen",
        modifier,
        maximumContentWidth = 980.dp,
        spacing = 12.dp,
    ) { _ ->
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ${operations.size} operations",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.catalog.tag.search",
            )
        }
        item("filter") {
            ToolSegmentedChoice(
                options = CloudflareOperationFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
                testTagPrefix = "cloudflare.catalog.tag.filter",
            )
        }
        if (visible.isEmpty()) {
            item("empty") {
                ToolPanel {
                    ToolEmptyBlock(
                        "No operations found",
                        "Adjust the search or read/write filter for this product group.",
                        Icons.Rounded.Search,
                    )
                }
            }
        } else {
            operationRows(visible, onOpenOperation)
        }
    }
}

/** Port of iOS `CloudflareGeneratedOperationView`. */
@Composable
internal fun CloudflareApiOperationScreen(
    operation: CloudflareOpenApiOperation,
    editor: CloudflareOperationEditorUi?,
    onUpdateValue: (String, String) -> Unit,
    onUpdateBody: (String) -> Unit,
    onUpdateContentType: (String) -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN,
) {
    if (editor == null || editor.operationId != operation.id) {
        ToolLoadingBlock("Preparing request…", modifier.fillMaxSize())
        return
    }
    CloudflareToolsPage(
        "cloudflare.catalog.operationScreen",
        modifier,
        maximumContentWidth = 980.dp,
        spacing = 16.dp,
    ) { _ ->
        item("header") { OperationHeader(operation) }
        if (!operation.supports(authMode)) {
            item("credential") {
                ToolBanner(
                    catalogCredentialWarning(authMode),
                    isError = true,
                    testTag = "cloudflare.catalog.credentialWarning",
                )
            }
        }
        if (operation.permissions.isNotEmpty()) {
            item("permissions") {
                ToolPanel {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            "REQUIRED TOKEN PERMISSIONS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Any one of these permissions grants access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        operation.permissions.forEach { permission ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Key, contentDescription = null, tint = CloudflareToolsColors.warning(), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(permission, style = MaterialTheme.typography.bodyMedium, color = CloudflareToolsColors.warning())
                            }
                        }
                    }
                }
            }
        }
        if (operation.parameters.isNotEmpty()) {
            item("parameters-heading") {
                ToolPanel { ToolSectionHeader("Request Parameters", Icons.Rounded.Tune, operation.parameters.size) }
            }
            items(operation.parameters, key = { "parameter-${it.key}" }) { parameter ->
                ToolPanel {
                    ParameterEditor(parameter, editor.values[parameter.key].orEmpty()) { onUpdateValue(parameter.key, it) }
                }
            }
        }
        if (operation.contentTypes.isNotEmpty()) {
            item("body") {
                ToolPanel {
                    ToolSectionHeader("Request Body", Icons.Rounded.Description)
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        if (operation.contentTypes.size > 1) {
                            ToolDropdownField(
                                label = "Content type",
                                value = editor.contentType,
                                options = operation.contentTypes.map { it to it.ifEmpty { "(none)" } },
                                selectedKey = editor.contentType,
                                onSelect = onUpdateContentType,
                                icon = Icons.Rounded.Description,
                                testTag = "cloudflare.catalog.contentType",
                            )
                        } else {
                            ToolDetailRow("Content type", editor.contentType, monospace = true)
                        }
                        if (operation.isMultipart) {
                            Text(
                                "The API Explorer includes a multipart composer for these ${operation.multipartFields.size} schema fields, including files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CloudflareToolsColors.warning(),
                            )
                            operation.multipartFields.forEach { field ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                                    Icon(
                                        if (field.isFile) Icons.Rounded.Description else Icons.Rounded.Tune,
                                        contentDescription = null,
                                        tint = CloudflareToolsColors.Orange,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(field.name, style = MonospaceSmall, modifier = Modifier.weight(1f))
                                    Text(
                                        if (field.required) "REQUIRED" else (field.format ?: field.type ?: "field").uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            ToolTextEditor(
                                label = "Body template",
                                value = editor.body,
                                onValueChange = onUpdateBody,
                                placeholder = "{\n  \"key\": \"value\"\n}",
                                minHeight = 180.dp,
                                maxHeight = 420.dp,
                                testTag = "cloudflare.catalog.body",
                            )
                        }
                    }
                }
            }
        }
        item("review") {
            ToolPrimaryButton(
                text = "Review and execute request",
                onClick = onReview,
                enabled = operation.supports(authMode),
                testTag = "cloudflare.catalog.review",
            )
        }
    }
}

@Composable
private fun OperationHeader(operation: CloudflareOpenApiOperation) {
    ToolPanel(accentAlpha = 0.08f) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodBadge(operation.method, width = 64.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    operation.primaryTag.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (operation.deprecated) StatusPill("Deprecated", CloudflareToolsColors.warning())
            }
            Text(operation.summary, style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(operation.path, style = MonospaceSmall, color = CloudflareToolsColors.Orange)
            }
            if (operation.description.isNotEmpty()) {
                Text(operation.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ParameterEditor(parameter: CloudflareOpenApiParameter, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                parameter.location.wireName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = CloudflareToolsColors.Orange,
            )
            Spacer(Modifier.width(7.dp))
            Text(parameter.name, style = MonospaceSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (parameter.required) {
                Text("REQUIRED", style = MaterialTheme.typography.labelSmall, color = CloudflareToolsColors.danger())
                Spacer(Modifier.width(7.dp))
            }
            Text(parameter.typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val options = parameter.enumOptions
        if (options.isNotEmpty()) {
            ToolDropdownField(
                label = if (parameter.required) "Required value" else "Optional",
                value = value.ifEmpty { "Choose a value" },
                options = listOf("" to "(none)") + options.map { it to it },
                selectedKey = value,
                onSelect = onValueChange,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                testTag = "cloudflare.catalog.parameter.${parameter.key}",
            )
        } else {
            ToolTextEditor(
                label = if (parameter.required) "Required value" else "Optional",
                value = value,
                onValueChange = onValueChange,
                placeholder = parameter.name,
                singleLine = true,
                testTag = "cloudflare.catalog.parameter.${parameter.key}",
            )
        }
        if (parameter.description.isNotEmpty()) {
            Text(parameter.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS `CloudflareGeneratedOperationView.credentialWarning`. */
internal fun catalogCredentialWarning(authMode: CloudflareAuthMode): String = when (authMode) {
    CloudflareAuthMode.GLOBAL_API_KEY ->
        "Cloudflare’s schema marks this endpoint as API-token only. Add a scoped token with the permissions shown below."
    CloudflareAuthMode.API_TOKEN ->
        "Cloudflare’s schema does not list API-token authentication for this endpoint. Switch to a Global API Key account."
}
