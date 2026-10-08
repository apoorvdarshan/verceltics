package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareWorkerUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsUnavailable
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyWorkerDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Root Worker detail (iOS `CloudflareWorkerDetailView`). */
@Composable
fun CloudflareWorkerDetailDestination(worker: CloudflareWorkerUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    val accountId = context.accountId
    if (client == null || accountId.isNullOrEmpty()) {
        CloudflareReadOnlyWorkerDetail(worker, context, modifier)
        return
    }
    val api = rememberWorkerApi(client)
    val viewModel = viewModel(key = "cloudflare.worker.detail|$accountId|${worker.id}") {
        CloudflareWorkerDetailViewModel(api, accountId, worker.id, client.mutations)
    }
    CloudflareWorkerDetailScreen(
        viewModel = viewModel,
        fallback = worker,
        refreshSignal = context.refreshSignal,
        onOpenOperations = { context.navigate(CloudflareWorkerRoutes.operations(accountId, worker.id)) },
        onWorkerDeleted = {
            context.inventoryChanged()
            context.closeResource()
        },
        modifier = modifier,
    )
}

/** Pushed Worker screens: operations, version detail, source and live logs. */
@Composable
fun CloudflareWorkerDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    if (client == null) {
        CloudflareOpsScreen("cloudflare.worker.unavailable", modifier) { item { CloudflareOperationsUnavailable() } }
        return
    }
    val accountId = route.arg(0)
    val scriptName = route.arg(1)
    if (accountId.isEmpty() || scriptName.isEmpty()) {
        CloudflareUnknownOperationsScreen(modifier)
        return
    }
    val api = rememberWorkerApi(client)
    when (route.screen) {
        CloudflareWorkerRoutes.OPERATIONS -> {
            val viewModel = viewModel(key = route.key) { CloudflareWorkerOperationsViewModel(api, accountId, scriptName) }
            CloudflareWorkerOperationsScreen(
                viewModel = viewModel,
                refreshSignal = context.refreshSignal,
                onOpenSource = { context.navigate(CloudflareWorkerRoutes.content(accountId, scriptName)) },
                onOpenLiveLogs = { context.navigate(CloudflareWorkerRoutes.liveTail(accountId, scriptName)) },
                onOpenVersion = { version ->
                    context.navigate(CloudflareWorkerRoutes.version(accountId, scriptName, version.id, version.displayTitle))
                },
                modifier = modifier,
            )
        }
        CloudflareWorkerRoutes.VERSION -> {
            val versionId = route.arg(2)
            if (versionId.isEmpty()) {
                CloudflareUnknownOperationsScreen(modifier)
                return
            }
            val viewModel = viewModel(key = route.key) { CloudflareWorkerVersionViewModel(api, accountId, scriptName, versionId) }
            CloudflareWorkerVersionScreen(viewModel, context.refreshSignal, modifier)
        }
        CloudflareWorkerRoutes.CONTENT -> {
            val viewModel = viewModel(key = route.key) { CloudflareWorkerContentViewModel(api, accountId, scriptName) }
            CloudflareWorkerContentScreen(viewModel, context.refreshSignal, modifier)
        }
        CloudflareWorkerRoutes.LIVE_TAIL -> {
            val viewModel = viewModel(key = route.key) { CloudflareWorkerLiveTailViewModel(api, accountId, scriptName) }
            CloudflareWorkerLiveTailScreen(viewModel, modifier)
        }
        else -> CloudflareUnknownOperationsScreen(modifier)
    }
}

@Composable
private fun rememberWorkerApi(client: CloudflareRestClient): CloudflareWorkerOperationsApi =
    remember(client) { CloudflareWorkerOperationsApi(client) }
