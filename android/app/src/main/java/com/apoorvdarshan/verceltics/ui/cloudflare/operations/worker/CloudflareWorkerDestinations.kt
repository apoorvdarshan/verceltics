package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareWorkerUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyWorkerDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Root Worker detail (iOS `CloudflareWorkerDetailView`). */
@Composable
fun CloudflareWorkerDetailDestination(worker: CloudflareWorkerUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareReadOnlyWorkerDetail(worker, context, modifier)
}

/** Pushed Worker screens (operations, versions, source, live logs, ...). */
@Composable
fun CloudflareWorkerDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareUnknownOperationsScreen(modifier)
}
