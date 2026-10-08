package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.boundedConcurrentMap
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.markPartialMetrics
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedFields
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedObject
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.rows
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Umami Cloud (`x-umami-api-key`) and self-hosted (bearer token) adapter. */
internal class UmamiSiteAdapter(private val context: SiteApiContext) {
    class RequestContext(
        val base: URI,
        val apiKey: SecretValue?,
        val bearerToken: SecretValue?,
    ) {
        fun request(url: URI, readTimeoutMillis: Int = 30_000): SiteHttpRequest = SiteHttpRequest(
            url = url,
            bearerToken = bearerToken,
            secretHeaders = apiKey?.let { mapOf(CLOUD_API_KEY_HEADER to it) }.orEmpty(),
            readTimeoutMillis = readTimeoutMillis,
        )
    }

    private data class SiteDescriptor(
        val id: String,
        val name: String,
        val domain: String?,
        val url: String?,
        val updatedAtMillis: Long?,
    )

    fun requestContext(account: SiteServiceAccount): RequestContext {
        val credential = account.requiredCredential("Umami credential")
        val base = apiBaseUrl(account.metadata)
        return if (authMode(account.metadata) == SELF_HOSTED) {
            RequestContext(base, apiKey = null, bearerToken = credential)
        } else {
            RequestContext(base, apiKey = credential, bearerToken = null)
        }
    }

    /** `/me` identity, used to recognize the same Umami user on the same endpoint. */
    suspend fun connectionMetadata(account: SiteServiceAccount): Map<String, String> {
        val request = requestContext(account)
        val root = context.http.requestJson(
            request.request(SiteApiSupport.endpoint(request.base, "me"), readTimeoutMillis = 8_000),
            maximumAttempts = 1,
        )
        return connectionMetadata(root, request.base)
    }

    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val request = requestContext(account)
        val websites = ArrayList<ProviderJsonValue>()
        val warnings = ArrayList<String>()
        val seenSignatures = HashSet<String>()
        val seenIds = HashSet<String>()
        var siteListIsPartial = false
        var expectedCount: Long? = null
        var page = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val root = context.http.requestJson(
                request.request(
                    SiteApiSupport.endpoint(
                        request.base,
                        "websites",
                        listOf("page" to page.toString(), "pageSize" to "100", "includeTeams" to "true"),
                    ),
                ),
            )
            if (root["data"]?.arrayValue == null && root["websites"]?.arrayValue == null) {
                throw SiteServiceException.decoding("Umami did not return a website list.")
            }
            val pageItems = root["data"]?.arrayValue ?: root["websites"].arr()
            val signature = pageItems.mapNotNull { it["id"].nonEmptyString() }.sorted().joinToString("|")
            if (signature.isNotEmpty() && !seenSignatures.add(signature)) {
                warnings += "Umami repeated a results page, so site loading stopped safely."
                siteListIsPartial = true
                break
            }
            val unique = pageItems.filter { website -> website["id"].nonEmptyString()?.let(seenIds::add) == true }
            websites += unique
            expectedCount = root["count"].int() ?: expectedCount
            page += 1
            if (pageItems.isNotEmpty() && unique.isEmpty()) {
                warnings += "Umami returned no new sites on the next page, so site loading stopped safely."
                siteListIsPartial = true
                break
            }
            if (pageItems.isEmpty() || pageItems.size < 100 || expectedCount?.let { seenIds.size >= it } == true) break
        }

        val endAt = context.nowMillis()
        val startAt = endAt - 30L * 86_400_000L
        val descriptors = websites.mapNotNull { website ->
            val id = website["id"].nonEmptyString() ?: return@mapNotNull null
            val domain = website["domain"].nonEmptyString()
            SiteDescriptor(
                id = id,
                name = website["name"].str() ?: domain ?: "Umami site",
                domain = domain,
                url = domain?.let { if (it.contains("://")) it else "https://$it" }?.takeIf { runCatching { URI(it) }.isSuccess },
                updatedAtMillis = (website["updatedAt"] ?: website["createdAt"]).dateMillis(),
            )
        }
        val loads = boundedConcurrentMap(descriptors) { loadSite(it, request, startAt, endAt) }
        val resources = loads.map(SiteResourceLoad::resource)
        warnings += loads.flatMap(SiteResourceLoad::warnings)
        val hasPartialMetrics = siteListIsPartial || loads.any(SiteResourceLoad::metricsArePartial)
        return SiteSnapshot(
            provider = SiteProvider.UMAMI,
            resources = resources,
            metrics = markPartialMetrics(aggregate(resources), hasPartialMetrics),
            status = when {
                resources.isEmpty() -> "No sites"
                hasPartialMetrics -> "Connected · Partial metrics"
                else -> "Connected"
            },
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    private suspend fun loadSite(
        descriptor: SiteDescriptor,
        request: RequestContext,
        startAt: Long,
        endAt: Long,
    ): SiteResourceLoad {
        var metrics = emptyList<SiteMetric>()
        val warnings = ArrayList<String>()
        var partial = false
        try {
            val stats = context.http.requestJson(
                request.request(
                    SiteApiSupport.endpoint(
                        request.base,
                        "websites/${SiteApiSupport.pathComponent(descriptor.id)}/stats",
                        listOf("startAt" to startAt.toString(), "endAt" to endAt.toString()),
                    ),
                ),
            )
            metrics = STAT_DEFINITIONS.mapNotNull { (key, label, unit) ->
                statValue(stats[key])?.let { metric("umami.$key", label, it, unit, resourceId = descriptor.id) }
            }
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            partial = true
            warnings += "${descriptor.name} stats could not load: ${errorText(error)}"
        }
        return SiteResourceLoad(
            resource = SiteResource(
                id = descriptor.id,
                provider = SiteProvider.UMAMI,
                name = descriptor.name,
                subtitle = descriptor.domain,
                url = descriptor.url,
                status = "Connected",
                updatedAtMillis = descriptor.updatedAtMillis,
                metrics = metrics,
                metadata = mapOf("domain" to descriptor.domain.orEmpty()),
            ),
            warnings = warnings,
            metricsArePartial = partial,
        )
    }

    /** Umami v2 returns plain numbers; older builds nest `{ "value": n, "prev": n }`. */
    private fun statValue(value: ProviderJsonValue?): Double? = value.num() ?: value?.get("value").num()

    private fun aggregate(resources: List<SiteResource>): List<SiteMetric> {
        val all = resources.flatMap(SiteResource::metrics)
        return STAT_DEFINITIONS.mapNotNull { (key, label, unit) ->
            val matching = all.filter { it.key == "umami.$key" }
            if (matching.isEmpty()) null else metric("umami.$key", label, matching.sumOf(SiteMetric::value), unit)
        }
    }

    // MARK: Detail

    suspend fun detail(request: SiteDetailRequest.Umami): SiteDetailPayload {
        if (!request.baseUrl.scheme.equals("https", ignoreCase = true) || request.baseUrl.host.isNullOrBlank()) {
            throw SiteServiceException.invalidConfiguration("Umami requires a valid HTTPS API base URL.")
        }
        val auth = when (val authentication = request.authentication) {
            is UmamiAuthentication.CloudApiKey -> RequestContext(request.baseUrl, authentication.key, null)
            is UmamiAuthentication.BearerToken -> RequestContext(request.baseUrl, null, authentication.token)
        }
        val pathId = SiteApiSupport.pathComponent(request.websiteId)
        val common = listOf(
            "startAt" to request.range.startMillis.toString(),
            "endAt" to request.range.endMillis.toString(),
        )
        val raw = LinkedHashMap<String, ProviderJsonValue>()
        val sections = ArrayList<SiteDetailSection>()
        val tables = ArrayList<SiteDetailTable>()
        val series = ArrayList<SiteDetailSeries>()
        val warnings = ArrayList<String>()

        val stats = context.detailJson(auth.request(SiteApiSupport.endpoint(request.baseUrl, "websites/$pathId/stats", common)))
        raw["stats"] = stats
        sections += SiteDetailSection("umami.stats", "Traffic overview", flattenedFields(stats, 2))

        try {
            val pageviews = context.detailJson(
                auth.request(
                    SiteApiSupport.endpoint(request.baseUrl, "websites/$pathId/pageviews", common + ("unit" to "day")),
                ),
            )
            raw["pageviews"] = pageviews
            pageviewSeries(pageviews)?.let(series::add)
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Umami pageview history could not load: ${errorText(error)}"
        }

        var remainingRows = SiteDetailSupport.MAXIMUM_PAYLOAD_ROWS
        METRIC_TYPES.forEachIndexed { index, (id, type, title) ->
            try {
                val remainingMetrics = (METRIC_TYPES.size - index).coerceAtLeast(1)
                val rowLimit = minOf(MAXIMUM_METRIC_ROWS, (remainingRows / remainingMetrics).coerceAtLeast(1))
                val result = metricPages(auth, pathId, type, common, rowLimit)
                remainingRows = (remainingRows - result.items.size).coerceAtLeast(0)
                raw["metrics.$id"] = ProviderJsonValue.Arr(result.pages)
                val rows = result.items.map(::flattenedObject)
                tables += SiteDetailTable("umami.$id", title, orderedColumns(rows, listOf("x", "name", "value", "y")), rows)
                if (result.truncated) {
                    warnings += "Umami ${title.lowercase(Locale.ROOT)} reached the $rowLimit-row per-report limit within " +
                        "the shared device budget; additional rows may be available."
                }
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                warnings += "Umami ${title.lowercase(Locale.ROOT)} could not load: ${errorText(error)}"
            }
        }

        try {
            val active = context.detailJson(auth.request(SiteApiSupport.endpoint(request.baseUrl, "websites/$pathId/active")))
            raw["active"] = active
            sections += SiteDetailSection("umami.active", "Active visitors", flattenedFields(active, 2))
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Umami active visitors could not load: ${errorText(error)}"
        }

        try {
            val events = context.detailJson(
                auth.request(
                    SiteApiSupport.endpoint(request.baseUrl, "websites/$pathId/events/series", common + ("unit" to "day")),
                ),
            )
            raw["events.series"] = events
            SiteDetailSupport.simpleXYSeries(
                "umami.events.timeline", "Events over time", rows(events),
                listOf("x", "date", "timestamp"), listOf("y", "value", "events"),
            )?.let(series::add)
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Umami event history could not load: ${errorText(error)}"
        }

        return SiteDetailPayload(
            provider = SiteProvider.UMAMI,
            resourceId = request.websiteId,
            title = "Umami · ${request.websiteId}",
            sections = sections,
            series = series,
            tables = tables,
            rawResponses = raw,
            warnings = warnings,
            fetchedAtMillis = context.nowMillis(),
        )
    }

    private suspend fun metricPages(
        auth: RequestContext,
        pathId: String,
        type: String,
        common: List<Pair<String, String>>,
        maximumRows: Int,
    ): SitePagedCollection {
        var offset = 0
        val pages = ArrayList<ProviderJsonValue>()
        val items = ArrayList<ProviderJsonValue>()
        var lastPageFilled = false
        while (items.size < maximumRows) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(METRIC_PAGE_SIZE, maximumRows - items.size)
            val response = context.detailJson(
                auth.request(
                    SiteApiSupport.endpoint(
                        auth.base,
                        "websites/$pathId/metrics",
                        common + listOf("type" to type, "limit" to limit.toString(), "offset" to offset.toString()),
                    ),
                ),
            )
            val pageItems = response.arrayValue
                ?: throw SiteServiceException.detailDecoding("Umami did not return a metrics list for $type.")
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            items += pageItems.take(limit)
            lastPageFilled = pageItems.size >= limit
            if (pageItems.size < limit) break
            offset += pageItems.size
        }
        return SitePagedCollection(pages, items, truncated = items.size == maximumRows && lastPageFilled)
    }

    private fun pageviewSeries(response: ProviderJsonValue): SiteDetailSeries? {
        val byDate = java.util.TreeMap<String, MutableMap<String, Double>>()
        listOf("pageviews", "sessions").forEach { metricName ->
            rows(response[metricName] ?: ProviderJsonValue.Null).forEach { row ->
                val x = (row["x"] ?: row["date"])?.stringValue ?: return@forEach
                val y = (row["y"] ?: row["value"])?.numberValue ?: return@forEach
                byDate.getOrPut(x) { LinkedHashMap() }[metricName] = y
            }
        }
        if (byDate.isEmpty()) return null
        return SiteDetailSeries(
            id = "umami.pageviews.timeline",
            title = "Pageviews and sessions",
            metricLabels = mapOf("pageviews" to "Pageviews", "sessions" to "Sessions"),
            points = byDate.map { (x, values) -> SiteDetailSeriesPoint(x, values) },
        )
    }

    companion object {
        const val CLOUD_BASE: String = "https://api.umami.is/v1/"
        const val CLOUD_API_KEY_HEADER: String = "x-umami-api-key"
        const val CLOUD: String = "cloud"
        const val SELF_HOSTED: String = "selfHosted"
        private const val METRIC_PAGE_SIZE = 500
        private const val MAXIMUM_METRIC_ROWS = 10_000

        private val STAT_DEFINITIONS = listOf(
            Triple("pageviews", "Page Views", SiteMetricUnit.COUNT),
            Triple("visitors", "Visitors", SiteMetricUnit.COUNT),
            Triple("visits", "Visits", SiteMetricUnit.COUNT),
            Triple("bounces", "Bounces", SiteMetricUnit.COUNT),
            Triple("totaltime", "Time on Site", SiteMetricUnit.SECONDS),
        )

        private val METRIC_TYPES = listOf(
            Triple("paths", "path", "Paths"), Triple("entry-pages", "entry", "Entry pages"),
            Triple("exit-pages", "exit", "Exit pages"), Triple("titles", "title", "Page titles"),
            Triple("queries", "query", "Query parameters"), Triple("referrers", "referrer", "Referrers"),
            Triple("channels", "channel", "Channels"), Triple("domains", "domain", "Referrer domains"),
            Triple("countries", "country", "Countries"), Triple("regions", "region", "Regions"),
            Triple("cities", "city", "Cities"), Triple("browsers", "browser", "Browsers"),
            Triple("systems", "os", "Operating systems"), Triple("devices", "device", "Devices"),
            Triple("languages", "language", "Languages"), Triple("screens", "screen", "Screen sizes"),
            Triple("events", "event", "Events"), Triple("hostnames", "hostname", "Hostnames"),
            Triple("tags", "tag", "Tags"), Triple("distinct-ids", "distinctId", "Distinct IDs"),
        )

        fun authMode(metadata: Map<String, String>): String = metadata["authMode"].nonEmpty() ?: CLOUD

        /**
         * Cloud uses `https://api.umami.is/v1/`; self-hosted bases are normalized HTTPS URLs with an
         * `/api/` suffix (iOS `umamiAPIBaseURL`). Self-hosted mode refuses the Cloud host.
         */
        fun apiBaseUrl(metadata: Map<String, String>): URI {
            val mode = authMode(metadata)
            if (mode == CLOUD) return URI(CLOUD_BASE)
            if (mode != SELF_HOSTED) {
                throw SiteServiceException.invalidConfiguration("Umami authMode must be cloud or selfHosted.")
            }
            val rawBase = metadata["baseURL"].nonEmpty()
                ?: throw SiteServiceException.invalidConfiguration(SiteApiSupport.requiredMetadataMessage("self-hosted Umami URL"))
            val base = SiteApiSupport.normalizedHttpsBaseUrl(rawBase)
            if (base.host.equals("api.umami.is", ignoreCase = true)) {
                throw SiteServiceException.invalidConfiguration(
                    "Enter your own self-hosted Umami URL before using a bearer token.",
                )
            }
            var path = base.rawPath.orEmpty().trimEnd('/')
            if (!path.endsWith("/api")) path += "/api"
            return runCatching {
                URI("https://${base.host}${if (base.port >= 0) ":${base.port}" else ""}$path/")
            }.getOrElse { throw SiteServiceException.invalidConfiguration("The self-hosted Umami URL is invalid.") }
        }

        fun connectionMetadata(root: ProviderJsonValue, endpoint: URI): Map<String, String> {
            val user = root["user"]
            val userId = user["id"].nonEmptyString()
            val identity = SiteApiSupport.canonicalEndpointIdentity(endpoint.toASCIIString())
            if (userId == null || identity == null) {
                throw SiteServiceException.decoding("Umami did not return a stable account identity from /me.")
            }
            return LinkedHashMap<String, String>().apply {
                put("umamiUserID", userId)
                put("umamiEndpoint", identity)
                user["username"].nonEmptyString()?.let { put("umamiUsername", it) }
            }
        }
    }
}
