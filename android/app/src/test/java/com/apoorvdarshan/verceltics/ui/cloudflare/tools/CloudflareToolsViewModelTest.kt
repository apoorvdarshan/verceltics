package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountDetail
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountOperationsSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareApiPreset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareBodyEncoding
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDataset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartPart
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalogParser
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareRawResponse
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareToolsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = CloudflareToolsContext(
        accountId = "acc-1",
        accountName = "Studio",
        accountType = "standard",
        zones = listOf(CloudflareToolsZone("zone-b", "b.example"), CloudflareToolsZone("zone-a", "a.example")),
        zoneCount = 2,
        pagesCount = 3,
        workerCount = 4,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun routeStackPersistsAndRestoresFromSavedState() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val viewModel = CloudflareToolsViewModel(FakeToolsGateway(), handle)
        assertFalse(viewModel.uiState.value.isOpen)
        assertFalse(viewModel.pop())

        viewModel.open(CloudflareToolRoute.Catalog("acc-1"))
        viewModel.push(CloudflareToolRoute.CatalogTag("acc-1", "DNS Records for a Zone"))
        viewModel.push(CloudflareToolRoute.CatalogOperation("acc-1", "dns-records-list"))
        viewModel.openExplorer("acc-1", preset(method = CloudflareHttpMethod.PATCH, body = "{\"x\":1}"))
        viewModel.updateDraft { it.copy(queryText = "page=2") }

        val restored = CloudflareToolsViewModel(FakeToolsGateway(), handle)
        assertEquals(viewModel.uiState.value.stack, restored.uiState.value.stack)
        assertEquals(4, restored.uiState.value.stack.size)
        val explorer = restored.uiState.value.explorer
        assertEquals("Preset", explorer.title)
        assertEquals(CloudflareHttpMethod.PATCH, explorer.draft.method)
        assertEquals("page=2", explorer.draft.queryText)
        assertEquals("{\"x\":1}", explorer.draft.bodyText)
        assertEquals(listOf("Zone Write"), explorer.permissions)

        assertTrue(restored.pop())
        assertTrue(restored.uiState.value.route is CloudflareToolRoute.CatalogOperation)
        restored.closeAll()
        assertFalse(restored.uiState.value.isOpen)
        assertTrue(CloudflareToolsViewModel(FakeToolsGateway(), handle).uiState.value.stack.isEmpty())
    }

    @Test
    fun routeEncodingRoundTripsEveryRoute() {
        val routes = listOf(
            CloudflareToolRoute.Account("a"),
            CloudflareToolRoute.AccountOperations("a"),
            CloudflareToolRoute.Catalog("a"),
            CloudflareToolRoute.CatalogTag("a", "Tag with spaces & symbols"),
            CloudflareToolRoute.CatalogOperation("a", "op-id"),
            CloudflareToolRoute.GraphQL("a"),
            CloudflareToolRoute.GraphQLDataset("a", "httpRequests1dGroups"),
            CloudflareToolRoute.ProductCenter("a"),
            CloudflareToolRoute.Explorer("a"),
            CloudflareToolRoute.Explorer(null),
        )
        routes.forEach { assertEquals(it, CloudflareToolRoute.decode(CloudflareToolRoute.encode(it))) }
        assertNull(CloudflareToolRoute.decode("unknown"))
        assertNull(CloudflareToolRoute.decode("account"))
    }

    @Test
    fun readsExecuteImmediatelyAndWritesWaitForConfirmation() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))

        viewModel.requestExecute()
        advanceUntilIdle()
        assertEquals(1, gateway.executions.size)
        assertNull(gateway.executions.single().confirmation)
        assertEquals(200, viewModel.uiState.value.explorer.response?.statusCode)

        viewModel.selectMethod(CloudflareHttpMethod.DELETE)
        assertNull(viewModel.uiState.value.explorer.response)
        viewModel.updateDraft { it.copy(path = "/zones/abc") }
        viewModel.requestExecute()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.explorer.showConfirmation)
        assertEquals(1, gateway.executions.size)

        // Back dismisses the confirmation before popping the screen.
        assertTrue(viewModel.pop())
        assertFalse(viewModel.uiState.value.explorer.showConfirmation)
        assertTrue(viewModel.uiState.value.isOpen)
        assertEquals(1, gateway.executions.size)

        viewModel.requestExecute()
        viewModel.confirmExecute()
        advanceUntilIdle()
        assertEquals(2, gateway.executions.size)
        assertEquals(CloudflareMutationConfirmation("/zones/abc"), gateway.executions.last().confirmation)
        assertEquals(CloudflareHttpMethod.DELETE, gateway.executions.last().draft.method)
        assertFalse(viewModel.uiState.value.explorer.showConfirmation)
    }

    @Test
    fun readOnlyGraphQLPresetsSkipConfirmation() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.openExplorer(
            "acc-1",
            CloudflareApiPreset(
                id = "graphql",
                title = "Dataset",
                summary = "",
                method = CloudflareHttpMethod.POST,
                path = "/graphql",
                body = "{\"query\":\"{ viewer { zones { zoneTag } } }\"}",
                readOnlyGraphQL = true,
            ),
        )
        viewModel.requestExecute()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.explorer.showConfirmation)
        assertNull(gateway.executions.single().confirmation)

        viewModel.updateDraft { it.copy(bodyText = "{\"query\":\"mutation { purge }\"}") }
        viewModel.requestExecute()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.explorer.showConfirmation)
        assertEquals(1, gateway.executions.size)
    }

    @Test
    fun failuresAndCancellationAreReportedSafely() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer(null))
        gateway.executeResult = Result.failure(CloudflareToolsUiException("Cloudflare could not be reached."))
        viewModel.requestExecute()
        advanceUntilIdle()
        assertEquals("Cloudflare could not be reached.", viewModel.uiState.value.explorer.error)
        assertTrue(viewModel.uiState.value.explorer.canClear)
        viewModel.clearResponse()
        assertNull(viewModel.uiState.value.explorer.error)

        val pending = CompletableDeferred<Result<CloudflareRawResponse>>()
        gateway.executeDeferred = pending
        viewModel.requestExecute()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.explorer.isExecuting)
        viewModel.cancelExecution()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.explorer.isExecuting)
        assertEquals("Request cancelled.", viewModel.uiState.value.explorer.notice)
        pending.complete(Result.success(ok()))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.explorer.response)
    }

    @Test
    fun quickPathsSplitQueriesAndResetToGet() = runTest(dispatcher) {
        val viewModel = CloudflareToolsViewModel(FakeToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))
        viewModel.selectMethod(CloudflareHttpMethod.POST)
        viewModel.applyQuickPath("/zones?account.id=acc-1&page=1")
        val draft = viewModel.uiState.value.explorer.draft
        assertEquals(CloudflareHttpMethod.GET, draft.method)
        assertEquals("/zones", draft.path)
        assertEquals("account.id=acc-1\npage=1", draft.queryText)
        viewModel.applyQuickPath("/accounts")
        assertEquals("", viewModel.uiState.value.explorer.draft.queryText)
    }

    @Test
    fun importedBodiesAreInlineBase64OrAttachedWhenLarge() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))
        viewModel.selectMethod(CloudflareHttpMethod.PUT)

        viewModel.importBody(byteArrayOf(1, 2, 3), "small.bin")
        var explorer = viewModel.uiState.value.explorer
        assertEquals("AQID", explorer.draft.bodyText)
        assertEquals(CloudflareBodyEncoding.BASE64, explorer.draft.bodyEncoding)
        assertEquals("application/octet-stream", explorer.draft.contentType)
        assertNull(explorer.attachment)

        val large = ByteArray(CloudflareToolsViewModel.INLINE_BASE64_LIMIT_BYTES + 1) { 7 }
        viewModel.importBody(large, "large.bin")
        explorer = viewModel.uiState.value.explorer
        assertEquals(CloudflareExplorerAttachmentUi("large.bin", large.size), explorer.attachment)
        assertEquals("", explorer.draft.bodyText)
        viewModel.requestExecute()
        viewModel.confirmExecute()
        advanceUntilIdle()
        assertArrayEquals(large, gateway.executions.single().attachedBody)

        viewModel.removeAttachment()
        assertNull(viewModel.uiState.value.explorer.attachment)
        viewModel.requestExecute()
        viewModel.confirmExecute()
        advanceUntilIdle()
        assertNull(gateway.executions.last().attachedBody)

        viewModel.importBody(ByteArray(25 * 1_024 * 1_024 + 1), "huge.bin")
        assertEquals(
            "Could not import the request body: Request body files must be 25 MB or smaller.",
            viewModel.uiState.value.explorer.error,
        )
    }

    @Test
    fun multipartBodiesSetContentTypeAndBase64() = runTest(dispatcher) {
        val viewModel = CloudflareToolsViewModel(FakeToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.Explorer("acc-1"))
        val body = CloudflareMultipartBuilder.compose(listOf(CloudflareMultipartPart(name = "a", value = "b")), boundary = "X")
        viewModel.applyMultipartBody(body)
        val draft = viewModel.uiState.value.explorer.draft
        assertEquals("multipart/form-data; boundary=X", draft.contentType)
        assertEquals(CloudflareBodyEncoding.BASE64, draft.bodyEncoding)
        assertArrayEquals(body.bytes(), Base64.getMimeDecoder().decode(draft.bodyText))
    }

    @Test
    fun reconcileClosesToolsForOtherAccountsOrDisconnectedDashboards() = runTest(dispatcher) {
        val viewModel = CloudflareToolsViewModel(FakeToolsGateway(), SavedStateHandle())
        viewModel.open(CloudflareToolRoute.GraphQL("acc-1"))
        viewModel.reconcile("acc-1", connected = true)
        assertTrue(viewModel.uiState.value.isOpen)
        viewModel.reconcile("acc-2", connected = true)
        assertFalse(viewModel.uiState.value.isOpen)

        viewModel.open(CloudflareToolRoute.Explorer(null))
        viewModel.reconcile("acc-2", connected = true)
        assertTrue(viewModel.uiState.value.isOpen)
        viewModel.reconcile("acc-2", connected = false)
        assertFalse(viewModel.uiState.value.isOpen)
    }

    @Test
    fun catalogLoadsOnceAndOperationsOpenTheExplorer() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        val route = CloudflareToolRoute.CatalogOperation("acc-1", "zone-patch")
        viewModel.open(route)
        viewModel.ensureLoaded(route, context)
        viewModel.ensureLoaded(route, context)
        advanceUntilIdle()
        assertEquals(1, gateway.catalogLoads)
        viewModel.ensureLoaded(route, context)
        val editor = viewModel.uiState.value.operationEditor!!
        assertEquals("zone-b", editor.values["path:zone_id"])
        assertEquals("{\"paused\":false}", editor.body)

        viewModel.updateOperationValue("path:zone_id", "zone a")
        viewModel.updateOperationBody("{\"paused\":true}")
        viewModel.reviewOperation("acc-1")
        val state = viewModel.uiState.value
        assertTrue(state.route is CloudflareToolRoute.Explorer)
        assertEquals("/zones/zone%20a", state.explorer.draft.path)
        assertEquals("{\"paused\":true}", state.explorer.draft.bodyText)
        assertEquals(CloudflareHttpMethod.PATCH, state.explorer.draft.method)
        assertEquals(listOf("Zone Write"), state.explorer.permissions)

        gateway.catalogResult = Result.failure(CloudflareToolsUiException("broken"))
        val failing = CloudflareToolsViewModel(gateway, SavedStateHandle())
        failing.ensureCatalog()
        advanceUntilIdle()
        assertEquals(CloudflareToolLoad.Failed("broken"), failing.uiState.value.catalog)
        // A failed catalog is not retried automatically; the user taps Retry.
        failing.ensureCatalog()
        advanceUntilIdle()
        assertEquals(2, gateway.catalogLoads)
        failing.reloadCatalog()
        advanceUntilIdle()
        assertEquals(3, gateway.catalogLoads)
    }

    @Test
    fun datasetsPickTheScopeCacheAndReloadOnScopeChanges() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle())
        val route = CloudflareToolRoute.GraphQL("acc-1")
        viewModel.open(route)
        viewModel.ensureLoaded(route, context)
        advanceUntilIdle()
        var datasets = viewModel.uiState.value.datasets
        assertEquals(CloudflareGraphQLScope.ZONE, datasets.scope)
        assertEquals("zone-b", datasets.zoneId)
        assertEquals(listOf("ZONE|zone-b"), gateway.datasetLoads)
        assertEquals(1, datasets.datasets.size)

        viewModel.ensureLoaded(route, context)
        viewModel.loadDatasets(forceRefresh = false)
        advanceUntilIdle()
        assertEquals(1, gateway.datasetLoads.size)

        viewModel.selectDatasetScope(CloudflareGraphQLScope.ACCOUNT)
        advanceUntilIdle()
        assertEquals("ACCOUNT|acc-1", gateway.datasetLoads.last())
        viewModel.selectDatasetZone("zone-a")
        advanceUntilIdle()
        assertEquals("ACCOUNT|acc-1", gateway.datasetLoads.last())
        // Account scope ignores the zone, so the cached account datasets are reused until a refresh.
        assertEquals(2, gateway.datasetLoads.size)
        viewModel.loadDatasets(forceRefresh = true)
        advanceUntilIdle()
        assertEquals(3, gateway.datasetLoads.size)

        val noZones = CloudflareToolsViewModel(gateway, SavedStateHandle())
        noZones.open(route)
        noZones.ensureLoaded(route, context.copy(zones = emptyList()))
        advanceUntilIdle()
        datasets = noZones.uiState.value.datasets
        assertEquals(CloudflareGraphQLScope.ACCOUNT, datasets.scope)
        noZones.selectDatasetScope(CloudflareGraphQLScope.ZONE)
        assertEquals(CloudflareGraphQLScope.ACCOUNT, noZones.uiState.value.datasets.scope)
    }

    @Test
    fun accountScreensLoadAndCacheTheirSections() = runTest(dispatcher) {
        val gateway = FakeToolsGateway()
        var now = 0L
        val viewModel = CloudflareToolsViewModel(gateway, SavedStateHandle()) { now }
        val account = CloudflareToolRoute.Account("acc-1")
        viewModel.open(account)
        viewModel.ensureLoaded(account, context)
        advanceUntilIdle()
        assertEquals(CloudflareToolLoad.Loaded(gateway.detail), viewModel.uiState.value.accountDetail)
        viewModel.ensureLoaded(account, context)
        advanceUntilIdle()
        assertEquals(1, gateway.detailLoads)

        val operations = CloudflareToolRoute.AccountOperations("acc-1")
        viewModel.push(operations)
        viewModel.ensureLoaded(operations, context)
        advanceUntilIdle()
        assertEquals(1, gateway.operationLoads)
        assertEquals("Studio", viewModel.uiState.value.accountOperations.snapshot?.account?.name)
        now = CloudflareToolsViewModel.CACHE_LIFETIME_MILLIS - 1
        viewModel.ensureLoaded(operations, context)
        advanceUntilIdle()
        assertEquals(1, gateway.operationLoads)
        now = CloudflareToolsViewModel.CACHE_LIFETIME_MILLIS + 1
        viewModel.ensureLoaded(operations, context)
        advanceUntilIdle()
        assertEquals(2, gateway.operationLoads)

        gateway.operationsResult = Result.failure(CloudflareToolsUiException("Connect a Cloudflare account first."))
        viewModel.loadAccountOperations("acc-1", forceRefresh = true)
        advanceUntilIdle()
        assertEquals("Connect a Cloudflare account first.", viewModel.uiState.value.accountOperations.error)
        assertEquals("Studio", viewModel.uiState.value.accountOperations.snapshot?.account?.name)
    }

    @Test
    fun productCenterDefaultsToTheFirstAvailableZone() = runTest(dispatcher) {
        val viewModel = CloudflareToolsViewModel(FakeToolsGateway(), SavedStateHandle())
        val route = CloudflareToolRoute.ProductCenter("acc-1")
        viewModel.open(route)
        viewModel.ensureLoaded(route, context)
        assertEquals("zone-b", viewModel.uiState.value.productZoneId)
        viewModel.selectProductZone("zone-a")
        viewModel.ensureLoaded(route, context)
        assertEquals("zone-a", viewModel.uiState.value.productZoneId)
        viewModel.ensureLoaded(route, context.copy(zones = emptyList()))
        assertNull(viewModel.uiState.value.productZoneId)
    }

    private fun preset(method: CloudflareHttpMethod, body: String = "") = CloudflareApiPreset(
        id = "preset",
        title = "Preset",
        summary = "Summary",
        method = method,
        path = "/zones/zone-1",
        body = body,
        permissions = listOf("Zone Write"),
    )
}

private fun ok() = CloudflareRawResponse(200, mapOf("Content-Type" to "application/json"), "{\"success\":true}".toByteArray())

internal class FakeToolsGateway : CloudflareToolsGateway {
    data class Execution(
        val draft: CloudflareExplorerDraft,
        val confirmation: CloudflareMutationConfirmation?,
        val attachedBody: ByteArray?,
    )

    override val isOfflineSample: Boolean = false
    val executions = mutableListOf<Execution>()
    var executeResult: Result<CloudflareRawResponse> = Result.success(ok())
    var executeDeferred: CompletableDeferred<Result<CloudflareRawResponse>>? = null
    var catalogLoads = 0
    var catalogResult: Result<CloudflareOpenApiCatalog> = Result.success(CloudflareOpenApiCatalogParser.parse(CATALOG))
    val datasetLoads = mutableListOf<String>()
    var detailLoads = 0
    val detail = CloudflareAccountDetail("acc-1", "Studio", "standard", null, true, null, null, null)
    var operationLoads = 0
    var operationsResult: Result<CloudflareAccountOperationsSnapshot> = Result.success(
        CloudflareAccountOperationsSnapshot(detail, emptyList(), emptyList(), emptyList(), null, null, null, null),
    )

    override suspend fun execute(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray?,
    ): Result<CloudflareRawResponse> {
        executions += Execution(draft, confirmation, attachedBody)
        return executeDeferred?.await() ?: executeResult
    }

    override suspend fun loadCatalog(): Result<CloudflareOpenApiCatalog> {
        catalogLoads += 1
        return catalogResult
    }

    override suspend fun loadDatasets(scope: CloudflareGraphQLScope, accountId: String, zoneId: String?): Result<List<CloudflareGraphQLDataset>> {
        datasetLoads += "$scope|${if (scope == CloudflareGraphQLScope.ZONE) zoneId else accountId}"
        return Result.success(listOf(CloudflareGraphQLDataset("httpRequests1dGroups", "", true, listOf("sum_requests"), 86_400, null, 100, 10)))
    }

    override suspend fun loadAccountDetail(accountId: String): Result<CloudflareAccountDetail> {
        detailLoads += 1
        return Result.success(detail)
    }

    override suspend fun loadAccountOperations(accountId: String): Result<CloudflareAccountOperationsSnapshot> {
        operationLoads += 1
        return operationsResult
    }

    companion object {
        val CATALOG = """
            {"schemaVersion":1,"openAPIVersion":"3.0.3","apiVersion":"4","sourceCommit":"abcdef0123","sourceURL":"https://example.com","operationCount":1,
             "operations":[{"id":"zone-patch","method":"PATCH","path":"/zones/{zone_id}","summary":"Edit Zone","description":"Edits a zone.",
               "tags":["Zone"],"deprecated":false,"permissions":["Zone Write"],"supportsGlobalKey":true,"supportsAPIToken":true,"supportsUserServiceKey":false,
               "parameters":[{"name":"zone_id","location":"path","required":true,"description":"Zone","type":"string"}],
               "contentTypes":["application/json"],"requestBodyRequired":true,"bodyTemplate":"{\"paused\":false}","multipartFields":[]}]}
        """.trimIndent()
    }
}
