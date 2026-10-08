package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill

/**
 * One card per connected site service for the Sites tab: provider identity, status, the resource
 * count and up to two headline metrics (iOS `SitesView.serviceOverview`). Opening a service is
 * free; its detail workspace is Pro-gated inside [SiteServiceRoute].
 */
@Composable
fun SiteServiceConnectionCards(
    state: SiteServicesUiState,
    onOpenProvider: (providerId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectedProviderIds
    if (connected.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        connected.forEach { providerId ->
            SiteServiceConnectionCard(
                service = state.service(providerId),
                onClick = { onOpenProvider(providerId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun SiteServiceConnectionCard(
    service: SiteServiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerId = service.providerId
    val provider = remember(providerId) { siteCatalogProvider(providerId) }
    val accent = siteAccent(providerId)
    val haptic = LocalHapticFeedback.current
    val status = SiteServiceFormat.serviceStatus(service)
    val statusColor = siteToneColor(status.tone)
    val dashboard = service.dashboard
    val subtitle = dashboard?.accountName
        ?: service.savedAccountName
        ?: service.error
        ?: "Open to recover this connection"
    val count = dashboard?.loadedResourceCount ?: 0
    val metrics = SiteServiceFormat.headlineMetrics(dashboard)
    val stacked = LocalDensity.current.fontScale >= 1.3f

    OffsetPanel(
        modifier = modifier
            .heightIn(min = 88.dp)
            .semantics { stateDescription = status.text },
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.20f),
        shadowColor = accent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.sites.${providerId}Connection",
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(provider, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (stacked) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (stacked) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!stacked) {
                    Spacer(Modifier.width(10.dp))
                    StatusPill(status.text, statusColor)
                }
            }
            if (stacked) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StatusPill(status.text, statusColor) }
            }
            if (dashboard != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SiteCompactMetric(count.toString(), SiteServiceCopy.resourceNoun(providerId, count))
                    metrics.take(if (stacked) 1 else 2).forEach { metric ->
                        SiteCompactMetric(SiteServiceFormat.metric(metric), metric.label, Modifier.weight(1f, fill = false))
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
