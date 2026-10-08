package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.jsonObjectOf
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.humanized
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Plausible Stats API v2 adapter (`https://plausible.io/api/v2/query`). */
internal class PlausibleSiteAdapter(private val context: SiteApiContext) {
    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val token = account.requiredCredential("Plausible Stats API key")
        val siteId = account.requiredMetadata("siteID", "Plausible site ID")
        val requestedRange = account.metadata["dateRange"].nonEmpty() ?: "30d"
        val root = context.http.requestJson(
            SiteHttpRequest(
                url = URI(QUERY_ENDPOINT),
                method = "POST",
                bearerToken = token,
                jsonBody = jsonObjectOf(
                    "site_id" to siteId,
                    "metrics" to SNAPSHOT_METRICS.map { it.first },
                    "date_range" to requestedRange,
                    "filters" to emptyList<Any>(),
                ),
            ),
        )
        val results = root["results"]?.arrayValue
            ?: throw SiteServiceException.decoding("Plausible did not return a query result list.")
        val values = results.firstOrNull()?.get("metrics").arr()
        val resourceId = SiteApiSupport.stableSiteResourceId(siteId)
        val metrics = SNAPSHOT_METRICS.mapIndexedNotNull { index, (key, label, unit) ->
            values.getOrNull(index).num()?.let { metric("plausible.$key", label, it, unit, resourceId = resourceId) }
        }
        val siteUrl = if (siteId.contains("://")) siteId else "https://$siteId"
        val resource = SiteResource(
            id = resourceId,
            provider = SiteProvider.PLAUSIBLE,
            name = siteId,
            subtitle = requestedRange,
            url = siteUrl.takeIf { runCatching { URI(it) }.isSuccess },
            status = "Connected",
            updatedAtMillis = context.nowMillis(),
            metrics = metrics,
            metadata = mapOf("dateRange" to requestedRange),
        )
        val warnings = listOfNotNull(root["meta"]?.get("imports_warning").nonEmptyString())
        return SiteSnapshot(
            provider = SiteProvider.PLAUSIBLE,
            resources = listOf(resource),
            metrics = metrics.map { it.copy(resourceId = null) },
            status = "Connected",
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    // MARK: Detail

    private data class Query(
        val id: String,
        val title: String,
        val dimensions: List<String>,
        val metrics: List<String>,
        val timeline: Boolean,
    )

    private class PagedReport(
        val merged: ProviderJsonValue,
        val pages: List<ProviderJsonValue>,
        val totalRows: Int,
        val truncated: Boolean,
    )

    suspend fun detail(request: SiteDetailRequest.Plausible): SiteDetailPayload {
        val siteId = request.siteId.trim()
        if (siteId.isEmpty()) throw SiteServiceException.invalidConfiguration("A Plausible site ID is required.")
        val queries = listOf(
            Query(
                "overview", "Overview", emptyList(),
                listOf("visitors", "visits", "pageviews", "views_per_visit", "bounce_rate", "visit_duration", "events"),
                false,
            ),
            Query("timeline", "Traffic over time", listOf("time:day"), listOf("visitors", "visits", "pageviews"), true),
            Query("sources", "Sources", listOf("visit:source"), listOf("visitors", "visits", "bounce_rate"), false),
            // Page breakdowns cannot use visit-level bounce/duration metrics in Stats API v2.
            Query("pages", "Pages", listOf("event:page"), listOf("visitors", "pageviews", "time_on_page"), false),
            Query("goals", "Goals", listOf("event:goal"), listOf("visitors", "events", "conversion_rate"), false),
            Query("countries", "Countries", listOf("visit:country"), listOf("visitors", "visits"), false),
            Query("devices", "Devices", listOf("visit:device"), listOf("visitors", "visits"), false),
        )
        val raw = LinkedHashMap<String, ProviderJsonValue>()
        val sections = ArrayList<SiteDetailSection>()
        val tables = ArrayList<SiteDetailTable>()
        val series = ArrayList<SiteDetailSeries>()
        val warnings = ArrayList<String>()
        var remainingRows = SiteDetailSupport.MAXIMUM_PAYLOAD_ROWS
        queries.forEachIndexed { index, query ->
            try {
                val body = linkedMapOf<String, Any?>(
                    "site_id" to siteId,
                    "date_range" to listOf(request.range.startDate, request.range.endDate),
                    "dimensions" to query.dimensions,
                    "metrics" to query.metrics,
                    "filters" to emptyList<Any>(),
                    // Plausible only emits meta.total_rows when asked; without it a full first page
                    // is indistinguishable from the last page.
                    "include" to mapOf("total_rows" to true),
                )
                val remainingQueries = (queries.size - index).coerceAtLeast(1)
                val result = pages(
                    body,
                    request.apiKey,
                    minOf(MAXIMUM_ROWS, (remainingRows / remainingQueries).coerceAtLeast(1)),
                )
                remainingRows = (remainingRows - result.merged["results"].arr().size).coerceAtLeast(0)
                raw[query.id] = ProviderJsonValue.Arr(result.pages)
                if (result.truncated) {
                    warnings += "Plausible ${query.title.lowercase()} has ${result.totalRows} rows; Verceltics kept " +
                        "the first ${result.merged["results"].arr().size} rows to protect device memory."
                }
                val normalized = normalize(result.merged, query)
                when {
                    query.id == "overview" -> normalized.first.firstOrNull()?.let { first ->
                        sections += SiteDetailSection(
                            "plausible.overview",
                            "Overview · ${request.range.startDate} – ${request.range.endDate}",
                            first.keys.sorted().map { SiteDetailField(it, humanized(it), first[it] ?: ProviderJsonValue.Null) },
                        )
                    }
                    query.timeline -> series += SiteDetailSeries(
                        id = "plausible.timeline",
                        title = query.title,
                        metricLabels = query.metrics.associateWith(::humanized),
                        points = normalized.first.mapNotNull { row ->
                            val x = row[query.dimensions.first()]?.stringValue ?: return@mapNotNull null
                            SiteDetailSeriesPoint(x, query.metrics.mapNotNull { name -> row[name]?.numberValue?.let { name to it } }.toMap())
                        },
                    )
                    else -> tables += SiteDetailTable(
                        id = "plausible.${query.id}",
                        title = query.title,
                        columns = query.dimensions + query.metrics,
                        rows = normalized.first,
                        nextCursor = normalized.second,
                    )
                }
                result.merged["meta"]?.get("imports_warning").nonEmptyString()?.let(warnings::add)
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                if (query.id == "overview") throw error
                warnings += "Plausible ${query.title.lowercase()} could not load: ${errorText(error)}"
            }
        }
        return SiteDetailPayload(
            provider = SiteProvider.PLAUSIBLE,
            resourceId = siteId,
            title = siteId,
            sections = sections,
            series = series,
            tables = tables,
            rawResponses = raw,
            warnings = warnings,
            fetchedAtMillis = context.nowMillis(),
        )
    }

    private suspend fun pages(body: Map<String, Any?>, apiKey: SecretValue, maximumRows: Int): PagedReport {
        var offset = 0
        var totalRows: Int? = null
        val pages = ArrayList<ProviderJsonValue>()
        val results = ArrayList<ProviderJsonValue>()
        var firstResponse: ProviderJsonValue? = null
        var pageCount = 0
        while (offset < minOf(totalRows ?: Int.MAX_VALUE, maximumRows)) {
            currentCoroutineContext().ensureActive()
            val remaining = maximumRows - results.size
            if (remaining <= 0) break
            val pageBody = LinkedHashMap(body).apply {
                put("pagination", mapOf("limit" to minOf(PAGE_SIZE, remaining), "offset" to offset))
            }
            val response = context.detailJson(
                SiteHttpRequest(
                    url = URI(QUERY_ENDPOINT),
                    method = "POST",
                    bearerToken = apiKey,
                    jsonBody = ProviderJsonValue.from(pageBody),
                ),
            )
            pageCount += 1
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            if (firstResponse == null) firstResponse = response
            val pageResults = response["results"].arr()
            results += pageResults.take(remaining)
            val reported = response["meta"]?.get("total_rows").num()?.toInt() ?: results.size
            val total = maxOf(totalRows ?: 0, maxOf(results.size, reported))
            totalRows = total
            if (results.size >= total) break
            if (results.size >= maximumRows || pageCount >= SiteDetailSupport.MAXIMUM_PAGINATION_PAGES) break
            if (pageResults.isEmpty()) {
                throw SiteServiceException.detailDecoding("Plausible stopped returning rows before the reported total was reached.")
            }
            offset += pageResults.size
        }
        val first = firstResponse ?: throw SiteServiceException.invalidResponse()
        val finalTotal = totalRows ?: results.size
        val merged = LinkedHashMap(first.obj()).apply {
            put("results", ProviderJsonValue.Arr(results))
            first["meta"]?.objectValue?.let { meta ->
                put("meta", ProviderJsonValue.Obj(LinkedHashMap(meta).apply { put("total_rows", ProviderJsonValue.Num.of(finalTotal)) }))
            }
        }
        return PagedReport(ProviderJsonValue.Obj(merged), pages, finalTotal, finalTotal > results.size)
    }

    private fun normalize(response: ProviderJsonValue, query: Query): Pair<List<Map<String, ProviderJsonValue>>, String?> {
        val rows = response["results"].arr().map { result ->
            val row = LinkedHashMap<String, ProviderJsonValue>()
            val dimensions = result["dimensions"].arr()
            query.dimensions.forEachIndexed { index, name -> dimensions.getOrNull(index)?.let { row[name] = it } }
            val metrics = result["metrics"].arr()
            query.metrics.forEachIndexed { index, name -> metrics.getOrNull(index)?.let { row[name] = it } }
            row
        }
        val total = response["meta"]?.get("total_rows").num()?.toInt() ?: rows.size
        return rows to if (total > rows.size) "${rows.size}/$total rows returned" else null
    }

    companion object {
        const val QUERY_ENDPOINT: String = "https://plausible.io/api/v2/query"
        private const val PAGE_SIZE = 10_000
        private const val MAXIMUM_ROWS = 100_000
        private val SNAPSHOT_METRICS = listOf(
            Triple("visitors", "Visitors", SiteMetricUnit.COUNT),
            Triple("visits", "Visits", SiteMetricUnit.COUNT),
            Triple("pageviews", "Page Views", SiteMetricUnit.COUNT),
            Triple("views_per_visit", "Views / Visit", SiteMetricUnit.RATIO),
            Triple("bounce_rate", "Bounce Rate", SiteMetricUnit.PERCENT),
            Triple("visit_duration", "Visit Duration", SiteMetricUnit.SECONDS),
        )
    }
}
