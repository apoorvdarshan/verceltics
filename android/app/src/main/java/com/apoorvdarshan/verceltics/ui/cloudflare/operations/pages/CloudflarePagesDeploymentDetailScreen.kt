package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeployment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeploymentLog
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesStage
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflarePagesDeploymentDetailState(
    val deployment: CloudflarePagesDeployment? = null,
    val logs: List<CloudflarePagesDeploymentLog> = emptyList(),
    val isLoading: Boolean = true,
    /** A reload over an already loaded deployment (pull-to-refresh / toolbar refresh). */
    val isRefreshing: Boolean = false,
    val detailError: String? = null,
    val logsError: String? = null,
    val deleted: Boolean = false,
)

/** iOS `CloudflarePagesDeploymentDetailViewModel`, plus the deployment actions iOS offers in the list. */
class CloudflarePagesDeploymentDetailViewModel(
    private val api: CloudflarePagesApi,
    val accountId: String,
    val projectName: String,
    val deploymentId: String,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflarePagesDeploymentDetailState())
    val state: StateFlow<CloudflarePagesDeploymentDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update {
            it.copy(isLoading = it.deployment == null, isRefreshing = it.deployment != null, detailError = null, logsError = null)
        }
        loadJob = viewModelScope.launch {
            val (detail, logs) = coroutineScope {
                val detail = async { capture { api.fetchDeployment(accountId, projectName, deploymentId) } }
                val logs = async { capture { api.fetchDeploymentLogs(accountId, projectName, deploymentId) } }
                detail.await() to logs.await()
            }
            _state.update { current ->
                current.copy(
                    deployment = detail.getOrNull() ?: current.deployment,
                    detailError = detail.exceptionOrNull()?.let(::cloudflareUserMessage),
                    logs = logs.getOrNull() ?: current.logs,
                    logsError = logs.exceptionOrNull()?.let(::cloudflareUserMessage),
                    isLoading = false,
                    isRefreshing = false,
                )
            }
        }
    }

    fun requestRetry() {
        val deployment = _state.value.deployment ?: return
        requestConfirmation(CloudflarePagesPrompts.retry(projectName, deployment)) { confirmation ->
            api.retryDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Deployment retry started.")
            load()
        }
    }

    fun requestRollback() {
        val deployment = _state.value.deployment ?: return
        requestConfirmation(CloudflarePagesPrompts.rollback(projectName, deployment)) { confirmation ->
            api.rollbackDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Production rollback started.")
            load()
        }
    }

    fun requestDelete() {
        val deployment = _state.value.deployment ?: return
        requestConfirmation(CloudflarePagesPrompts.deleteDeployment(projectName, deployment)) { confirmation ->
            api.deleteDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Deployment deleted.")
            _state.update { it.copy(deleted = true) }
        }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

@Composable
internal fun CloudflarePagesDeploymentDetailRoute(
    client: CloudflareRestClient,
    accountId: String,
    projectName: String,
    deploymentId: String,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = "cloudflare.pages.deployment|$accountId|$projectName|$deploymentId") {
        CloudflarePagesDeploymentDetailViewModel(CloudflarePagesApi(client), accountId, projectName, deploymentId)
    }
    CloudflarePagesRefreshEffect(context.refreshSignal) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(context::close)
    LaunchedEffect(state.deleted) { if (state.deleted) close() }
    CloudflarePagesDeploymentDetailScreen(viewModel, modifier)
}

/** iOS `CloudflarePagesDeploymentDetailView`: identity, source, stages, configuration and logs. */
@Composable
fun CloudflarePagesDeploymentDetailScreen(viewModel: CloudflarePagesDeploymentDetailViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val item = state.deployment

    CloudflareOpsScreen(
        "cloudflare.pages.deploymentDetail",
        modifier,
        maximumContentWidth = 850.dp,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = viewModel::load,
    ) {
        if (item == null) {
            item("loading") {
                if (state.isLoading) {
                    CloudflareOpsLoading("Loading deployment…")
                } else {
                    CloudflareOpsPanel {
                        CloudflareOpsEmptySection(
                            Icons.Rounded.WarningAmber,
                            "Deployment unavailable",
                            state.detailError ?: "Cloudflare did not return this deployment.",
                        )
                    }
                }
            }
            return@CloudflareOpsScreen
        }
        item("hero") {
            val color = CloudflarePagesColors.stage(item.displayStatus)
            CloudflareOpsHero(
                title = item.displayId,
                subtitle = item.branch ?: item.url ?: viewModel.projectName,
                icon = if (item.isSkipped == true) Icons.Rounded.FastForward else Icons.Rounded.Layers,
                status = item.displayStatus.uppercase(),
                statusColor = color,
                testTag = "cloudflare.pages.deploymentHero",
            ) {
                item.url?.let { url ->
                    CloudflareOpsActionButton("Open deployment", Icons.AutoMirrored.Rounded.OpenInNew, onClick = { uriHandler.openPagesUrl(url) })
                }
                val busy = item.id in working
                CloudflareOpsActionButton("Retry", Icons.Rounded.Replay, onClick = viewModel::requestRetry, working = busy, testTag = "cloudflare.pages.deploymentDetail.retry")
                CloudflareOpsActionButton("Roll back", Icons.AutoMirrored.Rounded.Undo, onClick = viewModel::requestRollback, destructive = true, enabled = !busy, testTag = "cloudflare.pages.deploymentDetail.rollback")
                CloudflareOpsActionButton("Delete", Icons.Rounded.Delete, onClick = viewModel::requestDelete, destructive = true, enabled = !busy, testTag = "cloudflare.pages.deploymentDetail.delete")
            }
        }
        item("write-notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, onDismiss = viewModel::dismissBanner) } }
        item("identity") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Deployment", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Deployment ID", item.id, Icons.Rounded.Tag, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Project", item.projectName ?: viewModel.projectName, Icons.Rounded.Folder)
                CloudflareOpsDetailRow("Environment", CloudflarePagesText.environmentLabel(item.environment), Icons.Rounded.Inventory2)
                CloudflareOpsDetailRow("URL", item.url ?: "Not returned", Icons.Rounded.Link, copyable = item.url != null)
                CloudflareOpsDetailRow("Aliases", item.aliases.joinToString(", ").ifEmpty { "None" }, Icons.Rounded.Link)
                CloudflareOpsDetailRow("Functions", if (item.usesFunctions == true) "Used" else "Not detected", Icons.Rounded.Functions)
                CloudflareOpsDetailRow("Created", CloudflarePagesText.date(item.createdDate, "Not returned"), Icons.Rounded.CalendarToday)
                CloudflareOpsDetailRow("Modified", CloudflarePagesText.date(item.modifiedDate, "Not returned"), Icons.Rounded.Schedule)
                state.detailError?.let { CloudflareOpsDetailRow("Refresh warning", it, Icons.Rounded.WarningAmber) }
            }
        }
        item("source") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Source", Icons.Rounded.AccountTree)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Trigger", item.triggerType ?: "Unknown", Icons.Rounded.Bolt)
                CloudflareOpsDetailRow("Branch", item.branch ?: "Not returned", Icons.Rounded.AccountTree)
                CloudflareOpsDetailRow("Commit", item.commitHash ?: "Not returned", Icons.Rounded.Tag, monospace = true)
                CloudflareOpsDetailRow("Message", item.commitMessage ?: "Not returned", Icons.Rounded.Description)
                CloudflareOpsDetailRow("Dirty working tree", if (item.commitDirty == true) "Yes" else "No", Icons.Rounded.Build)
            }
        }
        item("stages") {
            val stages = item.stages.ifEmpty { listOfNotNull(item.latestStage) }
            CloudflareOpsPanel(testTag = "cloudflare.pages.stages") {
                CloudflareOpsSectionHeader("Build stages", Icons.Rounded.Layers, count = stages.size)
                CloudflareOpsDivider()
                if (stages.isEmpty()) {
                    CloudflareOpsEmptySection(
                        Icons.Rounded.Layers,
                        "No stages returned",
                        "Cloudflare did not include build-stage history for this deployment.",
                    )
                } else {
                    stages.forEachIndexed { index, stage ->
                        val color = CloudflarePagesColors.stage(stage.status)
                        CloudflareOpsResourceRow(
                            icon = stageIcon(stage.status),
                            title = stage.name?.let(CloudflarePagesText::capitalized) ?: "Stage ${index + 1}",
                            subtitle = stageTime(stage),
                            tint = color,
                        ) {
                            CloudflareOpsStatusPill((stage.status ?: "unknown").uppercase(), color)
                        }
                        if (index < stages.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("configuration") {
            val config = item.buildConfig
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Build configuration", Icons.Rounded.Build)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Build command", config?.buildCommand ?: "Not set", Icons.Rounded.Terminal, monospace = true)
                CloudflareOpsDetailRow("Destination", config?.destinationDirectory ?: "Not set", Icons.Rounded.Folder)
                CloudflareOpsDetailRow("Root directory", config?.rootDirectory ?: "Not set", Icons.Rounded.Folder)
                CloudflareOpsDetailRow("Build cache", if (config?.buildCaching == true) "Enabled" else "Off", Icons.Rounded.Inventory2)
                CloudflareOpsDetailRow("Web Analytics tag", config?.webAnalyticsTag ?: "Not set", Icons.Rounded.Tag)
                CloudflareOpsDetailRow(
                    "Environment keys",
                    item.environmentVariableNames.joinToString(", ").ifEmpty { "None returned" },
                    Icons.Rounded.Key,
                )
            }
        }
        item("logs") {
            CloudflareOpsPanel(testTag = "cloudflare.pages.logs") {
                CloudflareOpsSectionHeader("Deployment logs", Icons.Rounded.Description, count = state.logs.size)
                CloudflareOpsDivider()
                when {
                    state.isLoading && state.logs.isEmpty() -> CloudflareOpsLoading()
                    state.logsError != null && state.logs.isEmpty() ->
                        CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "Logs unavailable", requireNotNull(state.logsError))
                    state.logs.isEmpty() -> CloudflareOpsEmptySection(
                        Icons.Rounded.Description,
                        "No logs returned",
                        "This deployment has no build-history logs available.",
                    )
                    else -> {
                        state.logsError?.let {
                            CloudflareOpsDetailRow("Refresh warning", "$it Showing cached logs.", Icons.Rounded.WarningAmber)
                        }
                        state.logs.forEach { log ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                log.date?.let { date ->
                                    Text(
                                        LOG_TIME.withZone(ZoneId.systemDefault()).format(date),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = CloudflareOpsColors.Orange.copy(alpha = 0.8f),
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        log.line,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            CloudflareOpsDivider()
                        }
                    }
                }
            }
        }
    }
    CloudflareConfirmationHost(viewModel)
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

private fun stageIcon(status: String?) = when (status?.lowercase()) {
    "success" -> Icons.Rounded.CheckCircle
    "failure", "canceled" -> Icons.Rounded.Cancel
    else -> Icons.Rounded.Schedule
}

/** iOS `stageTime`: duration when both ends are known, otherwise the start time. */
internal fun stageTime(stage: CloudflarePagesStage): String? {
    val start = CloudflareDates.parse(stage.startedOn)
    val end = CloudflareDates.parse(stage.endedOn)
    if (start != null && end != null) {
        val seconds = Duration.between(start, end).seconds.coerceAtLeast(0)
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }
    return start?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it) }
}
