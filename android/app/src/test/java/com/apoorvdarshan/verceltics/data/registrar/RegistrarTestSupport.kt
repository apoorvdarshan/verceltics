package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** A materialized request as the registrar would see it (secrets included, test-only). */
internal data class RecordedRegistrarRequest(
    val origin: String,
    val path: String,
    val query: List<Pair<String, String>>,
    val secretQueryNames: Set<String>,
    val headers: Map<String, String>,
    val secretHeaderNames: Set<String>,
    val accept: String,
    val maximumResponseBytes: Int?,
) {
    fun queryValues(name: String): List<String> = query.filter { it.first == name }.map { it.second }

    fun queryValue(name: String): String? = queryValues(name).singleOrNull()

    companion object {
        fun from(request: RegistrarHttpRequest): RecordedRegistrarRequest = RecordedRegistrarRequest(
            origin = request.origin,
            path = request.path,
            query = request.queryParameters + request.secretQueryParameters.map { (name, secret) ->
                name to secret.use { it }
            },
            secretQueryNames = request.secretQueryParameters.map { it.first }.toSet(),
            headers = request.headers + request.secretHeaders.associate { (name, secret) ->
                name to secret.use { it }
            },
            secretHeaderNames = request.secretHeaders.map { it.first }.toSet(),
            accept = request.accept,
            maximumResponseBytes = request.maximumResponseBytes,
        )
    }
}

internal class FakeRegistrarResponse(
    val statusCode: Int = 200,
    val body: String = "",
    val error: Exception? = null,
)

/** In-process registrar origin: records each request and answers from [handler]. */
internal class FakeRegistrarTransport(
    private val handler: (RecordedRegistrarRequest) -> FakeRegistrarResponse,
) : RegistrarHttpTransport {
    val requests = CopyOnWriteArrayList<RecordedRegistrarRequest>()
    var onExecute: ((RecordedRegistrarRequest) -> Unit)? = null

    override fun newGetCall(request: RegistrarHttpRequest): CancelableCall<HttpResponse> {
        val recorded = RecordedRegistrarRequest.from(request)
        return object : CancelableCall<HttpResponse> {
            @Volatile
            private var cancelled = false

            override fun execute(): HttpResponse {
                if (cancelled) throw CancellationException("cancelled")
                requests += recorded
                onExecute?.invoke(recorded)
                if (cancelled) throw CancellationException("cancelled")
                val response = handler(recorded)
                response.error?.let { throw it }
                return HttpResponse(
                    statusCode = response.statusCode,
                    body = response.body.toByteArray(StandardCharsets.UTF_8),
                    headers = emptyMap(),
                )
            }

            override fun cancel() {
                cancelled = true
            }
        }
    }
}

internal class MemoryAtomicBytesStore : AtomicBytesStore {
    @Volatile
    var bytes: ByteArray? = null
    var failNextWrite: Exception? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        failNextWrite?.let {
            failNextWrite = null
            throw it
        }
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        bytes = null
    }
}

/** Deterministic AAD-bound XOR "cipher" for JVM tests (the Keystore cipher has its own tests). */
internal class TestAccountCipher : AccountCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
        SealedPayload(ByteArray(12) { 9 }, transform(plaintext, associatedData))

    override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
        transform(payload.ciphertext(), associatedData)

    private fun transform(input: ByteArray, associatedData: ByteArray): ByteArray =
        ByteArray(input.size) { index ->
            (input[index].toInt() xor associatedData[index % associatedData.size].toInt()).toByte()
        }
}

internal class RegistrarStorageFixture {
    val stores = RegistrarProvider.entries.associateWith { MemoryAtomicBytesStore() }
    val repository = RegistrarConnectionRepository({ stores.getValue(it) }, TestAccountCipher())
}

internal fun credentials(
    provider: RegistrarProvider,
    key: String = "${provider.id}-key",
    secret: String? = if (provider.requiresSecret) "${provider.id}-secret" else null,
    username: String = "alice",
    clientIp: String = "8.8.4.4",
    organization: String = "",
): RegistrarCredentials = RegistrarCredentials.fromInput(
    provider = provider,
    apiKey = SecretValue.of(key),
    apiSecret = secret?.let(SecretValue::of),
    username = username,
    clientIp = clientIp,
    organization = organization,
)

internal fun domain(
    name: String,
    expiresAtMillis: Long? = 1_800_000_000_000L,
    autoRenew: Boolean? = true,
    nameservers: List<String> = listOf("ns1.example.net", "ns2.example.net"),
): RegistrarDomain = RegistrarDomain(
    name = name,
    status = "Active",
    createdAtMillis = 1_500_000_000_000L,
    expiresAtMillis = expiresAtMillis,
    autoRenew = autoRenew,
    locked = true,
    privacyEnabled = false,
    nameservers = nameservers,
    metadata = mapOf("domainID" to "42"),
)

internal fun ok(body: String) = FakeRegistrarResponse(200, body)

internal fun utcMillis(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()
