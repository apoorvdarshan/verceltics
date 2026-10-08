package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.jsonObjectOf
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.boundedConcurrentMap
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.markPartialMetrics
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.distributedLimits
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedFields
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedObject
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.humanized
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class GoogleAnalyticsDataStream(
    val name: String,
    val type: String,
    val displayName: String?,
    val measurementId: String?,
    val defaultUrl: String?,
)

data class GoogleAnalyticsStreamAttribution(
    val url: String?,
    val subtitle: String,
    val metadata: Map<String, String>,
    val isPropertyWide: Boolean,
)

/** GA4 Admin + Data API adapter (iOS `fetchGoogleAnalyticsSnapshot` and detail client). */
internal class GoogleAnalyticsSiteAdapter(private val context: SiteApiContext) {
    private data class PropertyDescriptor(
        val propertyName: String,
        val propertyId: String,
        val displayName: String,
        val accountName: String,
        val propertyType: String,
        val canEdit: Boolean,
    )

    // MARK: Snapshot

    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val token = account.googleAccessToken ?: throw SiteServiceException.oauthNotConfigured(
            SiteProvider.GOOGLE_ANALYTICS,
            SiteProvider.GOOGLE_ANALYTICS.oauthScopes,
        )
        val summaries = ArrayList<ProviderJsonValue>()
        val seenTokens = HashSet<String>()
        val warnings = ArrayList<String>()
        var summaryListIsPartial = false
        var pageToken: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val query = buildList {
                add("pageSize" to "200")
                pageToken?.let { add("pageToken" to it) }
            }
            val root = context.http.requestJson(
                SiteHttpRequest(SiteApiSupport.url("$ADMIN_BASE/v1beta/accountSummaries", query), bearerToken = token),
            )
            if (root["accountSummaries"] == null && root.obj().isNotEmpty()) {
                throw SiteServiceException.decoding("Google Analytics did not return account summaries.")
            }
            summaries += root["accountSummaries"].arr()
            val next = root["nextPageToken"].nonEmptyString()
            pageToken = if (next != null && !seenTokens.add(next)) {
                warnings += "Google Analytics repeated a pagination token, so account loading stopped safely."
                summaryListIsPartial = true
                null
            } else {
                next
            }
        } while (pageToken != null)

        val properties = summaries.flatMap { summary ->
            val accountName = summary["displayName"].str() ?: summary["account"].str() ?: "Google Analytics"
            summary["propertySummaries"].arr().mapNotNull { value ->
                val propertyName = value["property"].nonEmptyString() ?: return@mapNotNull null
                val propertyId = propertyName.split('/').lastOrNull()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: return@mapNotNull null
                PropertyDescriptor(
                    propertyName = propertyName,
                    propertyId = propertyId,
                    displayName = value["displayName"].str() ?: "GA4 property $propertyId",
                    accountName = accountName,
                    propertyType = value["propertyType"].str() ?: "PROPERTY_TYPE_ORDINARY",
                    canEdit = value["canEdit"].bool() ?: false,
                )
            }
        }
        val loads = boundedConcurrentMap(properties) { property -> loadProperty(property, token) }
        val resources = loads.map(SiteResourceLoad::resource)
        warnings += loads.flatMap(SiteResourceLoad::warnings)
        val hasPartialMetrics = summaryListIsPartial || loads.any(SiteResourceLoad::metricsArePartial)
        return SiteSnapshot(
            provider = SiteProvider.GOOGLE_ANALYTICS,
            resources = resources,
            metrics = markPartialMetrics(
                aggregateMetrics(resources),
                hasPartialMetrics,
                excluding = if (summaryListIsPartial) emptySet() else setOf("ga4.properties"),
            ),
            status = when {
                resources.isEmpty() -> "No GA4 properties"
                hasPartialMetrics -> "Connected · Partial metrics"
                else -> "Connected"
            },
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    private suspend fun loadProperty(property: PropertyDescriptor, token: SecretValue): SiteResourceLoad {
        val metrics = ArrayList<SiteMetric>()
        val warnings = ArrayList<String>()
        var metricsArePartial = false
        var attribution = GoogleAnalyticsStreamAttribution(
            url = null,
            subtitle = property.accountName,
            metadata = mapOf("metricsScope" to "Property-wide"),
            isPropertyWide = true,
        )
        try {
            attribution = streamAttribution(dataStreams(property.propertyId, token), property.accountName)
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfUnauthorized(error)
            warnings += "${property.displayName} data streams could not load: ${errorText(error)}"
        }
        try {
            metrics += report(property.propertyId, token, property.propertyName)
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfUnauthorized(error)
            metricsArePartial = true
            warnings += "${property.displayName} analytics could not load: ${errorText(error)}"
        }
        try {
            realtimeUsers(property.propertyId, token)?.let { value ->
                metrics += metric(
                    "ga4.realtime_active_users", "Active Now", value, SiteMetricUnit.COUNT,
                    resourceId = property.propertyName,
                )
            }
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfUnauthorized(error)
            metricsArePartial = true
            warnings += "${property.displayName} realtime data could not load: ${errorText(error)}"
        }
        val metadata = LinkedHashMap<String, String>().apply {
            put("property", property.propertyName)
            put("propertyID", property.propertyId)
            put("accountName", property.accountName)
            put("propertyType", property.propertyType)
            put("canEdit", property.canEdit.toString())
            put("range", "30 days")
            putAll(attribution.metadata)
        }
        val status = when {
            metrics.isEmpty() -> "Property found"
            attribution.isPropertyWide -> "Reporting · Property-wide"
            else -> "Reporting"
        }
        return SiteResourceLoad(
            resource = SiteResource(
                id = property.propertyName,
                provider = SiteProvider.GOOGLE_ANALYTICS,
                name = property.displayName,
                subtitle = attribution.subtitle,
                url = attribution.url,
                status = status,
                updatedAtMillis = context.nowMillis(),
                metrics = metrics,
                metadata = metadata,
            ),
            warnings = warnings,
            metricsArePartial = metricsArePartial,
        )
    }

    private suspend fun dataStreams(propertyId: String, token: SecretValue): List<GoogleAnalyticsDataStream> {
        val streams = ArrayList<GoogleAnalyticsDataStream>()
        val seenTokens = HashSet<String>()
        var pageToken: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val query = buildList {
                add("pageSize" to "200")
                pageToken?.let { add("pageToken" to it) }
            }
            val root = context.http.requestJson(
                SiteHttpRequest(
                    SiteApiSupport.url("$ADMIN_BASE/v1beta/properties/$propertyId/dataStreams", query),
                    bearerToken = token,
                ),
            )
            if (root["dataStreams"] == null && root.obj().isNotEmpty()) {
                throw SiteServiceException.decoding("Google Analytics did not return a data stream list.")
            }
            streams += root["dataStreams"].arr().mapNotNull { stream ->
                val name = stream["name"].nonEmptyString() ?: return@mapNotNull null
                val type = stream["type"].nonEmptyString() ?: return@mapNotNull null
                val web = stream["webStreamData"]
                GoogleAnalyticsDataStream(
                    name = name,
                    type = type,
                    displayName = stream["displayName"].nonEmptyString(),
                    measurementId = web["measurementId"].nonEmptyString(),
                    defaultUrl = web["defaultUri"].nonEmptyString(),
                )
            }
            val next = root["nextPageToken"].nonEmptyString()
            if (next != null && !seenTokens.add(next)) {
                throw SiteServiceException.decoding("Google Analytics data stream pagination repeated a token.")
            }
            pageToken = next
        } while (pageToken != null)
        return streams
    }

    private suspend fun report(propertyId: String, token: SecretValue, resourceId: String): List<SiteMetric> {
        val root = context.http.requestJson(
            SiteHttpRequest(
                URI("$DATA_BASE/v1beta/properties/$propertyId:runReport"),
                method = "POST",
                bearerToken = token,
                jsonBody = jsonObjectOf(
                    "dateRanges" to listOf(mapOf("startDate" to "30daysAgo", "endDate" to "today")),
                    "metrics" to SNAPSHOT_METRICS.map { mapOf("name" to it.first) },
                    "keepEmptyRows" to true,
                ),
            ),
        )
        val values = root["rows"].arr().firstOrNull()?.get("metricValues").arr()
        if (root["rows"].arr().isEmpty()) return emptyList()
        return SNAPSHOT_METRICS.mapIndexedNotNull { index, (name, label, unit) ->
            val value = values.getOrNull(index)?.get("value").num() ?: return@mapIndexedNotNull null
            metric(
                "ga4.$name", label,
                if (unit == SiteMetricUnit.PERCENT) value * 100 else value,
                unit,
                resourceId = resourceId,
            )
        }
    }

    private suspend fun realtimeUsers(propertyId: String, token: SecretValue): Double? {
        val root = context.http.requestJson(
            SiteHttpRequest(
                URI("$DATA_BASE/v1beta/properties/$propertyId:runRealtimeReport"),
                method = "POST",
                bearerToken = token,
                jsonBody = jsonObjectOf("metrics" to listOf(mapOf("name" to "activeUsers"))),
            ),
        )
        val row = root["rows"].arr().firstOrNull() ?: return null
        return row["metricValues"].arr().firstOrNull()?.get("value").num()
    }

    private fun aggregateMetrics(resources: List<SiteResource>): List<SiteMetric> {
        val metrics = arrayListOf(
            metric("ga4.properties", "Properties", resources.size.toDouble(), SiteMetricUnit.COUNT),
        )
        val all = resources.flatMap(SiteResource::metrics)
        listOf(
            Triple("ga4.activeUsers", "Active Users", SiteMetricUnit.COUNT),
            Triple("ga4.sessions", "Sessions", SiteMetricUnit.COUNT),
            Triple("ga4.screenPageViews", "Page Views", SiteMetricUnit.COUNT),
            Triple("ga4.eventCount", "Events", SiteMetricUnit.COUNT),
            Triple("ga4.realtime_active_users", "Active Now", SiteMetricUnit.COUNT),
        ).forEach { (key, label, unit) ->
            val matching = all.filter { it.key == key }
            if (matching.isNotEmpty()) metrics += metric(key, label, matching.sumOf(SiteMetric::value), unit)
        }
        val weighted = resources.mapNotNull { resource ->
            val rate = resource.metrics.firstOrNull { it.key == "ga4.engagementRate" }?.value ?: return@mapNotNull null
            val sessions = resource.metrics.firstOrNull { it.key == "ga4.sessions" }?.value ?: return@mapNotNull null
            if (sessions > 0) rate to sessions else null
        }
        if (weighted.isNotEmpty()) {
            val totalSessions = weighted.sumOf { it.second }
            metrics += metric(
                "ga4.engagementRate", "Engagement Rate",
                weighted.sumOf { it.first * it.second } / totalSessions,
                SiteMetricUnit.PERCENT,
            )
        }
        return metrics
    }

    // MARK: Detail

    private data class Query(
        val id: String,
        val title: String,
        val dimensions: List<String>,
        val metrics: List<String>,
        val isTimeline: Boolean,
    )

    private data class ReportWork(val query: Query, val maximumRows: Int)

    private class PagedReport(
        val merged: ProviderJsonValue,
        val pages: List<ProviderJsonValue>,
        val totalRows: Int,
        val truncated: Boolean,
    ) {
        val retainedRows: Int get() = merged["rows"].arr().size
    }

    private class ReportResult(val query: Query, val report: PagedReport)

    private class Surfaces {
        var title: String? = null
        val raw = LinkedHashMap<String, ProviderJsonValue>()
        val sections = ArrayList<SiteDetailSection>()
        val series = ArrayList<SiteDetailSeries>()
        val tables = ArrayList<SiteDetailTable>()
        val warnings = ArrayList<String>()

        fun merge(other: Surfaces) {
            other.title?.let { title = it }
            raw.putAll(other.raw)
            sections += other.sections
            series += other.series
            tables += other.tables
            warnings += other.warnings
        }
    }

    private sealed interface Auxiliary {
        data object RealtimeOverview : Auxiliary
        data object RealtimeTimeline : Auxiliary
        data object Property : Auxiliary
        data object DataStreams : Auxiliary
        data object DataApiMetadata : Auxiliary
        data class Setting(val key: String, val title: String, val version: String, val suffix: String) : Auxiliary
    }

    suspend fun detail(
        request: SiteDetailRequest.GoogleAnalytics,
        onPartial: (suspend (SiteDetailPayload) -> Unit)?,
    ): SiteDetailPayload {
        val propertyId = request.propertyId.trim()
        if (propertyId.isEmpty()) throw SiteServiceException.invalidConfiguration("A GA4 property ID is required.")
        val encodedProperty = SiteApiSupport.pathComponent(propertyId.removePrefix("properties/"))
        val reportUrl = URI("$DATA_BASE/v1beta/properties/$encodedProperty:runReport")
        val coreMetrics = listOf(
            "activeUsers", "sessions", "screenPageViews", "engagementRate", "eventCount", "averageSessionDuration",
        )
        val queries = listOf(
            Query("overview", "Overview", emptyList(), coreMetrics, false),
            Query("timeline", "Traffic over time", listOf("date"), coreMetrics, true),
            Query(
                "acquisition", "Acquisition", listOf("sessionDefaultChannelGroup", "sessionSource", "sessionMedium"),
                listOf("sessions", "activeUsers", "engagementRate"), false,
            ),
            Query(
                "pages", "Pages", listOf("pagePath", "pageTitle"),
                listOf("screenPageViews", "activeUsers", "averageSessionDuration"), false,
            ),
            Query("events", "Events", listOf("eventName"), listOf("eventCount", "activeUsers"), false),
            Query("geography", "Geography", listOf("country", "city"), listOf("activeUsers", "sessions"), false),
            Query(
                "technology", "Technology", listOf("deviceCategory", "browser", "operatingSystem"),
                listOf("activeUsers", "sessions"), false,
            ),
        )
        val coreQueries = queries.take(2)
        val breakdownQueries = queries.drop(2)
        val timelineRows = minOf(SiteDetailSupport.MAXIMUM_PAYLOAD_ROWS / 4, request.range.dayCount.coerceAtLeast(1))
        val coreWork = listOf(ReportWork(coreQueries[0], 1), ReportWork(coreQueries[1], timelineRows))
        val coreResults = boundedConcurrentMap(coreWork, coreWork.size) { work ->
            fetchReport(work, reportUrl, request.accessToken, request.range)
        }
        val fetchedAt = context.nowMillis()
        val surfaces = surfaces(coreResults, request.range)
        onPartial?.invoke(
            SiteDetailSupport.boundedForDevice(
                SiteDetailPayload(
                    provider = SiteProvider.GOOGLE_ANALYTICS,
                    resourceId = propertyId,
                    title = "Google Analytics · $propertyId",
                    sections = surfaces.sections.toList(),
                    series = surfaces.series.toList(),
                    tables = surfaces.tables.toList(),
                    rawResponses = LinkedHashMap(surfaces.raw),
                    warnings = surfaces.warnings.toList(),
                    fetchedAtMillis = fetchedAt,
                ),
            ),
        )

        val usedCoreRows = coreResults.sumOf { it.report.retainedRows }
        val remainingRows = maxOf(breakdownQueries.size, SiteDetailSupport.MAXIMUM_PAYLOAD_ROWS - usedCoreRows)
        val breakdownWork = breakdownQueries.zip(distributedLimits(remainingRows, breakdownQueries.size)) { query, limit ->
            ReportWork(query, limit)
        }
        val (initialBreakdowns, auxiliary) = coroutineScope {
            val breakdowns = async {
                boundedConcurrentMap(breakdownWork, 3) { work ->
                    fetchReport(work, reportUrl, request.accessToken, request.range)
                }
            }
            val auxiliaryJob = async { auxiliarySurfaces(encodedProperty, request.accessToken) }
            breakdowns.await() to auxiliaryJob.await()
        }
        val loadedBreakdowns = refillBudget(initialBreakdowns, remainingRows, reportUrl, request.accessToken, request.range)
        surfaces.merge(surfaces(loadedBreakdowns, request.range))
        surfaces.merge(auxiliary)
        return SiteDetailPayload(
            provider = SiteProvider.GOOGLE_ANALYTICS,
            resourceId = propertyId,
            title = surfaces.title ?: "Google Analytics · $propertyId",
            sections = surfaces.sections.toList(),
            series = surfaces.series.toList(),
            tables = surfaces.tables.toList(),
            rawResponses = LinkedHashMap(surfaces.raw),
            warnings = surfaces.warnings.toList(),
            fetchedAtMillis = fetchedAt,
        )
    }

    private suspend fun fetchReport(
        work: ReportWork,
        url: URI,
        token: SecretValue,
        range: SiteDetailRange,
    ): ReportResult {
        val body = LinkedHashMap<String, Any?>().apply {
            put("dateRanges", listOf(mapOf("startDate" to range.startDate, "endDate" to range.endDate)))
            put("dimensions", work.query.dimensions.map { mapOf("name" to it) })
            put("metrics", work.query.metrics.map { mapOf("name" to it) })
            if (work.query.dimensions.isNotEmpty()) {
                // Stable dimension ordering is required while offset paging.
                put("orderBys", work.query.dimensions.map { mapOf("dimension" to mapOf("dimensionName" to it), "desc" to false) })
            }
        }
        val report = fetchReportPages(url, body, token, minOf(MAXIMUM_REPORT_ROWS, work.maximumRows.coerceAtLeast(1)))
        return ReportResult(work.query, report)
    }

    private suspend fun fetchReportPages(
        url: URI,
        body: Map<String, Any?>,
        token: SecretValue,
        maximumRows: Int,
    ): PagedReport {
        var offset = 0
        var totalRows: Int? = null
        val pages = ArrayList<ProviderJsonValue>()
        val combinedRows = ArrayList<ProviderJsonValue>()
        var firstResponse: ProviderJsonValue? = null
        while (offset < minOf(totalRows ?: Int.MAX_VALUE, maximumRows)) {
            currentCoroutineContext().ensureActive()
            val remaining = maximumRows - offset
            if (remaining <= 0) break
            val pageBody = LinkedHashMap(body).apply {
                put("limit", minOf(REPORT_PAGE_SIZE, remaining).toString())
                put("offset", offset.toString())
            }
            val response = detailJson(SiteHttpRequest(url, method = "POST", bearerToken = token, jsonBody = ProviderJsonValue.from(pageBody)))
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            if (firstResponse == null) firstResponse = response
            val pageRows = response["rows"].arr()
            val reported = (response["rowCount"].num() ?: pageRows.size.toDouble()).toInt().coerceAtLeast(0)
            totalRows = totalRows?.let { maxOf(it, reported) } ?: reported
            combinedRows += pageRows.take(remaining)
            if (combinedRows.size >= minOf(totalRows, maximumRows)) break
            if (pageRows.isEmpty()) {
                throw SiteServiceException.detailDecoding(
                    "Google Analytics stopped returning rows before the reported row count was reached.",
                )
            }
            offset += pageRows.size
        }
        val first = firstResponse ?: throw SiteServiceException.invalidResponse()
        val total = totalRows ?: combinedRows.size
        val merged = LinkedHashMap(first.obj()).apply {
            put("rows", ProviderJsonValue.Arr(combinedRows))
            put("rowCount", ProviderJsonValue.Num.of(total))
        }
        return PagedReport(
            merged = ProviderJsonValue.Obj(merged),
            pages = pages,
            totalRows = total,
            truncated = total > combinedRows.size && combinedRows.size == maximumRows,
        )
    }

    /** Refills only truncated breakdowns until the shared row budget is actually consumed. */
    private suspend fun refillBudget(
        initial: List<ReportResult>,
        totalBudget: Int,
        url: URI,
        token: SecretValue,
        range: SiteDetailRange,
    ): List<ReportResult> {
        val results = initial.toMutableList()
        var remaining = (totalBudget - results.sumOf { it.report.retainedRows }).coerceAtLeast(0)
        var round = 0
        while (remaining > 0 && round < results.size) {
            val candidates = results.indices.filter { results[it].report.truncated }
            if (candidates.isEmpty()) break
            val work = candidates.zip(distributedLimits(remaining, candidates.size)).mapNotNull { (index, increment) ->
                if (increment <= 0) return@mapNotNull null
                val current = results[index].report.retainedRows
                val expanded = minOf(MAXIMUM_REPORT_ROWS, results[index].report.totalRows, current + increment)
                if (expanded <= current) null else index to ReportWork(results[index].query, expanded)
            }
            if (work.isEmpty()) break
            val refilled = boundedConcurrentMap(work, 3) { (index, item) -> index to fetchReport(item, url, token, range) }
            var added = 0
            refilled.forEach { (index, result) ->
                val old = results[index].report.retainedRows
                val new = result.report.retainedRows
                if (new > old) {
                    results[index] = result
                    added += new - old
                }
            }
            if (added <= 0) break
            remaining = (remaining - added).coerceAtLeast(0)
            round += 1
        }
        return results
    }

    private fun surfaces(results: List<ReportResult>, range: SiteDetailRange): Surfaces {
        val output = Surfaces()
        results.forEach { result ->
            val query = result.query
            val report = result.report
            output.raw[query.id] = ProviderJsonValue.Arr(report.pages)
            if (report.truncated) {
                output.warnings += "Google Analytics ${query.title.lowercase()} has ${report.totalRows} rows; " +
                    "Verceltics kept the first ${report.retainedRows} rows within the shared device budget."
            }
            val normalized = normalizeReport(report.merged)
            when {
                query.id == "overview" -> normalized.rows.firstOrNull()?.let { first ->
                    output.sections += SiteDetailSection(
                        id = "ga4.overview",
                        title = "Overview · ${range.startDate} – ${range.endDate}",
                        fields = first.keys.sorted().map { SiteDetailField(it, humanized(it), first[it] ?: ProviderJsonValue.Null) },
                    )
                }
                query.isTimeline -> output.series += SiteDetailSeries(
                    id = "ga4.timeline",
                    title = query.title,
                    metricLabels = normalized.metricNames.associateWith(::humanized),
                    points = normalized.rows.mapNotNull { row ->
                        val rawDate = row["date"]?.stringValue ?: return@mapNotNull null
                        SiteDetailSeriesPoint(
                            normalizedDate(rawDate),
                            normalized.metricNames.mapNotNull { name -> row[name]?.numberValue?.let { name to it } }.toMap(),
                        )
                    },
                )
                else -> output.tables += SiteDetailTable(
                    id = "ga4.${query.id}",
                    title = query.title,
                    columns = normalized.columns,
                    rows = normalized.rows,
                    nextCursor = normalized.nextCursor,
                )
            }
        }
        return output
    }

    private suspend fun auxiliarySurfaces(propertyId: String, token: SecretValue): Surfaces {
        val requests = listOf(
            Auxiliary.RealtimeOverview,
            Auxiliary.RealtimeTimeline,
            Auxiliary.Property,
            Auxiliary.DataStreams,
            Auxiliary.DataApiMetadata,
            Auxiliary.Setting("dataRetentionSettings", "Data retention", "v1beta", "dataRetentionSettings"),
            Auxiliary.Setting("googleSignalsSettings", "Google Signals", "v1alpha", "googleSignalsSettings"),
            Auxiliary.Setting("attributionSettings", "Attribution", "v1alpha", "attributionSettings"),
            Auxiliary.Setting("reportingIdentitySettings", "Reporting identity", "v1alpha", "reportingIdentitySettings"),
            Auxiliary.Setting("userProvidedDataSettings", "User-provided data", "v1alpha", "userProvidedDataSettings"),
        )
        val fragments = boundedConcurrentMap(requests, 4) { auxiliaryFragment(it, propertyId, token) }
        return Surfaces().also { output -> fragments.forEach(output::merge) }
    }

    private suspend fun auxiliaryFragment(request: Auxiliary, propertyId: String, token: SecretValue): Surfaces {
        val output = Surfaces()
        try {
            when (request) {
                Auxiliary.RealtimeOverview -> {
                    val response = detailJson(
                        SiteHttpRequest(
                            URI("$DATA_BASE/v1beta/properties/$propertyId:runRealtimeReport"),
                            method = "POST",
                            bearerToken = token,
                            jsonBody = jsonObjectOf(
                                "metrics" to REALTIME_METRICS.map { mapOf("name" to it) },
                                "returnPropertyQuota" to true,
                            ),
                        ),
                    )
                    output.raw["realtime.overview"] = response
                    val normalized = normalizeReport(response)
                    normalized.rows.firstOrNull()?.let { row ->
                        output.sections += SiteDetailSection(
                            id = "ga4.realtime.overview",
                            title = "Realtime · last 30 minutes",
                            fields = normalized.metricNames.map { SiteDetailField(it, humanized(it), row[it] ?: ProviderJsonValue.Null) },
                        )
                    }
                }
                Auxiliary.RealtimeTimeline -> {
                    val response = detailJson(
                        SiteHttpRequest(
                            URI("$DATA_BASE/v1beta/properties/$propertyId:runRealtimeReport"),
                            method = "POST",
                            bearerToken = token,
                            jsonBody = jsonObjectOf(
                                "dimensions" to listOf(mapOf("name" to "minutesAgo")),
                                "metrics" to REALTIME_METRICS.map { mapOf("name" to it) },
                                "orderBys" to listOf(mapOf("dimension" to mapOf("dimensionName" to "minutesAgo"), "desc" to false)),
                                "limit" to "60",
                                "returnPropertyQuota" to true,
                            ),
                        ),
                    )
                    output.raw["realtime.report"] = response
                    val normalized = normalizeReport(response)
                    output.tables += SiteDetailTable(
                        id = "ga4.realtime.report",
                        title = "Realtime activity by minute",
                        columns = normalized.columns,
                        rows = normalized.rows,
                        nextCursor = normalized.nextCursor,
                    )
                    output.series += SiteDetailSeries(
                        id = "ga4.realtime.timeline",
                        title = "Realtime activity",
                        metricLabels = normalized.metricNames.associateWith(::humanized),
                        points = normalized.rows.mapNotNull { row ->
                            val minute = row["minutesAgo"]?.stringValue ?: return@mapNotNull null
                            SiteDetailSeriesPoint(
                                minute,
                                normalized.metricNames.mapNotNull { name -> row[name]?.numberValue?.let { name to it } }.toMap(),
                            )
                        },
                    )
                }
                Auxiliary.Property -> {
                    val response = detailJson(
                        SiteHttpRequest(URI("$ADMIN_BASE/v1beta/properties/$propertyId"), bearerToken = token),
                    )
                    output.raw["property"] = response
                    output.title = response["displayName"].str() ?: "Google Analytics · $propertyId"
                    output.sections += SiteDetailSection("ga4.property", "Property metadata", flattenedFields(response, 3))
                }
                Auxiliary.DataStreams -> {
                    val result = SitePaging.googleTokenPages(
                        context,
                        SiteApiSupport.url("$ADMIN_BASE/v1beta/properties/$propertyId/dataStreams", listOf("pageSize" to "200")),
                        itemKey = "dataStreams",
                        token = token,
                    )
                    output.raw["dataStreams"] = ProviderJsonValue.Arr(result.pages)
                    if (result.truncated) {
                        output.warnings += "Google Analytics data streams reached the on-device pagination limit; " +
                            "additional streams may be available."
                    }
                    val rows = result.items.map(::flattenedObject)
                    output.tables += SiteDetailTable(
                        id = "ga4.dataStreams",
                        title = "Data streams",
                        columns = orderedColumns(
                            rows,
                            listOf(
                                "name", "type", "displayName", "webStreamData.measurementId",
                                "webStreamData.defaultUri", "androidAppStreamData.packageName",
                                "iosAppStreamData.bundleId",
                            ),
                        ),
                        rows = rows,
                    )
                }
                Auxiliary.DataApiMetadata -> {
                    val response = detailJson(
                        SiteHttpRequest(URI("$DATA_BASE/v1beta/properties/$propertyId/metadata"), bearerToken = token),
                    )
                    output.raw["dataAPI.metadata"] = response
                    listOf("dimensions" to "Available dimensions", "metrics" to "Available metrics").forEach { (key, title) ->
                        val rows = response[key].arr().map(::flattenedObject)
                        output.tables += SiteDetailTable(
                            id = "ga4.metadata.$key",
                            title = title,
                            columns = orderedColumns(rows, listOf("apiName", "uiName", "description", "category", "deprecatedApiNames")),
                            rows = rows,
                        )
                    }
                }
                is Auxiliary.Setting -> {
                    val response = detailJson(
                        SiteHttpRequest(
                            URI("$ADMIN_BASE/${request.version}/properties/$propertyId/${request.suffix}"),
                            bearerToken = token,
                        ),
                    )
                    output.raw[request.key] = response
                    output.sections += SiteDetailSection("ga4.${request.key}", request.title, flattenedFields(response, 3))
                }
            }
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            output.warnings += "Google Analytics ${description(request)} could not load: ${errorText(error)}"
        }
        return output
    }

    private fun description(request: Auxiliary): String = when (request) {
        Auxiliary.RealtimeOverview -> "realtime overview"
        Auxiliary.RealtimeTimeline -> "realtime report"
        Auxiliary.Property -> "property metadata"
        Auxiliary.DataStreams -> "data streams"
        Auxiliary.DataApiMetadata -> "Data API metadata"
        is Auxiliary.Setting -> request.title.lowercase()
    }

    private class NormalizedReport(
        val columns: List<String>,
        val metricNames: List<String>,
        val rows: List<Map<String, ProviderJsonValue>>,
        val nextCursor: String?,
    )

    private fun normalizeReport(response: ProviderJsonValue): NormalizedReport {
        val dimensionNames = response["dimensionHeaders"].arr().mapNotNull { it["name"].str() }
        val metricNames = response["metricHeaders"].arr().mapNotNull { it["name"].str() }
        val rows = response["rows"].arr().map { rawRow ->
            val row = LinkedHashMap<String, ProviderJsonValue>()
            val dimensions = rawRow["dimensionValues"].arr()
            dimensionNames.forEachIndexed { index, name ->
                dimensions.getOrNull(index)?.let { row[name] = it["value"] ?: ProviderJsonValue.Null }
            }
            val metrics = rawRow["metricValues"].arr()
            metricNames.forEachIndexed { index, name ->
                metrics.getOrNull(index)?.let { metricValue ->
                    val value = metricValue["value"] ?: ProviderJsonValue.Null
                    row[name] = value.numberValue?.let(ProviderJsonValue.Num::of) ?: value
                }
            }
            row
        }
        val total = (response["rowCount"].num() ?: rows.size.toDouble()).toInt()
        return NormalizedReport(
            columns = dimensionNames + metricNames,
            metricNames = metricNames,
            rows = rows,
            nextCursor = if (total > rows.size) "${rows.size}/$total rows returned" else null,
        )
    }

    private fun normalizedDate(value: String): String =
        if (value.length == 8) "${value.take(4)}-${value.substring(4, 6)}-${value.takeLast(2)}" else value

    private suspend fun detailJson(request: SiteHttpRequest): ProviderJsonValue = context.detailJson(request)

    companion object {
        const val ADMIN_BASE: String = "https://analyticsadmin.googleapis.com"
        const val DATA_BASE: String = "https://analyticsdata.googleapis.com"
        private const val MAXIMUM_REPORT_ROWS = 100_000
        private const val REPORT_PAGE_SIZE = 10_000
        private val REALTIME_METRICS = listOf("activeUsers", "eventCount", "screenPageViews")
        private val SNAPSHOT_METRICS = listOf(
            Triple("activeUsers", "Active Users", SiteMetricUnit.COUNT),
            Triple("sessions", "Sessions", SiteMetricUnit.COUNT),
            Triple("screenPageViews", "Page Views", SiteMetricUnit.COUNT),
            Triple("engagementRate", "Engagement Rate", SiteMetricUnit.PERCENT),
            Triple("eventCount", "Events", SiteMetricUnit.COUNT),
            Triple("averageSessionDuration", "Avg. Session", SiteMetricUnit.SECONDS),
        )

        /**
         * Attributes property-wide GA4 metrics to a website only when the property has exactly one
         * stream and it is a web stream (iOS `googleAnalyticsStreamAttribution`).
         */
        fun streamAttribution(
            streams: List<GoogleAnalyticsDataStream>,
            accountName: String,
        ): GoogleAnalyticsStreamAttribution {
            val webStreams = streams.filter { it.type == "WEB_DATA_STREAM" }
            val singleWebOnly = streams.size == 1 && webStreams.size == 1
            val metadata = LinkedHashMap<String, String>().apply {
                put("dataStreamCount", streams.size.toString())
                put("webStreamCount", webStreams.size.toString())
                put("metricsScope", if (singleWebOnly) "Single data stream" else "Property-wide")
                streams.map(GoogleAnalyticsDataStream::name).takeIf(List<String>::isNotEmpty)
                    ?.let { put("dataStreams", it.joinToString(", ")) }
                webStreams.mapNotNull(GoogleAnalyticsDataStream::measurementId).takeIf(List<String>::isNotEmpty)
                    ?.let { put("measurementIDs", it.joinToString(", ")) }
                webStreams.mapNotNull(GoogleAnalyticsDataStream::defaultUrl).takeIf(List<String>::isNotEmpty)
                    ?.let { put("siteURLs", it.joinToString(", ")) }
            }
            if (singleWebOnly) {
                val stream = webStreams.first()
                metadata["dataStream"] = stream.name
                stream.measurementId?.let { metadata["measurementID"] = it }
                stream.defaultUrl?.let { metadata["siteURL"] = it }
                return GoogleAnalyticsStreamAttribution(
                    url = stream.defaultUrl,
                    subtitle = stream.defaultUrl ?: stream.displayName ?: accountName,
                    metadata = metadata,
                    isPropertyWide = false,
                )
            }
            val subtitle = when {
                webStreams.size > 1 && webStreams.size == streams.size -> "${webStreams.size} web streams · $accountName"
                streams.isNotEmpty() -> "${streams.size} data streams · $accountName"
                else -> accountName
            }
            return GoogleAnalyticsStreamAttribution(null, subtitle, metadata, isPropertyWide = true)
        }
    }
}
