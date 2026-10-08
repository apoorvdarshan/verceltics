package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareDisplayText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnssecStatus
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDnsSettings
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDnsSettingsDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneSetting
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneSettingEditing
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsChoiceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDropdownField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMetric
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMetricGrid
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsToggleRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Port of iOS `CloudflareZoneOperationsView`. */
@Composable
internal fun CloudflareZoneOperationsScreen(
    viewModel: CloudflareZoneOperationsViewModel,
    zoneName: String,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    LaunchedEffect(context.refreshSignal) { viewModel.onRefreshSignal(context.refreshSignal) }

    CloudflareConfirmationHost(viewModel)
    state.editingSetting?.let { setting ->
        CloudflareZoneSettingEditorSheet(
            setting = setting,
            error = state.settingEditorError,
            isSaving = CloudflareZoneOperationsViewModel.settingWorkingId(setting.id) in working,
            onSave = { proposed -> viewModel.requestSaveSetting(setting, proposed) },
            onDismiss = viewModel::dismissSettingEditor,
        )
    }
    val dnsSettings = state.dnsSettings
    if (state.showingDnsSettingsEditor && dnsSettings != null) {
        CloudflareZoneDnsSettingsEditorSheet(
            settings = dnsSettings,
            error = state.dnsSettingsEditorError,
            isSaving = CloudflareZoneOperationsViewModel.WORKING_DNS_SETTINGS in working,
            onSave = viewModel::requestSaveDnsSettings,
            onDismiss = viewModel::dismissDnsSettingsEditor,
        )
    }

    val zone = state.zone
    CloudflareOpsScreen("cloudflare.zoneOperations", modifier) {
        item("header") {
            CloudflareOpsHero(
                title = zone?.name ?: zoneName,
                subtitle = "${zone?.accountName ?: "Cloudflare zone"} · DNS & EDGE CONTROL",
                icon = Icons.Rounded.Public,
                status = when {
                    zone == null -> null
                    zone.paused == true -> "PAUSED"
                    else -> zone.status?.uppercase() ?: "UNKNOWN"
                },
                statusColor = if (zone?.isActive == true) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
            ) {
                CloudflareOpsActionButton(
                    "Activation check",
                    Icons.Rounded.Verified,
                    onClick = viewModel::requestActivationCheck,
                    working = CloudflareZoneOperationsViewModel.WORKING_ACTIVATION in working,
                    testTag = "cloudflare.zoneOps.activation",
                )
                zone?.modifiedDate?.let { date ->
                    Text(
                        "Updated ${CloudflareDates.relative(date)}",
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.zoneError?.let { error ->
            item("zone-error") { CloudflareActionResultBanner(CloudflareActionBanner("Zone refresh: $error", isError = true)) }
        }
        banner?.let { value -> item("banner") { CloudflareActionResultBanner(value, viewModel::dismissBanner) } }
        item("write-notice") { CloudflareWriteNotice() }
        item("dns-overview") { DnsOverview(state.isLoading, state) }
        item("dnssec") {
            DnssecPanel(
                dnssec = state.dnssec,
                isLoading = state.isLoading,
                error = state.dnssecError,
                working = CloudflareZoneOperationsViewModel.WORKING_DNSSEC in working,
                onToggle = viewModel::requestDnssec,
            )
        }
        item("dns-settings") {
            DnsSettingsPanel(state.dnsSettings, state.isLoading, state.dnsSettingsError, onEdit = viewModel::openDnsSettingsEditor)
        }
        item("zone-settings") {
            ZoneSettingsPanel(state.settings, state.isLoading, state.settingsError, working, onEdit = viewModel::openSettingEditor)
        }
        if (zone != null) {
            item("identity") { MetadataPanel("Identity & ownership", Icons.Rounded.Info, identityRows(zone)) }
            item("lifecycle") { MetadataPanel("Lifecycle", Icons.Rounded.Schedule, lifecycleRows(zone)) }
            item("nameservers") { MetadataPanel("Nameservers", Icons.Rounded.Dns, nameserverRows(zone)) }
            zone.plan?.let { plan ->
                item("plan") {
                    MetadataPanel(
                        "Plan",
                        Icons.Rounded.CreditCard,
                        listOf(
                            "Plan ID" to plan.id,
                            "Name" to plan.name,
                            "Currency" to plan.currency,
                            "Billing frequency" to plan.frequency,
                            "Price" to plan.price?.let { planPrice(it, plan.currency, plan.frequency) },
                            "Subscribed" to onOff(plan.isSubscribed),
                            "Can subscribe" to onOff(plan.canSubscribe),
                            "Externally managed" to onOff(plan.externallyManaged),
                            "Legacy discount" to onOff(plan.legacyDiscount),
                            "Legacy plan ID" to plan.legacyId,
                        ),
                    )
                }
            }
            if (zone.meta.isNotEmpty()) {
                item("meta") {
                    CloudflareOpsPanel {
                        CloudflareOpsSectionHeader("Zone metadata", Icons.AutoMirrored.Rounded.ListAlt, count = zone.meta.size)
                        CloudflareOpsDivider()
                        zone.meta.entries.sortedBy { it.key }.forEach { (key, value) ->
                            CloudflareOpsDetailRow(key.replace('_', ' '), value.cloudflareDisplayText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsOverview(isLoading: Boolean, state: CloudflareZoneOperationsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DNS · LAST 24 HOURS", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.dnsAnalytics?.let { Text("lag ${it.dataLag.toInt()}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val analytics = state.dnsAnalytics
        when {
            analytics != null -> CloudflareOpsMetricGrid(
                listOf(
                    CloudflareOpsMetric("Queries", metricText(analytics.total("queryCount")), Icons.AutoMirrored.Rounded.HelpOutline),
                    CloudflareOpsMetric("Uncached", metricText(analytics.total("uncachedCount")), Icons.Rounded.Bolt, CloudflareOpsColors.Amber),
                    CloudflareOpsMetric("Stale", metricText(analytics.total("staleCount")), Icons.Rounded.Timer, MaterialTheme.colorScheme.error),
                    CloudflareOpsMetric("Rows", CloudflareFormat.grouped(analytics.rows.toLong()), Icons.Rounded.TableRows, CloudflareOpsColors.Green),
                ),
            )
            isLoading -> CloudflareOpsPanel { CloudflareOpsLoading() }
            state.dnsAnalyticsError != null -> CloudflareOpsPanel {
                CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "DNS analytics unavailable", state.dnsAnalyticsError)
            }
        }
        val usage = state.dnsUsage
        if (usage != null) {
            CloudflareOpsPanel {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${CloudflareFormat.grouped(usage.recordUsage.toLong())} records used",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        usage.recordQuota?.let { "${CloudflareFormat.grouped(it.toLong())} quota" } ?: "Account-level quota",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (state.dnsUsageError != null) {
            CloudflareOpsPanel { CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "DNS quota unavailable", state.dnsUsageError) }
        }
    }
}

@Composable
private fun DnssecPanel(
    dnssec: CloudflareDnssecStatus?,
    isLoading: Boolean,
    error: String?,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    CloudflareOpsPanel(testTag = "cloudflare.zoneOps.dnssec") {
        CloudflareOpsSectionHeader("DNSSEC", Icons.Rounded.GppGood)
        CloudflareOpsDivider()
        when {
            dnssec != null -> {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (dnssec.isActive) "Zone signing is active" else "Zone signing is disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (dnssec.isActive) {
                                "Verify the DS record remains published at your registrar."
                            } else {
                                "Enable DNSSEC to protect DNS responses from tampering."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CloudflareOpsStatusPill(
                        dnssec.status?.uppercase() ?: "UNKNOWN",
                        if (dnssec.isActive) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
                    )
                }
                CloudflareOpsDivider()
                dnssec.keyTag?.let { CloudflareOpsDetailRow("Key tag", it.toString()) }
                dnssec.algorithm?.let { CloudflareOpsDetailRow("Algorithm", it) }
                dnssec.digestType?.let { CloudflareOpsDetailRow("Digest type", it) }
                dnssec.ds?.let { CloudflareOpsDetailRow("DS record", it, monospace = true, copyable = true) }
                dnssec.digest?.let { CloudflareOpsDetailRow("Digest", it, monospace = true, copyable = true) }
                dnssec.publicKey?.let { CloudflareOpsDetailRow("Public key", it, monospace = true, copyable = true) }
                CloudflareOpsDetailRow("Multi-signer", onOff(dnssec.multiSigner))
                CloudflareOpsDetailRow("Pre-signed", onOff(dnssec.presigned))
                CloudflareOpsDetailRow("NSEC3", onOff(dnssec.useNsec3))
                dnssec.modifiedDate?.let { CloudflareOpsDetailRow("Modified", CloudflareDates.formatDateTime(it)) }
                CloudflareOpsDivider()
                Row(Modifier.padding(16.dp)) {
                    CloudflareOpsActionButton(
                        title = if (dnssec.isActive) "Disable DNSSEC" else "Enable DNSSEC",
                        icon = if (dnssec.isActive) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        onClick = { onToggle(!dnssec.isActive) },
                        destructive = dnssec.isActive,
                        working = working,
                        testTag = "cloudflare.zoneOps.dnssecToggle",
                    )
                }
            }
            isLoading -> CloudflareOpsLoading()
            else -> CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "DNSSEC unavailable", error ?: "No DNSSEC status was returned.")
        }
    }
}

@Composable
private fun DnsSettingsPanel(settings: CloudflareZoneDnsSettings?, isLoading: Boolean, error: String?, onEdit: () -> Unit) {
    CloudflareOpsPanel(testTag = "cloudflare.zoneOps.dnsSettings") {
        CloudflareOpsSectionHeader(
            "DNS configuration",
            Icons.Rounded.Tune,
            actionTitle = if (settings == null) null else "Edit",
            actionTestTag = "cloudflare.zoneOps.dnsSettings.edit",
            onAction = onEdit,
        )
        CloudflareOpsDivider()
        when {
            settings != null -> {
                CloudflareOpsDetailRow("Flatten all CNAMEs", onOff(settings.flattenAllCnames))
                CloudflareOpsDetailRow("Foundation DNS", onOff(settings.foundationDns))
                CloudflareOpsDetailRow("Multi-provider DNS", onOff(settings.multiProvider))
                CloudflareOpsDetailRow("Secondary overrides", onOff(settings.secondaryOverrides))
                CloudflareOpsDetailRow(
                    "Zone mode",
                    settings.zoneMode?.let { mode -> mode.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } }
                        ?: "Not returned",
                )
                settings.nameServerTtl?.let { CloudflareOpsDetailRow("Nameserver TTL", durationText(it)) }
                CloudflareOpsDetailRow("Nameserver type", settings.nameserversType ?: "Not returned")
                settings.nameserversSet?.let { CloudflareOpsDetailRow("Nameserver set", it.toString()) }
                settings.internalReferenceZoneId?.let { CloudflareOpsDetailRow("Internal fallback zone", it, monospace = true) }
                settings.soa?.let { soa ->
                    CloudflareOpsDetailRow("SOA primary", soa.primaryNameServer ?: "Cloudflare assigned")
                    CloudflareOpsDetailRow("SOA administrator", soa.responsibleName ?: "Not returned")
                    CloudflareOpsDetailRow("SOA refresh / retry", "${durationText(soa.refresh)} / ${durationText(soa.retry)}")
                    CloudflareOpsDetailRow("SOA expire / negative TTL", "${durationText(soa.expire)} / ${durationText(soa.minimumTtl)}")
                    CloudflareOpsDetailRow("SOA TTL", durationText(soa.ttl))
                }
            }
            isLoading -> CloudflareOpsLoading()
            else -> CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "DNS settings unavailable", error ?: "No DNS settings were returned.")
        }
    }
}

@Composable
private fun ZoneSettingsPanel(
    settings: List<CloudflareZoneSetting>,
    isLoading: Boolean,
    error: String?,
    working: Set<String>,
    onEdit: (CloudflareZoneSetting) -> Unit,
) {
    CloudflareOpsPanel(testTag = "cloudflare.zoneOps.settings") {
        CloudflareOpsSectionHeader("Zone settings", Icons.Rounded.ToggleOn, count = settings.size)
        CloudflareOpsDivider()
        when {
            isLoading && settings.isEmpty() -> CloudflareOpsLoading()
            error != null -> CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "Zone settings unavailable", error)
            settings.isEmpty() -> CloudflareOpsEmptySection(
                Icons.Rounded.WarningAmber,
                "No settings returned",
                "Cloudflare returned an empty settings collection for this zone.",
            )
            else -> settings.forEachIndexed { index, setting ->
                val editable = setting.editable && setting.isScalar
                val isWorking = CloudflareZoneOperationsViewModel.settingWorkingId(setting.id) in working
                CloudflareOpsResourceRow(
                    icon = settingIcon(setting.id),
                    title = CloudflareZoneSetting.displayName(setting.id),
                    subtitle = setting.value.cloudflareDisplayText,
                    tint = if (setting.editable) CloudflareOpsColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = if (editable && working.isEmpty()) ({ onEdit(setting) }) else null,
                    testTag = "cloudflare.zoneOps.setting.${setting.id}",
                ) {
                    when {
                        isWorking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                        editable -> Text("Edit", style = MaterialTheme.typography.labelLarge, color = CloudflareOpsColors.Orange)
                        else -> CloudflareOpsStatusPill(
                            if (setting.editable) "ADVANCED" else "READ ONLY",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (index < settings.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
}

@Composable
private fun MetadataPanel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, rows: List<Pair<String, String?>>) {
    val populated = rows.filter { !it.second.isNullOrEmpty() }
    CloudflareOpsPanel {
        CloudflareOpsSectionHeader(title, icon)
        CloudflareOpsDivider()
        populated.forEach { (label, value) -> CloudflareOpsDetailRow(label, value.orEmpty()) }
    }
}

private fun identityRows(zone: CloudflareZoneDetail) = listOf(
    "Zone ID" to zone.id,
    "Account" to zone.accountName,
    "Account ID" to zone.accountId,
    "Owner" to objectSummary(zone.owner),
    "Tenant" to objectSummary(zone.tenant),
    "Tenant unit" to objectSummary(zone.tenantUnit),
    "Verification key" to zone.verificationKey,
    "Permissions" to zone.permissions.takeIf { it.isNotEmpty() }?.joinToString(", "),
)

private fun lifecycleRows(zone: CloudflareZoneDetail) = listOf(
    "Status" to zone.status,
    "Paused" to onOff(zone.paused),
    "Type" to zone.type,
    "Development mode" to if ((zone.developmentMode ?: 0) > 0) "Active" else "Off",
    "Created" to zone.createdDate?.let(CloudflareDates::formatDateTime),
    "Modified" to zone.modifiedDate?.let(CloudflareDates::formatDateTime),
    "Activated" to zone.activatedDate?.let(CloudflareDates::formatDateTime),
    "Original registrar" to zone.originalRegistrar,
    "Original DNS host" to zone.originalDnsHost,
    "CNAME suffix" to zone.cnameSuffix,
)

private fun nameserverRows(zone: CloudflareZoneDetail) = listOf(
    "Assigned" to zone.nameServers.takeIf { it.isNotEmpty() }?.joinToString(", "),
    "Original" to zone.originalNameServers.takeIf { it.isNotEmpty() }?.joinToString(", "),
    "Vanity" to zone.vanityNameServers.takeIf { it.isNotEmpty() }?.joinToString(", "),
)

private fun objectSummary(value: Map<String, ProviderJsonValue>?): String? =
    value?.takeIf { it.isNotEmpty() }?.let { ProviderJsonValue.Obj(it).cloudflareDisplayText }

internal fun onOff(value: Boolean?): String = when (value) {
    null -> "Not returned"
    true -> "On"
    false -> "Off"
}

private fun metricText(value: Double?): String = value?.let { CloudflareFormat.compact(it.toLong()) } ?: "—"

/** iOS `Duration.formatted(.units([.hours, .minutes, .seconds], width: .abbreviated))`. */
internal fun durationText(value: Double?): String {
    value ?: return "Not returned"
    val total = value.toLong().coerceAtLeast(0)
    val parts = buildList {
        val hours = total / 3_600
        val minutes = total % 3_600 / 60
        val seconds = total % 60
        if (hours > 0) add("$hours hr")
        if (minutes > 0) add("$minutes min")
        if (seconds > 0 || isEmpty()) add("$seconds sec")
    }
    return parts.joinToString(", ")
}

private fun planPrice(price: Double, currency: String?, frequency: String?): String {
    val amount = runCatching {
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { this.currency = Currency.getInstance(currency ?: "USD") }.format(price)
    }.getOrElse { String.format(Locale.US, "%.2f %s", price, currency ?: "USD") }
    return frequency?.let { "$amount / $it" } ?: amount
}

private fun settingIcon(id: String) = when (id) {
    "ssl", "min_tls_version", "tls_1_3" -> Icons.Rounded.Https
    "always_use_https", "automatic_https_rewrites" -> Icons.Rounded.Lock
    "http2", "http3", "ipv6" -> Icons.Rounded.Language
    "brotli", "cache_level", "browser_cache_ttl" -> Icons.Rounded.Bolt
    "security_level", "browser_check" -> Icons.Rounded.Shield
    "development_mode" -> Icons.Rounded.Build
    else -> Icons.Rounded.ToggleOn
}

/** iOS `CloudflareZoneSettingEditor`. */
@Composable
private fun CloudflareZoneSettingEditorSheet(
    setting: CloudflareZoneSetting,
    error: String?,
    isSaving: Boolean,
    onSave: (ProviderJsonValue?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(setting.id) { mutableStateOf(CloudflareZoneSettingEditing.initialText(setting.value)) }
    var toggle by remember(setting.id) { mutableStateOf(CloudflareZoneSettingEditing.initialToggle(setting.value)) }
    val displayName = CloudflareZoneSetting.displayName(setting.id)
    val options = CloudflareZoneSettingEditing.options(setting.id)
    val proposed = CloudflareZoneSettingEditing.proposedValue(setting.value, text, toggle)
    CloudflareOpsEditorSheet(title = displayName, onDismiss = onDismiss, testTag = "cloudflare.zoneOps.settingEditor") {
        CloudflareOpsPanel(accent = 0.06f) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(displayName.uppercase(), style = MaterialTheme.typography.labelSmall, color = CloudflareOpsColors.Orange)
                Text(
                    "Current value: ${setting.value.cloudflareDisplayText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CloudflareOpsPanel {
            Column(Modifier.padding(vertical = 6.dp)) {
                when {
                    CloudflareZoneSettingEditing.isOnOffString(setting.value) || setting.value is ProviderJsonValue.Bool ->
                        CloudflareOpsToggleRow("Enabled", toggle, { toggle = it }, testTag = "cloudflare.zoneOps.settingEditor.toggle")
                    options.isNotEmpty() -> Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        CloudflareOpsDropdownField(
                            label = "Value",
                            options = if (text in options) options else options + text,
                            selected = text,
                            optionLabel = { option -> option.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } },
                            onSelect = { text = it },
                            testTag = "cloudflare.zoneOps.settingEditor.value",
                        )
                    }
                    else -> Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        CloudflareOpsTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = "Value",
                            monospace = CloudflareZoneSettingEditing.isNumeric(setting.value),
                            keyboardType = if (CloudflareZoneSettingEditing.isNumeric(setting.value)) KeyboardType.Decimal else KeyboardType.Text,
                            isError = proposed == null,
                            testTag = "cloudflare.zoneOps.settingEditor.value",
                        )
                    }
                }
                Text(
                    "Cloudflare validates plan availability and allowed values when you save.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        ThemedActionButton(
            text = "SAVE SETTING",
            onClick = { onSave(proposed) },
            enabled = proposed != null,
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.zoneOps.settingEditor.save",
        )
    }
}

/** iOS `CloudflareZoneDNSSettingsEditor`. */
@Composable
private fun CloudflareZoneDnsSettingsEditorSheet(
    settings: CloudflareZoneDnsSettings,
    error: String?,
    isSaving: Boolean,
    onSave: (CloudflareZoneDnsSettingsDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(CloudflareZoneDnsSettingsDraft.from(settings)) }
    CloudflareOpsEditorSheet(title = "DNS settings", onDismiss = onDismiss, testTag = "cloudflare.zoneOps.dnsSettingsEditor") {
        CloudflareWriteNotice()
        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Resolution", Icons.Rounded.Dns)
            CloudflareOpsDivider()
            CloudflareOpsToggleRow(
                "Flatten all CNAMEs",
                draft.flattenAllCnames,
                { draft = draft.copy(flattenAllCnames = it) },
                subtitle = "Flatten CNAME records throughout this zone.",
                testTag = "cloudflare.zoneOps.dnsSettingsEditor.flatten",
            )
            CloudflareOpsToggleRow(
                "Multi-provider DNS",
                draft.multiProvider,
                { draft = draft.copy(multiProvider = it) },
                subtitle = "Respect other providers’ apex NS records.",
            )
            CloudflareOpsToggleRow(
                "Secondary overrides",
                draft.secondaryOverrides,
                { draft = draft.copy(secondaryOverrides = it) },
                subtitle = "Allow proxied overrides for Secondary DNS.",
            )
            CloudflareOpsToggleRow(
                "Foundation DNS",
                draft.foundationDns,
                { draft = draft.copy(foundationDns = it) },
                subtitle = "Use Advanced Nameservers when the plan supports it.",
            )
        }
        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Zone mode & TTL", Icons.Rounded.Timer)
            CloudflareOpsDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ZONE MODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudflareOpsChoiceRow(
                    options = CloudflareZoneDnsSettingsDraft.ZONE_MODES.map { it.first },
                    selected = draft.zoneMode,
                    label = { mode -> CloudflareZoneDnsSettingsDraft.ZONE_MODES.firstOrNull { it.first == mode }?.second ?: mode },
                    onSelect = { draft = draft.copy(zoneMode = it) },
                    testTagPrefix = "cloudflare.zoneOps.dnsSettingsEditor.mode",
                )
                CloudflareOpsTextField(
                    value = draft.nameServerTtl,
                    onValueChange = { draft = draft.copy(nameServerTtl = it) },
                    label = "Nameserver TTL · 30–86,400 seconds",
                    placeholder = "86400",
                    monospace = true,
                    keyboardType = KeyboardType.Number,
                    testTag = "cloudflare.zoneOps.dnsSettingsEditor.ttl",
                )
            }
        }
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        ThemedActionButton(
            text = "SAVE DNS SETTINGS",
            onClick = { onSave(draft) },
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.zoneOps.dnsSettingsEditor.save",
        )
    }
}
