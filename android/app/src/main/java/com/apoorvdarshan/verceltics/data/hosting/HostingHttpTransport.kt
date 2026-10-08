package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderEndpointPolicy
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * An exact HTTPS origin a hosting adapter may call. Instances can only be created from the fixed
 * provider list or from a validated AWS region, so no user input can change the authority.
 */
class HostingEndpoint private constructor(baseUrl: String) {
    internal val policy = ProviderEndpointPolicy(baseUrl)

    val host: String
        get() = policy.baseUri.host

    override fun toString(): String = "HostingEndpoint(${policy.baseUri})"

    companion object {
        val RAILWAY = HostingEndpoint("https://backboard.railway.com/")
        val RENDER = HostingEndpoint("https://api.render.com/")
        val DIGITAL_OCEAN = HostingEndpoint("https://api.digitalocean.com/")
        val HEROKU = HostingEndpoint("https://api.heroku.com/")
        val FLY = HostingEndpoint("https://api.machines.dev/")
        val FIREBASE = HostingEndpoint("https://firebasehosting.googleapis.com/")
        val GOOGLE_OPENID = HostingEndpoint("https://openidconnect.googleapis.com/")

        /** iOS `awsAmplifyEndpoint`: `amplify.<region>.amazonaws.com` for a validated region only. */
        fun amplify(region: String): HostingEndpoint {
            AwsSigV4Signer.requireStandardRegion(region)
            return HostingEndpoint("https://amplify.$region.amazonaws.com/").also { endpoint ->
                check(endpoint.host == "amplify.$region.amazonaws.com") { "The Amplify endpoint is invalid." }
            }
        }
    }
}

enum class HostingHttpMethod {
    GET,
    POST,
    DELETE,
}

/** How the transport authenticates a request. Secrets are only materialized while sending. */
sealed class HostingAuth {
    data object None : HostingAuth()

    class Bearer(val token: SecretValue) : HostingAuth()

    /** Railway project tokens use a dedicated header instead of `Authorization`. */
    class RailwayProjectToken(val token: SecretValue) : HostingAuth()

    class AwsSigV4(
        val accessKeyId: String,
        val secretAccessKey: SecretValue,
        val sessionToken: SecretValue?,
        val region: String,
        val service: String,
    ) : HostingAuth()

    override fun toString(): String = "HostingAuth.${this::class.simpleName}(<redacted>)"
}

class HostingHttpRequest(
    val endpoint: HostingEndpoint,
    val method: HostingHttpMethod,
    /** Raw, unencoded path segments; every segment is percent-encoded by the transport. */
    val pathSegments: List<String>,
    val query: List<Pair<String, String>> = emptyList(),
    /** Non-credential headers such as Heroku's `Range` and `Accept`. */
    val headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    val contentType: String? = if (body == null) null else JSON_CONTENT_TYPE,
    val auth: HostingAuth,
) {
    private val storedBody = body?.copyOf()

    init {
        require(pathSegments.isNotEmpty() && pathSegments.size <= MAX_PATH_SEGMENTS) { "Invalid provider path." }
        pathSegments.forEach { segment ->
            require(segment.isNotEmpty() && segment.length <= MAX_SEGMENT_CHARACTERS) {
                "Invalid provider path segment."
            }
            require(segment != "." && segment != "..") { "Provider path traversal is not allowed." }
        }
        require(query.size <= MAX_QUERY_PARAMETERS) { "Too many query parameters." }
        query.forEach { (name, value) ->
            require(name.isNotBlank() && name.length <= MAX_QUERY_CHARACTERS && value.length <= MAX_QUERY_CHARACTERS) {
                "Invalid query parameter."
            }
        }
        headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name." }
            require(name.lowercase(Locale.ROOT) !in PROTECTED_HEADERS && !name.lowercase(Locale.ROOT).startsWith("x-amz-")) {
                "The $name header is controlled by the hosting transport."
            }
            require(value.length <= MAX_HEADER_CHARACTERS && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Invalid HTTP header value."
            }
        }
        require(method != HostingHttpMethod.GET || body == null) { "GET requests cannot carry a body." }
        require(storedBody == null || storedBody.size <= MAX_REQUEST_BODY_BYTES) { "The request body is too large." }
        require(contentType == null || contentType.none { it == '\r' || it == '\n' }) { "Invalid content type." }
    }

    fun bodyCopy(): ByteArray? = storedBody?.copyOf()

    val encodedPath: String
        get() = pathSegments.joinToString("/", prefix = "/") { AwsSigV4Signer.encode(it) }

    val encodedQuery: String?
        get() = query.takeIf { it.isNotEmpty() }
            ?.joinToString("&") { (name, value) -> "${AwsSigV4Signer.encode(name)}=${AwsSigV4Signer.encode(value)}" }

    fun uri(): URI {
        val base = endpoint.policy.baseUri.toASCIIString().trimEnd('/')
        val uri = URI(base + encodedPath + (encodedQuery?.let { "?$it" } ?: ""))
        check(endpoint.policy.isSameOrigin(uri)) { "Hosting request escaped its provider origin." }
        return uri
    }

    override fun toString(): String =
        "HostingHttpRequest(endpoint=$endpoint, method=$method, path=$encodedPath, " +
            "queryCount=${query.size}, body=${if (storedBody == null) "none" else "<redacted>"}, auth=$auth)"

    companion object {
        const val JSON_CONTENT_TYPE: String = "application/json"
        private const val MAX_PATH_SEGMENTS = 16
        private const val MAX_SEGMENT_CHARACTERS = 1_024
        private const val MAX_QUERY_PARAMETERS = 32
        private const val MAX_QUERY_CHARACTERS = 4_096
        private const val MAX_HEADER_CHARACTERS = 1_024
        private const val MAX_REQUEST_BODY_BYTES = 256 * 1_024
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")
        internal val PROTECTED_HEADERS = setOf(
            "authorization",
            "project-access-token",
            "host",
            "content-length",
            "content-type",
            "cookie",
            "proxy-authorization",
        )
    }
}

/** A request ready to send. Contains materialized credentials, so it never prints them. */
internal class PreparedHostingRequest(
    val uri: URI,
    val method: HostingHttpMethod,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
) {
    fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String =
        "PreparedHostingRequest(method=$method, uri=$uri, headerNames=${headers.map { it.first }})"
}

/** Builds the exact wire headers, including SigV4 signing for Amplify. */
internal fun HostingHttpRequest.prepare(instant: Instant): PreparedHostingRequest {
    val uri = uri()
    val body = bodyCopy()
    val headers = mutableListOf<Pair<String, String>>()
    headers += "Accept" to (this.headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: JSON_ACCEPT)
    headers += "Accept-Encoding" to "identity"
    headers += "User-Agent" to "Verceltics-Android/${BuildConfig.VERSION_NAME}"
    this.headers.filterKeys { !it.equals("Accept", true) }.forEach { (name, value) -> headers += name to value }
    when (val auth = auth) {
        HostingAuth.None -> contentType?.let { headers += "Content-Type" to it }
        is HostingAuth.Bearer -> {
            contentType?.let { headers += "Content-Type" to it }
            auth.token.use { token -> headers += "Authorization" to "Bearer $token" }
        }
        is HostingAuth.RailwayProjectToken -> {
            contentType?.let { headers += "Content-Type" to it }
            auth.token.use { token -> headers += "Project-Access-Token" to token }
        }
        is HostingAuth.AwsSigV4 -> {
            check(endpoint.host == "amplify.${auth.region}.amazonaws.com") {
                "AWS credentials can only sign requests for their own Amplify region."
            }
            // iOS always signs a content type, including for GET requests.
            val signedContentType = contentType ?: HostingHttpRequest.JSON_CONTENT_TYPE
            val amzDate = AwsSigV4Signer.amzDate(instant)
            val signed = mutableListOf(
                "content-type" to signedContentType,
                "host" to endpoint.host,
                "x-amz-date" to amzDate,
            )
            auth.sessionToken?.use { token -> signed += "x-amz-security-token" to token }
            val signature = AwsSigV4Signer.sign(
                method = method.name,
                canonicalUri = AwsSigV4Signer.canonicalUri(encodedPath),
                canonicalQuery = AwsSigV4Signer.canonicalQuery(query),
                headers = signed,
                payloadSha256Hex = sha256Hex(body ?: ByteArray(0)),
                accessKeyId = auth.accessKeyId,
                secretAccessKey = auth.secretAccessKey,
                region = auth.region,
                service = auth.service,
                instant = instant,
            )
            headers += "Content-Type" to signedContentType
            headers += "X-Amz-Date" to amzDate
            auth.sessionToken?.use { token -> headers += "X-Amz-Security-Token" to token }
            headers += "Authorization" to signature.authorization
        }
    }
    return PreparedHostingRequest(uri, method, headers, body)
}

private const val JSON_ACCEPT = "application/json"

interface HostingHttpTransport {
    /** Sends [request]. Coroutine cancellation aborts the in-flight connection. */
    suspend fun send(request: HostingHttpRequest): HttpResponse
}

/**
 * Bounded, cancellable HTTPS transport. Calls run on [executor]; redirects are followed only for
 * unsigned GET requests and only within the same origin, so credentials and request bodies can
 * never be replayed to another host.
 */
class SecureHostingHttpTransport(
    private val executor: ExecutorService,
    private val clock: Clock = Clock.systemUTC(),
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maximumResponseBytes: Int = HostingJson.MAX_INPUT_BYTES,
    private val maximumRedirects: Int = 3,
) : HostingHttpTransport {
    init {
        require(connectTimeoutMillis in 1..120_000 && readTimeoutMillis in 1..120_000) { "Invalid timeout." }
        require(maximumResponseBytes in 1..HostingJson.MAX_INPUT_BYTES) { "Invalid maximum response size." }
        require(maximumRedirects in 0..5) { "Invalid redirect limit." }
    }

    override suspend fun send(request: HostingHttpRequest): HttpResponse {
        val call = BoundedHostingHttpCall(
            request = request,
            clock = clock,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
            maximumRedirects = if (request.method == HostingHttpMethod.GET && request.auth !is HostingAuth.AwsSigV4) {
                maximumRedirects
            } else {
                0
            },
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            executor.execute {
                try {
                    val response = call.execute()
                    if (continuation.isActive) continuation.resume(response)
                } catch (error: java.util.concurrent.CancellationException) {
                    if (continuation.isActive) continuation.cancel(error)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
}

private class BoundedHostingHttpCall(
    private val request: HostingHttpRequest,
    private val clock: Clock,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
) {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>()

    fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "A hosting call can only execute once." }
        val policy = request.endpoint.policy
        var prepared = request.prepare(clock.instant())
        var redirects = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = open(prepared, policy)
                activeConnection.set(connection)
                try {
                    prepared.body?.let { body ->
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    }
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (status in REDIRECT_CODES) {
                        if (redirects >= maximumRedirects) {
                            throw UnsafeRedirectException("The hosting provider returned an unexpected redirect.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("The hosting redirect omitted its location.")
                        val target = try {
                            policy.resolveRedirect(prepared.uri, location)
                        } catch (error: Exception) {
                            throw UnsafeRedirectException("The hosting provider returned an unsafe redirect.", error)
                        }
                        prepared = PreparedHostingRequest(target, prepared.method, prepared.headers, null)
                        redirects += 1
                        continue
                    }
                    if (connection.contentLengthLong > maximumResponseBytes) {
                        throw ResponseTooLargeException(maximumResponseBytes)
                    }
                    val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    val bytes = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        HttpResponse(status, bytes, safeHeaders(connection))
                    } finally {
                        bytes.fill(0)
                    }
                } catch (error: IOException) {
                    if (cancelled.get()) throw java.util.concurrent.CancellationException("The hosting request was cancelled.")
                    throw error
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
        } finally {
            prepared.body?.fill(0)
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun open(prepared: PreparedHostingRequest, policy: ProviderEndpointPolicy): HttpsURLConnection {
        check(policy.isSameOrigin(prepared.uri)) { "Refusing a hosting URL outside its provider origin." }
        val connection = prepared.uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("The hosting URL did not create a secure connection.")
        connection.requestMethod = prepared.method.name
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        prepared.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingHostingResponseStream(minOf(32 * 1_024, maximumResponseBytes)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumResponseBytes) throw ResponseTooLargeException(maximumResponseBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeHeaders(connection: HttpsURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            name?.takeUnless { it.lowercase(Locale.ROOT) in SENSITIVE_RESPONSE_HEADERS }?.let { it to values.toList() }
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The hosting request was cancelled.")
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_RESPONSE_HEADERS = setOf("authorization", "proxy-authenticate", "set-cookie", "set-cookie2")
    }
}

private class WipingHostingResponseStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
