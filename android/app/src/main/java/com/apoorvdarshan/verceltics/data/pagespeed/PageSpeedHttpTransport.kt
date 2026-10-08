package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderEndpointPolicy
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

interface PageSpeedHttpTransport {
    fun newInsightsCall(
        credentials: PageSpeedCredentials,
        strategy: PageSpeedStrategy,
    ): CancelableCall<HttpResponse>

    fun newCruxCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse>

    /** Chrome UX Report History API: the last [PageSpeedReportParser.HISTORY_PERIOD_COUNT] periods. */
    fun newCruxHistoryCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse>
}

/**
 * Provider-specific HTTPS transport. Calls can only reach Google's two documented exact origins,
 * remain bounded, and reject cross-origin redirects before any credential or request body is sent.
 */
class SecurePageSpeedHttpTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
) : PageSpeedHttpTransport {
    private val insightsPolicy = ProviderEndpointPolicy(INSIGHTS_BASE_URL)
    private val cruxPolicy = ProviderEndpointPolicy(CRUX_BASE_URL)

    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid connect timeout." }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid read timeout." }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) {
            "Invalid maximum response size."
        }
        require(maximumRedirects in 0..HARD_MAXIMUM_REDIRECTS) { "Invalid redirect limit." }
    }

    override fun newInsightsCall(
        credentials: PageSpeedCredentials,
        strategy: PageSpeedStrategy,
    ): CancelableCall<HttpResponse> = BoundedPageSpeedHttpCall(
        method = "GET",
        initialUri = prepareInsightsUri(credentials, strategy),
        endpointPolicy = insightsPolicy,
        requestBody = null,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        maximumResponseBytes = maximumResponseBytes,
        maximumRedirects = maximumRedirects,
    )

    internal fun prepareInsightsUri(
        credentials: PageSpeedCredentials,
        strategy: PageSpeedStrategy,
    ): URI = credentials.apiKey.use { key ->
        insightsPolicy.resolve(
            relativePath = INSIGHTS_PATH,
            queryParameters = buildList {
                add("url" to credentials.siteUrl.toASCIIString())
                add("strategy" to strategy.wireValue)
                CATEGORIES.forEach { add("category" to it) }
                add("key" to key)
            },
        )
    }

    override fun newCruxCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> =
        prepareCruxUri(credentials).let { uri ->
            val body = jsonUrlBody(credentials.siteUrl)
            try {
                BoundedPageSpeedHttpCall(
                    method = "POST",
                    initialUri = uri,
                    endpointPolicy = cruxPolicy,
                    requestBody = body,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    maximumResponseBytes = maximumResponseBytes,
                    maximumRedirects = maximumRedirects,
                )
            } finally {
                body.fill(0)
            }
        }

    internal fun prepareCruxUri(credentials: PageSpeedCredentials): URI =
        credentials.apiKey.use { key ->
            cruxPolicy.resolve(
                relativePath = CRUX_PATH,
                queryParameters = listOf("key" to key),
            )
        }

    override fun newCruxHistoryCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> =
        prepareCruxHistoryUri(credentials).let { uri ->
            val body = jsonUrlBody(credentials.siteUrl, PageSpeedReportParser.HISTORY_PERIOD_COUNT)
            try {
                BoundedPageSpeedHttpCall(
                    method = "POST",
                    initialUri = uri,
                    endpointPolicy = cruxPolicy,
                    requestBody = body,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    maximumResponseBytes = maximumResponseBytes,
                    maximumRedirects = maximumRedirects,
                )
            } finally {
                body.fill(0)
            }
        }

    internal fun prepareCruxHistoryUri(credentials: PageSpeedCredentials): URI =
        credentials.apiKey.use { key ->
            cruxPolicy.resolve(
                relativePath = CRUX_HISTORY_PATH,
                queryParameters = listOf("key" to key),
            )
        }

    companion object {
        const val INSIGHTS_BASE_URL = "https://www.googleapis.com/"
        const val CRUX_BASE_URL = "https://chromeuxreport.googleapis.com/"
        internal const val INSIGHTS_PATH = "/pagespeedonline/v5/runPagespeed"
        internal const val CRUX_PATH = "/v1/records:queryRecord"
        internal const val CRUX_HISTORY_PATH = "/v1/records:queryHistoryRecord"
        internal val CATEGORIES = listOf("performance", "accessibility", "best-practices", "seo")

        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_MAXIMUM_REDIRECTS = 2
        private const val MAX_TIMEOUT_MILLIS = 120_000
        private const val HARD_MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val HARD_MAXIMUM_REDIRECTS = 5

        internal fun jsonUrlBody(siteUrl: URI, collectionPeriodCount: Int? = null): ByteArray {
            require(collectionPeriodCount == null || collectionPeriodCount in 1..PageSpeedReportParser.HISTORY_PERIOD_COUNT) {
                "Invalid CrUX history period count."
            }
            val escaped = buildString {
                siteUrl.toASCIIString().forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            val periods = collectionPeriodCount?.let { ",\"collectionPeriodCount\":$it" }.orEmpty()
            return "{\"url\":\"$escaped\"$periods}".toByteArray(StandardCharsets.UTF_8)
        }
    }
}

private class BoundedPageSpeedHttpCall(
    private val method: String,
    private val initialUri: URI,
    private val endpointPolicy: ProviderEndpointPolicy,
    requestBody: ByteArray?,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
) : CancelableCall<HttpResponse> {
    private val storedRequestBody = requestBody?.copyOf()
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "An HTTP call can only be executed once." }
        var uri = initialUri
        var redirectCount = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = openConnection(uri)
                activeConnection.set(connection)
                try {
                    throwIfCancelled()
                    writeRequestBody(connection)
                    throwIfCancelled()
                    val statusCode = connection.responseCode
                    throwIfCancelled()
                    if (statusCode in REDIRECT_STATUS_CODES) {
                        if (redirectCount >= maximumRedirects) {
                            throw UnsafeRedirectException("The provider returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("The provider redirect omitted its location.")
                        uri = try {
                            endpointPolicy.resolveRedirect(uri, location)
                        } catch (error: Exception) {
                            throw UnsafeRedirectException("The provider returned an unsafe redirect.", error)
                        }
                        redirectCount += 1
                        continue
                    }

                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > maximumResponseBytes) {
                        throw ResponseTooLargeException(maximumResponseBytes)
                    }
                    val stream = if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    val responseBody = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        HttpResponse(
                            statusCode = statusCode,
                            body = responseBody,
                            headers = safeResponseHeaders(connection),
                        )
                    } finally {
                        responseBody.fill(0)
                    }
                } catch (error: IOException) {
                    if (cancelled.get()) {
                        throw CancellationException("The PageSpeed request was cancelled.")
                    }
                    throw error
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
        } finally {
            storedRequestBody?.fill(0)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
        storedRequestBody?.fill(0)
    }

    private fun openConnection(uri: URI): HttpsURLConnection {
        check(endpointPolicy.isSameOrigin(uri)) { "Refusing to open a URL outside the provider origin." }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Provider URL did not create a secure HTTPS connection.")
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        return connection
    }

    private fun writeRequestBody(connection: HttpsURLConnection) {
        storedRequestBody?.let { body ->
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output ->
                throwIfCancelled()
                output.write(body)
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingPageSpeedByteArrayOutputStream(minOf(maximumResponseBytes, 32 * 1024)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumResponseBytes) {
                        throw ResponseTooLargeException(maximumResponseBytes)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeResponseHeaders(connection: HttpsURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            val headerName = name ?: return@mapNotNull null
            if (headerName.lowercase(Locale.ROOT) in SENSITIVE_RESPONSE_HEADERS) {
                return@mapNotNull null
            }
            headerName to values.toList()
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The PageSpeed request was cancelled.")
    }

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_RESPONSE_HEADERS = setOf(
            "authorization",
            "proxy-authenticate",
            "set-cookie",
            "set-cookie2",
        )
    }
}

private class WipingPageSpeedByteArrayOutputStream(initialSize: Int) :
    ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
