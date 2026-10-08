package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccountInventory
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccountSummary
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionRepository
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionStore
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareDataSource
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflarePage
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflarePagesProject
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareProfile
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareReadApi
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareTokenVerification
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareUserIdentity
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareWorkerScript
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareZone
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCloudflareUiGatewayTest {
    private val api = RecordingReadApi()
    private val transport = FakeCloudflareRestTransport()
    private val gateway = NativeCloudflareUiGateway(
        connectionStore = CloudflareConnectionStore(CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryStore()), XorCipher())),
        dataSource = CloudflareDataSource(api),
        networkExecutor = Executors.newSingleThreadExecutor(),
        storageExecutor = Executors.newSingleThreadExecutor(),
        restTransport = transport,
    )

    @Test
    fun globalApiKeyConnectsPersistsEncryptedAndSignsRefreshToolsAndOperations() = runTest {
        val credential = CloudflareCredential.globalApiKey("Owner@Example.com", "global-key-123")

        val dashboard = gateway.connect(credential).getOrThrow()

        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, dashboard.authMode)
        assertEquals("owner@example.com", dashboard.profile.email)
        assertEquals("owner@example.com", dashboard.profile.credentialLabel)
        assertFalse(dashboard.allowsR2)
        assertEquals(1, api.userCalls)
        assertEquals(0, api.verifyCalls)

        // Restore is offline and credential-free, but keeps the auth mode for the UI.
        val restored = gateway.restore().getOrThrow() as CloudflareRestoreUi.Available
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, restored.dashboard.authMode)
        assertFalse(restored.toString().contains("global-key-123"))

        // Tools borrow the same saved credential.
        val toolsCredential = gateway.loadSavedCredentialForTools() as CloudflareCredential.GlobalApiKey
        assertEquals("owner@example.com", toolsCredential.email)
        assertEquals("global-key-123", toolsCredential.key.use { it })

        // Refresh re-validates with /user and the same key.
        gateway.refresh().getOrThrow()
        assertEquals(2, api.userCalls)
        assertTrue(api.credentials.all { it == credential })

        // Operations and storage requests carry X-Auth-Email / X-Auth-Key.
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc-0/d1/database", envelope("[]"))
        gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("accounts", "acc-0", "d1", "database")))
        assertEquals(
            mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key-123"),
            transport.requests.single().authHeaders,
        )
    }

    @Test
    fun scopedTokenConnectionKeepsBearerAuthAndAllowsR2() = runTest {
        val dashboard = gateway.connect(SecretValue.of("scoped-token")).getOrThrow()
        assertEquals(CloudflareAuthMode.API_TOKEN, dashboard.authMode)
        assertEquals("Scoped API token", dashboard.profile.credentialLabel)
        assertTrue(dashboard.allowsR2)
        assertEquals(1, api.verifyCalls)

        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones", envelope("[]"))
        gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones")))
        assertEquals(mapOf("Authorization" to "Bearer scoped-token"), transport.requests.single().authHeaders)
    }

    @Test
    fun dashboardListsEveryAccountAndResourceWithoutDisplayCaps() {
        val accounts = List(75) { CloudflareAccountSummary("acc-$it", "Account $it", null, null) }
        val snapshot = CloudflareSnapshot(
            profile = CloudflareProfile("p", "Owner", "active"),
            accounts = accounts,
            selectedAccountId = "acc-70",
            selectedAccountInventory = CloudflareAccountInventory(
                accountId = "acc-70",
                zones = List(1_200) { zone("zone-$it") },
                pagesProjects = List(400) { pages("site-$it") },
                workers = List(600) { CloudflareWorkerScript("worker-$it", null, null, null, emptyList(), null, null) },
                zonesComplete = true,
                pagesComplete = true,
                workersComplete = true,
                warnings = emptyList(),
            ),
            accountsComplete = true,
            fetchedAtMillis = 1L,
            warnings = emptyList(),
        )

        val dashboard = with(gateway) { snapshot.toDashboardUi(CloudflareCacheState.LIVE) }

        assertEquals(75, dashboard.accounts.size)
        assertFalse(dashboard.accountsTruncatedForDisplay)
        val inventory = checkNotNull(dashboard.inventory)
        assertEquals(1_200, inventory.zones.size)
        assertEquals(400, inventory.pagesProjects.size)
        assertEquals(600, inventory.workers.size)
        assertFalse(inventory.isPartial)
        assertFalse(dashboard.isPartial)
    }

    @Test
    fun operationsAndToolsWritesShareOneMutationStream() = runTest {
        gateway.connect(SecretValue.of("scoped-token")).getOrThrow()
        val events = async(start = CoroutineStart.UNDISPATCHED) { gateway.mutationEvents().take(2).toList() }
        yield()

        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/zone-1", envelope("{}"))
        gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.DELETE, listOf("zones", "zone-1")))
        gateway.publishToolsMutation(CloudflareMutationEvent(CloudflareHttpMethod.PUT, "/accounts/acc-0/workers/scripts/api"))

        assertEquals(
            listOf("/zones/zone-1", "/accounts/acc-0/workers/scripts/api"),
            events.await().map { it.apiPath },
        )
    }

    private class RecordingReadApi : CloudflareReadApi {
        var verifyCalls = 0
        var userCalls = 0
        val credentials = mutableListOf<CloudflareCredential>()

        override fun newVerifyTokenCall(token: SecretValue): CancelableCall<CloudflareTokenVerification> = call {
            verifyCalls += 1
            CloudflareTokenVerification("token-id", "active", null, null)
        }

        override fun newUserCall(credential: CloudflareCredential.GlobalApiKey): CancelableCall<CloudflareUserIdentity> = call {
            userCalls += 1
            credentials += credential
            CloudflareUserIdentity("user-id", "owner@example.com", "Ada", "Lovelace", false)
        }

        override fun newAccountsPageCall(credential: CloudflareCredential, page: Int, perPage: Int) = call {
            credentials += credential
            CloudflarePage(listOf(CloudflareAccountSummary("acc-0", "Studio", null, null)), page, 1)
        }

        override fun newZonesPageCall(credential: CloudflareCredential, accountId: String, page: Int, perPage: Int) = call {
            credentials += credential
            CloudflarePage(listOf(zone("zone-1")), page, 1)
        }

        override fun newPagesProjectsPageCall(credential: CloudflareCredential, accountId: String, page: Int, perPage: Int) = call {
            credentials += credential
            CloudflarePage(listOf(pages("site")), page, 1)
        }

        override fun newWorkerScriptsCall(credential: CloudflareCredential, accountId: String) = call {
            credentials += credential
            listOf(CloudflareWorkerScript("api", null, null, null, listOf("fetch"), null, true, routes = listOf("example.com/api/*")))
        }

        private fun <T> call(block: () -> T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = block()
            override fun cancel() = Unit
        }
    }

    private class MemoryStore : AtomicBytesStore {
        private var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }
        override fun delete() {
            bytes = null
        }
    }

    private class XorCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
            SealedPayload(ByteArray(12) { 7 }, transform(plaintext, associatedData))

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
            transform(payload.ciphertext(), associatedData)

        private fun transform(input: ByteArray, key: ByteArray): ByteArray =
            ByteArray(input.size) { (input[it].toInt() xor key[it % key.size].toInt()).toByte() }
    }

    private companion object {
        fun zone(id: String) = CloudflareZone(id, "$id.example", "active", "full", false, "acc-0", "Studio", "Free")

        fun pages(id: String) = CloudflarePagesProject(id, id, null, emptyList(), "main", null, "success")
    }
}
