package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerDeploymentInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.titleCased
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareWorkerUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
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

/** Port of iOS `CloudflareWorkerDetailView`. */
@Composable
fun CloudflareWorkerDetailScreen(
    viewModel: CloudflareWorkerDetailViewModel,
    fallback: CloudflareWorkerUi,
    refreshSignal: Int,
    onOpenOperations: () -> Unit,
    onWorkerDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val deleted by rememberUpdatedState(onWorkerDeleted)
    LaunchedEffect(state.didDeleteWorker) { if (state.didDeleteWorker) deleted() }
    CloudflareWorkerRefreshEffect(refreshSignal) { viewModel.load(forceRefresh = true) }
    CloudflareConfirmationHost(viewModel)

    val worker = state.worker
    val scriptName = viewModel.scriptName
    val hasModules = worker?.hasModules ?: fallback.hasModules
    val routes = worker?.routes.orEmpty()

    CloudflareOpsScreen(
        "cloudflare.workerDetail",
        modifier,
        maximumContentWidth = 850.dp,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = { viewModel.load(forceRefresh = true) },
    ) {
        item("hero") {
            CloudflareOpsHero(
                title = scriptName,
                subtitle = if (hasModules == true) "Modules Worker" else "Service Worker",
                icon = Icons.Rounded.Inventory2,
                status = "DEPLOYED",
                statusColor = CloudflareOpsColors.Green,
                testTag = "cloudflare.worker.hero",
            ) {
                routes.firstOrNull()?.let { workerUrl(it.pattern) }?.let { url ->
                    CloudflareOpsActionButton(
                        "Open route",
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        testTag = "cloudflare.worker.openRoute",
                    )
                }
                CloudflareOpsActionButton(
                    "Dashboard",
                    Icons.Rounded.Public,
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://dash.cloudflare.com/${viewModel.accountId}/workers/services/view/$scriptName/production")
                        }
                    },
                    testTag = "cloudflare.worker.dashboard",
                )
            }
        }
        state.workerError?.let { error ->
            item("worker-error") { CloudflareActionResultBanner(CloudflareActionBanner(error, isError = true)) }
        }
        item("runtime") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.runtime") {
                CloudflareOpsSectionHeader("Runtime", Icons.Rounded.Memory)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow(
                    "Compatibility date",
                    worker?.compatibilityDate ?: fallback.compatibilityDate ?: "Default",
                    icon = Icons.Rounded.CalendarMonth,
                )
                CloudflareOpsDetailRow("Format", if (hasModules == true) "ES modules" else "Service Worker", icon = Icons.Rounded.Inventory2)
                CloudflareOpsDetailRow(
                    "Static assets",
                    if ((worker?.hasAssets ?: fallback.hasAssets) == true) "Included" else "None detected",
                    icon = Icons.Rounded.Image,
                )
                CloudflareOpsDetailRow("Usage model", worker?.usageModel?.titleCased() ?: "Account default", icon = Icons.Rounded.Speed)
                worker?.compatibilityFlags?.takeIf { it.isNotEmpty() }?.let {
                    CloudflareOpsDetailRow("Compatibility flags", it.joinToString(", "), icon = Icons.Rounded.Flag)
                }
                (worker?.handlers ?: fallback.handlers).takeIf { it.isNotEmpty() }?.let {
                    CloudflareOpsDetailRow("Handlers", it.joinToString(", "), icon = Icons.Rounded.Bolt)
                }
            }
        }
        item("operations-link") {
            CloudflareOpsPanel(accent = 0.07f) {
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.Tune,
                    title = "Worker operations",
                    subtitle = "Versions, live logs, secrets, cron, domains and settings",
                    onClick = onOpenOperations,
                    testTag = "cloudflare.worker.operations",
                )
            }
        }
        item("routes") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.routes") {
                CloudflareOpsSectionHeader("Routes", Icons.Rounded.AltRoute, count = routes.size)
                CloudflareOpsDivider()
                if (routes.isEmpty()) {
                    CloudflareOpsEmptySection(
                        icon = Icons.Rounded.AltRoute,
                        title = "No routes returned",
                        message = "The Worker may use a workers.dev domain or custom domain instead.",
                    )
                } else {
                    routes.forEachIndexed { index, route ->
                        val url = workerUrl(route.pattern)
                        CloudflareOpsResourceRow(
                            icon = Icons.Rounded.AltRoute,
                            title = route.pattern,
                            subtitle = route.script ?: "Worker route",
                            tint = CloudflareOpsColors.Amber,
                            trailing = if (url == null) {
                                null
                            } else {
                                {
                                    IconButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = "Open ${route.pattern}",
                                            tint = CloudflareOpsColors.Orange,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                        )
                        if (index < routes.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("write-notice") { CloudflareWriteNotice() }
        banner?.let { current ->
            item("banner") { CloudflareActionResultBanner(current, onDismiss = viewModel::dismissBanner) }
        }
        item("deployments") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.deployments") {
                CloudflareOpsSectionHeader("Deployments", Icons.Rounded.Layers, count = state.deployments.size)
                CloudflareOpsDivider()
                when {
                    state.isLoading -> CloudflareOpsLoading()
                    state.deploymentsError != null && state.deployments.isEmpty() -> CloudflareOpsEmptySection(
                        icon = Icons.Rounded.WarningAmber,
                        title = "Deployments unavailable",
                        message = state.deploymentsError.orEmpty(),
                    )
                    state.deployments.isEmpty() -> CloudflareOpsEmptySection(
                        icon = Icons.Rounded.Layers,
                        title = "No deployments",
                        message = "Cloudflare did not return deployment history for this Worker.",
                    )
                    else -> state.deployments.forEachIndexed { index, deployment ->
                        DeploymentRow(
                            deployment = deployment,
                            working = deployment.id in working,
                            onDelete = { viewModel.requestDeleteDeployment(deployment) },
                        )
                        if (index < state.deployments.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("danger") {
            CloudflareOpsPanel(testTag = "cloudflare.worker.danger") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("Danger zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Deleting the Worker removes the script from Cloudflare and stops traffic routed to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CloudflareOpsActionButton(
                        "Delete Worker",
                        Icons.Rounded.Delete,
                        onClick = viewModel::requestDeleteWorker,
                        destructive = true,
                        working = scriptName in working,
                        testTag = "cloudflare.worker.delete",
                    )
                }
            }
        }
    }
}

@Composable
private fun DeploymentRow(deployment: CloudflareWorkerDeploymentInfo, working: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("cloudflare.worker.deployment.${deployment.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CloudflareOpsIconTile(Icons.Rounded.Layers, CloudflareOpsColors.Green)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                deployment.id.take(14),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                deploymentSubtitle(deployment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
        } else {
            IconButton(onClick = onDelete, modifier = Modifier.testTag("cloudflare.worker.deployment.${deployment.id}.delete")) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete deployment", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** iOS `deploymentSubtitle`. */
internal fun deploymentSubtitle(deployment: CloudflareWorkerDeploymentInfo): String = buildList {
    deployment.source?.takeIf(String::isNotEmpty)?.let { add(it.titleCased()) }
    deployment.strategy?.takeIf(String::isNotEmpty)?.let { add(it.titleCased()) }
    if (deployment.versions.isNotEmpty()) add("${deployment.versions.size} versions")
    deployment.authorEmail?.takeIf(String::isNotEmpty)?.let(::add)
}.joinToString(" · ").ifEmpty { "Worker deployment" }

/** iOS `workerURL(from:)`: strips wildcards and slashes and defaults to HTTPS. */
internal fun workerUrl(pattern: String): String? {
    val normalized = pattern.replace("*", "").trim('/')
    if (normalized.isEmpty()) return null
    return if (normalized.startsWith("http")) normalized else "https://$normalized"
}

/** Runs [onRefresh] when the Cloudflare top-bar refresh fires after this screen appeared. */
@Composable
internal fun CloudflareWorkerRefreshEffect(signal: Int, onRefresh: () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(signal) }
    val latest by rememberUpdatedState(onRefresh)
    LaunchedEffect(signal) {
        if (signal != handled) {
            handled = signal
            latest()
        }
    }
}
