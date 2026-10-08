package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.boundedConcurrentMap
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.markPartialMetrics
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.humanized
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.rows
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Bing Webmaster Tools JSON API adapter (iOS `fetchBingSnapshot` and detail client). */
internal class BingWebmasterSiteAdapter(private val context: SiteApiContext) {
    private data class SiteDescriptor(
        val value: String,
        val url: String?,
        val isVerified: Boolean,
        val resourceId: String,
    )

    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val key = account.requiredCredential("Bing Webmaster API key")
        val root = context.http.requestJson(
            SiteHttpRequest(
                url = key.use { SiteApiSupport.url("$BASE/GetUserSites", listOf("apikey" to it)) },
                querySecrets = listOf(key),
            ),
        )
        if (root["d"] == null && root["sites"] == null) {
            throw SiteServiceException.decoding("Bing Webmaster did not return a site list.")
        }
        val descriptors = (root["d"] ?: root["sites"]).arr().mapNotNull { site ->
            val value = (site["Url"] ?: site["url"]).nonEmptyString() ?: return@mapNotNull null
            SiteDescriptor(
                value = value,
                url = value.takeIf { runCatching { URI(it) }.isSuccess },
                isVerified = (site["IsVerified"] ?: site["isVerified"]).bool() ?: false,
                resourceId = SiteApiSupport.stableSiteResourceId(value),
            )
        }
        val loads = boundedConcurrentMap(descriptors) { loadSite(it, key) }
        val resources = loads.map(SiteResourceLoad::resource)
        val warnings = loads.flatMap(SiteResourceLoad::warnings)
        val hasPartialMetrics = loads.any(SiteResourceLoad::metricsArePartial)
        val metrics = arrayListOf(
            metric("bing.sites", "Sites", resources.size.toDouble(), SiteMetricUnit.COUNT),
            metric("bing.verified", "Verified", resources.count { it.status == "Verified" }.toDouble(), SiteMetricUnit.COUNT),
        )
        val all = resources.flatMap(SiteResource::metrics)
        listOf(
            "bing.clicks" to "Clicks",
            "bing.impressions" to "Impressions",
            "bing.crawled_pages" to "Crawled Pages",
            "bing.crawl_errors" to "Crawl Errors",
            "bing.in_index" to "In Index",
        ).forEach { (key, label) ->
            val matching = all.filter { it.key == key }
            if (matching.isNotEmpty()) metrics += metric(key, label, matching.sumOf(SiteMetric::value), SiteMetricUnit.COUNT)
        }
        return SiteSnapshot(
            provider = SiteProvider.BING_WEBMASTER,
            resources = resources,
            metrics = markPartialMetrics(metrics, hasPartialMetrics, setOf("bing.sites", "bing.verified")),
            status = when {
                resources.isEmpty() -> "No sites"
                hasPartialMetrics -> "Connected · Partial metrics"
                else -> "Connected"
            },
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    private suspend fun loadSite(descriptor: SiteDescriptor, key: SecretValue): SiteResourceLoad {
        val metrics = ArrayList<SiteMetric>()
        val metadata = linkedMapOf("verified" to descriptor.isVerified.toString())
        val warnings = ArrayList<String>()
        var partial = false
        if (descriptor.isVerified) {
            try {
                metrics += trafficMetrics(descriptor, key)
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                partial = true
                warnings += "${descriptor.value} Bing traffic could not load: ${errorText(error)}"
            }
            try {
                val (crawlMetrics, crawlMetadata) = crawlMetrics(descriptor, key)
                metrics += crawlMetrics
                metadata.putAll(crawlMetadata)
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                partial = true
                warnings += "${descriptor.value} Bing crawl data could not load: ${errorText(error)}"
            }
        }
        return SiteResourceLoad(
            resource = SiteResource(
                id = descriptor.resourceId,
                provider = SiteProvider.BING_WEBMASTER,
                name = descriptor.url?.let { runCatching { URI(it).host }.getOrNull() } ?: descriptor.value,
                subtitle = descriptor.value,
                url = descriptor.url,
                status = if (descriptor.isVerified) "Verified" else "Unverified",
                metrics = metrics,
                metadata = metadata,
            ),
            warnings = warnings,
            metricsArePartial = partial,
        )
    }

    private suspend fun trafficMetrics(descriptor: SiteDescriptor, key: SecretValue): List<SiteMetric> {
        val rows = bingRows("GetRankAndTrafficStats", descriptor.value, key)
        val cutoff = context.nowMillis() - 30L * 86_400_000L
        val dated = rows.filter { row -> bingDate(row["Date"] ?: row["date"])?.let { it >= cutoff } ?: true }
        val clicks = dated.sumOf { (it["Clicks"] ?: it["clicks"]).num() ?: 0.0 }
        val impressions = dated.sumOf { (it["Impressions"] ?: it["impressions"]).num() ?: 0.0 }
        return listOf(
            metric("bing.clicks", "Clicks · 30d", clicks, SiteMetricUnit.COUNT, resourceId = descriptor.resourceId),
            metric("bing.impressions", "Impressions · 30d", impressions, SiteMetricUnit.COUNT, resourceId = descriptor.resourceId),
            metric(
                "bing.ctr", "CTR · 30d", if (impressions > 0) clicks / impressions * 100 else 0.0,
                SiteMetricUnit.PERCENT, resourceId = descriptor.resourceId,
            ),
        )
    }

    private suspend fun crawlMetrics(descriptor: SiteDescriptor, key: SecretValue): Pair<List<SiteMetric>, Map<String, String>> {
        val rows = bingRows("GetCrawlStats", descriptor.value, key)
        val latest = rows.maxByOrNull { bingDate(it["Date"] ?: it["date"]) ?: Long.MIN_VALUE }
            ?: return emptyList<SiteMetric>() to emptyMap()
        val metrics = listOf(
            Triple("CrawledPages", "bing.crawled_pages", "Crawled Pages"),
            Triple("CrawlErrors", "bing.crawl_errors", "Crawl Errors"),
            Triple("InIndex", "bing.in_index", "In Index"),
            Triple("InLinks", "bing.in_links", "Inbound Links"),
            Triple("Code4xx", "bing.code_4xx", "4xx Responses"),
            Triple("Code5xx", "bing.code_5xx", "5xx Responses"),
            Triple("BlockedByRobotsTxt", "bing.robots_blocked", "Blocked by robots.txt"),
        ).mapNotNull { (source, key, label) ->
            latest[source].num()?.let { metric(key, label, it, SiteMetricUnit.COUNT, resourceId = descriptor.resourceId) }
        }
        val metadata = bingDate(latest["Date"] ?: latest["date"])
            ?.let { mapOf("crawlStatsAt" to Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS).toString()) }
            .orEmpty()
        return metrics to metadata
    }

    private suspend fun bingRows(method: String, siteUrl: String, key: SecretValue): List<Map<String, ProviderJsonValue>> {
        val root = context.http.requestJson(
            SiteHttpRequest(
                url = key.use { SiteApiSupport.url("$BASE/$method", listOf("siteUrl" to siteUrl, "apikey" to it)) },
                querySecrets = listOf(key),
            ),
        )
        if (root["d"] == null) throw SiteServiceException.decoding("Bing Webmaster returned an unexpected $method response.")
        return root["d"].arr().map { it.obj() }
    }

    // MARK: Detail

    suspend fun detail(request: SiteDetailRequest.BingWebmaster): SiteDetailPayload {
        val siteUri = runCatching { URI(request.siteUrl) }.getOrNull()
        if (!siteUri?.scheme.equals("https", ignoreCase = true)) {
            throw SiteServiceException.invalidConfiguration("Bing requires a valid HTTPS site URL.")
        }
        val raw = LinkedHashMap<String, ProviderJsonValue>()
        val tables = ArrayList<SiteDetailTable>()
        val series = ArrayList<SiteDetailSeries>()
        val warnings = ArrayList<String>()
        var firstFailure: Exception? = null
        DETAIL_METHODS.forEach { (method, title) ->
            try {
                val response = context.detailJson(
                    SiteHttpRequest(
                        url = request.apiKey.use {
                            SiteApiSupport.url("$BASE/$method", listOf("siteUrl" to request.siteUrl, "apikey" to it))
                        },
                        querySecrets = listOf(request.apiKey),
                    ),
                )
                raw[method] = response
                val tableRows = rows(response["d"] ?: ProviderJsonValue.Null)
                tables += SiteDetailTable("bing.$method", title, orderedColumns(tableRows, listOf("Date")), tableRows)
                if (method == "GetRankAndTrafficStats" || method == "GetCrawlStats") {
                    series += series(method, title, tableRows)
                }
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                if (firstFailure == null) firstFailure = error
                warnings += "$title could not load: ${errorText(error)}"
            }
        }
        if (raw.isEmpty()) throw firstFailure ?: SiteServiceException.invalidResponse()
        return SiteDetailPayload(
            provider = SiteProvider.BING_WEBMASTER,
            resourceId = request.siteUrl,
            title = siteUri?.host ?: request.siteUrl,
            series = series,
            tables = tables,
            rawResponses = raw,
            warnings = warnings,
            fetchedAtMillis = context.nowMillis(),
        )
    }

    private fun series(id: String, title: String, rows: List<Map<String, ProviderJsonValue>>): SiteDetailSeries {
        val numericKeys = rows.flatMapTo(HashSet()) { it.keys }
            .filter { key -> !key.equals("date", ignoreCase = true) && rows.any { it[key]?.numberValue != null } }
            .sorted()
        val points = rows.mapNotNull { row ->
            val rawDate = (row["Date"] ?: row["date"])?.stringValue ?: return@mapNotNull null
            SiteDetailSeriesPoint(
                normalizedBingDate(rawDate),
                numericKeys.mapNotNull { key -> row[key]?.numberValue?.let { key to it } }.toMap(),
            )
        }
        return SiteDetailSeries("bing.$id.timeline", title, numericKeys.associateWith(::humanized), points)
    }

    companion object {
        const val BASE: String = "https://ssl.bing.com/webmaster/api.svc/json"

        private val DETAIL_METHODS = listOf(
            "GetRankAndTrafficStats" to "Rank and traffic",
            "GetCrawlStats" to "Crawl statistics",
            "GetQueryStats" to "Search queries",
            "GetPageStats" to "Pages",
            "GetCrawlIssues" to "Crawl issues",
            "GetFeeds" to "Sitemaps and feeds",
            "GetLinkCounts" to "Link counts",
        )

        /** Bing returns WCF dates such as `/Date(1700000000000-0800)/` or ISO-8601 strings. */
        fun bingDate(value: ProviderJsonValue?): Long? {
            val raw = value.str() ?: return null
            if (raw.startsWith("/Date(")) {
                val digits = raw.drop(6).takeWhile(Char::isDigit)
                digits.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { return it.toLong() }
            }
            return SiteApiSupport.dateMillis(value)
        }

        fun normalizedBingDate(value: String): String {
            if (!value.startsWith("/Date(")) return value
            val payload = value.drop(6)
            val digits = if (payload.startsWith("-")) {
                "-" + payload.drop(1).takeWhile(Char::isDigit)
            } else {
                payload.takeWhile(Char::isDigit)
            }
            val millis = digits.toLongOrNull() ?: return value
            return Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()
        }
    }
}
