package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Callbacks for the dashboard's iOS-style "Advanced" section and account-header tap. Every
 * callback is expected to already be wrapped in `proAccess.requestPro { ... }` by the route.
 * Account-scoped entries are null when no Cloudflare account is selected.
 */
data class CloudflareAdvancedToolsUi(
    val onOpenAccount: (() -> Unit)?,
    val onOpenCompleteApi: (() -> Unit)?,
    val onOpenGraphQL: (() -> Unit)?,
    val onOpenProductCenter: (() -> Unit)?,
    val onOpenExplorer: () -> Unit,
    /**
     * Placeholder hook for "Storage & databases" (D1, Workers KV, R2), which is owned by the
     * Cloudflare storage port. The row stays hidden while this is null.
     */
    val onOpenStorage: (() -> Unit)? = null,
)

/** Port of the iOS `CloudflareDashboardView.advancedTools` panel. */
@Composable
fun CloudflareAdvancedSection(tools: CloudflareAdvancedToolsUi, modifier: Modifier = Modifier) {
    ToolPanel(modifier = modifier.fillMaxWidth(), accentAlpha = 0.045f, testTag = "cloudflare.advanced") {
        ToolSectionHeader("Advanced", Icons.Rounded.Terminal)
        tools.onOpenCompleteApi?.let { open ->
            ToolNavigationRow(
                title = "Complete Cloudflare API",
                subtitle = "Every official REST operation · generated parameters and upload forms",
                icon = Icons.Rounded.Hub,
                tint = CloudflareToolsColors.success(),
                onClick = open,
                testTag = "cloudflare.advanced.completeApi",
            )
            ToolDivider(start = 64.dp)
        }
        tools.onOpenGraphQL?.let { open ->
            ToolNavigationRow(
                title = "GraphQL dataset directory",
                subtitle = "Live plan availability, fields, retention and query limits",
                icon = Icons.Rounded.QueryStats,
                onClick = open,
                testTag = "cloudflare.advanced.graphql",
            )
            ToolDivider(start = 64.dp)
        }
        tools.onOpenProductCenter?.let { open ->
            ToolNavigationRow(
                title = "Guided product operations",
                subtitle = "Curated shortcuts for common Cloudflare tasks",
                icon = Icons.Rounded.Thunderstorm,
                onClick = open,
                testTag = "cloudflare.advanced.productCenter",
            )
            ToolDivider(start = 64.dp)
        }
        tools.onOpenStorage?.let { open ->
            ToolNavigationRow(
                title = "Storage & databases",
                subtitle = "D1 SQL, Workers KV and R2 object storage",
                icon = Icons.Rounded.Storage,
                tint = CloudflareToolsColors.warning(),
                onClick = open,
                testTag = "cloudflare.advanced.storage",
            )
            ToolDivider(start = 64.dp)
        }
        ToolNavigationRow(
            title = "Cloudflare API Explorer",
            subtitle = "Any v4 REST request · JSON, text, Base64 or multipart",
            icon = Icons.Rounded.Terminal,
            onClick = tools.onOpenExplorer,
            testTag = "cloudflare.advanced.explorer",
        )
    }
}
