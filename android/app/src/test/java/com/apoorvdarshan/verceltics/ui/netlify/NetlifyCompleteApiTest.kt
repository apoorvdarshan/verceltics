package com.apoorvdarshan.verceltics.ui.netlify

import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.HostingRawApi
import com.apoorvdarshan.verceltics.data.hosting.MemoryAtomicBytesStore
import com.apoorvdarshan.verceltics.data.hosting.TestAccountCipher
import com.apoorvdarshan.verceltics.data.hosting.bearerToken
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
import com.apoorvdarshan.verceltics.data.netlify.NetlifyAccount
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionRepository
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionStore
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDataSource
import com.apoorvdarshan.verceltics.data.netlify.NetlifyStoredConnection
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiDestination
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Complete API wiring for Netlify: gateway and ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetlifyCompleteApiTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun gatewaySendsWithTheSavedPersonalTokenToNetlifysApiHost() = runBlocking {
        var fail = false
        val transport = FakeHostingTransport {
            if (fail) throw IOException("reset") else jsonResponse("{\"id\":\"site-1\",\"token\":\"nfp_secret_token\"}", status = 201)
        }
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val networkExecutor = Executors.newFixedThreadPool(2)
        val storageExecutor = Executors.newSingleThreadExecutor()
        try {
            val gateway = NativeNetlifyUiGateway(
                connectionStore = NetlifyConnectionStore(repository, nowMillis = { 42L }),
                dataSource = NetlifyDataSource(),
                networkExecutor = networkExecutor,
                storageExecutor = storageExecutor,
                rawApi = HostingRawApi(transport),
            )
            assertEquals(
                "Connect a Netlify account first.",
                gateway.sendApiRequest(ProviderRawRequest("GET", "/sites")).exceptionOrNull()?.message,
            )
            repository.save(
                NetlifyStoredConnection(
                    NetlifyAccount("account-1", "Studio", null, null, SecretValue.of("nfp_secret_token"), 1L, 1L),
                    null,
                ),
            )
            val response = gateway.sendApiRequest(ProviderRawRequest("POST", "/sites/site-1/builds")).getOrThrow()
            assertEquals(201, response.statusCode)
            assertEquals("{\"id\":\"site-1\",\"token\":\"<redacted>\"}", response.body)
            val request = transport.requests.single()
            assertEquals("https://api.netlify.com/api/v1/sites/site-1/builds", request.uri().toString())
            assertEquals("nfp_secret_token", request.bearerToken())

            assertEquals(
                "Enter a provider-relative path beginning with /.",
                gateway.sendApiRequest(ProviderRawRequest("GET", "sites")).exceptionOrNull()?.message,
            )
            fail = true
            assertEquals(
                "Netlify could not be reached. Check your connection and try again.",
                gateway.sendApiRequest(ProviderRawRequest("GET", "/sites")).exceptionOrNull()?.message,
            )
        } finally {
            networkExecutor.shutdownNow()
            storageExecutor.shutdownNow()
        }
    }

    @Test
    fun viewModelOpensSitePathsAndIntegratesBackAndDisconnect() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = Gateway()
        val viewModel = NetlifyViewModel(gateway, handle)
        advanceUntilIdle()

        viewModel.openApiWorkspace(siteId = "site-1")
        val workspace = viewModel.apiWorkspace
        workspace.openManualExplorer()
        assertEquals("/sites/site-1", workspace.state.value.explorer!!.path)
        workspace.requestSend()
        advanceUntilIdle()
        assertEquals(listOf("/sites/site-1"), gateway.sent)

        val restored = NetlifyViewModel(gateway, handle)
        advanceUntilIdle()
        assertEquals(ProviderApiDestination.Explorer, restored.apiWorkspace.state.value.destination)

        assertTrue(viewModel.handleBack())
        assertTrue(viewModel.handleBack())
        assertFalse(workspace.state.value.isOpen)

        viewModel.openApiWorkspace()
        workspace.openManualExplorer()
        assertEquals("/sites?per_page=100", workspace.state.value.explorer!!.path)
        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()
        assertFalse(workspace.state.value.isOpen)
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)

        viewModel.openApiWorkspace()
        assertFalse(workspace.state.value.isOpen)
    }

    private class Gateway : NetlifyUiGateway {
        val sent = mutableListOf<String>()

        override suspend fun restore(): Result<NetlifyRestoreUi> = Result.success(
            NetlifyRestoreUi.Available(
                NetlifyDashboardUi(
                    account = NetlifyAccountUi("account-1", "Studio", null),
                    sites = listOf(NetlifySiteUi("site-1", "Example", null, null, "current", null)),
                    loadedSiteCount = 1,
                    providerInventoryComplete = true,
                    warnings = emptyList(),
                    fetchedAtMillis = System.currentTimeMillis(),
                    cacheState = NetlifyCacheState.LIVE,
                ),
            ),
        )

        override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> = error("Unused")

        override suspend fun refresh(): Result<NetlifyDashboardUi> = Result.failure(NetlifyUiException("offline"))

        override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> = Result.failure(NetlifyUiException("offline"))

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

        override suspend fun sendApiRequest(request: ProviderRawRequest): Result<ProviderRawResponse> {
            sent += request.path
            return Result.success(ProviderRawResponse(200, emptyList(), "{}"))
        }
    }
}
