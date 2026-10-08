package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsDomain
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Route factories for the Storage & databases area. */
object CloudflareStorageRoutes {
    const val DASHBOARD = "dashboard"

    /** The storage dashboard for [accountId] (iOS `CloudflareStorageDashboardView`). */
    fun dashboard(accountId: String, accountName: String?) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.STORAGE,
        screen = DASHBOARD,
        title = "Storage & databases",
        args = listOf(accountId, accountName.orEmpty()),
    )
}

/** Pushed storage screens (dashboard, D1, KV, R2, multipart composer). */
@Composable
fun CloudflareStorageDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareUnknownOperationsScreen(modifier)
}
