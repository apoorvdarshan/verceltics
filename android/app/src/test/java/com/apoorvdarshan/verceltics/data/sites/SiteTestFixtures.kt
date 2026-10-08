package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsRequest
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.ZoneId
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Executes provider calls inline so unit tests stay deterministic. */
object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

class MemoryBytesStore : AtomicBytesStore {
    var bytes: ByteArray? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        bytes = null
    }
}

/** Real AES-256-GCM on the JVM, so tests prove authenticated-data binding. */
class AesGcmTestCipher : AccountCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return SealedPayload(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.initializationVector()))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.ciphertext())
    }
}

class FakeResponse(
    val status: Int = 200,
    val body: String = "{}",
    val headers: Map<String, List<String>> = emptyMap(),
)

/** One captured request, decoded for assertions (the fake never sees real credentials). */
class RecordedRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String>,
    val bearer: String?,
    val secretHeaders: Map<String, String>,
    val body: String?,
) {
    val path: String get() = uri.rawPath
    val query: Map<String, String> get() = decodeForm(uri.rawQuery)
    val form: Map<String, String> get() = decodeForm(body)
    val json: ProviderJsonValue get() = ProviderJsonParser.parse(body ?: "{}")
}

fun decodeForm(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('&').associate { pair ->
        val separator = pair.indexOf('=')
        val name = if (separator < 0) pair else pair.substring(0, separator)
        val value = if (separator < 0) "" else pair.substring(separator + 1)
        URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

/** Routes requests to a handler and records them, like the iOS `URLProtocol` mocks. */
class FakeProviderTransport(
    private val handler: (RecordedRequest) -> FakeResponse,
) : ProviderHttpsTransport {
    private val recorded = ArrayList<RecordedRequest>()

    val requests: List<RecordedRequest> get() = synchronized(recorded) { recorded.toList() }

    override fun newCall(request: ProviderHttpsRequest): CancelableCall<HttpResponse> {
        val captured = RecordedRequest(
            method = request.method,
            uri = request.uri,
            headers = request.headers,
            bearer = request.bearerToken?.use { it },
            secretHeaders = request.secretHeaders.mapValues { (_, value) -> value.use { it } },
            body = request.peekBody()?.toString(StandardCharsets.UTF_8),
        )
        return object : CancelableCall<HttpResponse> {
            override fun execute(): HttpResponse {
                synchronized(recorded) { recorded += captured }
                val response = handler(captured)
                return HttpResponse(response.status, response.body.toByteArray(StandardCharsets.UTF_8), response.headers)
            }

            override fun cancel() = Unit
        }
    }
}

fun json(vararg pairs: Pair<String, Any?>): String = ProviderJsonWriter.write(ProviderJsonValue.from(linkedMapOf(*pairs)))

fun jsonArray(vararg items: Any?): String = ProviderJsonWriter.write(ProviderJsonValue.from(items.toList()))

fun ok(body: String) = FakeResponse(200, body)

val TEST_ZONE: ZoneId = ZoneId.of("UTC")

/** 2026-07-15T00:00:00Z — a fixed "now" for deterministic ranges and cutoffs. */
const val TEST_NOW_MILLIS: Long = 1_784_073_600_000L

fun testApi(
    transport: ProviderHttpsTransport,
    sleeps: MutableList<Long> = ArrayList(),
    nowMillis: Long = TEST_NOW_MILLIS,
): SiteServicesApi = SiteServicesApi(
    SiteApiContext(
        http = SiteHttpClient(transport, DirectExecutor) { sleeps += it },
        nowMillis = { nowMillis },
        zone = { TEST_ZONE },
    ),
)

fun testRange(days: Int = 30, nowMillis: Long = TEST_NOW_MILLIS): SiteDetailRange =
    SiteDetailRange.lastDays(days, nowMillis, TEST_ZONE)
