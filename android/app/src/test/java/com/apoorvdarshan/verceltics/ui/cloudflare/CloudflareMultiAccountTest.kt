package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccount
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccountSummary
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionPayloadCodec
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionRepository
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionStore
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareDataSource
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflarePage
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflarePagesProject
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareProfile
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareReadApi
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareRestoreResult
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareStoredConnection
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareTokenVerification
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareUserIdentity
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareWorkerScript
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareZone
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultLayout
import com.apoorvdarshan.verceltics.data.hosting.SimpleMemoryStore
import com.apoorvdarshan.verceltics.data.hosting.TestAccountCipher
import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiple Cloudflare logins: lossless v1/v2 upgrade, identities, and the active credential everywhere. */
class CloudflareMultiAccountTest {
    private val primary = SimpleMemoryStore()
    private val repository = CloudflareConnectionRepository(
        primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, primary),
        TestAccountCipher(),
    )
    private val connectionStore = CloudflareConnectionStore(repository) { 1_000L }
    private val api = IdentityApi()
    private val transport = FakeCloudflareRestTransport()
    private val gateway = NativeCloudflareUiGateway(
        connectionStore = connectionStore,
        dataSource = CloudflareDataSource(api),
        networkExecutor = Executors.newSingleThreadExecutor(),
        storageExecutor = Executors.newSingleThreadExecutor(),
        restTransport = transport,
        profileApi = api,
    )

    @Test
    fun legacyVersionOneTokenRecordIsTheFirstLoginAndAddingAnotherKeepsItsBytes() = runTest {
        writeLegacyEnvelope(versionOneTokenPayload(token = "legacy-token", profileId = "tok-legacy"))
        val before = checkNotNull(primary.bytes).copyOf()

        val restored = connectionStore.restore() as CloudflareRestoreResult.Restored
        assertEquals(CloudflareAuthMode.API_TOKEN, restored.profile.authMode)
        assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, restored.savedAccountId)
        assertEquals(listOf("Legacy login"), gateway.savedLogins().getOrThrow().map { it.displayName })
        assertEquals(CloudflareCredential.apiToken("legacy-token"), gateway.loadSavedCredentialForTools())

        gateway.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-1")).getOrThrow()

        assertArrayEquals(before, primary.bytes)
        val logins = gateway.savedLogins().getOrThrow()
        assertEquals(listOf("Legacy login", "Ada Lovelace"), logins.map { it.displayName })
        assertEquals(listOf("Scoped API token", "owner@example.com"), logins.map { it.detail })
        assertEquals(listOf(false, true), logins.map { it.isActive })
    }

    @Test
    fun legacyVersionTwoGlobalKeyRecordUpgradesLosslessly() = runTest {
        val legacy = CloudflareStoredConnection(
            account = CloudflareAccount(
                profile = CloudflareProfile("user-1", "Ada Lovelace", "active", CloudflareAuthMode.GLOBAL_API_KEY, "owner@example.com"),
                credential = CloudflareCredential.globalApiKey("owner@example.com", "legacy-global"),
                createdAtMillis = 1L,
                updatedAtMillis = 2L,
            ),
            cachedSnapshot = null,
        )
        writeLegacyEnvelope(CloudflareConnectionPayloadCodec.encode(legacy))
        val before = checkNotNull(primary.bytes).copyOf()

        val restored = gateway.restore().getOrThrow() as CloudflareRestoreUi.SavedWithoutInventory
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, restored.profile.authMode)
        assertEquals("primary", restored.profile.savedAccountId)
        val tools = gateway.loadSavedCredentialForTools() as CloudflareCredential.GlobalApiKey
        assertEquals("legacy-global", tools.key.use { it })
        assertArrayEquals(before, primary.bytes)

        // Reconnecting the same Cloudflare user with a new key rotates the legacy login in place.
        gateway.connect(CloudflareCredential.globalApiKey("Owner@Example.com", "rotated-global")).getOrThrow()
        assertEquals(1, gateway.savedLogins().getOrThrow().size)
        assertEquals("rotated-global", (gateway.loadSavedCredentialForTools() as CloudflareCredential.GlobalApiKey).key.use { it })
    }

    @Test
    fun toolsOperationsAndStorageAlwaysUseTheActiveLoginsCredential() = runTest {
        gateway.connect(CloudflareCredential.apiToken("token-a")).getOrThrow()
        gateway.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-1")).getOrThrow()
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones", FakeCloudflareRestTransport.envelope("[]"))

        assertTrue(gateway.loadSavedCredentialForTools() is CloudflareCredential.GlobalApiKey)
        gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones")))
        assertEquals(mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-1"), transport.requests.last().authHeaders)

        val tokenLogin = gateway.savedLogins().getOrThrow().first()
        val switched = gateway.switchLogin(tokenLogin.id).getOrThrow() as CloudflareRestoreUi.Available
        assertEquals(tokenLogin.id, switched.dashboard.profile.savedAccountId)
        assertTrue(switched.dashboard.allowsR2)
        assertEquals(CloudflareCredential.apiToken("token-a"), gateway.loadSavedCredentialForTools())
        gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones")))
        assertEquals(mapOf("Authorization" to "Bearer token-a"), transport.requests.last().authHeaders)

        // Removing the active login hands every client the next one; removing all disconnects them.
        val next = gateway.removeLogin(tokenLogin.id).getOrThrow() as CloudflareRestoreUi.Available
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, next.dashboard.authMode)
        assertTrue(gateway.loadSavedCredentialForTools() is CloudflareCredential.GlobalApiKey)
        gateway.disconnect().getOrThrow()
        assertNull(gateway.loadSavedCredentialForTools())
        assertThrows(CloudflareOperationException::class.java) {
            kotlinx.coroutines.runBlocking {
                gateway.operationsClient().execute(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones")))
            }
        }
        assertTrue(gateway.savedLogins().getOrThrow().isEmpty())
    }

    @Test
    fun tokensAndGlobalKeysAreSeparateLoginsAndSameTokenIdentityRotatesInPlace() = runTest {
        gateway.connect(CloudflareCredential.apiToken("token-a")).getOrThrow()
        gateway.connect(CloudflareCredential.apiToken("token-b")).getOrThrow()
        gateway.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-1")).getOrThrow()
        assertEquals(3, gateway.savedLogins().getOrThrow().size)

        // `token-a-rotated` verifies as the same token id, so it replaces token-a's login.
        gateway.connect(CloudflareCredential.apiToken("token-a-rotated")).getOrThrow()
        val logins = gateway.savedLogins().getOrThrow()
        assertEquals(3, logins.size)
        assertEquals("primary", logins.single { it.isActive }.id)
        assertEquals(CloudflareCredential.apiToken("token-a-rotated"), gateway.loadSavedCredentialForTools())
    }

    @Test
    fun launchLoginRefreshRenamesGlobalKeyLoginsAndRefreshesTokenStatus() = runTest {
        gateway.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-1")).getOrThrow()
        gateway.connect(CloudflareCredential.apiToken("token-a")).getOrThrow()
        api.userName = "Ada King"
        api.tokenStatus = "disabled"

        val refreshed = gateway.refreshLoginProfiles().getOrThrow()

        assertEquals("Ada King", refreshed.first().displayName)
        val tokenProfile = connectionStore.accounts().last().profile
        assertEquals("disabled", tokenProfile?.tokenStatus)
        assertFalse(refreshed.toString().contains("global-1"))
    }

    @Test
    fun removingTheActiveLoginWithoutItsIdKeepsTheOthersAndMovesTheTools() = runTest {
        gateway.connect(CloudflareCredential.apiToken("token-a")).getOrThrow()
        gateway.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-1")).getOrThrow()

        val next = gateway.removeActiveLogin().getOrThrow() as CloudflareRestoreUi.Available

        assertEquals(CloudflareAuthMode.API_TOKEN, next.dashboard.authMode)
        assertEquals(1, gateway.savedLogins().getOrThrow().size)
        assertEquals(CloudflareCredential.apiToken("token-a"), gateway.loadSavedCredentialForTools())
    }

    private fun writeLegacyEnvelope(plaintext: ByteArray) {
        val sealed = TestAccountCipher().encrypt(
            plaintext,
            CloudflareConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8),
        )
        primary.bytes = AccountEnvelopeCodec.encode(sealed)
    }

    /** The original (token-only) version 1 payload. */
    private fun versionOneTokenPayload(token: String, profileId: String): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                fun string(value: String) {
                    val encoded = value.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeInt(1)
                string(CloudflareAccount.PROVIDER_ID)
                string(profileId)
                string("Legacy login")
                string("active")
                string(token)
                output.writeLong(10L)
                output.writeLong(100L)
                output.writeBoolean(false)
            }
        }.toByteArray()

    /** `token-x` and `token-x-rotated` verify as the same token id; the Global key is one user. */
    private class IdentityApi : CloudflareReadApi {
        @Volatile
        var userName = "Ada Lovelace"

        @Volatile
        var tokenStatus = "active"

        override fun newVerifyTokenCall(token: SecretValue): CancelableCall<CloudflareTokenVerification> = call {
            val raw = token.use { it }.removeSuffix("-rotated")
            CloudflareTokenVerification("tok-${raw.removePrefix("token-")}", tokenStatus, null, null)
        }

        override fun newUserCall(credential: CloudflareCredential.GlobalApiKey): CancelableCall<CloudflareUserIdentity> = call {
            val names = userName.split(' ')
            CloudflareUserIdentity("user-1", credential.email, names.first(), names.last(), false)
        }

        override fun newAccountsPageCall(credential: CloudflareCredential, page: Int, perPage: Int) = call {
            val name = when (credential) {
                is CloudflareCredential.ApiToken -> "Team ${credential.token.use { it }}"
                is CloudflareCredential.GlobalApiKey -> "Studio"
            }
            CloudflarePage(listOf(CloudflareAccountSummary("acc-0", name, null, null)), page, 1)
        }

        override fun newZonesPageCall(credential: CloudflareCredential, accountId: String, page: Int, perPage: Int) = call {
            CloudflarePage(listOf(CloudflareZone("zone-1", "example.com", "active", "full", false, "acc-0", "Studio", "Free")), page, 1)
        }

        override fun newPagesProjectsPageCall(credential: CloudflareCredential, accountId: String, page: Int, perPage: Int) = call {
            CloudflarePage(emptyList<CloudflarePagesProject>(), page, 1)
        }

        override fun newWorkerScriptsCall(credential: CloudflareCredential, accountId: String) =
            call { emptyList<CloudflareWorkerScript>() }

        private fun <T> call(block: () -> T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = block()
            override fun cancel() = Unit
        }
    }
}
