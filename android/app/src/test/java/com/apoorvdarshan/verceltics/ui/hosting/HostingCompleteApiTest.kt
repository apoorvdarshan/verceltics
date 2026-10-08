package com.apoorvdarshan.verceltics.ui.hosting

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogParserTest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.HostingAccount
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingProfile
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingProviderApi
import com.apoorvdarshan.verceltics.data.hosting.HostingRawApi
import com.apoorvdarshan.verceltics.data.hosting.HostingResource
import com.apoorvdarshan.verceltics.data.hosting.HostingSnapshot
import com.apoorvdarshan.verceltics.data.hosting.HostingStoreFixture
import com.apoorvdarshan.verceltics.data.hosting.HostingStoredConnection
import com.apoorvdarshan.verceltics.data.hosting.RailwayTokenType
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Complete API wiring for the generic hosting providers: gateway and ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostingCompleteApiTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    // region Gateway

    @Test
    fun rawRequestsUseTheSavedCredentialsAndReturnHttpErrorsAsResponses() = runBlocking {
        val transport = FakeHostingTransport { jsonResponse("{\"error\":\"nope\"}", status = 404) }
        GatewayFixture(transport).use { fixture ->
            fixture.save(HostingCredentials.Render(SecretValue.of("render-secret")))
            val response = fixture.gateway.sendApiRequest("render", ProviderRawRequest("GET", "/services/missing")).getOrThrow()
            assertEquals(404, response.statusCode)
            assertEquals("{\"error\":\"nope\"}", response.body)
            assertEquals("https://api.render.com/v1/services/missing", transport.requests.single().uri().toString())
        }
    }

    @Test
    fun rawRequestFailuresAreUserSafe() = runBlocking {
        GatewayFixture(FakeHostingTransport { throw IOException("reset") }).use { fixture ->
            assertEquals(
                "Connect Render first.",
                fixture.gateway.sendApiRequest("render", ProviderRawRequest("GET", "/services")).exceptionOrNull()?.message,
            )
            fixture.save(HostingCredentials.Render(SecretValue.of("render-secret")))
            assertEquals(
                "Enter a provider-relative path beginning with /.",
                fixture.gateway.sendApiRequest("render", ProviderRawRequest("GET", "services")).exceptionOrNull()?.message,
            )
            assertEquals(
                "Render could not be reached. Check your connection and try again.",
                fixture.gateway.sendApiRequest("render", ProviderRawRequest("GET", "/services")).exceptionOrNull()?.message,
            )
            assertEquals(
                "This hosting provider is not supported.",
                fixture.gateway.sendApiRequest("vercel", ProviderRawRequest("GET", "/")).exceptionOrNull()?.message,
            )
        }
        GatewayFixture(FakeHostingTransport { jsonResponse("{}") }, withRawApi = false).use { fixture ->
            fixture.save(HostingCredentials.Render(SecretValue.of("render-secret")))
            assertEquals(
                "The Complete API is unavailable in this build.",
                fixture.gateway.sendApiRequest("render", ProviderRawRequest("GET", "/services")).exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun railwayCatalogIsDiscoveredLiveCachedAndFallsBack() = runBlocking {
        var failIntrospection = false
        val transport = FakeHostingTransport {
            if (failIntrospection) jsonResponse("{\"errors\":[{\"message\":\"Not Authorized\"}]}") else jsonResponse(RAILWAY_SCHEMA)
        }
        GatewayFixture(transport).use { fixture ->
            fixture.save(HostingCredentials.Railway(SecretValue.of("railway-token"), RailwayTokenType.ACCOUNT), id = "railway-user")
            val bundled = suspend { ProviderApiCatalogParserTest.bundled("hosting.railway") }

            val live = fixture.gateway.loadApiCatalog("railway", bundled, forceRefresh = false).getOrThrow()
            assertEquals("Live GraphQL v2", live.apiVersion)
            assertEquals(listOf("railway.Query.me"), live.operations.map { it.id })
            fixture.gateway.loadApiCatalog("railway", bundled, forceRefresh = false).getOrThrow()
            assertEquals(1, transport.requests.size)

            failIntrospection = true
            // A failed refresh keeps the last live catalog.
            assertEquals(live, fixture.gateway.loadApiCatalog("railway", bundled, forceRefresh = true).getOrThrow())
            assertEquals(2, transport.requests.size)

            fixture.now += NativeHostingProviderUiGateway.RAILWAY_CATALOG_LIFETIME_MILLIS
            fixture.save(HostingCredentials.Railway(SecretValue.of("railway-token-2"), RailwayTokenType.ACCOUNT), id = "another-user")
            val fallback = fixture.gateway.loadApiCatalog("railway", bundled, forceRefresh = false).getOrThrow()
            assertEquals("GraphQL v2 · Bundled fallback", fallback.apiVersion)
            assertTrue(fallback.sourceDescription.endsWith("Not Authorized"))
            assertEquals(1, fallback.operations.size)
        }
    }

    @Test
    fun otherProvidersUseTheBundledCatalogWithoutNetwork() = runBlocking {
        val transport = FakeHostingTransport { error("No network expected") }
        GatewayFixture(transport).use { fixture ->
            val catalog = fixture.gateway.loadApiCatalog(
                "render",
                { ProviderApiCatalogParserTest.bundled("hosting.render") },
                forceRefresh = true,
            ).getOrThrow()
            assertEquals(207, catalog.operations.size)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun dashboardsCarryTheIosDefaultExplorerPaths() = runBlocking {
        GatewayFixture(FakeHostingTransport { jsonResponse("{}") }).use { fixture ->
            val fly = HostingCredentials.Fly(SecretValue.of("fly-token-123"), "acme")
            val app = HostingResource("app-1", "edge", null, null, "Running", null, "App", null, mapOf("appName" to "edge"))
            fixture.save(fly, snapshot = HostingSnapshot(HostingProvider.FLY, HostingProfile("acme", "acme", null, null), listOf(app), fixture.now))
            val dashboard = (fixture.gateway.restore().getOrThrow().getValue("fly") as HostingRestoreUi.Available).dashboard
            assertEquals("/apps?org_slug=acme", dashboard.apiExplorerPath)
            assertEquals("/apps/edge/machines", dashboard.resources.single().apiExplorerPath)
        }
    }

    @Test
    fun sampleGatewaysExplainThatLiveRequestsNeedAConnection() = runBlocking {
        val result = SampleHostingProviderUiGateway.sendApiRequest("render", ProviderRawRequest("GET", "/services"))
        assertEquals(HostingProviderUiGateway.SAMPLE_API_UNAVAILABLE, result.exceptionOrNull()?.message)
        val bundled = ProviderApiCatalogParserTest.bundled("hosting.fly")
        assertEquals(bundled, SampleHostingProviderUiGateway.loadApiCatalog("fly", { bundled }, false).getOrThrow())
    }

    // endregion

    // region ViewModel

    @Test
    fun completeApiOpensOnlyForConnectedProvidersWithTheRightStartingPath() = runTest(dispatcher) {
        val gateway = RecordingGateway()
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.openApiWorkspace("heroku")
        assertEquals(false, viewModel.apiWorkspace("heroku")?.state?.value?.isOpen)
        assertNull(viewModel.apiWorkspace("vercel"))

        viewModel.openApiWorkspace("render", resourceId = "srv-1")
        val workspace = viewModel.apiWorkspace("render")!!
        assertTrue(workspace.state.value.isOpen)
        workspace.openManualExplorer()
        assertEquals("/services/srv-1", workspace.state.value.explorer!!.path)

        viewModel.openApiWorkspace("render")
        workspace.openManualExplorer()
        assertEquals("/services?limit=100", workspace.state.value.explorer!!.path)

        workspace.requestSend()
        advanceUntilIdle()
        assertEquals(listOf("render" to "/services?limit=100"), gateway.sent)
        assertEquals(200, workspace.state.value.response!!.statusCode)
    }

    @Test
    fun backDisconnectAndSavedStateIntegrateWithTheProviderRoute() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = RecordingGateway()
        val viewModel = HostingProvidersViewModel(gateway, handle)
        advanceUntilIdle()
        viewModel.openApiWorkspace("render")
        val workspace = viewModel.apiWorkspace("render")!!
        workspace.openManualExplorer()

        // Process recreation restores the open workspace from saved state.
        val restored = HostingProvidersViewModel(gateway, handle)
        advanceUntilIdle()
        assertEquals(ProviderApiDestination.Explorer, restored.apiWorkspace("render")!!.state.value.destination)

        assertTrue(viewModel.handleBack("render"))
        assertEquals(ProviderApiDestination.Catalog, workspace.state.value.destination)
        assertTrue(viewModel.handleBack("render"))
        assertFalse(workspace.state.value.isOpen)
        assertFalse(viewModel.handleBack("render"))

        viewModel.openApiWorkspace("render")
        viewModel.requestDisconnectConfirmation("render")
        viewModel.confirmDisconnect("render")
        advanceUntilIdle()
        assertFalse(workspace.state.value.isOpen)
        assertEquals(HostingConnectionStatus.DISCONNECTED, viewModel.uiState.value.provider("render").status)
    }

    // endregion

    private class GatewayFixture(val transport: FakeHostingTransport, withRawApi: Boolean = true) : AutoCloseable {
        var now = 1_000L
        val storage = HostingStoreFixture()
        private val storageExecutor = Executors.newSingleThreadExecutor()
        val gateway = NativeHostingProviderUiGateway(
            connectionStore = HostingConnectionStore(storage.repository) { now },
            api = HostingProviderApi(transport),
            storageExecutor = storageExecutor,
            workDispatcher = Dispatchers.Default,
            nowMillis = { now },
            rawApi = if (withRawApi) HostingRawApi(transport) else null,
        )

        fun save(credentials: HostingCredentials, id: String = "profile-1", snapshot: HostingSnapshot? = null) {
            storage.repository.delete(credentials.provider)
            storage.repository.save(
                HostingStoredConnection(
                    HostingAccount(HostingProfile(id, "Studio", null, null), credentials, 1L, 1L),
                    snapshot,
                ),
            )
        }

        override fun close() {
            storageExecutor.shutdownNow()
        }
    }

    private class RecordingGateway : HostingProviderUiGateway {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun restore(): Result<Map<String, HostingRestoreUi>> = Result.success(
            mapOf(
                "render" to HostingRestoreUi.Available(
                    HostingDashboardUi(
                        providerId = "render",
                        account = HostingAccountUi("owner", "Studio", null),
                        resources = listOf(
                            HostingResourceUi("srv-1", "web", null, null, "Live", null, "Web Service", null, "https://dashboard.render.com/srv-1"),
                        ),
                        loadedResourceCount = 1,
                        warnings = emptyList(),
                        fetchedAtMillis = System.currentTimeMillis(),
                        cacheState = HostingCacheState.LIVE,
                        dashboardUrl = "https://dashboard.render.com/",
                    ),
                ),
            ),
        )

        override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> = error("Unused")

        override suspend fun refresh(providerId: String): Result<HostingDashboardUi> = Result.failure(HostingUiException("offline"))

        override suspend fun loadResource(providerId: String, resource: HostingResourceUi): Result<HostingResourceWorkspaceUi> =
            Result.success(HostingResourceWorkspaceUi(providerId, resource.id, emptyList(), 0))

        override suspend fun performPrimaryAction(providerId: String, resource: HostingResourceUi, latestDeploymentId: String?) =
            Result.success("ok")

        override suspend fun disconnect(providerId: String): Result<Unit> = Result.success(Unit)

        override suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> {
            sent += providerId to request.path
            return Result.success(ProviderRawResponse(200, emptyList(), "[]"))
        }

        override suspend fun loadApiCatalog(
            providerId: String,
            bundled: suspend () -> ProviderApiCatalog,
            forceRefresh: Boolean,
        ): Result<ProviderApiCatalog> = Result.success(bundled())
    }

    private companion object {
        val RAILWAY_SCHEMA = """
            {"data":{"__schema":{"queryType":{"name":"Query"},"mutationType":null,"types":[
              {"kind":"OBJECT","name":"Query","fields":[{"name":"me","description":null,"isDeprecated":false,"deprecationReason":null,"args":[],
                "type":{"kind":"OBJECT","name":"User","ofType":null}}]},
              {"kind":"OBJECT","name":"User","fields":[{"name":"id","isDeprecated":false,"args":[],"type":{"kind":"SCALAR","name":"ID"}}]},
              {"kind":"SCALAR","name":"ID"}
            ]}}}
        """.trimIndent()
    }
}
