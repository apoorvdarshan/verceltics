package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesCustomDomain
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeploymentConfiguration
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDirectUploadContract
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectEditDraft
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
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsIconTile
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudflarePagesOperationsRoute(
    client: CloudflareRestClient,
    accountId: String,
    projectName: String,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = "cloudflare.pages.operations|$accountId|$projectName") {
        CloudflarePagesOperationsViewModel(CloudflarePagesApi(client), accountId, projectName)
    }
    CloudflarePagesRefreshEffect(context.refreshSignal) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestContext by rememberUpdatedState(context)
    LaunchedEffect(state.didDeleteProject) {
        if (state.didDeleteProject) {
            latestContext.inventoryChanged()
            latestContext.closeResource()
        }
    }
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val chosen = runCatching {
                withContext(Dispatchers.IO) {
                    val folder = CloudflarePagesDocumentTreeFolder(appContext.contentResolver, uri)
                    folder to folder.displayName
                }
            }.getOrNull()
            if (chosen == null) {
                viewModel.closeUpload()
            } else {
                viewModel.requestUpload(chosen.first, chosen.second)
            }
        }
    }
    CloudflarePagesOperationsScreen(
        viewModel = viewModel,
        onChooseFolder = { if (viewModel.validateUploadDraft()) folderPicker.launch(null) },
        modifier = modifier,
    )
}

/** iOS `CloudflarePagesOperationsView`. [onChooseFolder] opens the Storage Access Framework folder picker. */
@Composable
fun CloudflarePagesOperationsScreen(
    viewModel: CloudflarePagesOperationsViewModel,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val project = state.project
    val isWorking = working.isNotEmpty()

    CloudflareOpsScreen(
        "cloudflare.pages.operations",
        modifier,
        maximumContentWidth = 880.dp,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = viewModel::load,
    ) {
        when {
            state.isLoading && project == null -> item("loading") { CloudflareOpsLoading("Loading Pages project…") }
            project == null -> item("error") {
                CloudflareOpsPanel {
                    CloudflareOpsEmptySection(
                        Icons.Rounded.WarningAmber,
                        "Pages project unavailable",
                        state.loadError ?: "Cloudflare did not return this project.",
                    )
                    Row(Modifier.padding(start = 14.dp, bottom = 14.dp)) {
                        CloudflareOpsActionButton("Try again", Icons.Rounded.Replay, onClick = viewModel::load)
                    }
                }
            }
            else -> {
                item("hero") { ProjectHeader(project) }
                item("write-notice") { CloudflareWriteNotice() }
                banner?.let { item("banner") { CloudflareActionResultBanner(it, onDismiss = viewModel::dismissBanner) } }
                state.loadError?.let { error ->
                    item("load-error") {
                        CloudflarePagesFeedbackCard("Project refresh failed", "$error Showing the last successful project details.", "Retry", viewModel::load)
                    }
                }
                item("identity") { IdentityPanel(project) }
                item("build") { BuildPanel(project) }
                item("source") { SourcePanel(project) }
                item("environment") { EnvironmentPanel(project, state.environment, viewModel::selectEnvironment) }
                item("domains") {
                    DomainsPanel(
                        domains = state.domains,
                        error = state.domainsError,
                        working = working,
                        onAdd = viewModel::openAddDomain,
                        onOpen = viewModel::openDomain,
                        onRetry = viewModel::requestRetryValidation,
                        onDelete = viewModel::requestDeleteDomain,
                    )
                }
                item("deploy") {
                    DeployPanel(
                        project = project,
                        state = state,
                        deploymentsPath = viewModel.deploymentsPath,
                        uploading = CloudflarePagesPrompts.DIRECT_UPLOAD_WORKING_ID in working,
                        isWorking = isWorking,
                        onUpload = viewModel::openUpload,
                        onCancelUpload = viewModel::cancelUpload,
                        onRedeploy = viewModel::requestRedeploy,
                        onGuide = { uriHandler.openPagesUrl("https://developers.cloudflare.com/pages/get-started/direct-upload/") },
                    )
                }
                item("maintenance") {
                    MaintenancePanel(
                        project = project,
                        isWorking = isWorking,
                        onEdit = viewModel::openEditor,
                        onPurge = viewModel::requestPurgeBuildCache,
                        onDelete = viewModel::requestDeleteProject,
                    )
                }
            }
        }
    }

    state.editorDraft?.let { draft ->
        CloudflarePagesProjectEditorSheet(
            draft = draft,
            error = state.editorError,
            isSaving = "project-settings" in working,
            onChange = viewModel::updateDraft,
            onSave = viewModel::requestSave,
            onDismiss = viewModel::closeEditor,
        )
    }
    if (state.isAddingDomain) {
        CloudflarePagesAddDomainSheet(
            error = state.addDomainError,
            isAdding = "add-domain" in working,
            onAdd = viewModel::requestAddDomain,
            onDismiss = viewModel::closeAddDomain,
        )
    }
    state.uploadDraft?.let { draft ->
        CloudflarePagesDirectUploadSheet(
            draft = draft,
            error = state.uploadDraftError,
            onChange = viewModel::updateUploadDraft,
            onChooseFolder = onChooseFolder,
            onDismiss = viewModel::closeUpload,
        )
    }
    state.domainDetail?.let { detail ->
        CloudflarePagesDomainDetailSheet(
            detail = detail,
            onRefresh = viewModel::refreshDomainDetail,
            onDismiss = viewModel::closeDomain,
        )
    }
    CloudflareConfirmationHost(viewModel)
}

@Composable
private fun ProjectHeader(project: CloudflarePagesProjectDetail) {
    val latest = project.latestDeployment
    CloudflareOpsHero(
        title = project.name,
        subtitle = project.subdomain ?: "Cloudflare Pages",
        icon = Icons.Rounded.Layers,
        status = latest?.displayStatus?.uppercase() ?: "READY",
        statusColor = CloudflarePagesColors.deploymentStatus(latest?.displayStatus),
    ) {
        project.framework?.takeIf(String::isNotEmpty)?.let { framework ->
            LabelChip(listOfNotNull(framework, project.frameworkVersion).joinToString(" "))
        }
        LabelChip(project.productionBranch ?: "No production branch")
        if (project.usesFunctions == true) LabelChip("Functions")
    }
}

@Composable
private fun IdentityPanel(project: CloudflarePagesProjectDetail) {
    CloudflareOpsPanel {
        CloudflareOpsSectionHeader("Project identity", Icons.Rounded.Fingerprint)
        CloudflareOpsDivider()
        CloudflareOpsDetailRow("Project ID", project.id, Icons.Rounded.Tag, monospace = true, copyable = true)
        CloudflareOpsDetailRow("Pages subdomain", project.subdomain ?: "Not assigned", Icons.Rounded.Public)
        CloudflareOpsDetailRow("Created", CloudflarePagesText.date(project.createdDate), Icons.Rounded.CalendarToday)
        CloudflareOpsDetailRow("Canonical deployment", CloudflarePagesText.deploymentLabel(project.canonicalDeployment), Icons.Rounded.Layers)
        CloudflareOpsDetailRow("Production script", project.productionScriptName ?: "Not provisioned", Icons.Rounded.Bolt)
        CloudflareOpsDetailRow("Preview script", project.previewScriptName ?: "Not provisioned", Icons.Rounded.Bolt)
    }
}

@Composable
private fun BuildPanel(project: CloudflarePagesProjectDetail) {
    CloudflareOpsPanel {
        CloudflareOpsSectionHeader("Build pipeline", Icons.Rounded.Build)
        CloudflareOpsDivider()
        val config = project.buildConfig
        if (config == null) {
            CloudflareOpsEmptySection(Icons.Rounded.Build, "No build configuration", "This project may use direct uploads.")
        } else {
            CloudflareOpsDetailRow("Build command", CloudflarePagesText.valueOrNotSet(config.buildCommand), Icons.Rounded.Terminal, monospace = true)
            CloudflareOpsDetailRow("Output directory", CloudflarePagesText.valueOrNotSet(config.destinationDirectory), Icons.Rounded.Folder)
            CloudflareOpsDetailRow("Root directory", CloudflarePagesText.valueOrNotSet(config.rootDirectory), Icons.Rounded.Folder)
            CloudflareOpsDetailRow("Build cache", CloudflarePagesText.enabled(config.buildCaching), Icons.Rounded.Inventory2)
            CloudflareOpsDetailRow("Web Analytics tag", CloudflarePagesText.valueOrNotSet(config.webAnalyticsTag), Icons.Rounded.Tag)
            CloudflareOpsDetailRow(
                "Analytics token",
                if (config.webAnalyticsTokenConfigured) "Configured · value hidden" else "Not configured",
                Icons.Rounded.Key,
            )
        }
    }
}

@Composable
private fun SourcePanel(project: CloudflarePagesProjectDetail) {
    CloudflareOpsPanel {
        CloudflareOpsSectionHeader("Source control", Icons.Rounded.AccountTree)
        CloudflareOpsDivider()
        val source = project.source
        val config = source?.config
        if (source == null || config == null) {
            CloudflareOpsEmptySection(Icons.Rounded.Link, "Direct upload project", "No Git repository is connected to this project.")
        } else {
            CloudflareOpsDetailRow("Provider", source.type?.uppercase() ?: "Unknown", Icons.Rounded.Code)
            CloudflareOpsDetailRow(
                "Repository",
                listOfNotNull(config.owner, config.repositoryName).joinToString("/").ifEmpty { "Not set" },
                Icons.Rounded.Code,
            )
            CloudflareOpsDetailRow("Owner ID", CloudflarePagesText.valueOrNotSet(config.ownerId), Icons.Rounded.Tag)
            CloudflareOpsDetailRow("Repository ID", CloudflarePagesText.valueOrNotSet(config.repositoryId), Icons.Rounded.Tag)
            CloudflareOpsDetailRow("Source production branch", CloudflarePagesText.valueOrNotSet(config.productionBranch), Icons.Rounded.AccountTree)
            CloudflareOpsDetailRow("Legacy deployment switch", CloudflarePagesText.enabled(config.deploymentsEnabled), Icons.Rounded.Bolt)
            CloudflareOpsDetailRow("Production deploys", CloudflarePagesText.enabled(config.productionDeploymentsEnabled), Icons.Rounded.CloudUpload)
            CloudflareOpsDetailRow(
                "Preview deploys",
                config.previewDeploymentSetting?.let(CloudflarePagesText::capitalized) ?: "Not set",
                Icons.Rounded.Layers,
            )
            CloudflareOpsDetailRow("Pull request comments", CloudflarePagesText.enabled(config.pullRequestCommentsEnabled), Icons.Rounded.TextFields)
            CloudflareOpsDetailRow(
                "Preview branches",
                CloudflarePagesText.ruleText(config.previewBranchIncludes, config.previewBranchExcludes),
                Icons.Rounded.AccountTree,
            )
            CloudflareOpsDetailRow("Path filters", CloudflarePagesText.ruleText(config.pathIncludes, config.pathExcludes), Icons.Rounded.Folder)
        }
    }
}

@Composable
private fun EnvironmentPanel(
    project: CloudflarePagesProjectDetail,
    environment: CloudflarePagesEnvironment,
    onSelect: (CloudflarePagesEnvironment) -> Unit,
) {
    val config = when (environment) {
        CloudflarePagesEnvironment.PRODUCTION -> project.deploymentConfigs?.production
        CloudflarePagesEnvironment.PREVIEW -> project.deploymentConfigs?.preview
    }
    CloudflareOpsPanel(accent = if (environment == CloudflarePagesEnvironment.PRODUCTION) 0.045f else 0.075f, testTag = "cloudflare.pages.environment") {
        CloudflareOpsSectionHeader("Runtime switchboard", Icons.Rounded.Tune)
        CloudflareOpsChoiceRow(
            options = CloudflarePagesEnvironment.entries,
            selected = environment,
            label = { it.label },
            onSelect = onSelect,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            testTagPrefix = "cloudflare.pages.environmentChoice",
        )
        CloudflareOpsDivider()
        if (config == null) {
            CloudflareOpsEmptySection(
                Icons.Rounded.Tune,
                "No environment configuration",
                "Cloudflare returned no ${environment.wireValue} runtime settings.",
            )
        } else {
            EnvironmentCore(config)
            CloudflareOpsDivider()
            CloudflareOpsSectionHeader("Environment variables", Icons.Rounded.TextFields, count = config.environmentVariables.size)
            if (config.environmentVariables.isEmpty()) {
                MutedLine("No variables configured")
            } else {
                config.environmentVariables.keys.sorted().forEach { key ->
                    val variable = config.environmentVariables.getValue(key)
                    CloudflareOpsResourceRow(
                        icon = if (variable.isSecret) Icons.Rounded.Lock else Icons.Rounded.TextFields,
                        title = key,
                        subtitle = if (variable.isSecret) "Secret · value hidden" else "Plain text · value hidden",
                        tint = if (variable.isSecret) CloudflareOpsColors.Amber else CloudflareOpsColors.Orange,
                    ) {
                        CloudflareOpsStatusPill(
                            if (variable.valueConfigured) "SET" else "EMPTY",
                            if (variable.valueConfigured) CloudflareOpsColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            CloudflareOpsDivider()
            CloudflareOpsSectionHeader("Function bindings", Icons.Rounded.Link, count = config.bindingCount)
            if (config.bindingGroups.isEmpty()) {
                MutedLine("No resource bindings configured")
            } else {
                config.bindingGroups.forEach { (group, references) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(group.uppercase(), style = MaterialTheme.typography.labelSmall, color = CloudflareOpsColors.Orange)
                        references.keys.sorted().forEach { key ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    references.getValue(key).summary.ifEmpty { "Configured" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentCore(config: CloudflarePagesDeploymentConfiguration) {
    CloudflareOpsDetailRow("Compatibility date", CloudflarePagesText.valueOrNotSet(config.compatibilityDate), Icons.Rounded.CalendarToday)
    CloudflareOpsDetailRow("Compatibility flags", config.compatibilityFlags.joinToString(", ").ifEmpty { "None" }, Icons.Rounded.Tag)
    CloudflareOpsDetailRow("Always latest date", CloudflarePagesText.enabled(config.alwaysUseLatestCompatibilityDate), Icons.Rounded.Replay)
    CloudflareOpsDetailRow("Build image", config.buildImageMajorVersion?.let { "Version $it" } ?: "Not set", Icons.Rounded.Inventory2)
    CloudflareOpsDetailRow("Usage model", config.usageModel?.let(CloudflarePagesText::capitalized) ?: "Standard", Icons.Rounded.Tune)
    CloudflareOpsDetailRow("Fail open", CloudflarePagesText.enabled(config.failOpen), Icons.Rounded.WarningAmber)
    CloudflareOpsDetailRow("CPU limit", config.cpuMilliseconds?.let { "$it ms" } ?: "Default", Icons.Rounded.HourglassTop)
    CloudflareOpsDetailRow("Placement", config.placementMode?.let(CloudflarePagesText::capitalized) ?: "Default", Icons.Rounded.Public)
    CloudflareOpsDetailRow("Wrangler config hash", CloudflarePagesText.valueOrNotSet(config.wranglerConfigHash), Icons.Rounded.Tag, monospace = true)
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DomainsPanel(
    domains: List<CloudflarePagesCustomDomain>,
    error: String?,
    working: Set<String>,
    onAdd: () -> Unit,
    onOpen: (CloudflarePagesCustomDomain) -> Unit,
    onRetry: (CloudflarePagesCustomDomain) -> Unit,
    onDelete: (CloudflarePagesCustomDomain) -> Unit,
) {
    CloudflareOpsPanel(testTag = "cloudflare.pages.domains") {
        CloudflareOpsSectionHeader(
            "Custom domains",
            Icons.Rounded.Language,
            count = domains.size,
            actionTitle = "Add domain",
            actionTestTag = "cloudflare.pages.addDomain",
            onAction = onAdd,
        )
        CloudflareOpsDivider()
        when {
            error != null && domains.isEmpty() -> CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "Domains unavailable", error)
            domains.isEmpty() -> CloudflareOpsEmptySection(
                Icons.Rounded.Language,
                "No custom domains",
                "Add a hostname to start Cloudflare validation and certificate issuance.",
            )
            else -> domains.forEachIndexed { index, domain ->
                val tint = CloudflarePagesColors.domain(domain)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("cloudflare.pages.domain.${domain.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { onOpen(domain) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CloudflareOpsIconTile(if (domain.isActive) Icons.Rounded.Verified else Icons.Rounded.HourglassTop, tint)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(domain.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                domain.validationErrorMessage
                                    ?: "Certificate: ${CloudflarePagesText.certificateAuthority(domain, "pending")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        CloudflareOpsStatusPill((domain.status ?: "unknown").uppercase(), tint)
                    }
                    if (domain.id in working) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                    } else {
                        CloudflarePagesOverflowMenu(
                            contentDescription = "Actions for ${domain.name}",
                            testTag = "cloudflare.pages.domain.${domain.name}.menu",
                            actions = listOf(
                                CloudflarePagesMenuAction("View details", Icons.Rounded.Info) { onOpen(domain) },
                                CloudflarePagesMenuAction(
                                    "Retry validation",
                                    Icons.Rounded.Replay,
                                    testTag = "cloudflare.pages.domain.${domain.name}.retry",
                                ) { onRetry(domain) },
                                CloudflarePagesMenuAction(
                                    "Remove domain",
                                    Icons.Rounded.Delete,
                                    destructive = true,
                                    testTag = "cloudflare.pages.domain.${domain.name}.delete",
                                ) { onDelete(domain) },
                            ),
                        )
                    }
                }
                if (index < domains.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
}

@Composable
private fun DeployPanel(
    project: CloudflarePagesProjectDetail,
    state: CloudflarePagesOperationsState,
    deploymentsPath: String,
    uploading: Boolean,
    isWorking: Boolean,
    onUpload: () -> Unit,
    onCancelUpload: () -> Unit,
    onRedeploy: () -> Unit,
    onGuide: () -> Unit,
) {
    CloudflareOpsPanel(accent = 0.055f, testTag = "cloudflare.pages.deploy") {
        CloudflareOpsSectionHeader("Deploy a build", Icons.Rounded.CloudUpload)
        CloudflareOpsDivider()
        project.latestDeployment?.let { latest ->
            CloudflareOpsDetailRow("Latest deployment", CloudflarePagesText.deploymentLabel(latest), Icons.Rounded.Layers)
            CloudflareOpsDetailRow("Latest URL", CloudflarePagesText.valueOrNotSet(latest.url), Icons.Rounded.Public)
            CloudflareOpsDetailRow(
                "Latest source",
                listOfNotNull(latest.branch, latest.commitHash?.take(10)).joinToString(" · ").ifEmpty { "Direct upload" },
                Icons.Rounded.AccountTree,
            )
            CloudflareOpsDetailRow("Latest created", CloudflarePagesText.date(latest.createdDate), Icons.Rounded.CalendarToday)
            CloudflareOpsDivider()
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CloudflareOpsIconTile(Icons.Rounded.CloudUpload, size = 40)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Direct upload", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Choose a prebuilt folder and Verceltics will hash, deduplicate and upload every asset directly to Cloudflare Pages. Binary files stay intact and are sent only to Cloudflare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        deploymentsPath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.uploadProgress?.let { progress ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .testTag("cloudflare.pages.uploadProgress"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                    Text(progress.message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                progress.fractionCompleted?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth(), color = CloudflareOpsColors.Orange)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (uploading) {
                CloudflareOpsActionButton("Cancel upload", Icons.Rounded.Close, onClick = onCancelUpload, destructive = true, testTag = "cloudflare.pages.cancelUpload")
            } else {
                CloudflareOpsActionButton("Upload build", Icons.Rounded.Folder, onClick = onUpload, enabled = !isWorking, testTag = "cloudflare.pages.uploadBuild")
            }
            if (project.latestDeployment != null) {
                CloudflareOpsActionButton("Redeploy latest", Icons.Rounded.Replay, onClick = onRedeploy, enabled = !isWorking, testTag = "cloudflare.pages.redeploy")
            }
            CloudflareOpsActionButton("Upload guide", Icons.AutoMirrored.Rounded.MenuBook, onClick = onGuide)
        }
        CloudflareOpsDivider()
        CloudflareOpsDetailRow("Required multipart parts", CloudflarePagesDirectUploadContract.requiredParts.joinToString(", "), Icons.Rounded.Layers)
        CloudflareOpsDetailRow("Optional metadata", CloudflarePagesDirectUploadContract.optionalParts.joinToString(", "), Icons.Rounded.Add)
    }
}

@Composable
private fun MaintenancePanel(
    project: CloudflarePagesProjectDetail,
    isWorking: Boolean,
    onEdit: () -> Unit,
    onPurge: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEdit = CloudflarePagesProjectEditDraft.canSafelyEdit(project)
    CloudflareOpsPanel(testTag = "cloudflare.pages.maintenance") {
        CloudflareOpsSectionHeader("Project controls", Icons.Rounded.Settings)
        CloudflareOpsDivider()
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OperationRow(
                icon = Icons.Rounded.Tune,
                title = "Build and deploy settings",
                message = if (canEdit) {
                    "Edit the production branch, build pipeline and Git automation."
                } else {
                    "Cloudflare did not return every editable value, so this form stays read-only to avoid overwriting defaults."
                },
                enabled = canEdit && !isWorking,
                testTag = "cloudflare.pages.editSettings",
                onClick = onEdit,
            )
            OperationRow(
                icon = Icons.Rounded.DeleteSweep,
                title = "Purge build cache",
                message = "Clear cached dependencies and build artifacts before the next deployment.",
                enabled = !isWorking,
                testTag = "cloudflare.pages.purgeCache",
                onClick = onPurge,
            )
        }
        CloudflareOpsDivider()
        Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.04f).compositeOver(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Text("Danger zone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
                CloudflareOpsActionButton(
                    "Delete Pages project",
                    Icons.Rounded.Delete,
                    onClick = onDelete,
                    destructive = true,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "cloudflare.pages.deleteProject",
                )
            }
        }
    }
}

@Composable
private fun OperationRow(
    icon: ImageVector,
    title: String,
    message: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            CloudflareOpsIconTile(icon, size = 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
