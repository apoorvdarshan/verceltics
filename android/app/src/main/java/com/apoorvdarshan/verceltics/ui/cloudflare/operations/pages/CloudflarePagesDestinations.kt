package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflarePagesProjectUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyPagesDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Root Pages project detail (iOS `CloudflarePagesProjectDetailView`). */
@Composable
fun CloudflarePagesProjectDetailDestination(
    project: CloudflarePagesProjectUi,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    CloudflareReadOnlyPagesDetail(project, context, modifier)
}

/** Pushed Pages screens (operations, deployment detail, ...). */
@Composable
fun CloudflarePagesDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareUnknownOperationsScreen(modifier)
}
