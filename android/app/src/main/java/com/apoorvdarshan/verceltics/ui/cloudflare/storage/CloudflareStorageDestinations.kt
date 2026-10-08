package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsDomain
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsUnavailable
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Route factories for the Storage & databases area. Args always start with the account id. */
object CloudflareStorageRoutes {
    const val DASHBOARD = "dashboard"
    const val D1 = "d1"
    const val KV = "kv"
    const val R2 = "r2"

    /** The storage dashboard for [accountId] (iOS `CloudflareStorageDashboardView`). */
    fun dashboard(accountId: String, accountName: String?) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.STORAGE,
        screen = DASHBOARD,
        title = "Storage & databases",
        args = listOf(accountId, accountName.orEmpty()),
    )

    fun d1(accountId: String, databaseId: String, name: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.STORAGE,
        screen = D1,
        title = name,
        args = listOf(accountId, databaseId, name),
    )

    fun kv(accountId: String, namespaceId: String, title: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.STORAGE,
        screen = KV,
        title = title,
        args = listOf(accountId, namespaceId, title),
    )

    fun r2(accountId: String, bucketName: String, jurisdiction: String?) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.STORAGE,
        screen = R2,
        title = bucketName,
        args = listOf(accountId, bucketName, jurisdiction.orEmpty()),
    )
}

/** Pushed storage screens: dashboard, D1 database, KV namespace and R2 bucket. */
@Composable
fun CloudflareStorageDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    if (client == null) {
        CloudflareOpsScreen("cloudflare.storage.unavailable", modifier) {
            item { CloudflareOperationsUnavailable() }
        }
        return
    }
    val accountId = route.arg(0)
    when (route.screen) {
        CloudflareStorageRoutes.DASHBOARD -> CloudflareStorageDashboardRoute(
            client = client,
            accountId = accountId,
            accountName = route.arg(1).ifEmpty { context.accountName.orEmpty() },
            context = context,
            routeKey = route.key,
            modifier = modifier,
        )
        CloudflareStorageRoutes.D1 -> CloudflareD1DatabaseRoute(client, accountId, route.arg(1), route.arg(2), context, route.key, modifier)
        CloudflareStorageRoutes.KV -> CloudflareKVNamespaceRoute(client, accountId, route.arg(1), route.arg(2), context, route.key, modifier)
        CloudflareStorageRoutes.R2 -> if (context.allowsR2) {
            CloudflareR2BucketRoute(
                client,
                accountId,
                route.arg(1),
                route.arg(2).ifEmpty { null },
                context,
                route.key,
                modifier,
            )
        } else {
            // A restored R2 route after reconnecting with a Global API Key: iOS never offers R2 there.
            CloudflareOpsScreen("cloudflare.storage.r2.requiresToken", modifier) {
                item {
                    CloudflareOpsPanel {
                        CloudflareOpsEmptySection(
                            icon = Icons.Rounded.Storage,
                            title = "R2 requires a scoped token",
                            message = CLOUDFLARE_R2_REQUIRES_TOKEN_MESSAGE,
                        )
                    }
                }
            }
        }
        else -> CloudflareUnknownOperationsScreen(modifier)
    }
}

/**
 * Dashboard entry for Storage & databases (iOS row in the account tools list). Wire it with
 * `onOpen = { proAccess.requestPro { viewModel.openStorage() } }`.
 */
@Composable
fun CloudflareStorageEntryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CloudflareOpsPanel(modifier = modifier, accent = 0.045f, testTag = "cloudflare.storage.entry") {
        CloudflareOpsResourceRow(
            icon = Icons.Rounded.Storage,
            title = "Storage & databases",
            subtitle = "D1 SQL, Workers KV and R2 object storage",
            tint = CloudflareOpsColors.Amber,
            onClick = onOpen,
            testTag = "cloudflare.storage.open",
        )
    }
}

internal fun storageApi(client: CloudflareRestClient) = CloudflareStorageApi(client)

/** Runs [onRefresh] each time the Cloudflare top-bar refresh fires after this screen appeared. */
@Composable
internal fun CloudflareStorageRefreshEffect(signal: Int, onRefresh: () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(signal) }
    val latest by rememberUpdatedState(onRefresh)
    LaunchedEffect(signal) {
        if (signal != handled) {
            handled = signal
            latest()
        }
    }
}

/** Uppercase label above a form section. */
@Composable
internal fun CloudflareStorageFormColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
internal fun rememberStorageFiles(): CloudflareStorageFiles {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { AndroidCloudflareStorageFiles(context.applicationContext.contentResolver) }
}
