package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.automirrored.rounded.Subject
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareDisplayText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareCachePurgeKind
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecord
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecordDraft
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDropdownField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsIconTile
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsToggleRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone

/** iOS `CloudflareDNSRecordEditor` as a sheet. Saving asks for confirmation before any request. */
@Composable
fun CloudflareDnsRecordEditorSheet(
    zoneName: String,
    record: CloudflareDnsRecord?,
    error: String?,
    isSaving: Boolean,
    onSave: (CloudflareDnsRecordDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(record?.id) { mutableStateOf(CloudflareDnsRecordDraft.from(record)) }
    var showingAdvanced by rememberSaveable(record?.id) { mutableStateOf(record?.data != null || record?.settings != null) }
    val isLocked = record?.locked == true
    val canProxy = draft.canProxy(record)
    CloudflareOpsEditorSheet(
        title = if (record == null) "Add DNS Record" else "Edit DNS Record",
        onDismiss = onDismiss,
        testTag = "cloudflare.zone.dnsEditor",
    ) {
        if (isLocked) {
            CloudflareOpsPanel {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = CloudflareOpsColors.Amber, modifier = Modifier.size(18.dp))
                    Text(
                        "Cloudflare manages this record. Its fields are shown for reference and cannot be changed here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Record", Icons.Rounded.Dns)
            CloudflareOpsDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsDropdownField(
                    label = "Type",
                    options = CloudflareDnsRecordDraft.RECORD_TYPES,
                    selected = draft.type,
                    optionLabel = { it },
                    onSelect = { draft = draft.withType(it, record) },
                    enabled = record == null && !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.type",
                )
                CloudflareOpsTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = "Name",
                    placeholder = "@ or host.$zoneName",
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.name",
                )
                CloudflareOpsTextField(
                    value = draft.content,
                    onValueChange = { draft = draft.copy(content = it) },
                    label = "Content",
                    singleLine = false,
                    minLines = 3,
                    monospace = true,
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.content",
                )
            }
        }

        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Routing", Icons.Rounded.AccountTree)
            CloudflareOpsDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsDropdownField(
                    label = "TTL",
                    options = CloudflareDnsRecordDraft.TTL_OPTIONS.map { it.second }.let { if (draft.ttl in it) it else it + draft.ttl },
                    selected = draft.ttl,
                    optionLabel = CloudflareDnsRecordDraft::ttlLabel,
                    onSelect = { draft = draft.copy(ttl = it) },
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.ttl",
                )
            }
            if (canProxy) {
                CloudflareOpsDivider()
                CloudflareOpsToggleRow(
                    title = "Proxy through Cloudflare",
                    subtitle = if (draft.proxied) "Traffic uses Cloudflare’s edge" else "DNS only",
                    checked = draft.proxied,
                    onCheckedChange = { draft = draft.copy(proxied = it) },
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.proxied",
                )
            }
            if (draft.showsPriority) {
                CloudflareOpsDivider()
                Column(Modifier.padding(14.dp)) {
                    CloudflareOpsTextField(
                        value = draft.priority,
                        onValueChange = { draft = draft.copy(priority = it) },
                        label = "Priority",
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                        enabled = !isLocked,
                        testTag = "cloudflare.zone.dnsEditor.priority",
                    )
                }
            }
        }

        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Metadata", Icons.Rounded.Sell)
            CloudflareOpsDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsTextField(
                    value = draft.comment,
                    onValueChange = { draft = draft.copy(comment = it) },
                    label = "Comment",
                    placeholder = "Optional",
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.comment",
                )
                CloudflareOpsTextField(
                    value = draft.tags,
                    onValueChange = { draft = draft.copy(tags = it) },
                    label = "Tags",
                    placeholder = "tag:value, owner:team",
                    enabled = !isLocked,
                    testTag = "cloudflare.zone.dnsEditor.tags",
                )
            }
        }

        if (record != null) {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Returned metadata", Icons.AutoMirrored.Rounded.ListAlt)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Record ID", record.id, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Can be proxied", yesNo(record.proxiable))
                CloudflareOpsDetailRow("Managed by Cloudflare", yesNo(record.locked))
                record.createdDate?.let { CloudflareOpsDetailRow("Created", CloudflareDates.formatDateTime(it)) }
                record.modifiedDate?.let { CloudflareOpsDetailRow("Modified", CloudflareDates.formatDateTime(it)) }
                record.commentModifiedDate?.let { CloudflareOpsDetailRow("Comment modified", CloudflareDates.formatDateTime(it)) }
                record.tagsModifiedDate?.let { CloudflareOpsDetailRow("Tags modified", CloudflareDates.formatDateTime(it)) }
                if (record.meta.isNotEmpty()) {
                    CloudflareOpsDetailRow("Metadata", ProviderJsonValue.Obj(record.meta).cloudflareDisplayText)
                }
            }
        }

        CloudflareOpsPanel {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { showingAdvanced = !showingAdvanced }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("cloudflare.zone.dnsEditor.advanced"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                CloudflareOpsIconTile(Icons.Rounded.DataObject, size = 28)
                Text("Advanced JSON", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(if (showingAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            if (showingAdvanced) {
                CloudflareOpsDivider()
                if (draft.supportsPrivateRouting) {
                    CloudflareOpsToggleRow(
                        title = "Private routing",
                        checked = draft.privateRouting,
                        onCheckedChange = { draft = draft.copy(privateRouting = it) },
                        enabled = !isLocked,
                    )
                    CloudflareOpsDivider()
                }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CloudflareOpsTextField(
                        value = draft.dataJson,
                        onValueChange = { draft = draft.copy(dataJson = it) },
                        label = "Structured data",
                        singleLine = false,
                        minLines = 4,
                        monospace = true,
                        enabled = !isLocked,
                        testTag = "cloudflare.zone.dnsEditor.data",
                    )
                    CloudflareOpsTextField(
                        value = draft.settingsJson,
                        onValueChange = { draft = draft.copy(settingsJson = it) },
                        label = "Settings",
                        singleLine = false,
                        minLines = 4,
                        monospace = true,
                        enabled = !isLocked,
                        testTag = "cloudflare.zone.dnsEditor.settings",
                    )
                }
            }
        }

        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        CloudflareWriteNotice()
        ThemedActionButton(
            text = if (record == null) "CREATE RECORD" else "SAVE CHANGES",
            onClick = { onSave(draft) },
            enabled = !isLocked,
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.zone.dnsEditor.save",
        )
    }
}

/** iOS `CloudflareCachePurgeView` as a sheet. "Everything" requires typing the zone name. */
@Composable
fun CloudflareCachePurgeSheet(
    zoneName: String,
    error: String?,
    isPurging: Boolean,
    onContinue: (CloudflareCachePurgeKind, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(CloudflareCachePurgeKind.FILES) }
    var values by rememberSaveable { mutableStateOf("") }
    var confirmationText by rememberSaveable { mutableStateOf("") }
    CloudflareOpsEditorSheet(title = "Purge Cache", onDismiss = onDismiss, testTag = "cloudflare.zone.purgeSheet") {
        CloudflareOpsPanel(accent = 0.07f) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = CloudflareOpsColors.Orange, modifier = Modifier.size(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cached traffic may briefly increase origin load", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Purging removes matching objects from Cloudflare’s edge. New requests refill the cache from your origin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Purge scope", Icons.Rounded.FilterCenterFocus)
            CloudflareOpsDivider()
            CloudflareCachePurgeKind.entries.forEachIndexed { index, option ->
                val selected = option == kind
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.RadioButton) { kind = option }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .testTag("cloudflare.zone.purgeSheet.kind.${option.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    CloudflareOpsIconTile(
                        purgeIcon(option),
                        tint = if (selected) CloudflareOpsColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 30,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(option.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) CloudflareOpsColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index < CloudflareCachePurgeKind.entries.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
        if (kind != CloudflareCachePurgeKind.EVERYTHING) {
            CloudflareOpsTextField(
                value = values,
                onValueChange = { values = it },
                label = kind.inputLabel,
                singleLine = false,
                minLines = 4,
                monospace = true,
                supportingText = "Enter one value per line or separate values with commas.",
                testTag = "cloudflare.zone.purgeSheet.values",
            )
        } else {
            CloudflareOpsPanel {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TYPE THE ZONE NAME TO CONTINUE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(zoneName, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Bold)
                    CloudflareOpsTextField(
                        value = confirmationText,
                        onValueChange = { confirmationText = it },
                        label = "Zone name",
                        placeholder = zoneName,
                        monospace = true,
                        isError = confirmationText.isNotEmpty() && confirmationText != zoneName,
                        testTag = "cloudflare.zone.purgeSheet.confirmation",
                    )
                }
            }
        }
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        ThemedActionButton(
            text = "CONTINUE",
            onClick = { onContinue(kind, values, confirmationText) },
            enabled = kind != CloudflareCachePurgeKind.EVERYTHING || confirmationText == zoneName,
            isBusy = isPurging,
            tone = if (kind == CloudflareCachePurgeKind.EVERYTHING) ThemedActionTone.DESTRUCTIVE else ThemedActionTone.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.zone.purgeSheet.continue",
        )
    }
}

private fun purgeIcon(kind: CloudflareCachePurgeKind) = when (kind) {
    CloudflareCachePurgeKind.FILES -> Icons.Rounded.Description
    CloudflareCachePurgeKind.TAGS -> Icons.Rounded.Sell
    CloudflareCachePurgeKind.HOSTS -> Icons.Rounded.Language
    CloudflareCachePurgeKind.PREFIXES -> Icons.AutoMirrored.Rounded.Subject
    CloudflareCachePurgeKind.EVERYTHING -> Icons.Rounded.Delete
}

internal fun yesNo(value: Boolean?): String = when (value) {
    null -> "Not returned"
    true -> "Yes"
    false -> "No"
}
