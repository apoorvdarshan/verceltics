package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflarePagesProjectUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsUnavailable
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyPagesDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Root Pages project detail (iOS `CloudflarePagesProjectDetailView`). */
@Composable
fun CloudflarePagesProjectDetailDestination(
    project: CloudflarePagesProjectUi,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val client = context.client
    val accountId = context.accountId
    if (client == null || accountId.isNullOrEmpty()) {
        CloudflareReadOnlyPagesDetail(project, context, modifier)
        return
    }
    CloudflarePagesProjectDetailRoute(project, client, accountId, context, modifier)
}

/** Pushed Pages screens: operations (domains, settings, direct upload) and deployment detail. */
@Composable
fun CloudflarePagesDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    if (client == null) {
        CloudflareOpsScreen("cloudflare.pages.unavailable", modifier) {
            item { CloudflareOperationsUnavailable() }
        }
        return
    }
    val accountId = route.arg(0)
    val projectName = route.arg(1)
    if (accountId.isEmpty() || projectName.isEmpty()) {
        CloudflareUnknownOperationsScreen(modifier)
        return
    }
    when (route.screen) {
        CloudflarePagesRoutes.OPERATIONS -> CloudflarePagesOperationsRoute(client, accountId, projectName, context, modifier)
        CloudflarePagesRoutes.DEPLOYMENT -> route.arg(2).takeIf(String::isNotEmpty)
            ?.let { deploymentId -> CloudflarePagesDeploymentDetailRoute(client, accountId, projectName, deploymentId, context, modifier) }
            ?: CloudflareUnknownOperationsScreen(modifier)
        else -> CloudflareUnknownOperationsScreen(modifier)
    }
}
