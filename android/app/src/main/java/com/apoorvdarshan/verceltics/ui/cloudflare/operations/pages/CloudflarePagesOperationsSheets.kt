package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectEditDraft
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsChoiceRow
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
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

/** iOS `CloudflarePagesProjectEditorSheet`. Saving opens a confirmation stating the new settings. */
@Composable
internal fun CloudflarePagesProjectEditorSheet(
    draft: CloudflarePagesProjectEditDraft,
    error: String?,
    isSaving: Boolean,
    onChange: (CloudflarePagesProjectEditDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    CloudflareOpsEditorSheet(title = "Project settings", onDismiss = { if (!isSaving) onDismiss() }, testTag = "cloudflare.pages.editor") {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Build pipeline", Icons.Rounded.Build)
                CloudflareOpsDivider()
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloudflareOpsTextField(
                        draft.productionBranch,
                        { onChange(draft.copy(productionBranch = it)) },
                        "Production branch",
                        testTag = "cloudflare.pages.editor.branch",
                    )
                    CloudflareOpsTextField(draft.buildCommand, { onChange(draft.copy(buildCommand = it)) }, "Build command", monospace = true)
                    CloudflareOpsTextField(draft.destinationDirectory, { onChange(draft.copy(destinationDirectory = it)) }, "Output directory")
                    CloudflareOpsTextField(draft.rootDirectory, { onChange(draft.copy(rootDirectory = it)) }, "Root directory")
                }
                CloudflareOpsToggleRow("Build caching", draft.buildCaching, { onChange(draft.copy(buildCaching = it)) })
            }
            if (draft.sourceType != null) {
                CloudflareOpsPanel {
                    CloudflareOpsSectionHeader("Git automation", Icons.Rounded.AccountTree)
                    CloudflareOpsDivider()
                    CloudflareOpsToggleRow(
                        "Production deployments",
                        draft.productionDeploymentsEnabled,
                        { onChange(draft.copy(productionDeploymentsEnabled = it)) },
                    )
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        val options = CloudflarePagesProjectEditDraft.PREVIEW_SETTINGS
                        CloudflareOpsDropdownField(
                            label = "Preview deployments",
                            options = options.map { it.first }.let { values ->
                                if (draft.previewDeploymentSetting in values) values else values + draft.previewDeploymentSetting
                            },
                            selected = draft.previewDeploymentSetting,
                            optionLabel = { value -> options.firstOrNull { it.first == value }?.second ?: value },
                            onSelect = { onChange(draft.copy(previewDeploymentSetting = it)) },
                            testTag = "cloudflare.pages.editor.preview",
                        )
                    }
                    CloudflareOpsToggleRow(
                        "Pull request comments",
                        draft.pullRequestCommentsEnabled,
                        { onChange(draft.copy(pullRequestCommentsEnabled = it)) },
                    )
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (draft.previewDeploymentSetting == "custom") {
                            CloudflareOpsTextField(
                                draft.previewBranchIncludes,
                                { onChange(draft.copy(previewBranchIncludes = it)) },
                                "Preview branch includes",
                            )
                            CloudflareOpsTextField(
                                draft.previewBranchExcludes,
                                { onChange(draft.copy(previewBranchExcludes = it)) },
                                "Preview branch excludes",
                            )
                        }
                        CloudflareOpsTextField(draft.pathIncludes, { onChange(draft.copy(pathIncludes = it)) }, "Path includes")
                        CloudflareOpsTextField(draft.pathExcludes, { onChange(draft.copy(pathExcludes = it)) }, "Path excludes")
                    }
                }
            }
            Text(
                "Separate multiple branches or paths with commas. Secret variables and Analytics credentials are never sent by this editor, so existing sensitive values remain untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
            ThemedActionButton(
                text = if (isSaving) "SAVING…" else "SAVE",
                onClick = onSave,
                enabled = !isSaving && draft.productionBranch.isNotBlank(),
                isBusy = isSaving,
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.pages.editor.save",
            )
        }
    }
}

/** iOS `CloudflarePagesAddDomainSheet`. */
@Composable
internal fun CloudflarePagesAddDomainSheet(
    error: String?,
    isAdding: Boolean,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var domain by rememberSaveable { mutableStateOf("") }
    CloudflareOpsEditorSheet(title = "Custom domain", onDismiss = { if (!isAdding) onDismiss() }, testTag = "cloudflare.pages.addDomainSheet") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudflareOpsIconTile(Icons.Rounded.Language)
            Text("Connect a hostname", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Cloudflare will check DNS ownership, validate the hostname and provision its certificate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CloudflareOpsTextField(
            value = domain,
            onValueChange = { domain = it },
            label = "Hostname",
            placeholder = "www.example.com",
            monospace = true,
            keyboardType = KeyboardType.Uri,
            testTag = "cloudflare.pages.addDomain.field",
        )
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        ThemedActionButton(
            text = if (isAdding) "ADDING DOMAIN…" else "ADD DOMAIN",
            onClick = { onAdd(domain) },
            enabled = !isAdding && domain.isNotBlank(),
            isBusy = isAdding,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.pages.addDomain.submit",
        )
    }
}

/** iOS `CloudflarePagesDirectUploadSheet`: choose environment, branch and message, then the folder. */
@Composable
internal fun CloudflarePagesDirectUploadSheet(
    draft: CloudflarePagesDirectUploadDraft,
    error: String?,
    onChange: (CloudflarePagesDirectUploadDraft) -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    CloudflareOpsEditorSheet(title = "Direct upload", onDismiss = onDismiss, testTag = "cloudflare.pages.uploadSheet") {
        CloudflareOpsPanel(accent = 0.08f) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsIconTile(Icons.Rounded.CloudUpload, size = 43)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Deploy a prebuilt folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Assets are hashed on this device, deduplicated by Cloudflare and then attached to a new Pages deployment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        CloudflareOpsPanel {
            CloudflareOpsSectionHeader("Deployment", Icons.Rounded.Build)
            CloudflareOpsDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsChoiceRow(
                    options = CloudflarePagesEnvironment.entries,
                    selected = draft.environment,
                    label = { it.label },
                    onSelect = { onChange(draft.copy(environment = it)) },
                    testTagPrefix = "cloudflare.pages.upload.environment",
                )
                if (draft.environment == CloudflarePagesEnvironment.PRODUCTION) {
                    CloudflareOpsDetailRow(
                        "Production branch",
                        draft.productionBranch?.takeIf(String::isNotEmpty) ?: "Cloudflare default",
                        Icons.Rounded.AccountTree,
                    )
                } else {
                    CloudflareOpsTextField(
                        draft.previewBranch,
                        { onChange(draft.copy(previewBranch = it)) },
                        "Preview branch",
                        testTag = "cloudflare.pages.upload.previewBranch",
                    )
                }
                CloudflareOpsTextField(
                    draft.commitMessage,
                    { onChange(draft.copy(commitMessage = it)) },
                    "Commit message (optional)",
                    testTag = "cloudflare.pages.upload.message",
                )
            }
        }
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UploadHint(Icons.Rounded.Folder, "Choose the output folder, not the source project.")
            UploadHint(Icons.Rounded.Shield, "Each file may be up to 25 MiB; the account plan controls the file-count limit.")
            UploadHint(Icons.Rounded.Functions, "Raw Pages Functions source must be bundled first.")
        }
        ThemedActionButton(
            text = "CHOOSE FOLDER AND DEPLOY",
            onClick = onChooseFolder,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.pages.upload.chooseFolder",
        )
    }
}

@Composable
private fun UploadHint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** iOS `CloudflarePagesDomainDetailSheet`: domain metadata and validation records. */
@Composable
internal fun CloudflarePagesDomainDetailSheet(
    detail: CloudflarePagesDomainDetailState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = detail.domain
    CloudflareOpsEditorSheet(title = current.name, onDismiss = onDismiss, testTag = "cloudflare.pages.domainDetail") {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudflareOpsPanel(accent = 0.06f) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    CloudflareOpsIconTile(
                        if (current.isActive) Icons.Rounded.Verified else Icons.Rounded.Language,
                        if (current.isActive) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
                        size = 46,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            CloudflarePagesText.capitalized(current.status ?: "unknown"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CloudflareOpsActionButton(
                        if (detail.isRefreshing) "Refreshing" else "Refresh",
                        Icons.Rounded.Refresh,
                        onClick = onRefresh,
                        working = detail.isRefreshing,
                    )
                }
            }
            detail.error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Domain", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Domain ID", current.domainId ?: current.id, Icons.Rounded.Tag, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Zone tag", current.zoneTag ?: "Not linked", Icons.Rounded.Tag)
                CloudflareOpsDetailRow("Certificate authority", CloudflarePagesText.certificateAuthority(current, "Pending"), Icons.Rounded.Shield)
                CloudflareOpsDetailRow("Created", CloudflarePagesText.date(current.createdDate), Icons.Rounded.CalendarToday)
            }
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Validation", Icons.Rounded.CheckCircle)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Method", current.validationMethod?.uppercase() ?: "Not assigned", Icons.Rounded.Dns)
                CloudflareOpsDetailRow(
                    "Validation status",
                    current.validationStatus?.let(CloudflarePagesText::capitalized) ?: "Pending",
                    Icons.Rounded.CheckCircle,
                )
                CloudflareOpsDetailRow(
                    "Verification status",
                    current.verificationStatus?.let(CloudflarePagesText::capitalized) ?: "Pending",
                    Icons.Rounded.Verified,
                )
                current.txtName?.let { CloudflareOpsDetailRow("TXT name", it, Icons.Rounded.TextFields, monospace = true, copyable = true) }
                current.txtValue?.let { CloudflareOpsDetailRow("TXT value", it, Icons.Rounded.TextFields, monospace = true, copyable = true) }
                (current.validationErrorMessage ?: current.verificationErrorMessage)?.let {
                    CloudflareOpsDetailRow("Cloudflare message", it, Icons.Rounded.WarningAmber)
                }
            }
        }
    }
}
