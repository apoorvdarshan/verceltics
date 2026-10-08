package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareDashboardUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareResourceKind
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareResourceSelection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages.CloudflarePagesDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages.CloudflarePagesProjectDetailDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker.CloudflareWorkerDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker.CloudflareWorkerDetailDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone.CloudflareZoneDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone.CloudflareZoneDetailDestination
import com.apoorvdarshan.verceltics.ui.cloudflare.storage.CloudflareStorageDestination

/**
 * Everything an operations screen needs from the Cloudflare shell. [client] is null for sample data
 * and tests, in which case screens render [CloudflareOperationsUnavailable] instead of loading.
 */
@Stable
class CloudflareOperationsContext(
    val client: CloudflareRestClient?,
    val accountId: String?,
    val accountName: String?,
    /** Changes whenever the Cloudflare top-bar refresh is pressed on an operations screen. */
    val refreshSignal: Int,
    private val navigateAction: (CloudflareOperationsRoute) -> Unit,
    private val closeAction: () -> Unit,
    private val closeResourceAction: () -> Unit,
    private val inventoryChangedAction: () -> Unit,
    /** iOS `allowsR2`: R2 needs a scoped API token, so Global API Key connections hide it. */
    val allowsR2: Boolean = true,
) {
    /** Pushes [route]. Pro-gated: shows the paywall first when Pro is not active. */
    fun navigate(route: CloudflareOperationsRoute) = navigateAction(route)

    /** Pops the current operations screen (e.g. after deleting the resource it showed). */
    fun close() = closeAction()

    /** Closes the selected zone, Pages project or Worker and returns to the dashboard. */
    fun closeResource() = closeResourceAction()

    /** Asks the dashboard to reload its inventory after a create, rename or delete. */
    fun inventoryChanged() = inventoryChangedAction()
}

/**
 * Renders the top operations screen, or the selected resource's detail when nothing is pushed.
 * Each screen gets its own [ViewModelStore] from [navigator], so ViewModels are cleared when their
 * screen leaves the stack.
 */
@Composable
fun CloudflareOperationsHost(
    navigator: CloudflareOperationsNavigator,
    client: CloudflareRestClient?,
    dashboard: CloudflareDashboardUi?,
    selectedResource: CloudflareResourceSelection?,
    onCloseResource: () -> Unit,
    onInventoryChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stack by navigator.stack.collectAsStateWithLifecycle()
    val refreshSignal by navigator.refreshRequests.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val holder = rememberSaveableStateHolder()
    val knownKeys = remember { mutableSetOf<String>() }

    // Every operations screen is Pro: close restored screens once access is confirmed locked.
    LaunchedEffect(proAccess.isConfirmedLocked, stack.isEmpty()) {
        if (proAccess.isConfirmedLocked && stack.isNotEmpty()) navigator.clear()
    }
    LaunchedEffect(stack) {
        val live = stack.map(CloudflareOperationsRoute::key).toSet()
        (knownKeys - live).forEach(holder::removeState)
        knownKeys.retainAll(live)
        knownKeys.addAll(live)
    }

    val allowsR2 = dashboard?.allowsR2 ?: true
    val context = remember(client, dashboard?.selectedAccountId, dashboard?.selectedAccount?.name, refreshSignal, proAccess, allowsR2) {
        CloudflareOperationsContext(
            client = client,
            accountId = dashboard?.inventory?.accountId ?: dashboard?.selectedAccountId,
            accountName = dashboard?.selectedAccount?.name,
            refreshSignal = refreshSignal,
            navigateAction = { route -> proAccess.requestPro { navigator.push(route) } },
            closeAction = { navigator.pop() },
            closeResourceAction = onCloseResource,
            inventoryChangedAction = onInventoryChanged,
            allowsR2 = allowsR2,
        )
    }

    val route = stack.lastOrNull()
    val key = route?.key
        ?: selectedResource?.let { CloudflareOperationsNavigator.resourceKey(it.kind.name, it.id) }
        ?: return
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = navigator.storeFor(key)
        }
    }
    Box(modifier.fillMaxSize().testTag("cloudflare.resourceDetail")) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            holder.SaveableStateProvider(key) {
                val screenModifier = Modifier.fillMaxSize()
                if (route != null) {
                    when (route.domain) {
                        CloudflareOperationsDomain.ZONE -> CloudflareZoneDestination(route, context, screenModifier)
                        CloudflareOperationsDomain.PAGES -> CloudflarePagesDestination(route, context, screenModifier)
                        CloudflareOperationsDomain.WORKER -> CloudflareWorkerDestination(route, context, screenModifier)
                        CloudflareOperationsDomain.STORAGE -> CloudflareStorageDestination(route, context, screenModifier)
                    }
                } else if (selectedResource != null) {
                    val inventory = dashboard?.inventory
                    when (selectedResource.kind) {
                        CloudflareResourceKind.ZONE -> inventory?.zones?.firstOrNull { it.id == selectedResource.id }
                            ?.let { CloudflareZoneDetailDestination(it, context, screenModifier) }
                            ?: MissingResource(screenModifier)
                        CloudflareResourceKind.PAGES -> inventory?.pagesProjects?.firstOrNull { it.id == selectedResource.id }
                            ?.let { CloudflarePagesProjectDetailDestination(it, context, screenModifier) }
                            ?: MissingResource(screenModifier)
                        CloudflareResourceKind.WORKER -> inventory?.workers?.firstOrNull { it.id == selectedResource.id }
                            ?.let { CloudflareWorkerDetailDestination(it, context, screenModifier) }
                            ?: MissingResource(screenModifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingResource(modifier: Modifier) {
    CloudflareOpsScreen("cloudflare.missingResource", modifier) {
        item {
            CloudflareOpsPanel {
                CloudflareOpsEmptySection(
                    icon = Icons.Rounded.Info,
                    title = "Resource unavailable",
                    message = "This resource is no longer in the loaded Cloudflare inventory. Go back and refresh.",
                )
            }
        }
    }
}
