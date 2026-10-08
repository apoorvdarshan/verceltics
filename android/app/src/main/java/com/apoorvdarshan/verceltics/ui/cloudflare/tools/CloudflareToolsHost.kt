package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDatasets
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsApi
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareConnectionStatus
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareUiState
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareViewModel

/** Renders the top Cloudflare tool screen. Back pops one level; the dashboard handles the rest. */
@Composable
fun CloudflareToolsHost(
    viewModel: CloudflareToolsViewModel,
    context: CloudflareToolsContext?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Each stacked screen keeps its own search text and scroll position while covered.
    val screenStates = rememberSaveableStateHolder()
    val stackKeys = state.stack.mapIndexed { index, item -> "$index:${CloudflareToolRoute.encode(item)}" }
    val retainedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(stackKeys) {
        retainedKeys.filter { it !in stackKeys }.forEach(screenStates::removeState)
        retainedKeys.retainAll(stackKeys.toSet())
        retainedKeys.addAll(stackKeys)
    }
    val route = state.route ?: return
    BackHandler { viewModel.pop() }
    val catalogLoaded = state.catalog is CloudflareToolLoad.Loaded
    LaunchedEffect(route, context?.accountId, catalogLoaded) {
        if (context != null) viewModel.ensureLoaded(route, context)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("cloudflare.tools"),
    ) {
        CloudflareToolsTopBar(
            title = toolTitle(route, state),
            onBack = { viewModel.pop() },
            trailing = { ToolTrailingAction(route, state, viewModel) },
        )
        val body = Modifier.weight(1f)
        if (context == null && route !is CloudflareToolRoute.Explorer) {
            ToolLoadingBlock("Opening Cloudflare account…", body)
            return@Column
        }
        screenStates.SaveableStateProvider(stackKeys.last()) {
            ToolRouteContent(route, state, context, viewModel, body)
        }
    }
}

/** The body for [route]; [body] carries the host column's weight. */
@Composable
private fun ToolRouteContent(
    route: CloudflareToolRoute,
    state: CloudflareToolsUiState,
    context: CloudflareToolsContext?,
    viewModel: CloudflareToolsViewModel,
    body: Modifier,
) {
    when (route) {
        is CloudflareToolRoute.Account -> CloudflareAccountDetailScreen(
            context = requireNotNull(context),
            detail = if (state.accountDetailId == route.accountId) state.accountDetail else CloudflareToolLoad.Loading,
            onRetry = { viewModel.loadAccountDetail(route.accountId) },
            onOpenOperations = { viewModel.push(CloudflareToolRoute.AccountOperations(route.accountId)) },
            modifier = body,
        )
        is CloudflareToolRoute.AccountOperations -> CloudflareAccountOperationsScreen(
            context = requireNotNull(context),
            state = state.accountOperations,
            onRetry = { viewModel.loadAccountOperations(route.accountId, forceRefresh = true) },
            modifier = body,
        )
        is CloudflareToolRoute.Catalog -> CatalogLoadGate(state.catalog, viewModel::reloadCatalog, body) { catalog ->
            CloudflareApiCatalogScreen(
                catalog = catalog,
                onOpenTag = { viewModel.push(CloudflareToolRoute.CatalogTag(route.accountId, it)) },
                onOpenOperation = { viewModel.push(CloudflareToolRoute.CatalogOperation(route.accountId, it)) },
                modifier = body,
            )
        }
        is CloudflareToolRoute.CatalogTag -> CatalogLoadGate(state.catalog, viewModel::reloadCatalog, body) { catalog ->
            CloudflareApiTagScreen(
                catalog = catalog,
                tag = route.tag,
                onOpenOperation = { viewModel.push(CloudflareToolRoute.CatalogOperation(route.accountId, it)) },
                modifier = body,
            )
        }
        is CloudflareToolRoute.CatalogOperation -> CatalogLoadGate(state.catalog, viewModel::reloadCatalog, body) { catalog ->
            val operation = catalog.operation(route.operationId)
            if (operation == null) {
                Column(body.padding(18.dp)) {
                    ToolBanner("This operation is no longer in the bundled Cloudflare API catalog.", isError = true)
                }
            } else {
                CloudflareApiOperationScreen(
                    operation = operation,
                    authMode = context?.authMode ?: CloudflareAuthMode.API_TOKEN,
                    editor = state.operationEditor,
                    onUpdateValue = viewModel::updateOperationValue,
                    onUpdateBody = viewModel::updateOperationBody,
                    onUpdateContentType = viewModel::updateOperationContentType,
                    onReview = { viewModel.reviewOperation(route.accountId, context?.authMode ?: CloudflareAuthMode.API_TOKEN) },
                    modifier = body,
                )
            }
        }
        is CloudflareToolRoute.GraphQL -> CloudflareGraphQLDatasetScreen(
            state = state.datasets,
            zones = requireNotNull(context).zones,
            onSelectScope = viewModel::selectDatasetScope,
            onSelectZone = viewModel::selectDatasetZone,
            onRetry = { viewModel.loadDatasets(forceRefresh = true) },
            onOpenDataset = { viewModel.push(CloudflareToolRoute.GraphQLDataset(route.accountId, it)) },
            modifier = body,
        )
        is CloudflareToolRoute.GraphQLDataset -> {
            val dataset = state.datasets.datasets.firstOrNull { it.name == route.datasetName }
            when {
                dataset != null -> CloudflareGraphQLDatasetDetailScreen(
                    dataset = dataset,
                    onOpenQuery = {
                        viewModel.openExplorer(
                            route.accountId,
                            CloudflareGraphQLDatasets.preset(dataset, state.datasets.scope, route.accountId, state.datasets.zoneId),
                        )
                    },
                    modifier = body,
                )
                state.datasets.isLoading -> ToolLoadingBlock("Discovering GraphQL datasets…", body)
                else -> Column(body.padding(18.dp)) {
                    ToolBanner(
                        state.datasets.error ?: "Cloudflare no longer reports this dataset for the selected scope.",
                        isError = true,
                        actionLabel = "Retry",
                        onAction = { viewModel.loadDatasets(forceRefresh = true) },
                    )
                }
            }
        }
        is CloudflareToolRoute.ProductCenter -> CloudflareProductCenterScreen(
            context = requireNotNull(context),
            selectedZoneId = state.productZoneId,
            onSelectZone = viewModel::selectProductZone,
            onOpenOperation = { preset -> viewModel.openExplorer(route.accountId, preset) },
            modifier = body,
        )
        is CloudflareToolRoute.Explorer -> CloudflareApiExplorerScreen(
            state = state.explorer,
            accountId = route.accountId,
            isOfflineSample = state.isOfflineSample,
            authMode = context?.authMode ?: CloudflareAuthMode.API_TOKEN,
            onSelectMethod = viewModel::selectMethod,
            onUpdatePath = { value -> viewModel.updateDraft { it.copy(path = value) } },
            onUpdateQuery = { value -> viewModel.updateDraft { it.copy(queryText = value) } },
            onUpdateHeaders = { value -> viewModel.updateDraft { it.copy(headerText = value) } },
            onUpdateBody = { value -> viewModel.updateDraft { it.copy(bodyText = value) } },
            onUpdateContentType = { value -> viewModel.updateDraft { it.copy(contentType = value) } },
            onUpdateEncoding = { value -> viewModel.updateDraft { it.copy(bodyEncoding = value) } },
            onQuickPath = viewModel::applyQuickPath,
            onExecute = viewModel::requestExecute,
            onConfirm = viewModel::confirmExecute,
            onDismissConfirmation = viewModel::dismissConfirmation,
            onCancel = viewModel::cancelExecution,
            onImportBody = viewModel::importBody,
            onApplyMultipart = viewModel::applyMultipartBody,
            onRemoveAttachment = viewModel::removeAttachment,
            onReportError = viewModel::reportExplorerError,
            modifier = body,
        )
    }
}

@Composable
private fun ToolTrailingAction(route: CloudflareToolRoute, state: CloudflareToolsUiState, viewModel: CloudflareToolsViewModel) {
    when (route) {
        is CloudflareToolRoute.Explorer -> TextButton(
            onClick = viewModel::clearResponse,
            enabled = state.explorer.canClear,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("cloudflare.explorer.clear"),
        ) {
            Text("Clear", style = MaterialTheme.typography.labelLarge)
        }
        is CloudflareToolRoute.Account -> ToolsRefreshAction(
            isLoading = state.accountDetail == CloudflareToolLoad.Loading,
            onRefresh = { viewModel.loadAccountDetail(route.accountId) },
            label = "Refresh account",
        )
        is CloudflareToolRoute.AccountOperations -> ToolsRefreshAction(
            isLoading = state.accountOperations.isLoading,
            onRefresh = { viewModel.loadAccountOperations(route.accountId, forceRefresh = true) },
            label = "Refresh account operations",
        )
        is CloudflareToolRoute.GraphQL -> ToolsRefreshAction(
            isLoading = state.datasets.isLoading,
            onRefresh = { viewModel.loadDatasets(forceRefresh = true) },
            label = "Refresh datasets",
        )
        else -> Unit
    }
}

internal fun toolTitle(route: CloudflareToolRoute, state: CloudflareToolsUiState): String = when (route) {
    is CloudflareToolRoute.Account -> "Account"
    is CloudflareToolRoute.AccountOperations -> "Account operations"
    is CloudflareToolRoute.Catalog -> "Complete API"
    is CloudflareToolRoute.CatalogTag -> route.tag
    is CloudflareToolRoute.CatalogOperation ->
        (state.catalog as? CloudflareToolLoad.Loaded)?.value?.operation(route.operationId)?.summary ?: "Operation"
    is CloudflareToolRoute.GraphQL -> "GraphQL Datasets"
    is CloudflareToolRoute.GraphQLDataset -> "Dataset"
    is CloudflareToolRoute.ProductCenter -> "Product Operations"
    is CloudflareToolRoute.Explorer -> state.explorer.title ?: "API Explorer"
}

/** Builds the tools context from the dashboard's selected account; null until one is loaded. */
fun cloudflareToolsContext(state: CloudflareUiState): CloudflareToolsContext? {
    val dashboard = state.dashboard ?: return null
    val account = dashboard.selectedAccount ?: return null
    val inventory = dashboard.inventory?.takeIf { it.accountId == account.id }
    return CloudflareToolsContext(
        accountId = account.id,
        accountName = account.name,
        accountType = account.type,
        // Every loaded zone is pickable in the tools (no display cap).
        zones = inventory?.zones.orEmpty().map { CloudflareToolsZone(it.id, it.name) },
        zoneCount = inventory?.loadedZoneCount ?: 0,
        pagesCount = inventory?.loadedPagesProjectCount ?: 0,
        workerCount = inventory?.loadedWorkerCount ?: 0,
        credentialLabel = dashboard.profile.credentialLabel,
        authMode = dashboard.authMode,
    )
}

/**
 * Creates the tools ViewModel beside the dashboard's. Live dashboards borrow their saved credential
 * and report successful writes back to the dashboard; offline sample dashboards get offline sample
 * tools that never read credentials.
 */
@Composable
fun rememberCloudflareToolsViewModel(dashboardViewModel: CloudflareViewModel): CloudflareToolsViewModel {
    val appContext = LocalContext.current.applicationContext
    val credentialSource = remember(dashboardViewModel) { dashboardViewModel.toolsCredentialSource }
    val factory = remember(dashboardViewModel, credentialSource) {
        val catalogStore = CloudflareToolsServices.catalogStore(appContext)
        CloudflareToolsViewModel.Factory(
            if (credentialSource != null) {
                NativeCloudflareToolsGateway(
                    credentialSource = credentialSource,
                    catalogStore = catalogStore,
                    api = CloudflareToolsApi(mutationSink = dashboardViewModel::publishToolsMutation),
                )
            } else {
                SampleCloudflareToolsGateway(catalogStore)
            },
        )
    }
    return viewModel(
        key = if (credentialSource != null) "cloudflare.tools" else "cloudflare.tools.offline",
        factory = factory,
    )
}

/**
 * Keeps the tool stack consistent with the dashboard and Pro access: tools close when access is
 * confirmed locked, when Cloudflare disconnects, or when another account is selected.
 */
@Composable
fun CloudflareToolsRouteEffects(
    tools: CloudflareToolsViewModel,
    isOpen: Boolean,
    dashboardState: CloudflareUiState,
    proAccess: ProAccess,
) {
    LaunchedEffect(proAccess.isConfirmedLocked, isOpen) {
        if (proAccess.isConfirmedLocked && isOpen) tools.closeAll()
    }
    val status = dashboardState.status
    val selectedAccountId = dashboardState.dashboard?.selectedAccountId
    LaunchedEffect(status, selectedAccountId, isOpen) {
        if (!isOpen || status == CloudflareConnectionStatus.RESTORING) return@LaunchedEffect
        tools.reconcile(
            selectedAccountId = selectedAccountId,
            connected = status == CloudflareConnectionStatus.CONNECTED && dashboardState.dashboard != null,
        )
    }
}

/** Advanced-section callbacks, each gated behind Verceltics Pro. */
fun cloudflareAdvancedTools(
    tools: CloudflareToolsViewModel,
    dashboardState: CloudflareUiState,
    proAccess: ProAccess,
    onOpenStorage: ((accountId: String) -> Unit)? = null,
): CloudflareAdvancedToolsUi? {
    if (dashboardState.status != CloudflareConnectionStatus.CONNECTED || dashboardState.dashboard == null) return null
    val accountId = dashboardState.dashboard.selectedAccount?.id
    val allowsR2 = dashboardState.dashboard.allowsR2
    fun gated(route: CloudflareToolRoute): () -> Unit = { proAccess.requestPro { tools.open(route) } }
    return CloudflareAdvancedToolsUi(
        onOpenAccount = accountId?.let { gated(CloudflareToolRoute.Account(it)) },
        onOpenCompleteApi = accountId?.let { gated(CloudflareToolRoute.Catalog(it)) },
        onOpenGraphQL = accountId?.let { gated(CloudflareToolRoute.GraphQL(it)) },
        onOpenProductCenter = accountId?.let { gated(CloudflareToolRoute.ProductCenter(it)) },
        onOpenExplorer = gated(CloudflareToolRoute.Explorer(accountId)),
        onOpenStorage = if (accountId != null && onOpenStorage != null) {
            { proAccess.requestPro { onOpenStorage(accountId) } }
        } else {
            null
        },
        storageSubtitle = if (allowsR2) {
            "D1 SQL, Workers KV and R2 object storage"
        } else {
            "D1 SQL and Workers KV · R2 requires a scoped token"
        },
    )
}
