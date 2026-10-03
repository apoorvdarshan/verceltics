package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChangeHistory
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.SectionHeading

@Composable
fun CatalogScreen(
    workspace: Workspace,
    onProviderClick: (IntegrationProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable(workspace.id) { mutableStateOf("") }
    val providers = IntegrationCatalog.search(query, workspace)

    LazyColumn(
        modifier = modifier.testTag("catalog.${workspace.id}"),
        contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "brand") {
            BrandHeader()
        }
        item(key = "search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ${workspace.displayName.lowercase()}",
                modifier = Modifier.fillMaxWidth(),
                testTag = "catalog.${workspace.id}.search",
            )
        }
        item(key = "inventory") {
            InventoryBand(
                workspace = workspace,
                visibleCount = providers.size,
                totalCount = IntegrationCatalog.providers(workspace).size,
            )
        }
        item(key = "heading") {
            SectionHeading(
                eyebrow = "${workspace.displayName} control desk",
                title = if (query.isBlank()) "Choose a provider" else "Search results",
                trailing = {
                    LabelChip(
                        text = "${providers.size} shown",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            )
        }
        if (providers.isEmpty()) {
            item(key = "empty") {
                EmptySearch(query = query, workspace = workspace)
            }
        } else {
            items(providers, key = IntegrationProvider::id) { provider ->
                ProviderCard(
                    provider = provider,
                    onClick = { onProviderClick(provider) },
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Verceltics ${BuildConfig.VERSION_NAME}" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "VERCELTICS",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "INFRASTRUCTURE, IN ONE POCKET",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.onBackground)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

@Composable
private fun InventoryBand(
    workspace: Workspace,
    visibleCount: Int,
    totalCount: Int,
) {
    val useStackedStats = LocalDensity.current.fontScale >= 1.5f
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp),
        color = MaterialTheme.colorScheme.primary,
        testTag = "catalog.inventory",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = workspace.displayName.uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = workspaceLedgerCopy(workspace),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                )
                Spacer(Modifier.height(14.dp))
                if (useStackedStats) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LedgerStat(value = visibleCount.toString(), label = "VISIBLE")
                        LedgerStat(value = totalCount.toString(), label = "TOTAL")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LedgerStat(value = visibleCount.toString(), label = "VISIBLE")
                        LedgerStat(value = totalCount.toString(), label = "TOTAL")
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .background(MaterialTheme.colorScheme.onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = workspaceIcon(workspace),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

@Composable
private fun LedgerStat(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

@Composable
fun ProviderCard(
    provider: IntegrationProvider,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val useExpandedCopy = LocalDensity.current.fontScale >= 1.5f
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "provider.${provider.id}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMark(provider = provider)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (useExpandedCopy) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (useExpandedCopy) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = provider.authenticationModes.joinToString(" / ") { it.displayName },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = if (useExpandedCopy) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Open ${provider.displayName}",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EmptySearch(query: String, workspace: Workspace) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No match for “$query”",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Try a provider name or a ${workspace.displayName.lowercase()} feature.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun workspaceLedgerCopy(workspace: Workspace): String = when (workspace) {
    Workspace.HOSTING -> "Deployments, runtime and edge controls"
    Workspace.REGISTRARS -> "Domains, DNS and renewal controls"
    Workspace.SITES -> "Search, analytics and uptime signals"
}

fun workspaceIcon(workspace: Workspace): ImageVector = when (workspace) {
    Workspace.HOSTING -> Icons.Rounded.Storage
    Workspace.REGISTRARS -> Icons.Rounded.Language
    Workspace.SITES -> Icons.Rounded.QueryStats
}

fun providerIcon(provider: IntegrationProvider): ImageVector = when (provider.id) {
    "vercel" -> Icons.Rounded.ChangeHistory
    "cloudflare" -> Icons.Rounded.Cloud
    else -> when (provider.workspace) {
        Workspace.HOSTING -> Icons.Rounded.Dns
        Workspace.REGISTRARS -> Icons.Rounded.Language
        Workspace.SITES -> Icons.Rounded.QueryStats
    }
}
