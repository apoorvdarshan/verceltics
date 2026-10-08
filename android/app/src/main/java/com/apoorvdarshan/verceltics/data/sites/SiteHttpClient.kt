package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsRequest
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.ProviderJsonException
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import com.apoorvdarshan.verceltics.data.network.map
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import javax.net.ssl.SSLException
import kotlin.math.pow
import kotlinx.coroutines.delay

/** How failures are worded: snapshot errors include provider text, detail errors match iOS detail copy. */
enum class SiteErrorStyle {
    SNAPSHOT,
    DETAIL,
}

/**
 * One logical provider request. Bodies are rebuilt for every attempt, and form/secret values are
 * only materialized while the transport request is created.
 */
class SiteHttpRequest(
    val url: URI,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val bearerToken: SecretValue? = null,
    val secretHeaders: Map<String, SecretValue> = emptyMap(),
    val jsonBody: ProviderJsonValue? = null,
    val formFields: List<Pair<String, String>>? = null,
    val secretFormFields: List<Pair<String, SecretValue>> = emptyList(),
    /** Secrets embedded in the URL query (for example Bing's `apikey`), used only for redaction. */
    val querySecrets: List<SecretValue> = emptyList(),
    val readTimeoutMillis: Int = ProviderHttpsRequest.DEFAULT_READ_TIMEOUT_MILLIS,
    val maximumResponseBytes: Int = ProviderHttpsRequest.DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
    init {
        require(url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()) {
            "Provider requests must use HTTPS."
        }
        require(jsonBody == null || formFields == null) { "Choose either a JSON or a form body." }
    }

    internal val secrets: List<SecretValue>
        get() = listOfNotNull(bearerToken) + secretHeaders.values + secretFormFields.map { it.second } + querySecrets

    internal fun withMaximumResponseBytes(bytes: Int): SiteHttpRequest = SiteHttpRequest(
        url = url,
        method = method,
        headers = headers,
        bearerToken = bearerToken,
        secretHeaders = secretHeaders,
        jsonBody = jsonBody,
        formFields = formFields,
        secretFormFields = secretFormFields,
        querySecrets = querySecrets,
        readTimeoutMillis = readTimeoutMillis,
        maximumResponseBytes = bytes,
    )

    internal fun toTransportRequest(): ProviderHttpsRequest {
        var body: ByteArray? = null
        var contentType: String? = null
        if (jsonBody != null) {
            body = ProviderJsonWriter.writeBytes(jsonBody)
            contentType = "application/json"
        } else if (formFields != null || secretFormFields.isNotEmpty()) {
            body = buildFormBody()
            contentType = "application/x-www-form-urlencoded"
        }
        return try {
            ProviderHttpsRequest(
                method = method,
                uri = url,
                headers = headers,
                bearerToken = bearerToken,
                secretHeaders = secretHeaders,
                body = body,
                contentType = contentType,
                readTimeoutMillis = readTimeoutMillis,
                maximumResponseBytes = maximumResponseBytes,
            )
        } finally {
            body?.fill(0)
        }
    }

    private fun buildFormBody(): ByteArray {
        val builder = StringBuilder()
        fun append(name: String, value: String) {
            if (builder.isNotEmpty()) builder.append('&')
            builder.append(SiteHttpClient.formEncode(name)).append("=").append(SiteHttpClient.formEncode(value))
        }
        secretFormFields.forEach { (name, secret) -> secret.use { append(name, it) } }
        formFields.orEmpty().forEach { (name, value) -> append(name, value) }
        val bytes = builder.toString().toByteArray(StandardCharsets.UTF_8)
        builder.setLength(0)
        return bytes
    }

    override fun toString(): String =
        "SiteHttpRequest(method=$method, host=${url.host}, path=${url.rawPath}, secrets=<redacted>)"
}

private sealed interface SiteAttempt {
    class Success(val json: ProviderJsonValue) : SiteAttempt

    class HttpFailure(val status: Int, val message: String, val retryAfter: String?) : SiteAttempt
}

/**
 * JSON-over-HTTPS client shared by every site adapter. Transient HTTP statuses and network errors
 * retry with the iOS backoff schedule (`retryDelaySeconds`); bodies are parsed on the network
 * executor so large provider reports never parse on the main thread.
 */
class SiteHttpClient(
    private val transport: ProviderHttpsTransport,
    private val executor: Executor,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun requestJson(
        request: SiteHttpRequest,
        maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
        style: SiteErrorStyle = SiteErrorStyle.SNAPSHOT,
    ): ProviderJsonValue {
        require(maximumAttempts in 1..MAXIMUM_ATTEMPTS) { "Invalid retry budget." }
        var attempt = 0
        while (true) {
            val outcome = try {
                newAttempt(request, style).awaitProviderCall(executor)
            } catch (error: IOException) {
                if (isRetryable(error) && attempt + 1 < maximumAttempts) {
                    sleep(retryDelayMillis(null, attempt))
                    attempt += 1
                    continue
                }
                throw networkFailure(error)
            }
            when (outcome) {
                is SiteAttempt.Success -> return outcome.json
                is SiteAttempt.HttpFailure -> {
                    if (isRetryableStatus(outcome.status) && attempt + 1 < maximumAttempts) {
                        sleep(retryDelayMillis(outcome.retryAfter, attempt))
                        attempt += 1
                        continue
                    }
                    throw when (style) {
                        SiteErrorStyle.SNAPSHOT -> SiteServiceException.requestFailed(outcome.status, outcome.message)
                        SiteErrorStyle.DETAIL -> SiteServiceException.detailRequestFailed(outcome.status)
                    }
                }
            }
        }
    }

    private fun newAttempt(request: SiteHttpRequest, style: SiteErrorStyle): CancelableCall<SiteAttempt> {
        val transportRequest = request.toTransportRequest()
        val secrets = request.secrets
        return transport.newCall(transportRequest).map { response ->
            val body = response.takeBody()
            try {
                if (response.statusCode in 200..299) {
                    if (body.isEmpty()) return@map SiteAttempt.Success(ProviderJsonValue.Obj(emptyMap()))
                    val json = try {
                        ProviderJsonParser.parse(body)
                    } catch (error: ProviderJsonException) {
                        throw when (style) {
                            SiteErrorStyle.SNAPSHOT -> SiteServiceException.decoding(error.message.orEmpty())
                            SiteErrorStyle.DETAIL -> SiteServiceException.detailDecoding(
                                "The provider returned data that could not be read: ${error.message.orEmpty()}",
                            )
                        }
                    }
                    SiteAttempt.Success(json)
                } else {
                    SiteAttempt.HttpFailure(
                        status = response.statusCode,
                        message = redacted(errorMessage(body), secrets),
                        retryAfter = response.headers.entries
                            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                            ?.value?.firstOrNull(),
                    )
                }
            } finally {
                body.fill(0)
            }
        }
    }

    private fun networkFailure(error: IOException): SiteServiceException = when (error) {
        is ResponseTooLargeException -> SiteServiceException.network(
            "The provider response exceeded the safe on-device size limit.",
        )
        is UnsafeRedirectException -> SiteServiceException.network("The provider returned an unsafe redirect.")
        is SocketTimeoutException -> SiteServiceException.network("The request timed out.")
        is UnknownHostException -> SiteServiceException.network(
            "A server with the specified hostname could not be found.",
        )
        is ConnectException, is NoRouteToHostException -> SiteServiceException.network("Could not connect to the server.")
        is SSLException -> SiteServiceException.network("A secure connection to the server could not be made.")
        else -> SiteServiceException.network("The network connection was lost.")
    }

    companion object {
        const val DEFAULT_MAXIMUM_ATTEMPTS: Int = 3
        private const val MAXIMUM_ATTEMPTS = 6

        fun isRetryableStatus(status: Int): Boolean =
            status == 429 || status == 500 || status == 502 || status == 503 || status == 504

        fun isRetryable(error: IOException): Boolean = when (error) {
            is ResponseTooLargeException, is UnsafeRedirectException, is SSLException -> false
            is SocketTimeoutException, is ConnectException, is UnknownHostException,
            is NoRouteToHostException, is SocketException, is EOFException -> true
            else -> false
        }

        /**
         * iOS `SiteIntegrationsAPI.retryDelaySeconds`: honour a finite numeric `Retry-After`, else
         * back off 0.5 s × 2^attempt (attempt clamped to 0…4), always bounded to 0.1…8 seconds.
         */
        fun retryDelaySeconds(retryAfterHeader: String?, attempt: Int): Double {
            val retryAfter = retryAfterHeader?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)
            val boundedAttempt = attempt.coerceIn(0, 4)
            val fallback = 0.5 * 2.0.pow(boundedAttempt)
            return (retryAfter ?: fallback).coerceIn(0.1, 8.0)
        }

        fun retryDelayMillis(retryAfterHeader: String?, attempt: Int): Long =
            (retryDelaySeconds(retryAfterHeader, attempt) * 1_000).toLong()

        /** iOS `errorMessage(_:)`: the provider's own message, else ≤300 characters of the body. */
        internal fun errorMessage(body: ByteArray): String {
            val json = runCatching { ProviderJsonParser.parse(body) }.getOrNull()
            if (json != null) {
                json["message"]?.stringValue?.takeIf(String::isNotBlank)?.let { return it.take(300) }
                json["error_description"]?.stringValue?.takeIf(String::isNotBlank)?.let { return it.take(300) }
                val error = json["error"]
                error?.get("message")?.stringValue?.takeIf(String::isNotBlank)?.let { return it.take(300) }
                error?.get("error_description")?.stringValue?.takeIf(String::isNotBlank)?.let { return it.take(300) }
                json["errors"]?.arrayValue?.firstOrNull()?.get("message")?.stringValue
                    ?.takeIf(String::isNotBlank)?.let { return it.take(300) }
            }
            val text = String(body, StandardCharsets.UTF_8).trim()
            return text.take(300)
        }

        /** Never echo a credential back in an error message, even if a provider includes it. */
        internal fun redacted(message: String, secrets: List<SecretValue>): String {
            val leaks = secrets.any { secret -> secret.use { it.length >= 4 && message.contains(it) } }
            return if (leaks) "" else message.replace(Regex("[\\r\\n\\t]+"), " ")
        }

        internal fun formEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
