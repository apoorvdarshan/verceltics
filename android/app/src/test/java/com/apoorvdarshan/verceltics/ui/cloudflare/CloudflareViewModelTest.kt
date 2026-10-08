package com.apoorvdarshan.verceltics.ui.cloudflare

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareMutationAffectsDashboard
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareMutationEventFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedRestoreIsOfflineFirstAndRefreshesOnceAfterForeground() = runTest(dispatcher) {
        val cached = dashboard(cacheState = CloudflareCacheState.CACHED_STALE)
        val gateway = FakeGateway(CloudflareRestoreUi.Available(cached))
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())

        advanceUntilIdle()
        assertEquals(0, gateway.refreshCalls)
        assertEquals(cached, viewModel.uiState.value.dashboard)

        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.refreshCalls)
        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.refreshCalls)
    }

    @Test
    fun tokenNeverEntersUiStateAndSecureWindowEndsAfterConnect() = runTest(dispatcher) {
        val rawToken = "never-publish-cloudflare-token"
        val gateway = FakeGateway(CloudflareRestoreUi.NotConnected)
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible(true)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)

        viewModel.connect(SecretValue.of(rawToken))
        advanceUntilIdle()

        assertEquals(rawToken, gateway.lastToken?.use { it })
        assertFalse(viewModel.uiState.value.toString().contains(rawToken))
        assertEquals(CloudflareConnectionStatus.CONNECTED, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
    }

    @Test
    fun globalApiKeyConnectPassesEmailAndKeyWithoutPublishingTheKey() = runTest(dispatcher) {
        val rawKey = "never-publish-global-api-key"
        val gateway = FakeGateway(CloudflareRestoreUi.NotConnected)
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(CloudflareCredential.globalApiKey("Owner@Example.com", rawKey))
        advanceUntilIdle()

        val credential = gateway.lastCredential as CloudflareCredential.GlobalApiKey
        assertEquals("owner@example.com", credential.email)
        assertEquals(rawKey, credential.key.use { it })
        assertFalse(viewModel.uiState.value.toString().contains(rawKey))
        assertFalse(credential.toString().contains(rawKey))
        assertFalse(credential.toString().contains("owner@example.com"))
        assertEquals(CloudflareConnectionStatus.CONNECTED, viewModel.uiState.value.status)
    }

    @Test
    fun explorerWriteThatChangesTheSummaryRefreshesTheDashboard() = runTest(dispatcher) {
        val gateway = FakeGateway(CloudflareRestoreUi.Available(dashboard()))
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, gateway.refreshCalls)

        // A DNS record write (nested zone path) leaves the dashboard summary alone, like iOS.
        gateway.events.tryEmit(CloudflareMutationEvent(CloudflareHttpMethod.POST, "/zones/zone-primary/dns_records"))
        advanceUntilIdle()
        assertEquals(0, gateway.refreshCalls)

        // Creating a Worker script through the API explorer changes the dashboard inventory.
        gateway.events.tryEmit(CloudflareMutationEvent(CloudflareHttpMethod.PUT, "/accounts/account-primary/workers/scripts/new-worker"))
        advanceUntilIdle()
        assertEquals(1, gateway.refreshCalls)
        assertEquals("account-primary", gateway.lastPreferredAccountId)

        // Deleting a zone also refreshes.
        gateway.events.tryEmit(CloudflareMutationEvent(CloudflareHttpMethod.DELETE, "/zones/zone-primary"))
        advanceUntilIdle()
        assertEquals(2, gateway.refreshCalls)
    }

    @Test
    fun mutationsAreIgnoredWhileDisconnected() = runTest(dispatcher) {
        val gateway = FakeGateway(CloudflareRestoreUi.NotConnected)
        CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        gateway.events.tryEmit(CloudflareMutationEvent(CloudflareHttpMethod.POST, "/zones"))
        advanceUntilIdle()
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun dashboardSummaryFilterMatchesIos() {
        listOf(
            "/zones",
            "/zones/abc",
            "/accounts/acc",
            "/accounts/acc/pages/projects",
            "/accounts/acc/pages/projects/site",
            "/accounts/acc/workers/scripts/api",
        ).forEach { assertTrue(it, cloudflareMutationAffectsDashboard(it)) }
        listOf(
            "/zones/abc/dns_records",
            "/zones/abc/purge_cache",
            "/accounts/acc/pages/projects/site/deployments",
            "/accounts/acc/workers/scripts/api/deployments",
            "/accounts/acc/d1/database",
            "/graphql",
            "/user",
        ).forEach { assertFalse(it, cloudflareMutationAffectsDashboard(it)) }
    }

    @Test
    fun accountSelectionRefreshesPreferredInventoryAndClosesResource() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = FakeGateway(CloudflareRestoreUi.Available(dashboard()))
        val viewModel = CloudflareViewModel(gateway, handle)
        advanceUntilIdle()

        viewModel.openResource(CloudflareResourceKind.ZONE, "zone-primary")
        assertEquals("zone-primary", viewModel.uiState.value.selectedResource?.id)

        viewModel.selectAccount("account-secondary")
        advanceUntilIdle()

        assertEquals("account-secondary", gateway.lastPreferredAccountId)
        assertEquals("account-secondary", viewModel.uiState.value.dashboard?.selectedAccountId)
        assertNull(viewModel.uiState.value.selectedResource)
        assertNull(handle.get<String>(CloudflareViewModel.SELECTED_RESOURCE_ID))
    }

    @Test
    fun resourceSelectionSurvivesViewModelRecreationOnlyWhileResourceExists() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = FakeGateway(CloudflareRestoreUi.Available(dashboard()))
        val first = CloudflareViewModel(gateway, handle)
        advanceUntilIdle()
        first.openResource(CloudflareResourceKind.WORKER, "worker-primary")

        val recreated = CloudflareViewModel(gateway, handle)
        advanceUntilIdle()

        assertEquals(CloudflareResourceKind.WORKER, recreated.uiState.value.selectedResource?.kind)
        assertEquals("worker-primary", recreated.uiState.value.selectedResource?.id)
    }

    @Test
    fun rejectedRefreshPersistenceKeepsPreviouslyDisplayedDashboard() = runTest(dispatcher) {
        val cached = dashboard(cacheState = CloudflareCacheState.CACHED_FRESH)
        val gateway = FakeGateway(CloudflareRestoreUi.Available(cached)).apply {
            refreshFailure = CloudflareUiException(
                "The saved Cloudflare connection changed while refreshing. Reopen it and try again.",
            )
        }
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(cached, viewModel.uiState.value.dashboard)
        assertEquals(
            "The saved Cloudflare connection changed while refreshing. Reopen it and try again.",
            viewModel.uiState.value.error,
        )
    }

    private class FakeGateway(
        private val restored: CloudflareRestoreUi,
    ) : CloudflareUiGateway {
        var refreshCalls = 0
        var lastPreferredAccountId: String? = null
        var lastToken: SecretValue? = null
        var lastCredential: CloudflareCredential? = null
        var refreshFailure: Throwable? = null
        val events = cloudflareMutationEventFlow()

        override suspend fun restore(): Result<CloudflareRestoreUi> = Result.success(restored)

        override suspend fun connect(credential: CloudflareCredential): Result<CloudflareDashboardUi> {
            lastCredential = credential
            lastToken = (credential as? CloudflareCredential.ApiToken)?.token
            return Result.success(
                dashboard().let { dashboard ->
                    if (credential is CloudflareCredential.GlobalApiKey) {
                        dashboard.copy(
                            profile = CloudflareProfileUi(
                                "user-id",
                                "Owner",
                                "active",
                                CloudflareAuthMode.GLOBAL_API_KEY,
                                credential.email,
                            ),
                        )
                    } else {
                        dashboard
                    }
                },
            )
        }

        override fun mutationEvents(): Flow<CloudflareMutationEvent> = events

        override suspend fun refresh(preferredAccountId: String?): Result<CloudflareDashboardUi> {
            refreshCalls += 1
            lastPreferredAccountId = preferredAccountId
            refreshFailure?.let { return Result.failure(it) }
            return Result.success(dashboard(preferredAccountId ?: "account-primary"))
        }

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
    }
}

private fun dashboard(
    selectedAccountId: String = "account-primary",
    cacheState: CloudflareCacheState = CloudflareCacheState.LIVE,
): CloudflareDashboardUi {
    val primary = selectedAccountId == "account-primary"
    val inventory = CloudflareInventoryUi(
        accountId = selectedAccountId,
        zones = listOf(
            CloudflareZoneUi(
                id = if (primary) "zone-primary" else "zone-secondary",
                name = if (primary) "primary.example" else "secondary.example",
                status = "active",
                type = "full",
                paused = false,
                accountName = if (primary) "Primary" else "Secondary",
                planName = "Free",
            ),
        ),
        pagesProjects = emptyList(),
        workers = if (primary) {
            listOf(CloudflareWorkerUi("worker-primary", null, null, listOf("fetch"), false, true))
        } else {
            emptyList()
        },
        loadedZoneCount = 1,
        loadedPagesProjectCount = 0,
        loadedWorkerCount = if (primary) 1 else 0,
        zonesComplete = true,
        pagesComplete = true,
        workersComplete = true,
        zonesTruncatedForDisplay = false,
        pagesTruncatedForDisplay = false,
        workersTruncatedForDisplay = false,
        warnings = emptyList(),
    )
    return CloudflareDashboardUi(
        profile = CloudflareProfileUi("profile", "Cloudflare token", "active"),
        accounts = listOf(
            CloudflareAccountUi("account-primary", "Primary", "standard"),
            CloudflareAccountUi("account-secondary", "Secondary", "standard"),
        ),
        loadedAccountCount = 2,
        accountsComplete = true,
        accountsTruncatedForDisplay = false,
        selectedAccountId = selectedAccountId,
        inventory = inventory,
        warnings = emptyList(),
        fetchedAtMillis = 1_700_000_000_000,
        cacheState = cacheState,
    )
}
