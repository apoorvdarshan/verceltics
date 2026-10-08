package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import com.apoorvdarshan.verceltics.ui.hosting.adaptiveRows
import com.apoorvdarshan.verceltics.ui.hosting.ProviderGridRow
import com.apoorvdarshan.verceltics.ui.hosting.ProviderLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Domain
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnLock
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareApiPreset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareProductCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareProductDefinition
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsFormat
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.StatusPill

/**
 * Port of iOS `CloudflareProductCenterView`. Presets marked as API-token only are locked for Global
 * API Key connections, exactly like iOS (`operation.requiresAPIToken && authenticationMode != .apiToken`).
 */
@Composable
internal fun CloudflareProductCenterScreen(
    context: CloudflareToolsContext,
    selectedZoneId: String?,
    onSelectZone: (String) -> Unit,
    onOpenOperation: (CloudflareApiPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val products = remember(query) { CloudflareProductCatalog.filtered(query) }
    val selectedZone = context.zones.firstOrNull { it.id == selectedZoneId }
    CloudflareToolsPage(
        "cloudflare.productCenter",
        modifier,
        maximumContentWidth = ProviderLayout.CatalogMaxWidth,
        spacing = 16.dp,
    ) { page ->
        item("header") {
            ToolPanel(accentAlpha = 0.09f) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        ToolIconTile(Icons.Rounded.Thunderstorm, size = 48.dp)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cloudflare control plane", style = MaterialTheme.typography.titleLarge)
                            Text(
                                context.accountName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (context.authMode == CloudflareAuthMode.API_TOKEN) {
                            StatusPill("Scoped", CloudflareToolsColors.success())
                        } else {
                            StatusPill("Global", CloudflareToolsColors.warning())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ToolMetric("PRODUCTS", CloudflareToolsFormat.count(CloudflareProductCatalog.products.size), Modifier.weight(1f))
                        ToolMetric("OPERATIONS", CloudflareToolsFormat.count(CloudflareProductCatalog.operationCount), Modifier.weight(1f))
                        ToolMetric("ZONES", CloudflareToolsFormat.count(context.zoneCount), Modifier.weight(1f))
                    }
                }
            }
        }
        item("context") {
            ToolPanel {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("REQUEST CONTEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Domain, contentDescription = null, tint = CloudflareToolsColors.Orange)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("ACCOUNT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(context.accountName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(context.accountId.take(8), style = MonospaceSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (context.zones.isEmpty()) {
                        Text(
                            "No zone is available. Account-scoped operations still work; zone paths keep a ZONE_ID placeholder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CloudflareToolsColors.warning(),
                        )
                    } else {
                        ToolDropdownField(
                            label = "Zone",
                            value = selectedZone?.name ?: "Choose zone",
                            options = context.zones.sortedBy { it.name.lowercase() }.map { it.id to it.name },
                            selectedKey = selectedZoneId,
                            onSelect = onSelectZone,
                            icon = Icons.Rounded.Public,
                            testTag = "cloudflare.productCenter.zone",
                        )
                    }
                    Text(
                        "Every mutation opens an editable request and requires a final confirmation before it reaches Cloudflare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search products and operations",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.productCenter.search",
            )
        }
        if (products.isEmpty()) {
            item("empty") {
                ToolPanel {
                    ToolEmptyBlock(
                        "No operations found",
                        "Try a product name such as DNS, Workers, Images or Tunnel.",
                        Icons.Rounded.Search,
                    )
                }
            }
        }
        // iOS `productColumns`: adaptiveColumns(regularMinimum: 400, regularMaximum: 520, spacing: 16).
        val columns = page.columns(minimumCellWidth = 400.dp, spacing = 16.dp, maximumColumns = 3)
        items(products.adaptiveRows(columns), key = { row -> "product-${row.joinToString("+") { it.id }}" }) { row ->
            ProviderGridRow(row, columns, spacing = 16.dp) { product ->
                ProductPanel(product, context.authMode) { preset ->
                    onOpenOperation(preset.resolved(context.accountId, selectedZoneId))
                }
            }
        }
    }
}

/** iOS `operationRow` lock: API-token-only presets cannot run with a Global API Key. */
internal fun isProductOperationLocked(operation: CloudflareApiPreset, authMode: CloudflareAuthMode): Boolean =
    operation.requiresApiToken && authMode != CloudflareAuthMode.API_TOKEN

@Composable
private fun ProductPanel(
    product: CloudflareProductDefinition,
    authMode: CloudflareAuthMode,
    onOpen: (CloudflareApiPreset) -> Unit,
) {
    ToolPanel(accentAlpha = 0.04f, testTag = "cloudflare.productCenter.product.${product.id}") {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIconTile(productIcon(product.id), size = 36.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(product.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    product.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    product.operations.size.toString(),
                    style = MonospaceSmall,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
        ToolDivider()
        product.operations.forEachIndexed { index, operation ->
            ProductOperationRow(operation, locked = isProductOperationLocked(operation, authMode)) { onOpen(operation) }
            if (index < product.operations.lastIndex) ToolDivider(start = 61.dp)
        }
    }
}

@Composable
private fun ProductOperationRow(operation: CloudflareApiPreset, locked: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        enabled = !locked,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (locked) 0.48f else 1f)
            .testTag("cloudflare.productCenter.operation.${operation.id}"),
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(operation.method, width = 48.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(operation.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (locked) "Scoped API token required" else operation.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (locked) CloudflareToolsColors.warning() else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (locked) {
                Icon(Icons.Rounded.Lock, contentDescription = "Scoped API token required", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun productIcon(id: String): ImageVector = when (id) {
    "analytics" -> Icons.Rounded.QueryStats
    "accounts" -> Icons.Rounded.Domain
    "zones" -> Icons.Rounded.Public
    "dns" -> Icons.Rounded.Dns
    "security" -> Icons.Rounded.Security
    "pages" -> Icons.Rounded.Description
    "workers" -> Icons.Rounded.Code
    "d1-kv" -> Icons.Rounded.Storage
    "r2" -> Icons.Rounded.Cloud
    "turnstile" -> Icons.Rounded.VerifiedUser
    "images" -> Icons.Rounded.PhotoLibrary
    "stream" -> Icons.Rounded.PlayCircle
    "email-routing" -> Icons.Rounded.Email
    "load-balancing" -> Icons.Rounded.AccountTree
    "queues" -> Icons.Rounded.Inbox
    "hyperdrive" -> Icons.Rounded.Bolt
    "vectorize" -> Icons.Rounded.Layers
    "workers-ai" -> Icons.Rounded.Memory
    "ai-gateway" -> Icons.Rounded.Hub
    "tunnels" -> Icons.Rounded.VpnLock
    "logpush" -> Icons.Rounded.CloudUpload
    "zaraz" -> Icons.Rounded.AutoAwesome
    "waiting-rooms" -> Icons.Rounded.Groups
    else -> Icons.Rounded.Cloud
}
