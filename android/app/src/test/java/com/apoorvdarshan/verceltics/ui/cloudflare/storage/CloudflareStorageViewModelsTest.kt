package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.DELETE
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.GET
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.POST
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.PUT
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVKey
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
class CloudflareStorageViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeCloudflareRestTransport()
    private val api = CloudflareStorageApi(FakeCloudflareRestTransport.client(transport))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun stubDashboard(d1: String = "[{\"uuid\":\"db\",\"name\":\"beta\"},{\"uuid\":\"db0\",\"name\":\"Alpha\"}]") {
        transport.alwaysJson(GET, "/accounts/acc/d1/database", envelope(d1))
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces", envelope("[{\"id\":\"ns\",\"title\":\"CACHE\"}]"))
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets", envelope("{\"buckets\":[{\"name\":\"media\"}]}"))
    }

    @Test
    fun dashboardLoadsSortedInventoryAndReportsSectionWarnings() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/d1/database", envelope("[{\"uuid\":\"db\",\"name\":\"beta\"},{\"uuid\":\"db0\",\"name\":\"Alpha\"}]"))
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces", "{}", status = 403)
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets", envelope("{\"buckets\":[{\"name\":\"media\"}]}"))
        val model = CloudflareStorageDashboardViewModel(api, "acc")
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(listOf("Alpha", "beta"), state.databases.map { it.name })
        assertEquals(setOf("media"), state.buckets.map { it.name }.toSet())
        assertEquals(listOf("KV: This Cloudflare user cannot access that resource."), state.warnings)
        assertFalse(state.isLoading)
    }

    @Test
    fun creatingStorageRequiresConfirmationAndReconcilesTheList() = runTest(dispatcher) {
        stubDashboard()
        val model = CloudflareStorageDashboardViewModel(api, "acc")
        advanceUntilIdle()
        model.openCreation(CloudflareStorageCreation.D1)
        model.requestCreateD1(CloudflareD1CreateInput(" gamma ", readReplicationMode = "disabled"))
        advanceUntilIdle()
        val prompt = requireNotNull(model.confirmation.value)
        assertEquals("Create this D1 database?", prompt.title)
        assertEquals("Cloudflare will create gamma in this account.", prompt.message)
        assertFalse(prompt.destructive)
        assertTrue(transport.mutations().isEmpty())

        transport.enqueueJson(POST, "/accounts/acc/d1/database", envelope("{\"uuid\":\"g\",\"name\":\"gamma\"}"))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals("gamma", transport.mutations().single().bodyJson.str("name"))
        assertEquals(listOf("Alpha", "beta", "gamma"), model.state.value.databases.map { it.name })
        assertNull(model.state.value.creation)
        assertEquals(CloudflareActionBanner("D1 database created.", isError = false), model.banner.value)
    }

    @Test
    fun invalidBucketNamesFailBeforeAnyConfirmation() = runTest(dispatcher) {
        stubDashboard()
        val model = CloudflareStorageDashboardViewModel(api, "acc")
        advanceUntilIdle()
        model.openCreation(CloudflareStorageCreation.R2)
        model.requestCreateR2(CloudflareR2CreateInput("Bad_Name"))
        assertNull(model.confirmation.value)
        assertEquals(
            "R2 bucket names must be 3–63 lowercase letters, numbers, or hyphens, and cannot start or end with a hyphen.",
            model.state.value.creationError,
        )

        model.requestCreateR2(CloudflareR2CreateInput("media-2", jurisdiction = "eu"))
        assertEquals(
            "Cloudflare will create the bucket media-2 in the EU jurisdiction. Bucket names cannot be changed after creation.",
            model.confirmation.value?.message,
        )
        model.dismissPendingMutation()
        advanceUntilIdle()
        assertTrue(transport.mutations().isEmpty())
    }

    @Test
    fun storageChangesElsewhereReloadTheDashboardWhenItReappears() = runTest(dispatcher) {
        stubDashboard()
        val events = MutableSharedFlow<CloudflareMutationEvent>(extraBufferCapacity = 4)
        val model = CloudflareStorageDashboardViewModel(api, "acc", events)
        advanceUntilIdle()
        val loads = transport.requests.size

        events.emit(CloudflareMutationEvent(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/r"))
        advanceUntilIdle()
        model.onAppear()
        advanceUntilIdle()
        assertEquals(loads, transport.requests.size)

        events.emit(CloudflareMutationEvent(CloudflareHttpMethod.DELETE, "/accounts/acc/d1/database/db"))
        advanceUntilIdle()
        model.onAppear()
        advanceUntilIdle()
        // D1 + KV + R2 default, EU and FedRAMP scopes.
        assertEquals(loads + 5, transport.requests.size)
    }

    @Test
    fun d1ConsoleConfirmsEveryStatementAndRefreshesChangedDatabases() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/d1/database/db", envelope("{\"uuid\":\"db\",\"name\":\"main\",\"num_tables\":1}"))
        val model = CloudflareD1DatabaseViewModel(api, "acc", "db", "main")
        advanceUntilIdle()
        assertEquals(1, model.state.value.database.numberOfTables)

        model.requestRun("  ")
        assertNull(model.confirmation.value)
        model.requestRun("DELETE FROM users;")
        val prompt = requireNotNull(model.confirmation.value)
        assertEquals("Run this SQL statement?", prompt.title)
        assertTrue(prompt.message.startsWith("D1 accepts both read and write SQL here. Review the statement before running it."))
        assertTrue(prompt.message.endsWith("DELETE FROM users;"))
        assertEquals("db", prompt.resourceId)

        transport.enqueueJson(
            POST,
            "/accounts/acc/d1/database/db/query",
            envelope("[{\"success\":true,\"results\":[],\"meta\":{\"changed_db\":true,\"changes\":3}}]"),
        )
        val before = transport.requests.size
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf(POST, GET), transport.requests.drop(before).map { it.method })
        assertEquals(CloudflareActionBanner("Query returned 0 rows.", isError = false), model.banner.value)
        assertEquals(1, model.state.value.queryResults.size)
    }

    @Test
    fun d1DeleteIsDestructiveAndMarksTheScreenDeleted() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/d1/database/db", envelope("{\"uuid\":\"db\",\"name\":\"main\"}"))
        transport.enqueueJson(DELETE, "/accounts/acc/d1/database/db", "")
        val model = CloudflareD1DatabaseViewModel(api, "acc", "db", "main")
        advanceUntilIdle()
        model.requestDelete()
        val prompt = requireNotNull(model.confirmation.value)
        assertTrue(prompt.destructive)
        assertEquals("main and all of its data will be permanently deleted.", prompt.message)
        assertEquals("Delete Database", prompt.confirmLabel)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.didDelete)
        assertEquals(DELETE, transport.mutations().single().method)
    }

    @Test
    fun kvEditorReadsValidatesAndWritesOnlyAfterConfirmation() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces/ns/keys", envelope("[{\"name\":\"theme\",\"expiration\":1000}]"))
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces/ns/values/theme", "dark")
        val model = CloudflareKVNamespaceViewModel(api, "acc", "ns", "CACHE", nowSeconds = { 900.0 })
        advanceUntilIdle()
        val key = model.state.value.keys.single()

        model.openEditor(key)
        assertEquals("100", model.state.value.editor?.expirationTtl)
        advanceUntilIdle()
        assertEquals("dark", model.state.value.editor?.value)
        assertTrue(model.state.value.editor?.canSave == true)

        model.updateEditor { it.copy(expirationTtl = "30") }
        model.requestSaveValue()
        assertEquals("Expiration must be a whole number of at least 60 seconds.", model.state.value.editor?.error)
        model.updateEditor { it.copy(expirationTtl = "", encoding = CloudflareKVEncoding.BASE64, value = "not base64!") }
        model.requestSaveValue()
        assertEquals("The value is not valid Base64 data.", model.state.value.editor?.error)
        assertNull(model.confirmation.value)

        model.updateEditor { it.copy(encoding = CloudflareKVEncoding.TEXT, value = "light") }
        model.requestSaveValue()
        assertEquals("This overwrites the current value for theme.", model.confirmation.value?.message)
        transport.enqueueJson(PUT, "/accounts/acc/storage/kv/namespaces/ns/values/theme", envelope("null"))
        model.confirmPendingMutation()
        advanceUntilIdle()
        val write = transport.mutations().single()
        assertArrayEquals("light".toByteArray(StandardCharsets.UTF_8), write.request.bodyCopy())
        assertEquals("text/plain; charset=utf-8", write.request.contentType)
        assertNull(model.state.value.editor)
        assertEquals(CloudflareActionBanner("KV value saved.", isError = false), model.banner.value)
    }

    @Test
    fun kvKeyAndNamespaceDeletesAreDestructive() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces/ns/keys", envelope("[{\"name\":\"a/b\"}]"))
        transport.enqueueJson(DELETE, "/accounts/acc/storage/kv/namespaces/ns/values/a%2Fb", envelope("null"))
        transport.enqueueJson(DELETE, "/accounts/acc/storage/kv/namespaces/ns", envelope("null"))
        val model = CloudflareKVNamespaceViewModel(api, "acc", "ns", "CACHE")
        advanceUntilIdle()

        model.requestDeleteKey(CloudflareKVKey("a/b", null, null))
        assertEquals("a/b will be permanently removed.", model.confirmation.value?.message)
        assertTrue(model.confirmation.value?.destructive == true)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.keys.isEmpty())

        model.requestDeleteNamespace()
        assertEquals("CACHE and every key in it will be permanently deleted.", model.confirmation.value?.message)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.didDelete)
        assertEquals(2, transport.mutations().size)
    }

    @Test
    fun kvRenameStatesTheChange() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces/ns/keys", envelope("[]"))
        transport.enqueueJson(PUT, "/accounts/acc/storage/kv/namespaces/ns", envelope("{\"id\":\"ns\",\"title\":\"SESSIONS\"}"))
        val model = CloudflareKVNamespaceViewModel(api, "acc", "ns", "CACHE")
        advanceUntilIdle()
        model.openRename()
        model.requestRename("  ")
        assertEquals("Enter a KV namespace title.", model.state.value.renameError)
        model.requestRename("SESSIONS")
        assertEquals("CACHE will be renamed to SESSIONS.", model.confirmation.value?.message)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals("SESSIONS", model.state.value.namespace.title)
        assertFalse(model.state.value.isRenaming)
    }

    @Test
    fun r2UploadDownloadAndDeleteObjects() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets/media", envelope("{\"name\":\"media\"}"))
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets/media/objects", envelope("[{\"key\":\"docs/readme.txt\",\"size\":5}]"))
        val files = FakeFiles(mapOf("content://picked" to CloudflarePickedFile("readme.txt", "text/plain", "hello".toByteArray())))
        val model = CloudflareR2BucketViewModel(api, files, "acc", "media", "eu")
        advanceUntilIdle()
        model.openFolder("docs/")
        advanceUntilIdle()
        assertEquals("docs/readme.txt", model.state.value.objects.single().key)

        model.prepareUpload("content://picked")
        advanceUntilIdle()
        assertEquals("docs/readme.txt", model.state.value.upload?.key)
        model.requestUpload()
        val prompt = requireNotNull(model.confirmation.value)
        assertEquals("Replace Object", prompt.confirmLabel)
        assertTrue(prompt.destructive)
        assertEquals(
            "readme.txt (5 bytes) will be uploaded to media as docs/readme.txt. The existing object with this key will be replaced.",
            prompt.message,
        )
        transport.enqueueJson(PUT, "/accounts/acc/r2/buckets/media/objects/docs/readme.txt", "")
        model.confirmPendingMutation()
        advanceUntilIdle()
        val upload = transport.mutations().single()
        assertEquals("eu", upload.request.headers["cf-r2-jurisdiction"])
        assertEquals("text/plain", upload.request.contentType)
        assertNull(model.state.value.upload)

        transport.enqueue(GET, "/accounts/acc/r2/buckets/media/objects/docs/readme.txt", FakeCloudflareRestTransport.raw("hello".toByteArray()))
        model.download("docs/readme.txt", "content://saved")
        advanceUntilIdle()
        assertArrayEquals("hello".toByteArray(), files.written["content://saved"])

        transport.enqueueJson(DELETE, "/accounts/acc/r2/buckets/media/objects/docs/readme.txt", "")
        model.requestDeleteObject(model.state.value.objects.single())
        assertEquals("docs/readme.txt will be permanently deleted from media.", model.confirmation.value?.message)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.objects.isEmpty())
        assertEquals(listOf(PUT, DELETE), transport.mutations().map { it.method })
    }

    @Test
    fun r2BucketDeleteIsConfirmedDestructively() = runTest(dispatcher) {
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets/media", envelope("{\"name\":\"media\"}"))
        transport.alwaysJson(GET, "/accounts/acc/r2/buckets/media/objects", envelope("[]"))
        transport.enqueueJson(DELETE, "/accounts/acc/r2/buckets/media", "")
        val model = CloudflareR2BucketViewModel(api, null, "acc", "media", null)
        advanceUntilIdle()
        model.requestDeleteBucket()
        val prompt = requireNotNull(model.confirmation.value)
        assertTrue(prompt.destructive)
        assertEquals(
            "Cloudflare only deletes an empty bucket. media and all of its configuration will be permanently removed.",
            prompt.message,
        )
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.didDelete)
        assertNull(transport.mutations().single().request.headers["cf-r2-jurisdiction"])
    }

    private class FakeFiles(private val files: Map<String, CloudflarePickedFile>) : CloudflareStorageFiles {
        val written = mutableMapOf<String, ByteArray>()

        override suspend fun read(uri: String, maximumBytes: Int): CloudflarePickedFile = requireNotNull(files[uri])

        override suspend fun write(uri: String, data: ByteArray) {
            written[uri] = data.copyOf()
        }
    }
}
