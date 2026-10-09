package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountDetail
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountOperationsSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDataset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalogParser
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareRawResponse
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareAccountUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareCacheState
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareConnectionStatus
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareDashboardUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareInventoryUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareProfileUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareRestoreUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareUiGateway
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareUiState
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflareToolsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun advancedSectionListsToolsAndHidesStorageUntilWired() {
        val opened = mutableListOf<String>()
        val storageHook: (() -> Unit)? = null
        compose.setContent {
            VercelticsTheme {
                CloudflareAdvancedSection(
                    CloudflareAdvancedToolsUi(
                        onOpenAccount = { opened += "account" },
                        onOpenCompleteApi = { opened += "api" },
                        onOpenGraphQL = { opened += "graphql" },
                        onOpenProductCenter = { opened += "products" },
                        onOpenExplorer = { opened += "explorer" },
                        onOpenStorage = storageHook,
                    ),
                )
            }
        }
        compose.onNodeWithTag("cloudflare.advanced.storage").assertDoesNotExist()
        compose.onNodeWithText("Complete Cloudflare API").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.advanced.completeApi").performClick()
        compose.onNodeWithTag("cloudflare.advanced.graphql").performClick()
        compose.onNodeWithTag("cloudflare.advanced.productCenter").performClick()
        compose.onNodeWithTag("cloudflare.advanced.explorer").performClick()
        compose.runOnIdle { assertEquals(listOf("api", "graphql", "products", "explorer"), opened) }
    }

    @Test
    fun advancedSectionShowsStorageWhenTheHookIsProvided() {
        var storageOpened = false
        compose.setContent {
            VercelticsTheme {
                CloudflareAdvancedSection(
                    CloudflareAdvancedToolsUi(null, null, null, null, onOpenExplorer = {}, onOpenStorage = { storageOpened = true }),
                )
            }
        }
        compose.onNodeWithTag("cloudflare.advanced.completeApi").assertDoesNotExist()
        compose.onNodeWithTag("cloudflare.advanced.storage").performClick()
        compose.runOnIdle { assertTrue(storageOpened) }
    }

    @Test
    fun dashboardShowsAdvancedToolsAndAccountHeaderTap() {
        val opened = mutableListOf<String>()
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState(),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    advancedTools = CloudflareAdvancedToolsUi(
                        onOpenAccount = { opened += "account" },
                        onOpenCompleteApi = { opened += "api" },
                        onOpenGraphQL = {},
                        onOpenProductCenter = {},
                        onOpenExplorer = { opened += "explorer" },
                    ),
                )
            }
        }
        compose.onNodeWithTag("cloudflare.summary.openAccount").performClick()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.advanced"))
        compose.onNodeWithTag("cloudflare.advanced.explorer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "explorer"), opened) }
    }

    @Test
    fun explorerAsksBeforeWritesAndShowsTheResponse() {
        val gateway = ScriptedToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }

        compose.onNodeWithTag("cloudflare.explorer.method.DELETE").performClick()
        compose.onNodeWithTag("cloudflare.explorer.execute").performScrollTo().performClick()
        compose.onNodeWithTag("cloudflare.explorer.confirmation").assertIsDisplayed()
        compose.onNodeWithText("Send a destructive API request?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(gateway.executions.isEmpty()) }

        compose.onNodeWithTag("cloudflare.explorer.execute").performScrollTo().performClick()
        compose.onNodeWithText("Send DELETE Request").performClick()
        compose.waitUntil(5_000) { gateway.executions.isNotEmpty() && viewModel.uiState.value.explorer.response != null }
        compose.runOnIdle {
            assertEquals(CloudflareMutationConfirmation("/accounts"), gateway.executions.single().second)
        }
        // The status row merges into one polite live region, so the status text keeps its own tag
        // only in the unmerged tree.
        compose.onNodeWithTag("cloudflare.explorer.status", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTP 200").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.explorer.copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.explorer.clear").performClick()
        compose.onNodeWithTag("cloudflare.explorer.response").assertDoesNotExist()
    }

    @Test
    fun explorerExplainsTokenScopeFailures() {
        val gateway = ScriptedToolsGateway(status = 403)
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }

        compose.onNodeWithTag("cloudflare.explorer.path").performTextInput("/members")
        compose.onNodeWithTag("cloudflare.explorer.execute").performScrollTo().performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.explorer.response != null }
        compose.onNodeWithTag("cloudflare.explorer.scopeHint").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(gateway.executions.single().second) }
    }

    @Test
    fun productCenterOpensAResolvedExplorerPreset() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.ProductCenter("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }

        compose.onNodeWithText("Cloudflare control plane").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.productCenter")
            .performScrollToNode(hasTestTag("cloudflare.productCenter.operation.account-details"))
        compose.onNodeWithTag("cloudflare.productCenter.operation.account-details").performClick()
        compose.onNodeWithTag("cloudflare.explorer").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("/accounts/acc-1", viewModel.uiState.value.explorer.draft.path)
            assertEquals("Account details", viewModel.uiState.value.explorer.title)
        }
        compose.onNodeWithTag("cloudflare.tools.back").performClick()
        compose.onNodeWithTag("cloudflare.productCenter").assertIsDisplayed()
    }

    @Test
    fun productCenterLocksApiTokenOnlyPresetsForGlobalApiKeys() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.ProductCenter("acc-1"))
        val context = toolsContext().copy(
            credentialLabel = "owner@example.com",
            authMode = com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode.GLOBAL_API_KEY,
        )
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, context) } }

        // iOS labels the credential pill "GLOBAL" / "SCOPED".
        compose.onNodeWithText("GLOBAL").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.productCenter")
            .performScrollToNode(hasTestTag("cloudflare.productCenter.operation.account-tokens"))
        compose.onNodeWithTag("cloudflare.productCenter.operation.account-tokens").assertIsNotEnabled()
        compose.onNodeWithTag("cloudflare.productCenter.operation.account-tokens").performClick()
        compose.runOnIdle { assertTrue(viewModel.uiState.value.route is CloudflareToolRoute.ProductCenter) }

        // Presets that accept any credential stay available.
        compose.onNodeWithTag("cloudflare.productCenter")
            .performScrollToNode(hasTestTag("cloudflare.productCenter.operation.account-details"))
        compose.onNodeWithTag("cloudflare.productCenter.operation.account-details").performClick()
        compose.onNodeWithTag("cloudflare.explorer").assertIsDisplayed()
    }

    @Test
    fun catalogSearchOpensAnOperationAndReviewsItInTheExplorer() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Catalog("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }

        compose.waitUntil(5_000) { viewModel.uiState.value.catalog is CloudflareToolLoad.Loaded }
        compose.onNodeWithTag("cloudflare.catalog.header").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.catalog.search").performTextInput("edit zone")
        compose.onNodeWithTag("cloudflare.catalog.operation.zone-patch").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.operationEditor != null }
        compose.onNodeWithTag("cloudflare.catalog.operationScreen")
            .performScrollToNode(hasTestTag("cloudflare.catalog.review"))
        compose.onNodeWithTag("cloudflare.catalog.review").performClick()
        compose.runOnIdle {
            assertEquals("/zones/zone-1", viewModel.uiState.value.explorer.draft.path)
            assertEquals(CloudflareHttpMethod.PATCH, viewModel.uiState.value.explorer.draft.method)
        }
    }

    @Test
    fun graphQLDirectoryOpensDatasetDetailAndGeneratedQuery() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.GraphQL("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }

        compose.waitUntil(5_000) { viewModel.uiState.value.datasets.datasets.isNotEmpty() }
        compose.onNodeWithTag("cloudflare.graphql").performScrollToNode(hasTestTag("cloudflare.graphql.dataset.firewallEventsAdaptive"))
        compose.onNodeWithText("Not enabled on this plan").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.graphql").performScrollToNode(hasTestTag("cloudflare.graphql.dataset.httpRequests1dGroups"))
        compose.onNodeWithTag("cloudflare.graphql.dataset.httpRequests1dGroups").performClick()
        compose.onNodeWithTag("cloudflare.graphql.detail").performScrollToNode(hasTestTag("cloudflare.graphql.openQuery"))
        compose.onNodeWithTag("cloudflare.graphql.openQuery").performClick()
        compose.runOnIdle {
            val explorer = viewModel.uiState.value.explorer
            assertEquals("/graphql", explorer.draft.path)
            assertTrue(explorer.draft.readOnlyGraphQL)
            assertTrue(explorer.draft.bodyText.contains("zone-1"))
        }
    }

    @Test
    fun routeRequiresProForAdvancedToolsAndTheAccountHeader() {
        var paywallRequests = 0
        val dashboard = CloudflareViewModel(ConnectedDashboardGateway, SavedStateHandle())
        val tools = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) { paywallRequests += 1 }
        compose.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    CloudflareRoute(viewModel = dashboard, onBack = {}, toolsViewModel = tools)
                }
            }
        }
        compose.waitUntil(5_000) { dashboard.uiState.value.status == CloudflareConnectionStatus.CONNECTED }

        compose.onNodeWithTag("cloudflare.summary.openAccount").performClick()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.advanced"))
        compose.onNodeWithTag("cloudflare.advanced.completeApi").performScrollTo().performClick()
        compose.onNodeWithTag("cloudflare.advanced.explorer").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(3, paywallRequests)
        assertFalse(tools.uiState.value.isOpen)
        compose.onNodeWithTag("cloudflare.tools").assertDoesNotExist()
    }

    @Test
    fun routeOpensToolsForProUsersAndClosesThemWhenAccessIsLocked() {
        val dashboard = CloudflareViewModel(ConnectedDashboardGateway, SavedStateHandle())
        val tools = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        var access by mutableStateOf(ProAccess.Unlocked)
        compose.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides access) {
                    CloudflareRoute(viewModel = dashboard, onBack = {}, toolsViewModel = tools)
                }
            }
        }
        compose.waitUntil(5_000) { dashboard.uiState.value.status == CloudflareConnectionStatus.CONNECTED }
        compose.onNodeWithTag("cloudflare.summary.openAccount").performClick()
        compose.onNodeWithTag("cloudflare.account").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.tools.title").assertIsDisplayed()
        compose.waitUntil(5_000) { tools.uiState.value.accountDetail is CloudflareToolLoad.Loaded }
        compose.onNodeWithText("Required").assertIsDisplayed()

        compose.onNodeWithTag("cloudflare.tools.back").performClick()
        compose.onNodeWithTag("cloudflare.dashboard").assertIsDisplayed()

        compose.onNodeWithTag("cloudflare.summary.openAccount").performClick()
        compose.onNodeWithTag("cloudflare.account").assertIsDisplayed()
        access = ProAccess(isUnlocked = false, isConfirmedLocked = true) {}
        compose.waitUntil(5_000) { !tools.uiState.value.isOpen }
        compose.onNodeWithTag("cloudflare.dashboard").assertIsDisplayed()
    }

    @Test
    fun accountOperationsShowEachSectionAndItsFailures() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.AccountOperations("acc-1"))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, toolsContext()) } }
        compose.waitUntil(5_000) { viewModel.uiState.value.accountOperations.snapshot != null }
        compose.onNodeWithText("ACCESS & AUDIT").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.accountOperations")
            .performScrollToNode(hasText("This API token can’t access account roles.", substring = true))
        compose.onNodeWithText("Roles unavailable").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.tools.refresh").assertIsDisplayed()
    }

    @Test
    fun clearIsDisabledUntilThereIsAResponse() {
        val viewModel = CloudflareToolsViewModel(ScriptedToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer(null))
        compose.setContent { VercelticsTheme { CloudflareToolsHost(viewModel, null) } }
        compose.onNodeWithTag("cloudflare.explorer.clear").assertIsNotEnabled()
        compose.onNodeWithTag("cloudflare.explorer.quick.Accounts").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.explorer.quick.Pages").assertDoesNotExist()
        compose.onNodeWithText("Ready for a request").performScrollTo().assertIsDisplayed()
    }
}

private fun toolsContext() = CloudflareToolsContext(
    accountId = "acc-1",
    accountName = "Production",
    accountType = "standard",
    zones = listOf(CloudflareToolsZone("zone-1", "verceltics.app")),
    zoneCount = 1,
    pagesCount = 0,
    workerCount = 0,
)

private class ScriptedToolsGateway(private val status: Int = 200) : CloudflareToolsGateway {
    override val isOfflineSample: Boolean = false
    val executions = mutableListOf<Pair<CloudflareExplorerDraft, CloudflareMutationConfirmation?>>()

    override suspend fun execute(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray?,
    ): Result<CloudflareRawResponse> {
        executions += draft to confirmation
        return Result.success(
            CloudflareRawResponse(status, mapOf("Content-Type" to "application/json"), "{\"success\":${status == 200}}".toByteArray(), 12),
        )
    }

    override suspend fun loadCatalog(): Result<CloudflareOpenApiCatalog> = Result.success(
        CloudflareOpenApiCatalogParser.parse(
            """{"schemaVersion":1,"openAPIVersion":"3.0.3","apiVersion":"4","sourceCommit":"abcdef0123","sourceURL":"https://example.com","operationCount":1,
               "operations":[{"id":"zone-patch","method":"PATCH","path":"/zones/{zone_id}","summary":"Edit Zone","description":"Edits a zone.",
               "tags":["Zone"],"deprecated":false,"permissions":["Zone Write"],"supportsGlobalKey":true,"supportsAPIToken":true,"supportsUserServiceKey":false,
               "parameters":[{"name":"zone_id","location":"path","required":true,"description":"Zone","type":"string"}],
               "contentTypes":["application/json"],"requestBodyRequired":true,"bodyTemplate":"{}","multipartFields":[]}]}""",
        ),
    )

    override suspend fun loadDatasets(scope: CloudflareGraphQLScope, accountId: String, zoneId: String?) = Result.success(
        listOf(
            CloudflareGraphQLDataset("httpRequests1dGroups", "Daily HTTP", true, listOf("sum_requests"), 86_400, 2_678_400, 10_000, 30),
            CloudflareGraphQLDataset("firewallEventsAdaptive", "", false, emptyList(), null, null, null, null),
        ),
    )

    override suspend fun loadAccountDetail(accountId: String) = Result.success(DETAIL)

    override suspend fun loadAccountOperations(accountId: String) = Result.success(
        CloudflareAccountOperationsSnapshot(
            account = DETAIL,
            members = emptyList(),
            roles = emptyList(),
            auditEvents = emptyList(),
            accountError = null,
            membersError = null,
            rolesError = "This API token can’t access account roles. Add the “Account Settings Read” permission to the token, then try again.",
            auditError = null,
        ),
    )

    companion object {
        val DETAIL = CloudflareAccountDetail("acc-1", "Production", "standard", "2024-01-02T03:04:05Z", true, null, null, null)
    }
}

private object ConnectedDashboardGateway : CloudflareUiGateway {
    override suspend fun restore() = Result.success<CloudflareRestoreUi>(CloudflareRestoreUi.Available(connectedState().dashboard!!))
    override suspend fun connect(credential: CloudflareCredential) = Result.success(connectedState().dashboard!!)
    override suspend fun refresh(preferredAccountId: String?) = Result.success(connectedState().dashboard!!)
    override suspend fun disconnect() = Result.success(Unit)
}

private fun connectedState(): CloudflareUiState = CloudflareUiState(
    status = CloudflareConnectionStatus.CONNECTED,
    dashboard = CloudflareDashboardUi(
        profile = CloudflareProfileUi("profile", "Apoorv Cloudflare", "active"),
        accounts = listOf(CloudflareAccountUi("acc-1", "Production", "standard")),
        loadedAccountCount = 1,
        accountsComplete = true,
        accountsTruncatedForDisplay = false,
        selectedAccountId = "acc-1",
        inventory = CloudflareInventoryUi(
            accountId = "acc-1",
            zones = listOf(CloudflareZoneUi("zone-1", "verceltics.app", "active", "full", false, "Production", "Pro")),
            pagesProjects = emptyList(),
            workers = emptyList(),
            loadedZoneCount = 1,
            loadedPagesProjectCount = 0,
            loadedWorkerCount = 0,
            zonesComplete = true,
            pagesComplete = true,
            workersComplete = true,
            zonesTruncatedForDisplay = false,
            pagesTruncatedForDisplay = false,
            workersTruncatedForDisplay = false,
            warnings = emptyList(),
        ),
        warnings = emptyList(),
        fetchedAtMillis = 1_700_000_000_000,
        cacheState = CloudflareCacheState.LIVE,
    ),
    savedProfile = CloudflareProfileUi("profile", "Apoorv Cloudflare", "active"),
    operation = null,
)
