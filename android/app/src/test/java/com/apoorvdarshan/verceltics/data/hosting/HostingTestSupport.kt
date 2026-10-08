package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scripted transport: records every request and answers through [handler]. */
internal class FakeHostingTransport(
    private val handler: (HostingHttpRequest) -> HttpResponse,
) : HostingHttpTransport {
    val requests = CopyOnWriteArrayList<HostingHttpRequest>()

    override suspend fun send(request: HostingHttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }

    fun paths(): List<String> = requests.map { it.encodedPath }
}

internal fun jsonResponse(
    body: String,
    status: Int = 200,
    headers: Map<String, List<String>> = emptyMap(),
): HttpResponse = HttpResponse(status, body.toByteArray(StandardCharsets.UTF_8), headers)

internal fun HostingHttpRequest.queryValue(name: String): String? = query.firstOrNull { it.first == name }?.second

internal fun HostingHttpRequest.bodyText(): String? = bodyCopy()?.toString(StandardCharsets.UTF_8)

internal fun HostingHttpRequest.bodyJson(): JsonObject = HostingJson.parse(checkNotNull(bodyCopy())).asObject()

internal fun HostingHttpRequest.graphqlQuery(): String = bodyJson().string("query").orEmpty()

internal fun HostingHttpRequest.graphqlVariables(): JsonObject = bodyJson()["variables"].asObject()

internal fun HostingHttpRequest.bearerToken(): String? = (auth as? HostingAuth.Bearer)?.token?.use { it }

internal class MemoryAtomicBytesStore : AtomicBytesStore {
    @Volatile
    var bytes: ByteArray? = null
    private var blockNextWrite = false
    private val writeStarted = CountDownLatch(1)
    private val writeRelease = CountDownLatch(1)

    @Synchronized
    fun blockNextWrite() {
        blockNextWrite = true
    }

    fun awaitWriteStarted(): Boolean = writeStarted.await(5, TimeUnit.SECONDS)

    fun releaseBlockedWrite() = writeRelease.countDown()

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        val shouldBlock = synchronized(this) {
            blockNextWrite.also { blockNextWrite = false }
        }
        if (shouldBlock) {
            writeStarted.countDown()
            writeRelease.await(5, TimeUnit.SECONDS)
        }
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        bytes = null
    }
}

/** Deterministic AEAD stand-in: the AAD is mixed in, so a slot mismatch corrupts the payload. */
internal class TestAccountCipher : AccountCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
        SealedPayload(ByteArray(12) { 9 }, transform(plaintext, associatedData))

    override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
        transform(payload.ciphertext(), associatedData)

    private fun transform(bytes: ByteArray, associatedData: ByteArray): ByteArray = ByteArray(bytes.size) { index ->
        (bytes[index].toInt() xor associatedData[index % associatedData.size].toInt() xor 0x5A).toByte()
    }
}

internal class HostingStoreFixture {
    val stores: Map<HostingProvider, MemoryAtomicBytesStore> =
        HostingProvider.entries.associateWith { MemoryAtomicBytesStore() }
    /** Every path other than a provider's pre-multi-account record (index and added accounts). */
    val files = MemoryFiles(HostingProvider.entries.associate { HostingConnectionRepository.accountPath(it) to stores.getValue(it) })
    val repository = HostingConnectionRepository(storeFactory = files, cipher = TestAccountCipher())
}

internal fun profile(id: String = "profile-1", name: String = "Studio") = HostingProfile(id, name, "owner@example.com", null)

internal fun resource(id: String, name: String = id, metadata: Map<String, String> = emptyMap()) = HostingResource(
    id = id,
    name = name,
    subtitle = "subtitle-$id",
    url = "https://$id.example",
    status = "Active",
    region = "oregon",
    kind = "Web Service",
    updatedAtMillis = 1_000L,
    metadata = metadata,
)
