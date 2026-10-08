package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.truncate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared request context for every provider adapter. */
class SiteApiContext(
    val http: SiteHttpClient,
    val nowMillis: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = ZoneId::systemDefault,
)

// MARK: - JSON accessors (iOS object/array/string/number/int/boolean/date helpers)

/** Null-tolerant member lookup so provider fields can be chained like `root["a"]["b"]`. */
internal operator fun ProviderJsonValue?.get(key: String): ProviderJsonValue? = this?.objectValue?.get(key)

internal fun ProviderJsonValue?.obj(): Map<String, ProviderJsonValue> = this?.objectValue ?: emptyMap()

internal fun ProviderJsonValue?.arr(): List<ProviderJsonValue> = this?.arrayValue ?: emptyList()

internal fun ProviderJsonValue?.str(): String? = this?.stringValue

internal fun ProviderJsonValue?.num(): Double? = this?.numberValue

internal fun ProviderJsonValue?.int(): Long? = num()?.let(SiteApiSupport::safeInteger)

internal fun ProviderJsonValue?.bool(): Boolean? = this?.booleanValue

internal fun ProviderJsonValue?.nonEmptyString(): String? = str().nonEmpty()

internal fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)

internal fun ProviderJsonValue?.dateMillis(): Long? = SiteApiSupport.dateMillis(this)

object SiteApiSupport {
    const val MAXIMUM_CONCURRENT_DETAIL_LOADS: Int = 4

    /** iOS `safeInteger`: finite values truncated toward zero, only when representable. */
    fun safeInteger(value: Double): Long? {
        if (!value.isFinite()) return null
        val truncated = truncate(value)
        if (truncated < Long.MIN_VALUE.toDouble() || truncated >= Long.MAX_VALUE.toDouble()) return null
        return truncated.toLong()
    }

    fun detailIndexBatches(itemCount: Int, maximumConcurrent: Int = MAXIMUM_CONCURRENT_DETAIL_LOADS): List<List<Int>> {
        if (itemCount <= 0) return emptyList()
        return (0 until itemCount).chunked(maximumConcurrent.coerceAtLeast(1))
    }

    /** Runs [operation] for every value, at most [maximumConcurrent] at a time, keeping order. */
    suspend fun <I, O> boundedConcurrentMap(
        values: List<I>,
        maximumConcurrent: Int = MAXIMUM_CONCURRENT_DETAIL_LOADS,
        operation: suspend (I) -> O,
    ): List<O> {
        val results = ArrayList<O>(values.size)
        for (batch in detailIndexBatches(values.size, maximumConcurrent)) {
            currentCoroutineContext().ensureActive()
            results += coroutineScope {
                batch.map { index -> async { operation(values[index]) } }.awaitAll()
            }
        }
        return results
    }

    /** Numbers are seconds (or milliseconds above 10^10); strings are ISO-8601 instants. */
    fun dateMillis(value: ProviderJsonValue?): Long? {
        value.num()?.let { timestamp ->
            if (timestamp > 0) {
                return if (timestamp > 10_000_000_000.0) timestamp.toLong() else (timestamp * 1_000).toLong()
            }
        }
        val text = value.str().nonEmpty() ?: return null
        return parseIsoMillis(text)
    }

    fun parseIsoMillis(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    /** Lowercases scheme/host and drops fragments so the same site maps to one resource id. */
    fun stableSiteResourceId(rawValue: String): String {
        val value = rawValue.trim()
        if (value.lowercase(Locale.ROOT).startsWith("sc-domain:")) {
            return "sc-domain:" + value.drop("sc-domain:".length).lowercase(Locale.ROOT)
        }
        val candidate = if (value.contains("://")) value else "https://$value"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return value
        val scheme = uri.scheme ?: return value
        val host = uri.host ?: return value
        return buildString {
            append(scheme.lowercase(Locale.ROOT)).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(host.lowercase(Locale.ROOT))
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    /** iOS `normalizedHTTPSBaseURL`: HTTPS only, no credentials, lowercase host, no query/fragment. */
    fun normalizedHttpsBaseUrl(rawValue: String): URI {
        val value = rawValue.trim()
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            throw SiteServiceException.invalidConfiguration("Enter a complete HTTPS base URL.")
        }
        val text = buildString {
            append("https://").append(uri.host.lowercase(Locale.ROOT))
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
        }
        return runCatching { URI(text) }.getOrElse {
            throw SiteServiceException.invalidConfiguration("Enter a valid HTTPS base URL.")
        }
    }

    /** Stable identity for a self-hosted endpoint: lowercase host, default port dropped, one trailing slash. */
    fun canonicalEndpointIdentity(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            return null
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return buildString {
            append("https://").append(uri.host.lowercase(Locale.ROOT))
            if (uri.port >= 0 && uri.port != 443) append(':').append(uri.port)
            append(path).append('/')
        }
    }

    /** HTTPS origin (scheme, lowercase host, explicit port) with path, query and credentials removed. */
    fun originUrl(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        return buildString {
            append("https://").append(uri.host.lowercase(Locale.ROOT))
            if (uri.port >= 0) append(':').append(uri.port)
        }
    }

    /** Strict single path segment encoding (unreserved ASCII only), matching iOS `pathComponent`. */
    fun pathComponent(value: String): String {
        if (value.isEmpty()) throw SiteServiceException.invalidConfiguration("The resource identifier is invalid.")
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { raw ->
                val code = raw.toInt() and 0xff
                val character = code.toChar()
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~") {
                    append(character)
                } else {
                    append('%').append(HEX[code shr 4]).append(HEX[code and 0x0f])
                }
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** HTTPS URL with an encoded query string. */
    fun url(base: String, query: List<Pair<String, String>> = emptyList()): URI {
        val text = if (query.isEmpty()) base else "$base?${encodeQuery(query)}"
        return URI(text)
    }

    /** Resolves a provider-relative [path] beneath [base], refusing any change of origin. */
    fun endpoint(base: URI, path: String, query: List<Pair<String, String>> = emptyList()): URI {
        if (!base.scheme.equals("https", ignoreCase = true) || base.host.isNullOrBlank()) {
            throw SiteServiceException.invalidConfiguration("Provider requests must use HTTPS.")
        }
        val baseText = base.toASCIIString().substringBefore('?').substringBefore('#').trimEnd('/')
        val resolved = runCatching { URI("$baseText/${path.trimStart('/')}") }.getOrNull()
        if (resolved == null || !resolved.scheme.equals("https", ignoreCase = true) ||
            !resolved.host.equals(base.host, ignoreCase = true) || resolved.port != base.port
        ) {
            throw SiteServiceException.invalidConfiguration("The provider endpoint is invalid.")
        }
        return if (query.isEmpty()) resolved else URI("${resolved.toASCIIString()}?${encodeQuery(query)}")
    }

    fun encodeQuery(query: List<Pair<String, String>>): String = query.joinToString("&") { (name, value) ->
        "${queryEncode(name)}=${queryEncode(value)}"
    }

    private fun queryEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun metric(
        key: String,
        label: String,
        value: Double,
        unit: SiteMetricUnit = SiteMetricUnit.NONE,
        formattedValue: String? = null,
        resourceId: String? = null,
    ): SiteMetric = SiteMetric(key, label, value, unit, formattedValue, resourceId)

    fun markPartialMetrics(
        metrics: List<SiteMetric>,
        isPartial: Boolean,
        excluding: Set<String> = emptySet(),
    ): List<SiteMetric> = if (!isPartial) metrics else metrics.map { metric ->
        if (metric.key in excluding) metric else metric.copy(label = "${metric.label} · Partial")
    }

    /** iOS `inferredUnit(for:)` for Clarity's free-form numeric fields. */
    fun inferredUnit(key: String): SiteMetricUnit {
        val value = key.lowercase(Locale.ROOT)
        return when {
            value.contains("pagespersession") || value.contains("pages_per_session") -> SiteMetricUnit.RATIO
            value.contains("percentage") || value.contains("percent") || value.contains("rate") ||
                value.contains("depth") -> SiteMetricUnit.PERCENT
            value.contains("millisecond") || value.endsWith("ms") -> SiteMetricUnit.MILLISECONDS
            value.contains("duration") || value.contains("time") -> SiteMetricUnit.SECONDS
            else -> SiteMetricUnit.COUNT
        }
    }

    /** Lowercase alphanumeric slug with single dashes, matching iOS `slug(_:)`. */
    fun slug(value: String): String {
        val output = StringBuilder()
        value.lowercase(Locale.ROOT).forEach { character ->
            val next = if (character.isLetterOrDigit()) character else '-'
            if (next != '-' || output.lastOrNull() != '-') output.append(next)
        }
        return output.toString().trim('-')
    }

    /** iOS snapshot `humanized(_:)`: split camelCase and underscores into title-cased words. */
    fun humanizedSnapshotLabel(value: String): String {
        val spaced = StringBuilder()
        value.replace('_', ' ').forEach { character ->
            val last = spaced.lastOrNull()
            if (character.isUpperCase() && last != null && !last.isWhitespace() && !last.isUpperCase()) spaced.append(' ')
            spaced.append(character)
        }
        return spaced.split(' ').filter(String::isNotEmpty).joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /** iOS `throwIfGoogleUnauthorized`: a 401 must reach the store so it can refresh and retry. */
    fun rethrowIfUnauthorized(error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (error is SiteServiceException && error.isUnauthorized) throw error
    }

    fun rethrowIfCancellation(error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
    }

    fun requiredCredentialMessage(label: String): String = "Enter your $label."

    fun requiredMetadataMessage(label: String): String = "Enter the $label."
}

internal fun SiteServiceAccount.requiredCredential(label: String) = credential
    ?: throw SiteServiceException.invalidConfiguration(SiteApiSupport.requiredCredentialMessage(label))

internal fun SiteServiceAccount.requiredMetadata(key: String, label: String): String =
    metadata[key].nonEmpty()
        ?: throw SiteServiceException.invalidConfiguration(SiteApiSupport.requiredMetadataMessage(label))

/** Shared result of one resource's best-effort detail loading inside a snapshot. */
internal class SiteResourceLoad(
    val resource: SiteResource,
    val warnings: List<String>,
    val metricsArePartial: Boolean,
)

internal fun errorText(error: Throwable): String = error.message?.takeIf(String::isNotBlank)
    ?: "The provider request could not be completed."
