package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiAccessFilter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiOperation
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiParameter
import com.apoorvdarshan.verceltics.data.apicatalog.filteredOperations
import com.apoorvdarshan.verceltics.data.apicatalog.isManaged
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import java.text.NumberFormat

/**
 * iOS `ProviderFullAPICatalogView`: every official operation for one provider, searchable and
 * filterable by access and tag, plus the manual raw request entry.
 */
@Composable
fun ProviderFullApiCatalogScreen(
    state: ProviderApiWorkspaceState,
    accent: Color,
    onQueryChange: (String) -> Unit,
    onSelectTag: (String?) -> Unit,
    onSelectAccess: (ProviderApiAccessFilter) -> Unit,
    onOpenOperation: (String) -> Unit,
    onOpenManualExplorer: () -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val catalogState = state.catalog) {
        ProviderApiCatalogState.Loading -> Box(
            modifier
                .fillMaxSize()
                .testTag("providerApi.loading"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = accent)
                Spacer(Modifier.height(14.dp))
                Text("Loading operations", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is ProviderApiCatalogState.Failed -> Column(
            modifier
                .fillMaxSize()
                .padding(18.dp)
                .testTag("providerApi.error"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ApiEmptyState(Icons.Rounded.WarningAmber, "Catalog unavailable", catalogState.message)
            ThemedActionButton("TRY AGAIN", onClick = onRetry, modifier = Modifier.fillMaxWidth(), testTag = "providerApi.retry")
        }
        is ProviderApiCatalogState.Loaded -> CatalogBody(
            catalog = catalogState.catalog,
            state = state,
            accent = accent,
            onQueryChange = onQueryChange,
            onSelectTag = onSelectTag,
            onSelectAccess = onSelectAccess,
            onOpenOperation = onOpenOperation,
            onOpenManualExplorer = onOpenManualExplorer,
            onOpenLink = onOpenLink,
            modifier = modifier,
        )
    }
}

@Composable
private fun CatalogBody(
    catalog: ProviderApiCatalog,
    state: ProviderApiWorkspaceState,
    accent: Color,
    onQueryChange: (String) -> Unit,
    onSelectTag: (String?) -> Unit,
    onSelectAccess: (ProviderApiAccessFilter) -> Unit,
    onOpenOperation: (String) -> Unit,
    onOpenManualExplorer: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val tags = remember(catalog) { listOf(ALL_TAG) + catalog.sortedTags }
    val operations = remember(catalog, state.query, state.selectedTag, state.access) {
        catalog.filteredOperations(state.query, state.selectedTag, state.access)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("providerApi.catalog"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") { CatalogSummary(catalog, accent, onOpenLink) }
        item("manual") {
            ApiNavigationCard(
                title = "Manual raw request",
                subtitle = "For undocumented, beta, or newly released routes",
                icon = Icons.Rounded.Terminal,
                accent = accent,
                onClick = onOpenManualExplorer,
                testTag = "providerApi.manualExplorer",
            )
        }
        item("search") {
            ControlSearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "Search operations, paths and tags",
                modifier = Modifier.fillMaxWidth(),
                testTag = "providerApi.search",
            )
        }
        item("access") {
            ApiSegmentedControl(
                options = ProviderApiAccessFilter.entries,
                selected = state.access,
                label = ProviderApiAccessFilter::title,
                onSelect = onSelectAccess,
                testTag = { "providerApi.access.${it.name}" },
            )
        }
        item("tags") {
            ApiChipRow(
                items = tags,
                selected = state.selectedTag ?: ALL_TAG,
                onSelect = { tag -> onSelectTag(tag.takeUnless { it == ALL_TAG }) },
                testTagPrefix = "providerApi.tag",
            )
        }
        item("heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Operations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    formatCount(operations.size),
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("providerApi.operationCount"),
                )
            }
        }
        if (operations.isEmpty()) {
            item("empty") {
                ApiEmptyState(Icons.Rounded.Search, "No matching operations", "Change the search, access filter, or selected tag.")
            }
        } else {
            items(operations, key = { "operation-${it.id}" }) { operation ->
                OperationRow(operation, accent) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onOpenOperation(operation.id)
                }
            }
        }
    }
}

@Composable
private fun CatalogSummary(catalog: ProviderApiCatalog, accent: Color, onOpenLink: (String) -> Unit) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent.copy(alpha = 0.24f),
        testTag = "providerApi.summary",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(catalog.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(catalog.apiVersion, color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    formatCount(catalog.operations.size),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("providerApi.totalCount"),
                )
            }
            if (catalog.sourceDescription.isNotBlank()) {
                Text(catalog.sourceDescription, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (catalog.sourceUrl.startsWith("https://")) {
                Surface(
                    onClick = { onOpenLink(catalog.sourceUrl) },
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("providerApi.sourceLink"),
                    color = Color.Transparent,
                    contentColor = accent,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Official API definition", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(operation: ProviderApiOperation, accent: Color, onClick: () -> Unit) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline,
        onClick = onClick,
        testTag = "providerApi.operation.${operation.id}",
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(operation.method, operation.isMutation)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(operation.summary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    operation.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    operation.primaryTag.uppercase(),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open ${operation.summary}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MethodBadge(method: String, isMutation: Boolean) {
    val color = if (isMutation) ApiWriteColor else ApiReadColor
    Surface(
        modifier = Modifier.size(width = 58.dp, height = 30.dp),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = color,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                method,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ApiEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, testTag = "providerApi.empty") {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/**
 * iOS `ProviderAPIOperationView`: parameters, content type and body template for one operation,
 * then "Review request" into the raw explorer.
 */
@Composable
fun ProviderApiOperationScreen(
    operation: ProviderApiOperation,
    draft: ProviderApiOperationDraft,
    managedHeaders: Set<String>,
    hasMissingRequired: Boolean,
    accent: Color,
    onParameterChange: (parameterId: String, value: String) -> Unit,
    onBodyChange: (String) -> Unit,
    onContentTypeChange: (String) -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp)
            .testTag("providerApi.operationDetail"),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ApiPanel(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    operation.method,
                    color = if (operation.isMutation) ApiWriteColor else ApiReadColor,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.7.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    operation.primaryTag.uppercase(),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.7.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SelectionContainer {
                Text(
                    operation.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.testTag("providerApi.operationPath"),
                )
            }
            if (operation.description.isNotBlank()) {
                Text(operation.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        operation.parameters.forEach { parameter ->
            ParameterEditor(
                parameter = parameter,
                value = draft.values[parameter.id].orEmpty(),
                managed = parameter.isManaged(managedHeaders),
                accent = accent,
                onValueChange = { onParameterChange(parameter.id, it) },
            )
        }
        if (operation.contentTypes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorLabel("Content type")
                ApiDropdown(
                    value = draft.contentType,
                    options = operation.contentTypes,
                    onSelect = onContentTypeChange,
                    accessibilityLabel = "Content type",
                    testTag = "providerApi.contentType",
                )
            }
        }
        if (operation.bodyTemplate.isNotEmpty() || operation.requestBodyRequired) {
            ApiPanel(accent = accent) {
                Text(
                    if (operation.requestBodyRequired) "REQUEST BODY · REQUIRED" else "REQUEST BODY",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    fontWeight = FontWeight.SemiBold,
                )
                ApiTextField(
                    value = draft.body,
                    onValueChange = onBodyChange,
                    accessibilityLabel = "Request body",
                    minLines = 7,
                    maxLines = 18,
                    testTag = "providerApi.operationBody",
                )
            }
        }
        ThemedActionButton(
            text = when {
                hasMissingRequired -> "FILL REQUIRED FIELDS"
                operation.isMutation -> "REVIEW WRITE REQUEST"
                else -> "REVIEW REQUEST"
            },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onReview()
            },
            enabled = !hasMissingRequired,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            testTag = "providerApi.review",
        )
    }
}

@Composable
private fun ParameterEditor(
    parameter: ProviderApiParameter,
    value: String,
    managed: Boolean,
    accent: Color,
    onValueChange: (String) -> Unit,
) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "providerApi.parameter.${parameter.id}",
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    parameter.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(parameter.location.wireValue.uppercase(), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                if (parameter.required && !managed) {
                    Text("REQUIRED", color = ApiWarningColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            when {
                managed -> Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (parameter.name.equals("Content-Type", ignoreCase = true)) {
                            "Set with the request’s content type"
                        } else {
                            "Attached privately by Verceltics"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
                parameter.enumValues.isNotEmpty() -> ApiDropdown(
                    value = value,
                    options = parameter.enumValues,
                    onSelect = onValueChange,
                    accessibilityLabel = parameter.name,
                    testTag = "providerApi.parameterValue.${parameter.id}",
                )
                else -> ApiTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = parameter.type,
                    accessibilityLabel = parameter.name,
                    singleLine = true,
                    testTag = "providerApi.parameterValue.${parameter.id}",
                )
            }
            if (parameter.description.isNotBlank()) {
                Text(parameter.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal const val ALL_TAG = "All"

internal fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)
