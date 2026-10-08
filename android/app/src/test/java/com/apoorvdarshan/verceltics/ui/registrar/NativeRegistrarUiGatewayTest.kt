package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.registrar.FakeRegistrarResponse
import com.apoorvdarshan.verceltics.data.registrar.FakeRegistrarTransport
import com.apoorvdarshan.verceltics.data.registrar.MemoryAtomicBytesStore
import com.apoorvdarshan.verceltics.data.registrar.PublicIpv4Lookup
import com.apoorvdarshan.verceltics.data.registrar.RecordedRegistrarRequest
import com.apoorvdarshan.verceltics.data.registrar.RegistrarApi
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionRepository
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionStore
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.TestAccountCipher
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRegistrarUiGatewayTest {
    @Test
    fun connectValidatesPersistsEncryptedAndRestoresOffline() = runBlocking {
        val fixture = Fixture()
        try {
            val dashboard = fixture.gateway.connect(gandiRequest("pat-secret-token")).getOrThrow()

            assertEquals(RegistrarCacheState.LIVE, dashboard.cacheState)
            assertEquals("Example Org", dashboard.account.displayName)
            assertEquals(listOf("alpha.example", "Beta.example", "zeta.example"), dashboard.domains.map { it.name })
            val envelope = checkNotNull(fixture.stores.getValue(RegistrarProvider.GANDI).bytes)
            assertFalse(String(envelope, StandardCharsets.UTF_8).contains("pat-secret-token"))
            assertEquals("Bearer pat-secret-token", fixture.transport.requests.single().headers["Authorization"])

            val restored = fixture.gateway.restore().getOrThrow()
            val gandi = restored.providers.getValue("gandi") as RegistrarProviderRestoreUi.Available
            assertEquals(RegistrarCacheState.CACHED_FRESH, gandi.dashboard.cacheState)
            assertEquals(dashboard.domains, gandi.dashboard.domains)
            assertEquals(RegistrarProviderRestoreUi.NotConnected, restored.providers.getValue("porkbun"))
            assertEquals(RegistrarProvider.ids.toSet(), restored.providers.keys)
            assertFalse(restored.toString().contains("pat-secret-token"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rejectedCredentialsAreNeverSaved() = runBlocking {
        val fixture = Fixture(handler = { FakeRegistrarResponse(401, """{"code":401,"message":"Unauthorized"}""") })
        try {
            val result = fixture.gateway.connect(gandiRequest("bad-token"))

            assertEquals("Request failed (HTTP 401): Unauthorized", result.exceptionOrNull()?.message)
            assertTrue(result.exceptionOrNull() is RegistrarUiException)
            assertNull(fixture.stores.getValue(RegistrarProvider.GANDI).bytes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidFormInputFailsBeforeAnyNetworkRequest() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.gateway.connect(
                RegistrarConnectRequest(
                    providerId = "namecheap",
                    apiKey = SecretValue.of("key"),
                    username = "alice",
                    clientIp = "192.168.0.10",
                ),
            )

            assertEquals(
                "Enter a valid public IPv4 address that is whitelisted in Namecheap.",
                result.exceptionOrNull()?.message,
            )
            assertTrue(fixture.transport.requests.isEmpty())
            assertEquals(
                "This registrar is not supported.",
                fixture.gateway.connect(RegistrarConnectRequest("vercel", SecretValue.of("k"))).exceptionOrNull()?.message,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshUpdatesTheCacheAndFailuresPreserveIt() = runBlocking {
        var failing = false
        var domains = """[{"fqdn":"alpha.example"}]"""
        val fixture = Fixture(handler = {
            if (failing) FakeRegistrarResponse(503, "Service Unavailable") else FakeRegistrarResponse(200, domains)
        })
        try {
            fixture.gateway.connect(gandiRequest("token")).getOrThrow()
            domains = """[{"fqdn":"alpha.example"},{"fqdn":"new.example"}]"""
            fixture.now += 60_000L

            val refreshed = fixture.gateway.refresh("gandi").getOrThrow()
            assertEquals(listOf("alpha.example", "new.example"), refreshed.domains.map { it.name })
            assertEquals(fixture.now, refreshed.fetchedAtMillis)

            failing = true
            val failure = fixture.gateway.refresh("gandi")
            assertEquals("Request failed (HTTP 503): Service Unavailable", failure.exceptionOrNull()?.message)
            val restored = fixture.gateway.restore().getOrThrow().providers.getValue("gandi")
                as RegistrarProviderRestoreUi.Available
            assertEquals(listOf("alpha.example", "new.example"), restored.dashboard.domains.map { it.name })

            assertEquals("Connect Porkbun first.", fixture.gateway.refresh("porkbun").exceptionOrNull()?.message)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disconnectRemovesOnlyTheChosenRegistrar() = runBlocking {
        val fixture = Fixture(handler = { request ->
            if (request.origin == "https://api.gandi.net/") {
                FakeRegistrarResponse(200, """[{"fqdn":"g.example"}]""")
            } else {
                FakeRegistrarResponse(200, """{"status":"SUCCESS","domains":[{"domain":"p.example"}]}""")
            }
        })
        try {
            fixture.gateway.connect(gandiRequest("token")).getOrThrow()
            fixture.gateway.connect(
                RegistrarConnectRequest("porkbun", SecretValue.of("pk"), SecretValue.of("sk")),
            ).getOrThrow()

            fixture.gateway.disconnect("gandi").getOrThrow()

            val restored = fixture.gateway.restore().getOrThrow().providers
            assertEquals(RegistrarProviderRestoreUi.NotConnected, restored.getValue("gandi"))
            assertTrue(restored.getValue("porkbun") is RegistrarProviderRestoreUi.Available)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun accountsCanBeAddedSwitchedAndRemovedWithoutDisconnectingTheRegistrar() = runBlocking {
        val fixture = Fixture(handler = { request ->
            val token = request.headers["Authorization"].orEmpty()
            FakeRegistrarResponse(200, if (token.endsWith("pat-one")) """[{"fqdn":"one.example"}]""" else """[{"fqdn":"two.example"}]""")
        })
        try {
            val first = fixture.gateway.connect(gandiRequest("pat-one", organization = "One Org")).getOrThrow()
            val second = fixture.gateway.connect(gandiRequest("pat-two", organization = "Two Org")).getOrThrow()

            assertEquals("Two Org", second.account.displayName)
            assertEquals(listOf("One Org", "Two Org"), second.accounts.map { it.displayName })
            assertEquals(listOf(first.account.id, second.account.id), second.accounts.map { it.id })
            assertTrue(first.account.id.isNotEmpty() && first.account.id != second.account.id)

            val switched = fixture.gateway.switchAccount("gandi", first.account.id).getOrThrow()
                as RegistrarProviderRestoreUi.Available
            assertEquals(first.account.id, switched.dashboard.account.id)
            assertEquals(listOf("one.example"), switched.dashboard.domains.map { it.name })
            assertEquals(RegistrarCacheState.CACHED_FRESH, switched.dashboard.cacheState)

            // Refresh and the Complete API use the active account's credentials.
            fixture.transport.requests.clear()
            fixture.gateway.refresh("gandi").getOrThrow()
            assertEquals("Bearer pat-one", fixture.transport.requests.single().headers["Authorization"])

            val restored = fixture.gateway.restore().getOrThrow().providers.getValue("gandi")
                as RegistrarProviderRestoreUi.Available
            assertEquals(first.account.id, restored.dashboard.account.id)
            assertEquals(2, restored.dashboard.accounts.size)

            val afterRemoval = fixture.gateway.removeAccount("gandi", first.account.id).getOrThrow()
                as RegistrarProviderRestoreUi.Available
            assertEquals(second.account.id, afterRemoval.dashboard.account.id)
            assertEquals(listOf("two.example"), afterRemoval.dashboard.domains.map { it.name })
            assertEquals(listOf(second.account.id), afterRemoval.dashboard.accounts.map { it.id })

            assertEquals(
                "That Gandi account is no longer saved on this device.",
                fixture.gateway.switchAccount("gandi", first.account.id).exceptionOrNull()?.message,
            )
            assertEquals(
                RegistrarProviderRestoreUi.NotConnected,
                fixture.gateway.removeAccount("gandi", second.account.id).getOrThrow(),
            )
            assertNull(fixture.stores.getValue(RegistrarProvider.GANDI).bytes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unreadableRecordsRestoreAsAttentionWithoutDeletion() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.stores.getValue(RegistrarProvider.NAME_SILO).bytes = byteArrayOf(4, 4, 4, 4)

            val restored = fixture.gateway.restore().getOrThrow().providers.getValue("nameSilo")

            assertEquals(
                RegistrarProviderRestoreUi.SavedUnavailable(
                    "The saved NameSilo connection could not be opened. It was not deleted or replaced.",
                ),
                restored,
            )
            assertEquals(4, fixture.stores.getValue(RegistrarProvider.NAME_SILO).bytes?.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationDuringTheFirstEncryptedSaveRollsBackTheEmptySlot() = runBlocking {
        val blocking = BlockingStore()
        val fixture = Fixture(gandiStore = blocking)
        try {
            blocking.blockNextWrite()
            val job = async(Dispatchers.Default) { fixture.gateway.connect(gandiRequest("cancelled")) }
            assertTrue(blocking.awaitWriteStarted())

            job.cancel()
            blocking.releaseBlockedWrite()
            job.cancelAndJoin()

            assertNull(fixture.repository.load(RegistrarProvider.GANDI))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelledReplacementRestoresTheExactPriorRecord() = runBlocking {
        val blocking = BlockingStore()
        val fixture = Fixture(gandiStore = blocking)
        try {
            fixture.gateway.connect(gandiRequest("original")).getOrThrow()
            val original = checkNotNull(blocking.read())

            blocking.blockNextWrite()
            val job = async(Dispatchers.Default) { fixture.gateway.connect(gandiRequest("replacement")) }
            assertTrue(blocking.awaitWriteStarted())
            job.cancel()
            blocking.releaseBlockedWrite()
            job.cancelAndJoin()

            assertArrayEquals(original, blocking.read())
            assertEquals(
                SecretValue.of("original"),
                checkNotNull(fixture.repository.load(RegistrarProvider.GANDI)).active.connection.account.credentials.primary,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationAfterSaveButBeforeAcceptRestoresThePriorRecord() = runBlocking {
        var blockBeforeAccept = false
        val acceptStarted = CompletableDeferred<Unit>()
        val acceptRelease = CompletableDeferred<Unit>()
        val fixture = Fixture(beforeAccept = {
            if (blockBeforeAccept) {
                acceptStarted.complete(Unit)
                acceptRelease.await()
            }
        })
        try {
            fixture.gateway.connect(gandiRequest("original")).getOrThrow()
            val original = checkNotNull(fixture.stores.getValue(RegistrarProvider.GANDI).bytes)
            blockBeforeAccept = true

            val job = async(Dispatchers.Default) { fixture.gateway.connect(gandiRequest("replacement")) }
            acceptStarted.await()
            job.cancelAndJoin()

            assertArrayEquals(original, fixture.stores.getValue(RegistrarProvider.GANDI).bytes)
        } finally {
            acceptRelease.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun detectsPublicIpv4ThroughTheCredentialFreeLookup() = runBlocking {
        val fixture = Fixture(handler = { request ->
            if (request.origin == "https://api.ipify.org/") FakeRegistrarResponse(200, "203.0.114.7\n") else error("unexpected")
        })
        try {
            assertEquals("203.0.114.7", fixture.gateway.detectPublicIpv4().getOrThrow())
        } finally {
            fixture.close()
        }
    }

    private fun gandiRequest(token: String, organization: String = " Example Org ") = RegistrarConnectRequest(
        providerId = "gandi",
        apiKey = SecretValue.of(token),
        organization = organization,
    )

    private class Fixture(
        handler: (RecordedRegistrarRequest) -> FakeRegistrarResponse = {
            FakeRegistrarResponse(200, """[{"fqdn":"zeta.example"},{"fqdn":"alpha.example"},{"fqdn":"Beta.example"}]""")
        },
        gandiStore: AtomicBytesStore? = null,
        beforeAccept: suspend () -> Unit = {},
    ) {
        var now = 5_000_000L
        val transport = FakeRegistrarTransport(handler)
        val stores = RegistrarProvider.entries.associateWith { MemoryAtomicBytesStore() }
        val repository = RegistrarConnectionRepository(
            storeFactory = { provider ->
                if (provider == RegistrarProvider.GANDI && gandiStore != null) gandiStore else stores.getValue(provider)
            },
            cipher = TestAccountCipher(),
        )
        private val networkExecutor = Executors.newFixedThreadPool(2)
        private val storageExecutor = Executors.newSingleThreadExecutor()
        val gateway = NativeRegistrarUiGateway(
            connectionStore = RegistrarConnectionStore(repository) { now },
            api = RegistrarApi(transport),
            publicIpv4Lookup = PublicIpv4Lookup(transport),
            networkExecutor = networkExecutor,
            storageExecutor = storageExecutor,
            beforeAcceptValidatedConnection = beforeAccept,
        )

        fun close() {
            networkExecutor.shutdownNow()
            storageExecutor.shutdownNow()
        }
    }

    private class BlockingStore : AtomicBytesStore {
        @Volatile
        private var bytes: ByteArray? = null

        @Volatile
        private var blockNext = false
        private var writeStarted = CountDownLatch(1)
        private var release = CountDownLatch(1)

        fun blockNextWrite() {
            writeStarted = CountDownLatch(1)
            release = CountDownLatch(1)
            blockNext = true
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(5, TimeUnit.SECONDS)

        fun releaseBlockedWrite() = release.countDown()

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            if (blockNext) {
                blockNext = false
                writeStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }
    }
}
