package com.apoorvdarshan.verceltics.ui.registrar

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.registrar.FakeRegistrarResponse
import com.apoorvdarshan.verceltics.data.registrar.FakeRegistrarTransport
import com.apoorvdarshan.verceltics.data.registrar.MemoryAtomicBytesStore
import com.apoorvdarshan.verceltics.data.registrar.PublicIpv4Lookup
import com.apoorvdarshan.verceltics.data.registrar.RegistrarAccount
import com.apoorvdarshan.verceltics.data.registrar.RegistrarApi
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionRepository
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionStore
import com.apoorvdarshan.verceltics.data.registrar.RegistrarCredentials
import com.apoorvdarshan.verceltics.data.registrar.RegistrarHttpRequest
import com.apoorvdarshan.verceltics.data.registrar.RegistrarHttpTransport
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRawApi
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRawHttpRequest
import com.apoorvdarshan.verceltics.data.registrar.RegistrarStoredConnection
import com.apoorvdarshan.verceltics.data.registrar.SecureRegistrarHttpTransport
import com.apoorvdarshan.verceltics.data.registrar.TestAccountCipher
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiDestination
import java.nio.charset.StandardCharsets
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

/** Complete API wiring for registrars: gateway and ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrarCompleteApiTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class RawTransport : RegistrarHttpTransport {
        val uris = mutableListOf<String>()

        override fun newGetCall(request: RegistrarHttpRequest): CancelableCall<HttpResponse> = error("Unused")

        override fun newRawCall(request: RegistrarRawHttpRequest): CancelableCall<HttpResponse> {
            uris += SecureRegistrarHttpTransport().prepareRawUri(request).toString()
            return object : CancelableCall<HttpResponse> {
                override fun execute() = HttpResponse(422, "{\"message\":\"bad key-1234567\"}".toByteArray(StandardCharsets.UTF_8), emptyMap())

                override fun cancel() = Unit
            }
        }
    }

    @Test
    fun gatewaySendsWithTheSavedCredentialsAndRedactsEchoes() = runBlocking {
        val raw = RawTransport()
        val stores = RegistrarProvider.entries.associateWith { MemoryAtomicBytesStore() }
        val repository = RegistrarConnectionRepository(storeFactory = stores::getValue, cipher = TestAccountCipher())
        val networkExecutor = Executors.newFixedThreadPool(2)
        val storageExecutor = Executors.newSingleThreadExecutor()
        try {
            val readTransport = FakeRegistrarTransport { FakeRegistrarResponse(200, "{}") }
            val gateway = NativeRegistrarUiGateway(
                connectionStore = RegistrarConnectionStore(repository) { 1_000L },
                api = RegistrarApi(readTransport),
                publicIpv4Lookup = PublicIpv4Lookup(readTransport),
                networkExecutor = networkExecutor,
                storageExecutor = storageExecutor,
                rawApi = RegistrarRawApi(raw),
            )
            assertEquals(
                "Connect Dynadot first.",
                gateway.sendApiRequest("dynadot", ProviderRawRequest("GET", "/api3.json?command=list_domain")).exceptionOrNull()?.message,
            )
            repository.save(
                RegistrarStoredConnection(
                    RegistrarAccount(
                        "Dynadot",
                        RegistrarCredentials.fromInput(RegistrarProvider.DYNADOT, SecretValue.of("key-1234567"), null),
                        1L,
                        1L,
                    ),
                    null,
                ),
            )
            val response = gateway.sendApiRequest("dynadot", ProviderRawRequest("GET", "/api3.json?command=list_domain")).getOrThrow()
            assertEquals(422, response.statusCode)
            assertEquals("{\"message\":\"bad <redacted>\"}", response.body)
            assertEquals(listOf("https://api.dynadot.com/api3.json?command=list_domain&key=key-1234567"), raw.uris)
            assertEquals(
                "Enter a registrar-relative path beginning with /.",
                gateway.sendApiRequest("dynadot", ProviderRawRequest("GET", "api3.json")).exceptionOrNull()?.message,
            )
            assertEquals(
                "This registrar is not supported.",
                gateway.sendApiRequest("hover", ProviderRawRequest("GET", "/")).exceptionOrNull()?.message,
            )
        } finally {
            networkExecutor.shutdownNow()
            storageExecutor.shutdownNow()
        }
        assertEquals(
            RegistrarUiGateway.SAMPLE_API_UNAVAILABLE,
            SampleRegistrarUiGateway.sendApiRequest("namecheap", ProviderRawRequest("GET", "/")).exceptionOrNull()?.message,
        )
    }

    @Test
    fun viewModelOpensSuggestedPathsAndIntegratesBack() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = Gateway()
        val viewModel = RegistrarViewModel(gateway, handle)
        advanceUntilIdle()

        viewModel.openApiWorkspace("gandi")
        assertEquals(false, viewModel.apiWorkspace("gandi")!!.state.value.isOpen)

        viewModel.openApiWorkspace("namecheap", domainId = "launch.example")
        val workspace = viewModel.apiWorkspace("namecheap")!!
        workspace.openManualExplorer()
        assertEquals(
            "/xml.response?Command=namecheap.domains.getInfo&DomainName=launch.example",
            workspace.state.value.explorer!!.path,
        )
        workspace.requestSend()
        advanceUntilIdle()
        assertEquals(listOf("namecheap" to "/xml.response?Command=namecheap.domains.getInfo&DomainName=launch.example"), gateway.sent)

        val restored = RegistrarViewModel(gateway, handle)
        advanceUntilIdle()
        assertEquals(ProviderApiDestination.Explorer, restored.apiWorkspace("namecheap")!!.state.value.destination)

        assertTrue(viewModel.handleBack("namecheap"))
        assertTrue(viewModel.handleBack("namecheap"))
        assertFalse(workspace.state.value.isOpen)
        assertFalse(viewModel.handleBack("namecheap"))

        viewModel.openApiWorkspace("namecheap")
        workspace.openManualExplorer()
        assertEquals("/xml.response?Command=namecheap.domains.getList&ListType=ALL&PageSize=100", workspace.state.value.explorer!!.path)
        viewModel.requestDisconnectConfirmation("namecheap")
        viewModel.confirmDisconnect()
        advanceUntilIdle()
        assertFalse(workspace.state.value.isOpen)
    }

    private class Gateway : RegistrarUiGateway {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun restore(): Result<RegistrarRestoreUi> = Result.success(
            RegistrarRestoreUi(
                mapOf(
                    "namecheap" to RegistrarProviderRestoreUi.Available(
                        RegistrarDashboardUi(
                            account = RegistrarAccountUi("namecheap", "studio"),
                            domains = listOf(
                                RegistrarDomainUi("launch.example", "launch.example", "Active", null, null, true, true, true, emptyList()),
                            ),
                            inventoryComplete = true,
                            warnings = emptyList(),
                            fetchedAtMillis = System.currentTimeMillis(),
                            cacheState = RegistrarCacheState.LIVE,
                        ),
                    ),
                ),
            ),
        )

        override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> = error("Unused")

        override suspend fun refresh(providerId: String): Result<RegistrarDashboardUi> = Result.failure(RegistrarUiException("offline"))

        override suspend fun disconnect(providerId: String): Result<Unit> = Result.success(Unit)

        override suspend fun detectPublicIpv4(): Result<String> = Result.failure(RegistrarUiException("offline"))

        override suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> {
            sent += providerId to request.path
            return Result.success(ProviderRawResponse(200, emptyList(), "<ApiResponse/>"))
        }
    }
}
