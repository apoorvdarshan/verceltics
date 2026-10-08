package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAnalyticsBreakdownItem
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAnalyticsRange
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecord
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneAnalyticsBreakdowns
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneAnalyticsSummary
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneResourceIds
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsChoiceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMetric
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMetricGrid
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import kotlinx.coroutines.launch

/** Port of iOS `CloudflareZoneDetailView`. */
@Composable
internal fun CloudflareZoneDetailScreen(
    zone: CloudflareZoneUi,
    viewModel: CloudflareZoneDetailViewModel,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var showingCustomRange by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(context.refreshSignal) { viewModel.onRefreshSignal(context.refreshSignal) }
    // iOS `onZoneChange`: let the dashboard reconcile a zone whose status or plan changed.
    LaunchedEffect(state.zone) {
        val loaded = state.zone ?: return@LaunchedEffect
        val differs = loaded.name != zone.name || loaded.status != zone.status || loaded.paused != zone.paused ||
            (loaded.plan?.name != null && loaded.plan.name != zone.planName)
        if (differs && !viewModel.inventoryReconciled) {
            viewModel.inventoryReconciled = true
            context.inventoryChanged()
        }
    }

    CloudflareConfirmationHost(viewModel)
    state.dnsEditor?.let { target ->
        CloudflareDnsRecordEditorSheet(
            zoneName = viewModel.zoneDisplayName,
            record = target.record,
            error = state.editorError,
            isSaving = (target.record?.id ?: CloudflareZoneResourceIds.NEW_DNS_RECORD) in working,
            onSave = viewModel::requestSaveRecord,
            onDismiss = viewModel::dismissEditor,
        )
    }
    if (state.showingPurge) {
        CloudflareCachePurgeSheet(
            zoneName = viewModel.zoneDisplayName,
            error = state.purgeError,
            isPurging = CloudflareZoneResourceIds.CACHE_PURGE in working,
            onContinue = viewModel::requestPurge,
            onDismiss = viewModel::dismissPurge,
        )
    }
    if (showingCustomRange) {
        CloudflareCustomRangeDialog(
            initialFrom = state.customFrom,
            initialTo = state.customTo,
            onApply = { from, to -> viewModel.selectCustomRange(from, to).also { if (it == null) showingCustomRange = false } },
            onDismiss = { showingCustomRange = false },
        )
    }

    val loaded = state.zone
    val name = loaded?.name ?: zone.name
    val accountId = loaded?.accountId ?: context.accountId
    val isActive = loaded?.isActive ?: zone.isActive
    val paused = loaded?.paused ?: zone.paused
    val status = loaded?.status ?: zone.status
    val filtered = state.dnsRecords.filter { it.matches(search) }

    CloudflareOpsScreen("cloudflare.zoneDetail", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = name,
                subtitle = loaded?.plan?.name ?: zone.planName ?: (loaded?.type ?: zone.type)?.replaceFirstChar { it.uppercase() }
                    ?: "Cloudflare zone",
                icon = Icons.Rounded.Public,
                status = if (paused == true) "PAUSED" else status?.uppercase() ?: "UNKNOWN",
                statusColor = if (isActive) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
                testTag = "cloudflare.zone.hero",
            ) {
                CloudflareOpsActionButton(
                    "Open site",
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    onClick = { runCatching { uriHandler.openUri("https://$name") } },
                    testTag = "cloudflare.zone.openSite",
                )
                if (!accountId.isNullOrEmpty()) {
                    CloudflareOpsActionButton(
                        "Dashboard",
                        Icons.Rounded.Dashboard,
                        onClick = { runCatching { uriHandler.openUri("https://dash.cloudflare.com/$accountId/$name") } },
                        testTag = "cloudflare.zone.dashboard",
                    )
                }
                CloudflareOpsActionButton(
                    "Purge cache",
                    Icons.Rounded.Autorenew,
                    onClick = viewModel::openPurge,
                    working = CloudflareZoneResourceIds.CACHE_PURGE in working,
                    testTag = "cloudflare.zone.purge",
                )
            }
        }
        state.zoneError?.let { error ->
            item("zone-error") { UnavailableCard("Zone details could not refresh", error) }
        }
        item("links") {
            CloudflareOpsPanel(accent = 0.07f) {
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.Tune,
                    title = "Zone & DNS operations",
                    subtitle = "DNSSEC, settings, activation, quotas and DNS analytics",
                    onClick = { context.navigate(CloudflareZoneRoutes.operations(zone.id, name)) },
                    testTag = "cloudflare.zone.operationsLink",
                )
                CloudflareOpsDivider(inset = true)
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.Security,
                    title = "Security center",
                    subtitle = "WAF, firewall, rate limits, certificates, bots and API Shield",
                    tint = CloudflareOpsColors.Amber,
                    onClick = { context.navigate(CloudflareZoneRoutes.security(zone.id, name)) },
                    testTag = "cloudflare.zone.securityLink",
                )
            }
        }
        item("range") {
            AnalyticsRangeRail(
                selected = state.range,
                loading = state.isAnalyticsLoading,
                onSelect = { range ->
                    if (range == CloudflareAnalyticsRange.CUSTOM) showingCustomRange = true else viewModel.selectRange(range)
                },
            )
        }
        val analytics = state.analytics
        if (analytics != null) {
            item("analytics") { AnalyticsSection(analytics) }
            val breakdowns = state.breakdowns
            if (breakdowns != null) {
                breakdownSections(breakdowns).takeIf { it.isNotEmpty() || breakdowns.encryptedBytes > 0 }?.let { sections ->
                    item("breakdowns-header") { BreakdownHeader(breakdowns.encryptedBytes) }
                    items(sections, key = { "breakdown-${it.title}" }) { section -> BreakdownPanel(section) }
                }
            } else {
                state.breakdownError?.let { error -> item("breakdown-error") { UnavailableCard("Analytics breakdowns unavailable", error) } }
            }
        } else if (state.analyticsError != null) {
            item("analytics-error") { UnavailableCard("Analytics unavailable", state.analyticsError.orEmpty()) }
        } else if (state.isAnalyticsLoading) {
            item("analytics-loading") { CloudflareOpsPanel { CloudflareOpsLoading("Loading traffic…") } }
        }
        item("details") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Zone", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Zone ID", zone.id, icon = Icons.Rounded.Numbers, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Account", loaded?.accountName ?: zone.accountName ?: "Unknown", icon = Icons.Rounded.Business)
                CloudflareOpsDetailRow("Registrar", loaded?.originalRegistrar ?: "Unknown", icon = Icons.Rounded.AccountBalance)
                CloudflareOpsDetailRow(
                    "Name servers",
                    loaded?.nameServers?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None returned",
                    icon = Icons.Rounded.Dns,
                )
                CloudflareOpsDetailRow(
                    "Development mode",
                    if ((loaded?.developmentMode ?: 0) > 0) "Active" else "Off",
                    icon = Icons.Rounded.Build,
                )
            }
        }
        item("write-notice") { CloudflareWriteNotice() }
        banner?.let { value -> item("banner") { CloudflareActionResultBanner(value, viewModel::dismissBanner) } }
        item("dns-header") {
            CloudflareOpsPanel(testTag = "cloudflare.zone.dnsSection") {
                CloudflareOpsSectionHeader(
                    title = "DNS records",
                    icon = Icons.Rounded.Dns,
                    count = filtered.size,
                    actionTitle = "Add record",
                    actionTestTag = "cloudflare.zone.dns.add",
                    onAction = viewModel::openNewRecord,
                )
                CloudflareOpsDivider()
                ControlSearchField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "Search DNS records",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    testTag = "cloudflare.zone.dnsSearch",
                )
            }
        }
        when {
            state.isDnsLoading -> item("dns-loading") { CloudflareOpsPanel { CloudflareOpsLoading("Loading DNS records…") } }
            state.dnsError != null && state.dnsRecords.isEmpty() -> item("dns-error") {
                CloudflareOpsPanel {
                    CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "DNS unavailable", state.dnsError.orEmpty())
                }
            }
            filtered.isEmpty() -> item("dns-empty") {
                CloudflareOpsPanel {
                    if (search.isEmpty()) {
                        CloudflareOpsEmptySection(Icons.Rounded.Dns, "No DNS records", "Create a DNS record to begin routing this zone.")
                    } else {
                        CloudflareOpsEmptySection(Icons.Rounded.Search, "No matches", "No DNS records match “$search”.")
                    }
                }
            }
            else -> items(filtered, key = { "dns-${it.id}" }) { record ->
                DnsRecordRow(
                    record = record,
                    working = record.id in working,
                    onEdit = { viewModel.openEditRecord(record) },
                    onCopy = {
                        val value = record.content ?: record.name
                        scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DNS record", value))) } }
                    },
                    onDelete = { viewModel.requestDeleteRecord(record) },
                )
            }
        }
    }
}

@Composable
private fun UnavailableCard(title: String, message: String) {
    CloudflareOpsPanel { CloudflareOpsEmptySection(Icons.AutoMirrored.Rounded.ShowChart, title, message) }
}

@Composable
private fun AnalyticsRangeRail(selected: CloudflareAnalyticsRange, loading: Boolean, onSelect: (CloudflareAnalyticsRange) -> Unit) {
    CloudflareOpsPanel(accent = 0.045f, testTag = "cloudflare.zone.rangeRail") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Traffic interval",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
            }
            CloudflareOpsChoiceRow(
                options = CloudflareAnalyticsRange.entries,
                selected = selected,
                label = CloudflareAnalyticsRange::displayName,
                onSelect = onSelect,
                testTagPrefix = "cloudflare.zone.range",
            )
            Text(
                "Cloudflare automatically applies this zone’s retention and query-width limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalyticsSection(analytics: CloudflareZoneAnalyticsSummary) {
    val totals = analytics.totals
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("cloudflare.zone.analytics")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                analytics.chartTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = RoundedCornerShape(50), color = CloudflareOpsColors.Orange.copy(alpha = 0.1f)) {
                Text(
                    analytics.granularity.displayName,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = CloudflareOpsColors.Orange,
                )
            }
        }
        if (analytics.isWindowLimited) {
            Text(
                "Cloudflare shortened this range to fit the zone's analytics limit",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = CloudflareOpsColors.Amber,
            )
        }
        CloudflareOpsMetricGrid(
            listOf(
                CloudflareOpsMetric("Requests", CloudflareFormat.compact(totals.requests), Icons.Rounded.SwapHoriz),
                CloudflareOpsMetric("Visitors", CloudflareFormat.compact(totals.uniqueVisitors), Icons.Rounded.Group, CloudflareOpsColors.Amber),
                CloudflareOpsMetric("Bandwidth", CloudflareFormat.bytes(totals.bytes), Icons.Rounded.Language),
                CloudflareOpsMetric("Cache hit", totals.cacheHitRate?.let { CloudflareFormat.percent(it) } ?: "—", Icons.Rounded.Bolt, CloudflareOpsColors.Green),
                CloudflareOpsMetric("Page views", CloudflareFormat.compact(totals.pageViews), Icons.Rounded.Visibility, CloudflareOpsColors.Amber),
                CloudflareOpsMetric("Cached bytes", CloudflareFormat.bytes(totals.cachedBytes), Icons.Rounded.Storage, CloudflareOpsColors.Green),
                CloudflareOpsMetric(
                    "Threats",
                    CloudflareFormat.compact(totals.threats),
                    Icons.Rounded.Shield,
                    if (totals.threats > 0) MaterialTheme.colorScheme.error else CloudflareOpsColors.Green,
                ),
                CloudflareOpsMetric("HTTPS", totals.encryptedRequestRate?.let { CloudflareFormat.percent(it) } ?: "—", Icons.Rounded.Lock, CloudflareOpsColors.Green),
            ),
        )
        if (analytics.series.isNotEmpty()) {
            CloudflareOpsPanel(accent = 0.055f) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TRAFFIC TREND", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${CloudflareFormat.grouped(totals.pageViews)} page views",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CloudflareZoneTrafficChart(analytics.series, analytics.granularity)
                }
            }
        }
    }
}

private data class BreakdownSection(val title: String, val icon: ImageVector, val items: List<CloudflareAnalyticsBreakdownItem>)

private fun breakdownSections(breakdowns: CloudflareZoneAnalyticsBreakdowns): List<BreakdownSection> = listOf(
    BreakdownSection("Countries", Icons.Rounded.Public, breakdowns.countries),
    BreakdownSection("Status codes", Icons.Rounded.Numbers, breakdowns.statusCodes),
    BreakdownSection("Content types", Icons.Rounded.Description, breakdowns.contentTypes),
    BreakdownSection("TLS protocols", Icons.Rounded.Lock, breakdowns.tlsProtocols),
    BreakdownSection("Browsers", Icons.Rounded.Web, breakdowns.browsers),
    BreakdownSection("IP classes", Icons.Rounded.Router, breakdowns.ipClasses),
    BreakdownSection("Threat paths", Icons.Rounded.GppMaybe, breakdowns.threatTypes),
).filter { it.items.isNotEmpty() }

@Composable
private fun BreakdownHeader(encryptedBytes: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("TRAFFIC BREAKDOWNS", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (encryptedBytes > 0) {
            Text("${CloudflareFormat.bytes(encryptedBytes)} encrypted", style = MaterialTheme.typography.labelSmall, color = CloudflareOpsColors.Green)
        }
    }
}

@Composable
private fun BreakdownPanel(section: BreakdownSection) {
    val visible = section.items.take(12)
    CloudflareOpsPanel(testTag = "cloudflare.zone.breakdown.${section.title}") {
        CloudflareOpsSectionHeader(section.title, section.icon, count = section.items.size)
        CloudflareOpsDivider()
        visible.forEachIndexed { index, item ->
            CloudflareOpsResourceRow(
                icon = section.icon,
                title = item.label,
                subtitle = breakdownSubtitle(item),
                tint = if (item.threats > 0) MaterialTheme.colorScheme.error else CloudflareOpsColors.Orange,
            ) {
                Text(
                    CloudflareFormat.compact(if (item.requests > 0) item.requests else item.pageViews),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (index < visible.lastIndex) CloudflareOpsDivider(inset = true)
        }
    }
}

private fun breakdownSubtitle(item: CloudflareAnalyticsBreakdownItem): String? = buildList {
    if (item.bytes > 0) add(CloudflareFormat.bytes(item.bytes))
    if (item.threats > 0) add("${CloudflareFormat.grouped(item.threats)} threats")
    if (item.pageViews > 0) add("${CloudflareFormat.grouped(item.pageViews)} page views")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun DnsRecordRow(
    record: CloudflareDnsRecord,
    working: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val proxied = record.proxied == true
    CloudflareOpsPanel(testTag = "cloudflare.zone.dns.${record.id}") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (proxied) CloudflareOpsColors.Orange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (proxied) CloudflareOpsColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(Modifier.width(52.dp).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(record.type, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        record.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (record.locked == true) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Managed by Cloudflare", modifier = Modifier.size(12.dp))
                    }
                }
                Text(
                    record.content ?: "Structured record data",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (working) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("cloudflare.zone.dns.${record.id}.menu")) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${record.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit record") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                            modifier = Modifier.testTag("cloudflare.zone.dns.${record.id}.edit"),
                        )
                        DropdownMenuItem(
                            text = { Text("Copy value") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCopy()
                            },
                            modifier = Modifier.testTag("cloudflare.zone.dns.${record.id}.copy"),
                        )
                        if (record.locked != true) {
                            DropdownMenuItem(
                                text = { Text("Delete record", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                                modifier = Modifier.testTag("cloudflare.zone.dns.${record.id}.delete"),
                            )
                        }
                    }
                }
            }
        }
    }
}
