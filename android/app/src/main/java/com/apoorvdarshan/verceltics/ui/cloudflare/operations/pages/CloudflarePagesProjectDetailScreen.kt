package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeployment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflarePagesProjectUi
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
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice

/** Creates the project detail ViewModel for the selected Pages project and renders it. */
@Composable
internal fun CloudflarePagesProjectDetailRoute(
    summary: CloudflarePagesProjectUi,
    client: CloudflareRestClient,
    accountId: String,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = "cloudflare.pages.detail|$accountId|${summary.name}") {
        CloudflarePagesProjectDetailViewModel(client, accountId, summary.name)
    }
    CloudflarePagesRefreshEffect(context.refreshSignal) { viewModel.load() }
    CloudflarePagesProjectDetailScreen(
        viewModel = viewModel,
        summary = summary,
        onOpenOperations = { context.navigate(CloudflarePagesRoutes.operations(accountId, summary.name)) },
        onOpenDeployment = { deployment -> context.navigate(CloudflarePagesRoutes.deployment(accountId, summary.name, deployment)) },
        modifier = modifier,
    )
}

/** iOS `CloudflarePagesProjectDetailView`. */
@Composable
fun CloudflarePagesProjectDetailScreen(
    viewModel: CloudflarePagesProjectDetailViewModel,
    summary: CloudflarePagesProjectUi,
    onOpenOperations: () -> Unit,
    onOpenDeployment: (CloudflarePagesDeployment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val project = state.project
    val name = project?.name ?: summary.name
    val domains = project?.domains ?: summary.domains
    val subdomain = project?.subdomain ?: summary.subdomain

    CloudflareOpsScreen("cloudflare.pages.detail", modifier) {
        item("hero") {
            val environment = project?.latestDeployment?.environment
            CloudflareOpsHero(
                title = name,
                subtitle = domains.firstOrNull() ?: subdomain ?: "Cloudflare Pages",
                icon = Icons.Rounded.Web,
                status = environment?.wireValue?.uppercase(),
                statusColor = if (environment == CloudflarePagesEnvironment.PRODUCTION) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
                testTag = "cloudflare.pages.hero",
            ) {
                (domains.firstOrNull() ?: subdomain)?.let { host ->
                    CloudflareOpsActionButton("Open site", Icons.AutoMirrored.Rounded.OpenInNew, onClick = { uriHandler.openPagesUrl(host) })
                }
                CloudflareOpsActionButton(
                    "Dashboard",
                    Icons.Rounded.Dashboard,
                    onClick = { uriHandler.openPagesUrl("https://dash.cloudflare.com/${viewModel.accountId}/pages/view/$name") },
                )
            }
        }
        state.projectError?.let { error ->
            item("project-error") {
                CloudflarePagesFeedbackCard(
                    title = "Project refresh failed",
                    message = "$error Showing the last successful project details.",
                    actionTitle = "Retry",
                    onAction = viewModel::load,
                )
            }
        }
        item("project") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Project", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Project ID", project?.id ?: summary.id, Icons.Rounded.Tag, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Production branch", project?.productionBranch ?: summary.productionBranch ?: "Not set", Icons.Rounded.AccountTree)
                CloudflareOpsDetailRow("Functions", if (project?.usesFunctions == true) "Enabled" else "Not detected", Icons.Rounded.Functions)
                project?.framework?.takeIf(String::isNotEmpty)?.let { framework ->
                    CloudflareOpsDetailRow("Framework", listOfNotNull(framework, project.frameworkVersion).joinToString(" "), Icons.Rounded.Inventory2)
                }
                CloudflareOpsDetailRow("Domains", domains.joinToString(", ").ifEmpty { "None" }, Icons.Rounded.Language)
            }
        }
        item("operations") {
            CloudflareOpsPanel(accent = 0.07f) {
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.Tune,
                    title = "Pages operations",
                    subtitle = "Domains, builds, bindings, deployments and project settings",
                    testTag = "cloudflare.pages.openOperations",
                    onClick = onOpenOperations,
                )
            }
        }
        item("write-notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, onDismiss = viewModel::dismissBanner) } }
        item("deployments") {
            CloudflareOpsPanel(testTag = "cloudflare.pages.deployments") {
                CloudflareOpsSectionHeader("Deployments", Icons.Rounded.Layers, count = state.deployments.size)
                CloudflareOpsChoiceRow(
                    options = listOf(null, CloudflarePagesEnvironment.PRODUCTION, CloudflarePagesEnvironment.PREVIEW),
                    selected = state.environmentFilter,
                    label = { it?.label ?: "All" },
                    onSelect = viewModel::selectEnvironment,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    testTagPrefix = "cloudflare.pages.deploymentFilter",
                )
                CloudflareOpsDivider()
                when {
                    state.isLoading || (!state.hasLoadedDeployments && state.deploymentsError == null) ->
                        CloudflareOpsLoading("Loading deployments…")
                    state.deploymentsError != null && state.deployments.isEmpty() -> CloudflareOpsEmptySection(
                        Icons.Rounded.WarningAmber,
                        "Deployments unavailable",
                        requireNotNull(state.deploymentsError),
                    )
                    state.deployments.isEmpty() -> CloudflareOpsEmptySection(
                        Icons.Rounded.Layers,
                        "No deployments",
                        "This Pages project has no deployments.",
                    )
                    else -> {
                        state.deploymentsError?.let { error ->
                            Column(Modifier.padding(12.dp)) {
                                CloudflarePagesFeedbackCard(
                                    title = "Deployment refresh failed",
                                    message = "$error Showing the last successful result.",
                                    actionTitle = "Retry",
                                    onAction = viewModel::load,
                                )
                            }
                        }
                        state.deployments.forEachIndexed { index, deployment ->
                            CloudflarePagesDeploymentRow(
                                deployment = deployment,
                                isWorking = deployment.id in working,
                                onOpen = { onOpenDeployment(deployment) },
                                onOpenUrl = { uriHandler.openPagesUrl(deployment.url) },
                                onRetry = { viewModel.requestRetry(deployment) },
                                onRollback = { viewModel.requestRollback(deployment) },
                                onDelete = { viewModel.requestDelete(deployment) },
                            )
                            if (index < state.deployments.lastIndex) CloudflareOpsDivider(inset = true)
                        }
                    }
                }
            }
        }
    }
    CloudflareConfirmationHost(viewModel)
}

@Composable
internal fun CloudflarePagesDeploymentRow(
    deployment: CloudflarePagesDeployment,
    isWorking: Boolean,
    onOpen: () -> Unit,
    onOpenUrl: () -> Unit,
    onRetry: () -> Unit,
    onRollback: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = CloudflarePagesColors.deployment(deployment)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("cloudflare.pages.deployment.${deployment.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudflareOpsIconTile(if (deployment.isSkipped == true) Icons.Rounded.FastForward else Icons.Rounded.Layers, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    deployment.displayId,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    deployment.url ?: deployment.aliases.firstOrNull() ?: deployment.environment?.wireValue ?: "Deployment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (isWorking) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
        } else {
            CloudflarePagesOverflowMenu(
                contentDescription = "Actions for deployment ${deployment.displayId}",
                testTag = "cloudflare.pages.deployment.${deployment.id}.menu",
                actions = listOfNotNull(
                    deployment.url?.let {
                        CloudflarePagesMenuAction("Open deployment", Icons.AutoMirrored.Rounded.OpenInNew, onClick = onOpenUrl)
                    },
                    CloudflarePagesMenuAction(
                        "Retry deployment",
                        Icons.Rounded.Replay,
                        testTag = "cloudflare.pages.deployment.${deployment.id}.retry",
                        onClick = onRetry,
                    ),
                    CloudflarePagesMenuAction(
                        "Roll back production",
                        Icons.AutoMirrored.Rounded.Undo,
                        testTag = "cloudflare.pages.deployment.${deployment.id}.rollback",
                        onClick = onRollback,
                    ),
                    CloudflarePagesMenuAction(
                        "Delete deployment",
                        Icons.Rounded.Delete,
                        destructive = true,
                        testTag = "cloudflare.pages.deployment.${deployment.id}.delete",
                        onClick = onDelete,
                    ),
                ),
            )
        }
    }
}

/** iOS `AppFeedbackBanner` with a warning tint and optional retry. */
@Composable
internal fun CloudflarePagesFeedbackCard(
    title: String,
    message: String,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = CloudflareOpsColors.Amber
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionTitle != null && onAction != null) {
                Text(
                    actionTitle,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(vertical = 10.dp),
                    color = CloudflareOpsColors.Orange,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Runs [onRefresh] each time the Cloudflare top-bar refresh fires after this screen appeared. */
@Composable
internal fun CloudflarePagesRefreshEffect(signal: Int, onRefresh: () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(signal) }
    val latest by rememberUpdatedState(onRefresh)
    LaunchedEffect(signal) {
        if (signal != handled) {
            handled = signal
            latest()
        }
    }
}
