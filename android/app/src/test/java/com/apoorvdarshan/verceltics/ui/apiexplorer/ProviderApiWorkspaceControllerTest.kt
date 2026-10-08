package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiAccessFilter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogSource
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipartField
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiOperation
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiParameter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiParameterLocation
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.apicatalog.filteredOperations
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderApiWorkspaceControllerTest {
    private class FakeBackend : ProviderApiBackend {
        val sent = mutableListOf<ProviderRawRequest>()
        var catalogResult: Result<ProviderApiCatalog>? = null
        var catalogLoads = 0
        var response: Result<ProviderRawResponse> = Result.success(
            ProviderRawResponse(200, listOf("Content-Type" to "application/json"), "{\"b\":1,\"a\":2}"),
        )
        var pending: CompletableDeferred<Result<ProviderRawResponse>>? = null

        override suspend fun loadCatalog(bundled: suspend () -> ProviderApiCatalog, forceRefresh: Boolean): Result<ProviderApiCatalog> {
            catalogLoads += 1
            return catalogResult ?: super.loadCatalog(bundled, forceRefresh)
        }

        override suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse> {
            sent += request
            return pending?.await() ?: response
        }
    }

    private val source = ProviderApiCatalogSource { id -> catalog(id) }

    private fun TestScope.controller(
        profile: ProviderApiProfile = ProviderApiProfile.hosting(HostingProvider.RENDER),
        backend: ProviderApiBackend = FakeBackend(),
        handle: SavedStateHandle? = SavedStateHandle(),
    ): ProviderApiWorkspaceController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ProviderApiWorkspaceController(
            profile = profile,
            scope = CoroutineScope(dispatcher),
            backend = backend,
            savedStateHandle = handle,
            formatDispatcher = dispatcher,
        )
    }

    @Test
    fun openingLoadsTheCatalogOnceAndFilters() = runTest {
        val backend = FakeBackend()
        val workspace = controller(backend = backend)
        assertFalse(workspace.state.value.isOpen)
        workspace.loadCatalog(source)
        assertEquals(0, backend.catalogLoads)

        workspace.open("/services?limit=100")
        workspace.loadCatalog(source)
        workspace.loadCatalog(source)
        val state = workspace.state.value
        assertTrue(state.isOpen)
        assertEquals(listOf(ProviderApiDestination.Catalog), state.stack)
        assertEquals("hosting.render", state.loadedCatalog?.id)
        assertEquals(1, backend.catalogLoads)

        workspace.setQuery("site")
        workspace.selectTag("Sites")
        workspace.setAccess(ProviderApiAccessFilter.WRITE)
        val filtered = workspace.state.value.let { it.loadedCatalog!!.filteredOperations(it.query, it.selectedTag, it.access) }
        assertEquals(listOf("create-site"), filtered.map { it.id })

        workspace.loadCatalog(source, forceRefresh = true)
        assertEquals(2, backend.catalogLoads)
        assertFalse(workspace.state.value.isRefreshingCatalog)
    }

    @Test
    fun operationDetailRequiresFieldsThenCarriesThePresetIntoTheExplorer() = runTest {
        val workspace = controller()
        workspace.open("/services")
        workspace.loadCatalog(source)
        workspace.openOperation("get-site")
        assertEquals(ProviderApiDestination.Operation("get-site"), workspace.state.value.destination)
        assertTrue(workspace.operationHasMissingRequiredParameters())
        workspace.reviewOperation()
        assertEquals(ProviderApiDestination.Operation("get-site"), workspace.state.value.destination)

        workspace.updateParameter("path:site_id", "site 1")
        workspace.updateParameter("query:include", "deploys")
        workspace.updateParameter("header:X-Request-Id", "trace")
        assertFalse(workspace.operationHasMissingRequiredParameters())
        workspace.reviewOperation()
        val state = workspace.state.value
        assertEquals(
            listOf(ProviderApiDestination.Catalog, ProviderApiDestination.Operation("get-site"), ProviderApiDestination.Explorer),
            state.stack,
        )
        val explorer = state.explorer!!
        assertEquals("Get a site", explorer.title)
        assertEquals("GET", explorer.method)
        assertEquals("/sites/site%201?include=deploys", explorer.path)
        assertEquals("{\n  \"X-Request-Id\": \"trace\"\n}", explorer.headersText)
    }

    @Test
    fun manualExplorersUseIosDefaults() = runTest {
        val render = controller()
        render.open("/services/srv-1")
        render.loadCatalog(source)
        render.openManualExplorer()
        render.state.value.explorer!!.let {
            assertEquals("Render API", it.title)
            assertEquals("GET", it.method)
            assertEquals("/services/srv-1", it.path)
            assertEquals("", it.body)
            assertEquals("application/json", it.contentType)
        }

        val railway = controller(profile = ProviderApiProfile.hosting(HostingProvider.RAILWAY))
        railway.open("/graphql/v2")
        railway.openManualExplorer()
        railway.setMethod("GET")
        railway.state.value.explorer!!.let {
            assertEquals("POST", it.method)
            assertEquals("{\"query\":\"query { me { id name email } }\"}", it.body)
        }

        val porkbun = controller(profile = ProviderApiProfile.registrar(RegistrarProvider.PORKBUN))
        porkbun.open("/domain/listAll")
        porkbun.openManualExplorer()
        porkbun.state.value.explorer!!.let {
            assertEquals("Porkbun API", it.title)
            assertEquals("POST", it.method)
            assertEquals("{}", it.body)
            assertEquals("/domain/listAll", it.path)
        }
    }

    @Test
    fun readsSendImmediatelyAndWritesNeedAVisibleConfirmation() = runTest {
        val backend = FakeBackend()
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.openManualExplorer()
        workspace.setHeadersText("{\"X-Request-Id\":\"1\"}")
        workspace.requestSend()
        assertEquals(1, backend.sent.size)
        assertEquals("GET", backend.sent.single().method)
        assertEquals(mapOf("X-Request-Id" to "1"), backend.sent.single().headers)
        assertNull(backend.sent.single().body)
        val response = workspace.state.value.response!!
        assertEquals(200, response.statusCode)
        assertEquals("{\n  \"a\": 2,\n  \"b\": 1\n}", response.bodyText)
        assertEquals("Content-Type: application/json", response.headersText)
        assertFalse(response.bodyTruncated)

        workspace.setMethod("DELETE")
        assertTrue(workspace.explorerRequiresConfirmation())
        workspace.requestSend()
        assertTrue(workspace.state.value.showWriteConfirmation)
        assertEquals(1, backend.sent.size)
        workspace.dismissWriteConfirmation()
        workspace.confirmSend()
        assertEquals(1, backend.sent.size)

        workspace.setMethod("POST")
        workspace.setBody("{\"name\":\"x\"}")
        workspace.requestSend()
        workspace.confirmSend()
        assertEquals(2, backend.sent.size)
        assertEquals("POST", backend.sent.last().method)
        assertEquals("{\"name\":\"x\"}", backend.sent.last().body)
        assertFalse(workspace.state.value.showWriteConfirmation)
    }

    @Test
    fun registrarGetCommandsThatWriteNeedConfirmation() = runTest {
        val backend = FakeBackend()
        val workspace = controller(profile = ProviderApiProfile.registrar(RegistrarProvider.NAMECHEAP), backend = backend)
        workspace.open("/xml.response?Command=namecheap.domains.getList")
        workspace.openManualExplorer()
        workspace.requestSend()
        assertEquals(1, backend.sent.size)
        workspace.setPath("/xml.response?Command=namecheap.domains.dns.setHosts&SLD=a&TLD=com")
        workspace.requestSend()
        assertTrue(workspace.state.value.showWriteConfirmation)
        assertEquals(1, backend.sent.size)
    }

    @Test
    fun railwayOnlyConfirmsGraphQlThatIsNotProvablyARead() = runTest {
        val backend = FakeBackend()
        val workspace = controller(profile = ProviderApiProfile.hosting(HostingProvider.RAILWAY), backend = backend)
        workspace.open("/graphql/v2")
        workspace.openManualExplorer()
        workspace.requestSend()
        assertEquals(1, backend.sent.size)
        assertEquals("POST", backend.sent.single().method)
        workspace.setBody("{\"query\":\"mutation { projectDelete(id: \\\"x\\\") }\"}")
        workspace.requestSend()
        assertTrue(workspace.state.value.showWriteConfirmation)
        assertEquals(1, backend.sent.size)
    }

    @Test
    fun invalidHeaderJsonIsReportedWithoutSending() = runTest {
        val backend = FakeBackend()
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.openManualExplorer()
        workspace.setHeadersText("{\"X-Count\": 1}")
        workspace.requestSend()
        assertTrue(backend.sent.isEmpty())
        assertEquals("Custom headers must be a JSON object whose values are strings.", workspace.state.value.requestError)
    }

    @Test
    fun failuresAndLargeResponsesAreShownSafely() = runTest {
        val backend = FakeBackend()
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.openManualExplorer()
        backend.response = Result.failure(IllegalStateException("Render could not be reached. Check your connection and try again."))
        workspace.requestSend()
        assertEquals("Render could not be reached. Check your connection and try again.", workspace.state.value.requestError)
        assertNull(workspace.state.value.response)
        assertFalse(workspace.state.value.isSending)

        backend.response = Result.success(ProviderRawResponse(500, emptyList(), "x".repeat(150_000)))
        workspace.requestSend()
        val response = workspace.state.value.response!!
        assertFalse(response.isSuccess)
        assertTrue(response.bodyTruncated)
        assertEquals(100_000, response.bodyText.length)
        assertEquals(0, response.headerCount)
        assertNull(workspace.state.value.requestError)
    }

    @Test
    fun backPopsEachScreenThenCloses() = runTest {
        val workspace = controller()
        assertFalse(workspace.back())
        workspace.open("/services")
        workspace.loadCatalog(source)
        workspace.openOperation("create-site")
        workspace.reviewOperation()
        workspace.requestSend()
        assertTrue(workspace.state.value.showWriteConfirmation)
        assertTrue(workspace.back())
        assertFalse(workspace.state.value.showWriteConfirmation)
        assertEquals(ProviderApiDestination.Explorer, workspace.state.value.destination)
        assertTrue(workspace.back())
        assertNull(workspace.state.value.explorer)
        assertEquals(ProviderApiDestination.Operation("create-site"), workspace.state.value.destination)
        assertTrue(workspace.back())
        assertNull(workspace.state.value.operationDraft)
        assertEquals(ProviderApiDestination.Catalog, workspace.state.value.destination)
        assertTrue(workspace.back())
        assertFalse(workspace.state.value.isOpen)
    }

    @Test
    fun navigationAndDraftsSurviveSavedStateRestoration() = runTest {
        val handle = SavedStateHandle()
        val first = controller(handle = handle)
        first.open("/services/srv-1")
        first.loadCatalog(source)
        first.setQuery("sites")
        first.setAccess(ProviderApiAccessFilter.READ)
        first.openOperation("get-site")
        first.updateParameter("path:site_id", "abc")
        first.reviewOperation()
        first.setBody("draft body")

        val restored = controller(handle = handle)
        val state = restored.state.value
        assertTrue(state.isOpen)
        assertEquals(
            listOf(ProviderApiDestination.Catalog, ProviderApiDestination.Operation("get-site"), ProviderApiDestination.Explorer),
            state.stack,
        )
        assertEquals("sites", state.query)
        assertEquals(ProviderApiAccessFilter.READ, state.access)
        assertEquals("/sites/abc", state.explorer!!.path)
        assertEquals("draft body", state.explorer!!.body)
        assertEquals("abc", state.operationDraft!!.values["path:site_id"])
        restored.loadCatalog(source)
        assertEquals("abc", restored.state.value.operationDraft!!.values["path:site_id"])

        // Manual explorers restored after process death keep the default path too.
        restored.back()
        restored.back()
        restored.openManualExplorer()
        assertEquals("/services/srv-1", restored.state.value.explorer!!.path)

        restored.close()
        assertFalse(controller(handle = handle).state.value.isOpen)
        assertEquals(false, handle.get<Boolean>("providerApi.hosting.render.open"))
    }

    @Test
    fun restoredOperationsMissingFromTheCatalogAreDropped() = runTest {
        val handle = SavedStateHandle(
            mapOf(
                "providerApi.hosting.render.open" to true,
                "providerApi.hosting.render.stack" to arrayListOf("catalog", "operation:removed"),
            ),
        )
        val workspace = controller(handle = handle)
        assertEquals(ProviderApiDestination.Operation("removed"), workspace.state.value.destination)
        workspace.loadCatalog(source)
        assertEquals(listOf(ProviderApiDestination.Catalog), workspace.state.value.stack)
    }

    @Test
    fun lateResponsesAfterLeavingTheExplorerAreIgnored() = runTest {
        val backend = FakeBackend().apply { pending = CompletableDeferred() }
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.openManualExplorer()
        workspace.requestSend()
        assertTrue(workspace.state.value.isSending)
        workspace.close()
        backend.pending!!.complete(Result.success(ProviderRawResponse(200, emptyList(), "late")))
        assertNull(workspace.state.value.response)
        assertFalse(workspace.state.value.isSending)
        assertFalse(workspace.state.value.isOpen)
    }

    @Test
    fun catalogFailuresShowAMessageButRefreshFailuresKeepTheCatalog() = runTest {
        val backend = FakeBackend().apply { catalogResult = Result.failure(ProviderApiCatalogException.missingProvider("hosting.render")) }
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.loadCatalog(source)
        assertEquals(ProviderApiCatalogState.Failed("No API definition is bundled for hosting.render."), workspace.state.value.catalog)

        backend.catalogResult = null
        workspace.loadCatalog(source, forceRefresh = true)
        assertEquals("hosting.render", workspace.state.value.loadedCatalog?.id)

        backend.catalogResult = Result.failure(IllegalStateException("offline"))
        workspace.loadCatalog(source, forceRefresh = true)
        assertEquals("hosting.render", workspace.state.value.loadedCatalog?.id)
        assertFalse(workspace.state.value.isRefreshingCatalog)
    }

    @Test
    fun binaryFilesAreSentAsBytesAndDroppedWithTheirContentType() = runTest {
        val backend = FakeBackend()
        val workspace = controller(profile = ProviderApiProfile.netlify(), backend = backend)
        workspace.open("/sites")
        workspace.openManualExplorer()
        workspace.setMethod("PUT")
        workspace.setContentType("application/octet-stream")
        workspace.attachBinaryFile("logo.png", byteArrayOf(1, 2, 3))
        assertEquals(ProviderApiAttachmentUi("logo.png", 3, isMultipart = false), workspace.state.value.explorer!!.attachment)
        workspace.requestSend()
        workspace.confirmSend()
        assertArrayEquals(byteArrayOf(1, 2, 3), backend.sent.single().bodyBytes())
        assertNull(backend.sent.single().body)
        assertEquals("application/octet-stream", backend.sent.single().contentType)

        workspace.setContentType("application/json")
        assertNull(workspace.state.value.explorer!!.attachment)
        workspace.setBody("{}")
        workspace.requestSend()
        workspace.confirmSend()
        assertEquals("{}", backend.sent.last().body)
    }

    @Test
    fun multipartComposerBuildsAnUploadFromTheSchema() = runTest {
        val backend = FakeBackend()
        val workspace = controller(backend = backend)
        workspace.open("/services")
        workspace.loadCatalog(source)
        workspace.openOperation("validate")
        workspace.reviewOperation()
        assertEquals("multipart/form-data", workspace.state.value.explorer!!.contentType)
        workspace.openMultipartComposer()
        val parts = workspace.state.value.multipart!!.parts
        assertEquals(listOf("ownerId", "file"), parts.map { it.name })
        assertEquals("tea-1", parts[0].value)

        workspace.composeMultipart()
        assertEquals("Add values for required fields: file.", workspace.state.value.multipart!!.error)
        workspace.attachMultipartFile(parts[1].id, "render.yaml", "application/x-yaml", "services: []".toByteArray())
        workspace.addMultipartField()
        workspace.removeMultipartPart(parts[0].id)
        assertEquals(3, workspace.state.value.multipart!!.parts.size)
        workspace.composeMultipart()
        val state = workspace.state.value
        assertEquals(ProviderApiDestination.Explorer, state.destination)
        val explorer = state.explorer!!
        assertTrue(explorer.contentType.startsWith("multipart/form-data; boundary=Verceltics-"))
        assertTrue(explorer.attachment!!.isMultipart)

        workspace.requestSend()
        workspace.confirmSend()
        val sent = String(backend.sent.single().bodyBytes()!!)
        assertTrue(sent.contains("name=\"ownerId\"\r\n\r\ntea-1"))
        assertTrue(sent.contains("filename=\"render.yaml\"\r\nContent-Type: application/x-yaml\r\n\r\nservices: []"))
    }

    private fun catalog(id: String) = ProviderApiCatalog(
        id = id,
        title = "Render",
        apiVersion = "1",
        sourceUrl = "https://api-docs.render.com/openapi",
        sourceDescription = "Official Render OpenAPI definition",
        operations = listOf(
            operation(
                "list-sites",
                "GET",
                "/sites",
                "List sites",
                parameters = listOf(ProviderApiParameter("per_page", ProviderApiParameterLocation.QUERY, false, "", "integer", "10", emptyList())),
            ),
            operation(
                "get-site",
                "GET",
                "/sites/{site_id}",
                "Get a site",
                parameters = listOf(
                    ProviderApiParameter("site_id", ProviderApiParameterLocation.PATH, true, "", "string", "", emptyList()),
                    ProviderApiParameter("include", ProviderApiParameterLocation.QUERY, false, "", "string", "", emptyList()),
                    ProviderApiParameter("X-Request-Id", ProviderApiParameterLocation.HEADER, false, "", "string", "", emptyList()),
                    ProviderApiParameter("Authorization", ProviderApiParameterLocation.HEADER, true, "", "string", "", emptyList()),
                ),
            ),
            operation("create-site", "POST", "/sites", "Create a site", body = "{\n  \"name\": \"\"\n}", contentTypes = listOf("application/json")),
            operation(
                "validate",
                "POST",
                "/blueprints/validate",
                "Validate a blueprint",
                tags = listOf("Blueprints"),
                contentTypes = listOf("multipart/form-data"),
                multipartFields = listOf(
                    ProviderApiMultipartField("ownerId", true, false, "string", null, null, "tea-1"),
                    ProviderApiMultipartField("file", true, true, "string", "binary", null, ""),
                ),
            ),
        ),
    )

    private fun operation(
        id: String,
        method: String,
        path: String,
        summary: String,
        tags: List<String> = listOf("Sites"),
        parameters: List<ProviderApiParameter> = emptyList(),
        body: String = "",
        contentTypes: List<String> = emptyList(),
        multipartFields: List<ProviderApiMultipartField> = emptyList(),
    ) = ProviderApiOperation(
        id = id,
        method = method,
        path = path,
        summary = summary,
        description = "",
        tags = tags,
        deprecated = false,
        parameters = parameters,
        contentTypes = contentTypes,
        requestBodyRequired = body.isNotEmpty(),
        bodyTemplate = body,
        multipartFields = multipartFields,
    )
}
