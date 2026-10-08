package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareDisplayText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerSecretText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerVersionInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.titleCased
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsIconTile
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsToggleRow
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

private enum class WorkerEditor { SECRET, SCHEDULE, DOMAIN, OBSERVABILITY, SUBDOMAIN }

/** Port of iOS `CloudflareWorkerOperationsView`. */
@Composable
fun CloudflareWorkerOperationsScreen(
    viewModel: CloudflareWorkerOperationsViewModel,
    refreshSignal: Int,
    onOpenSource: () -> Unit,
    onOpenLiveLogs: () -> Unit,
    onOpenVersion: (CloudflareWorkerVersionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<WorkerEditor?>(null) }
    var warningsExpanded by rememberSaveable { mutableStateOf(false) }
    CloudflareWorkerRefreshEffect(refreshSignal) { viewModel.load(forceRefresh = true) }
    CloudflareConfirmationHost(viewModel)

    when (editor) {
        WorkerEditor.SECRET -> SecretEditor(onDismiss = { editor = null }) { name, value ->
            editor = null
            viewModel.requestSaveSecret(name, value)
        }
        WorkerEditor.SCHEDULE -> TextEditor(
            title = "Add cron trigger",
            subtitle = "Cron expressions run in UTC. Example: */30 * * * * runs every 30 minutes.",
            label = "Cron expression",
            testTag = "cloudflare.worker.scheduleEditor",
            keyboardType = KeyboardType.Ascii,
            isValid = CloudflareWorkerOperationsApi::isValidCronExpression,
            onDismiss = { editor = null },
        ) { cron ->
            editor = null
            viewModel.requestAddSchedule(cron)
        }
        WorkerEditor.DOMAIN -> TextEditor(
            title = "Attach custom domain",
            subtitle = "Use a hostname from a zone in this Cloudflare account, such as api.example.com.",
            label = "Hostname",
            testTag = "cloudflare.worker.domainEditor",
            keyboardType = KeyboardType.Uri,
            isValid = CloudflareWorkerOperationsApi::isValidHostname,
            onDismiss = { editor = null },
        ) { hostname ->
            editor = null
            viewModel.requestAttachDomain(hostname)
        }
        WorkerEditor.OBSERVABILITY -> {
            val observability = state.scriptLevelSettings?.observability
            ToggleEditor(
                title = "Configure observability",
                subtitle = "Control persisted events, invocation logs and traces for this Worker.",
                testTag = "cloudflare.worker.observabilityEditor",
                initial = listOf(observability?.enabled ?: false, observability?.logs?.enabled ?: false, observability?.traces?.enabled ?: false),
                labels = listOf("Collect events", "Invocation logs", "Traces"),
                dependsOnFirst = true,
                onDismiss = { editor = null },
            ) { values ->
                editor = null
                viewModel.requestUpdateObservability(values[0], values[1], values[2])
            }
        }
        WorkerEditor.SUBDOMAIN -> ToggleEditor(
            title = "Configure workers.dev",
            subtitle = "Production and preview URLs can be controlled independently.",
            testTag = "cloudflare.worker.subdomainEditor",
            initial = listOf(state.subdomain?.enabled ?: false, state.subdomain?.previewsEnabled ?: false),
            labels = listOf("Production URL", "Version preview URLs"),
            dependsOnFirst = false,
            onDismiss = { editor = null },
        ) { values ->
            editor = null
            viewModel.requestUpdateSubdomain(values[0], values[1])
        }
        null -> Unit
    }

    val worker = state.worker
    CloudflareOpsScreen("cloudflare.workerOperations", modifier) {
        item("rail") {
            CloudflareOpsPanel(accent = 0.09f, testTag = "cloudflare.worker.capabilities") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(viewModel.scriptName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "CONTROL PLANE",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = CloudflareOpsColors.Orange,
                            )
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                        } else {
                            CloudflareOpsStatusPill("SYNCED", CloudflareOpsColors.Green)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RailValue("VERSIONS", state.versions.size, Modifier.weight(1f))
                        RailValue("SECRETS", state.secrets.size, Modifier.weight(1f))
                        RailValue("CRONS", state.schedules.size, Modifier.weight(1f))
                        RailValue("DOMAINS", state.domains.size, Modifier.weight(1f))
                    }
                }
            }
        }
        item("quick-actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile("Source", Icons.Rounded.Code, "cloudflare.worker.source", onOpenSource, Modifier.weight(1f))
                ActionTile("Live logs", Icons.Rounded.Timeline, "cloudflare.worker.liveLogs", onOpenLiveLogs, Modifier.weight(1f))
            }
        }
        item("metadata") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.metadata") {
                CloudflareOpsSectionHeader("Returned metadata", Icons.Rounded.ListAlt)
                CloudflareOpsDivider()
                MetadataRow("Created", worker?.createdDate?.let(CloudflareDates::formatDateTime))
                MetadataRow("Modified", worker?.modifiedDate?.let(CloudflareDates::formatDateTime))
                MetadataRow("Last deployed from", worker?.lastDeployedFrom)
                MetadataRow("ETag", worker?.etag, monospace = true)
                MetadataRow("Migration tag", worker?.migrationTag)
                MetadataRow("Script tag", worker?.tag, monospace = true)
                MetadataRow("Logpush", worker?.logpush?.let { if (it) "Enabled" else "Disabled" })
                MetadataRow("Tags", worker?.tags?.takeIf { it.isNotEmpty() }?.joinToString(", "))
                MetadataRow("Placement mode", worker?.placementMode)
                MetadataRow("Placement status", worker?.placementStatus)
                MetadataRow("Named handlers", worker?.namedHandlerCount?.takeIf { it > 0 }?.let { "$it returned" })
                MetadataRow("Tail consumers", worker?.tailConsumerCount?.takeIf { it > 0 }?.let { "$it returned" })
                if (worker == null) {
                    Text(
                        if (state.isLoading) "Loading Worker metadata…" else "Cloudflare did not return metadata for this Worker.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.settings?.let { settings ->
            item("settings") {
                CloudflareOpsPanel(testTag = "cloudflare.worker.settings") {
                    CloudflareOpsSectionHeader("Script settings response", Icons.Rounded.DataObject)
                    CloudflareOpsDivider()
                    MetadataRow("Compatibility date", settings.compatibilityDate)
                    MetadataRow("Compatibility flags", settings.compatibilityFlags.takeIf { it.isNotEmpty() }?.joinToString(", "))
                    MetadataRow("Usage model", settings.usageModel)
                    MetadataRow("Tags", settings.tags.takeIf { it.isNotEmpty() }?.joinToString(", "))
                    MetadataRow(
                        "Annotations",
                        settings.annotations.takeIf { it.isNotEmpty() }?.entries?.sortedBy { it.key }?.joinToString(", ") { "${it.key}: ${it.value}" },
                    )
                    MetadataRow("Bindings", settings.bindings.takeIf { it.isNotEmpty() }?.let { ProviderJsonValue.Arr(it).cloudflareDisplayText })
                    MetadataRow("Cache options", settings.cacheOptions?.cloudflareDisplayText)
                    MetadataRow("Limits", settings.limits?.cloudflareDisplayText)
                    MetadataRow("Migrations", settings.migrations?.cloudflareDisplayText)
                    MetadataRow("Placement", settings.placement?.cloudflareDisplayText)
                    MetadataRow(
                        "Tail consumers",
                        settings.tailConsumers.takeIf { it.isNotEmpty() }?.let { ProviderJsonValue.Arr(it).cloudflareDisplayText },
                    )
                }
            }
        }
        if (state.warnings.isNotEmpty()) {
            item("warnings") {
                CloudflareOpsPanel(testTag = "cloudflare.worker.warnings") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { warningsExpanded = !warningsExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CloudflareOpsColors.Amber)
                        Text(
                            "Some Worker capabilities are unavailable",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = CloudflareOpsColors.Amber,
                        )
                        Icon(if (warningsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                    if (warningsExpanded) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.warnings.forEach { warning ->
                                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        banner?.let { current ->
            item("banner") { CloudflareActionResultBanner(current, onDismiss = viewModel::dismissBanner) }
        }
        item("observability") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.observability") {
                CloudflareOpsSectionHeader(
                    "Observability",
                    Icons.Rounded.Timeline,
                    actionTitle = if (state.scriptLevelSettings == null) null else "Configure",
                    actionTestTag = "cloudflare.worker.observability.configure",
                    actionEnabled = CloudflareWorkerOperationsViewModel.OBSERVABILITY !in working,
                    onAction = { editor = WorkerEditor.OBSERVABILITY },
                )
                CloudflareOpsDivider()
                val observability = state.scriptLevelSettings?.observability
                if (observability != null) {
                    StatusRow(
                        "Event collection",
                        observability.enabled,
                        observability.headSamplingRate?.let { "Sampling ${String.format(java.util.Locale.US, "%.0f", it * 100)}%" },
                    )
                    StatusRow("Invocation logs", observability.logs?.enabled == true, if (observability.logs?.persist == true) "Persisted" else null)
                    StatusRow("Traces", observability.traces?.enabled == true, observability.traces?.propagationPolicy)
                } else {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Timeline,
                        title = "No observability settings",
                        message = "This Worker or account did not return script observability configuration.",
                    )
                }
            }
        }
        item("subdomain") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.subdomain") {
                CloudflareOpsSectionHeader(
                    "workers.dev",
                    Icons.Rounded.Language,
                    actionTitle = if (state.subdomain == null) null else "Configure",
                    actionTestTag = "cloudflare.worker.subdomain.configure",
                    actionEnabled = CloudflareWorkerOperationsViewModel.SUBDOMAIN !in working,
                    onAction = { editor = WorkerEditor.SUBDOMAIN },
                )
                CloudflareOpsDivider()
                val subdomain = state.subdomain
                if (subdomain != null) {
                    StatusRow("Production URL", subdomain.enabled, viewModel.workerDevHostname)
                    StatusRow("Preview URLs", subdomain.previewsEnabled, "Version preview hostnames")
                } else {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Language,
                        title = "workers.dev unavailable",
                        message = "Cloudflare did not return subdomain settings for this Worker.",
                    )
                }
            }
        }
        item("versions") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.versions") {
                CloudflareOpsSectionHeader("Deployable versions", Icons.Rounded.History, count = state.versions.size)
                CloudflareOpsDivider()
                if (state.versions.isEmpty()) {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.History,
                        title = "No deployable versions",
                        message = "Version history may not be enabled for this Worker.",
                    )
                } else {
                    state.versions.forEachIndexed { index, version ->
                        VersionRow(
                            version = version,
                            working = CloudflareWorkerOperationsViewModel.deployWorkingId(version.id) in working,
                            onInfo = { onOpenVersion(version) },
                            onDeploy = { viewModel.requestDeploy(version) },
                        )
                        if (index < state.versions.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("secrets") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.secrets") {
                CloudflareOpsSectionHeader(
                    "Secrets",
                    Icons.Rounded.Key,
                    count = state.secrets.size,
                    actionTitle = "Add",
                    actionTestTag = "cloudflare.worker.secrets.add",
                    actionEnabled = CloudflareWorkerOperationsViewModel.SECRET_SAVE !in working,
                    onAction = { editor = WorkerEditor.SECRET },
                )
                CloudflareOpsDivider()
                if (state.secrets.isEmpty()) {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Key,
                        title = "No secrets",
                        message = "Secret values are accepted once and never displayed or saved by the app.",
                    )
                } else {
                    state.secrets.forEachIndexed { index, secret ->
                        DeletableRow(
                            icon = Icons.Rounded.Key,
                            title = secret.name,
                            subtitle = secret.type.replace('_', ' ').titleCased(),
                            working = "secret-${secret.name}" in working,
                            deleteDescription = "Delete secret ${secret.name}",
                            testTag = "cloudflare.worker.secret.${secret.name}",
                            onDelete = { viewModel.requestDeleteSecret(secret) },
                        )
                        if (index < state.secrets.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("schedules") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.schedules") {
                CloudflareOpsSectionHeader(
                    "Cron triggers",
                    Icons.Rounded.Schedule,
                    count = state.schedules.size,
                    actionTitle = "Add",
                    actionTestTag = "cloudflare.worker.schedules.add",
                    actionEnabled = CloudflareWorkerOperationsViewModel.SCHEDULES !in working,
                    onAction = { editor = WorkerEditor.SCHEDULE },
                )
                CloudflareOpsDivider()
                if (state.schedules.isEmpty()) {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Schedule,
                        title = "No cron triggers",
                        message = "Add a UTC cron expression to invoke the Worker's scheduled handler.",
                    )
                } else {
                    state.schedules.forEachIndexed { index, schedule ->
                        DeletableRow(
                            icon = Icons.Rounded.Schedule,
                            title = schedule.cron,
                            subtitle = schedule.modifiedOn ?: schedule.createdOn ?: "UTC schedule",
                            working = CloudflareWorkerOperationsViewModel.SCHEDULES in working,
                            deleteDescription = "Delete cron ${schedule.cron}",
                            testTag = "cloudflare.worker.schedule.$index",
                            onDelete = { viewModel.requestDeleteSchedule(schedule) },
                        )
                        if (index < state.schedules.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("domains") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.domains") {
                CloudflareOpsSectionHeader(
                    "Custom domains",
                    Icons.Rounded.Public,
                    count = state.domains.size,
                    actionTitle = "Attach",
                    actionTestTag = "cloudflare.worker.domains.attach",
                    actionEnabled = CloudflareWorkerOperationsViewModel.DOMAIN_ADD !in working,
                    onAction = { editor = WorkerEditor.DOMAIN },
                )
                CloudflareOpsDivider()
                if (state.domains.isEmpty()) {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Public,
                        title = "No custom domains",
                        message = "Attach a hostname from a zone in this Cloudflare account.",
                    )
                } else {
                    state.domains.forEachIndexed { index, domain ->
                        DeletableRow(
                            icon = Icons.Rounded.Public,
                            title = domain.hostname,
                            subtitle = listOfNotNull(domain.zoneName, domain.certificateId?.let { "TLS issued" }).joinToString(" · "),
                            working = "domain-${domain.id}" in working,
                            deleteDescription = "Detach ${domain.hostname}",
                            testTag = "cloudflare.worker.domain.${domain.id}",
                            onDelete = { viewModel.requestDetachDomain(domain) },
                        )
                        if (index < state.domains.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailValue(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(CloudflareFormat.grouped(value.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionTile(title: String, icon: ImageVector, testTag: String, onClick: () -> Unit, modifier: Modifier) {
    CloudflareOpsPanel(modifier = modifier) {
        CloudflareOpsResourceRow(icon = icon, title = title, onClick = onClick, testTag = testTag)
    }
}

@Composable
private fun MetadataRow(title: String, value: String?, monospace: Boolean = false) {
    if (value.isNullOrEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title.uppercase(),
            modifier = Modifier.widthIn(min = 105.dp, max = 120.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    CloudflareOpsDivider()
}

@Composable
private fun StatusRow(title: String, enabled: Boolean, subtitle: String?) {
    val tint = if (enabled) CloudflareOpsColors.Green else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CloudflareOpsIconTile(if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircle, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        CloudflareOpsStatusPill(if (enabled) "ON" else "OFF", tint)
    }
}

@Composable
private fun VersionRow(version: CloudflareWorkerVersionInfo, working: Boolean, onInfo: () -> Unit, onDeploy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("cloudflare.worker.version.${version.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CloudflareOpsIconTile(Icons.Rounded.History, CloudflareOpsColors.Green)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(version.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                versionSubtitle(version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onInfo, modifier = Modifier.testTag("cloudflare.worker.version.${version.id}.info")) {
            Icon(Icons.Rounded.Info, contentDescription = "Version details for ${version.displayTitle}")
        }
        if (working) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
            }
        } else {
            Surface(
                onClick = onDeploy,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("cloudflare.worker.version.${version.id}.deploy"),
                shape = CircleShape,
                color = CloudflareOpsColors.Orange.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
                contentColor = CloudflareOpsColors.Orange,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Send, contentDescription = "Deploy ${version.displayTitle}", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** iOS `versionSubtitle`: source, created date and author, or the id prefix. */
internal fun versionSubtitle(version: CloudflareWorkerVersionInfo): String = listOfNotNull(
    version.metadata?.source?.titleCased(),
    version.metadata?.createdDate?.let(CloudflareDates::formatDateTime),
    version.metadata?.authorEmail,
).filter(String::isNotEmpty).joinToString(" · ").ifEmpty { version.id.take(18) }

@Composable
private fun DeletableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    working: Boolean,
    deleteDescription: String,
    testTag: String,
    onDelete: () -> Unit,
) {
    CloudflareOpsResourceRow(icon = icon, title = title, subtitle = subtitle, tint = CloudflareOpsColors.Amber, testTag = testTag) {
        if (working) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
            }
        } else {
            IconButton(onClick = onDelete, modifier = Modifier.testTag("$testTag.delete")) {
                Icon(Icons.Rounded.Delete, contentDescription = deleteDescription, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun EditorIntro(subtitle: String) {
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.SaveButton(enabled: Boolean, testTag: String, onSave: () -> Unit) {
    Spacer(Modifier.width(1.dp))
    ThemedActionButton("SAVE", onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth(), testTag = testTag)
}

/** iOS `CloudflareWorkerSecretEditor`. The value lives only in this sheet until it is handed over. */
@Composable
private fun SecretEditor(onDismiss: () -> Unit, onSave: (String, CloudflareWorkerSecretText) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    CloudflareOpsEditorSheet("Add secret", onDismiss, testTag = "cloudflare.worker.secretEditor") {
        EditorIntro("The value is sent directly to Cloudflare and is never stored or shown again.")
        CloudflareOpsTextField(name, { name = it }, label = "Binding name", monospace = true, testTag = "cloudflare.worker.secretEditor.name")
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloudflare.worker.secretEditor.value"),
            label = { Text("Secret value") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = false,
            maxLines = 6,
            shape = RoundedCornerShape(13.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CloudflareOpsColors.Orange,
                focusedLabelColor = CloudflareOpsColors.Orange,
                cursorColor = CloudflareOpsColors.Orange,
            ),
        )
        SaveButton(name.isNotBlank() && value.isNotEmpty(), "cloudflare.worker.secretEditor.save") {
            val secret = CloudflareWorkerSecretText.of(value)
            value = ""
            onSave(name.trim(), secret)
        }
    }
}

@Composable
private fun TextEditor(
    title: String,
    subtitle: String,
    label: String,
    testTag: String,
    keyboardType: KeyboardType,
    isValid: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    CloudflareOpsEditorSheet(title, onDismiss, testTag = testTag) {
        EditorIntro(subtitle)
        CloudflareOpsTextField(text, { text = it }, label = label, monospace = true, keyboardType = keyboardType, testTag = "$testTag.field")
        SaveButton(isValid(text), "$testTag.save") { onSave(text.trim()) }
    }
}

/** iOS observability and workers.dev editors. When [dependsOnFirst], later toggles need the first. */
@Composable
private fun ToggleEditor(
    title: String,
    subtitle: String,
    testTag: String,
    initial: List<Boolean>,
    labels: List<String>,
    dependsOnFirst: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<Boolean>) -> Unit,
) {
    var values by remember { mutableStateOf(initial) }
    CloudflareOpsEditorSheet(title, onDismiss, testTag = testTag) {
        EditorIntro(subtitle)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column {
                labels.forEachIndexed { index, label ->
                    CloudflareOpsToggleRow(
                        title = label,
                        checked = values[index],
                        onCheckedChange = { checked -> values = values.toMutableList().also { it[index] = checked } },
                        enabled = !dependsOnFirst || index == 0 || values[0],
                        testTag = "$testTag.toggle.$index",
                    )
                    if (index < labels.lastIndex) CloudflareOpsDivider()
                }
            }
        }
        SaveButton(true, "$testTag.save") { onSave(values) }
    }
}
