package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.markPartialMetrics
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedFields
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedObject
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Better Stack Uptime API adapter (`https://uptime.betterstack.com/api`). */
internal class BetterStackSiteAdapter(private val context: SiteApiContext) {
    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val token = account.requiredCredential("Better Stack API token")
        var nextUrl: URI? = URI("$ORIGIN/api/v2/monitors")
        val monitors = ArrayList<ProviderJsonValue>()
        val seenPages = HashSet<String>()
        val seenIds = HashSet<String>()
        val warnings = ArrayList<String>()
        var pageCount = 0
        var partial = false
        while (true) {
            val url = nextUrl ?: break
            currentCoroutineContext().ensureActive()
            if (!seenPages.add(url.toASCIIString())) {
                warnings += "Better Stack repeated a pagination URL, so loading stopped to avoid an endless request loop."
                partial = true
                break
            }
            val root = context.http.requestJson(SiteHttpRequest(url, bearerToken = token))
            val data = root["data"]?.arrayValue
                ?: throw SiteServiceException.decoding("Better Stack did not return a monitor list.")
            for (monitor in data) {
                val id = monitor["id"].str() ?: continue
                if (!seenIds.add(id)) continue
                monitors += monitor
                if (monitors.size >= MAXIMUM_MONITORS) break
            }
            pageCount += 1
            val next = root["pagination"]?.get("next").str()?.takeIf(String::isNotEmpty)
            nextUrl = next?.let { link ->
                val candidate = runCatching { URI(link) }.getOrNull()
                if (candidate == null || !candidate.scheme.equals("https", ignoreCase = true) ||
                    !candidate.host.equals(HOST, ignoreCase = true)
                ) {
                    throw SiteServiceException.invalidResponse()
                }
                candidate
            }
            if (nextUrl != null && monitors.size >= MAXIMUM_MONITORS) {
                warnings += "Better Stack monitor loading stopped after $MAXIMUM_MONITORS monitors to protect the device."
                partial = true
                nextUrl = null
            } else if (nextUrl != null && pageCount >= MAXIMUM_PAGES) {
                warnings += "Better Stack monitor loading stopped after $MAXIMUM_PAGES pages to protect the device. " +
                    "Some monitors may not be shown."
                partial = true
                nextUrl = null
            }
        }

        val resources = monitors.mapNotNull { monitor ->
            val id = monitor["id"].str() ?: return@mapNotNull null
            val attributes = monitor["attributes"]
            val urlValue = attributes["url"].str()
            val metrics = attributes["check_frequency"].num()?.let {
                listOf(metric("betterstack.check_frequency", "Check Frequency", it, SiteMetricUnit.SECONDS, resourceId = id))
            }.orEmpty()
            SiteResource(
                id = id,
                provider = SiteProvider.BETTER_STACK,
                name = attributes["pronounceable_name"].str() ?: urlValue ?: "Monitor $id",
                subtitle = urlValue,
                url = urlValue?.takeIf { runCatching { URI(it) }.isSuccess },
                status = attributes["status"].str()?.let(::capitalizedStatus) ?: "Unknown",
                updatedAtMillis = (attributes["last_checked_at"] ?: attributes["updated_at"]).dateMillis(),
                metrics = metrics,
                metadata = mapOf(
                    "monitorType" to (attributes["monitor_type"].str() ?: ""),
                    "teamName" to (attributes["team_name"].str() ?: ""),
                ),
            )
        }
        val up = resources.count { it.status?.lowercase(Locale.ROOT) == "up" }
        val down = resources.count { it.status?.lowercase(Locale.ROOT) == "down" }
        val paused = resources.count { it.status?.lowercase(Locale.ROOT) == "paused" }
        val status = when {
            resources.isEmpty() -> "No monitors"
            down > 0 -> "$down down"
            up == resources.size -> "All operational"
            else -> "$up up · ${resources.size - up} paused or checking"
        }
        return SiteSnapshot(
            provider = SiteProvider.BETTER_STACK,
            resources = resources,
            metrics = markPartialMetrics(
                listOf(
                    metric("betterstack.monitors", "Monitors", resources.size.toDouble(), SiteMetricUnit.COUNT),
                    metric("betterstack.up", "Up", up.toDouble(), SiteMetricUnit.COUNT),
                    metric("betterstack.down", "Down", down.toDouble(), SiteMetricUnit.COUNT),
                    metric("betterstack.paused", "Paused", paused.toDouble(), SiteMetricUnit.COUNT),
                ),
                partial,
            ),
            status = if (partial) "$status · Partial metrics" else status,
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    // MARK: Detail

    suspend fun detail(request: SiteDetailRequest.BetterStack): SiteDetailPayload {
        val id = SiteApiSupport.pathComponent(request.monitorId)
        val base = URI("$ORIGIN/")
        val monitor = context.detailJson(SiteHttpRequest(SiteApiSupport.endpoint(base, "api/v2/monitors/$id"), bearerToken = request.token))
        val raw = linkedMapOf("monitor" to monitor)
        val sections = ArrayList<SiteDetailSection>()
        val tables = ArrayList<SiteDetailTable>()
        val series = ArrayList<SiteDetailSeries>()
        val warnings = ArrayList<String>()
        val monitorData = monitor["data"] ?: monitor
        sections += SiteDetailSection("betterstack.monitor", "Monitor configuration and status", flattenedFields(monitorData, 3))
        val range = listOf("from" to request.range.startDate, "to" to request.range.endDate)

        try {
            val response = context.detailJson(
                SiteHttpRequest(SiteApiSupport.endpoint(base, "api/v2/monitors/$id/response-times", range), bearerToken = request.token),
            )
            raw["responseTimes"] = response
            val items = responseTimeItems(response)
            val rows = items.map(::flattenedObject)
            tables += SiteDetailTable(
                "betterstack.response-times", "Response-time samples",
                orderedColumns(
                    rows,
                    listOf(
                        "attributes.at", "attributes.region", "attributes.response_time",
                        "attributes.name_lookup_time", "attributes.connection_time",
                        "attributes.tls_handshake_time", "attributes.data_transfer_time",
                    ),
                ),
                rows,
            )
            responseSeries(items)?.let(series::add)
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Better Stack response-time history could not load: ${errorText(error)}"
        }

        try {
            val response = context.detailJson(
                SiteHttpRequest(SiteApiSupport.endpoint(base, "api/v2/monitors/$id/sla", range), bearerToken = request.token),
            )
            raw["sla"] = response
            sections += SiteDetailSection("betterstack.sla", "SLA", flattenedFields(response["data"] ?: response, 4))
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Better Stack SLA could not load: ${errorText(error)}"
        }

        try {
            val url = SiteApiSupport.endpoint(
                base, "api/v3/incidents",
                listOf("monitor_id" to request.monitorId) + range + ("per_page" to "50"),
            )
            val result = SitePaging.sameOriginLinkedPages(context, url, request.token, "data")
            raw["incidents"] = ProviderJsonValue.Arr(result.pages)
            if (result.truncated) {
                warnings += "Better Stack incidents reached the on-device pagination limit; additional incidents may be available."
            }
            val rows = result.items.map(::flattenedObject)
            tables += SiteDetailTable(
                "betterstack.incidents", "Incidents",
                orderedColumns(
                    rows,
                    listOf(
                        "id", "attributes.name", "attributes.status", "attributes.started_at",
                        "attributes.resolved_at", "attributes.cause",
                    ),
                ),
                rows,
            )
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "Better Stack incidents could not load: ${errorText(error)}"
        }

        val attributes = monitorData["attributes"]
        return SiteDetailPayload(
            provider = SiteProvider.BETTER_STACK,
            resourceId = request.monitorId,
            title = attributes["pronounceable_name"].str() ?: attributes["url"].str() ?: "Better Stack monitor ${request.monitorId}",
            sections = sections,
            series = series,
            tables = tables,
            rawResponses = raw,
            warnings = warnings,
            fetchedAtMillis = context.nowMillis(),
        )
    }

    private fun responseSeries(items: List<ProviderJsonValue>): SiteDetailSeries? {
        val byDate = java.util.TreeMap<String, MutableMap<String, Double>>()
        val labels = LinkedHashMap<String, String>()
        items.forEach { item ->
            val attributes = item["attributes"] ?: item
            val x = (attributes["at"] ?: attributes["checked_at"] ?: attributes["created_at"]).str() ?: return@forEach
            val region = attributes["region"].str() ?: "default"
            RESPONSE_FIELDS.forEach { (key, label) ->
                val value = attributes[key].num() ?: return@forEach
                val metric = "$region.$key"
                byDate.getOrPut(x) { LinkedHashMap() }[metric] = value
                labels[metric] = if (region == "default") label else "$region · $label"
            }
        }
        if (byDate.isEmpty()) return null
        return SiteDetailSeries(
            id = "betterstack.response-times.timeline",
            title = "Response time by region",
            metricLabels = labels,
            points = byDate.map { (x, values) -> SiteDetailSeriesPoint(x, values) },
        )
    }

    /** Samples live under `data.attributes.regions[].response_times[]`; keep the flat-array fallback. */
    private fun responseTimeItems(response: ProviderJsonValue): List<ProviderJsonValue> {
        (response["data"]?.arrayValue ?: response.arrayValue)?.let { return it }
        val data = response["data"] ?: response
        val attributes = data["attributes"] ?: data
        val items = ArrayList<ProviderJsonValue>()
        attributes["regions"].arr().forEach { region ->
            val samples = region["response_times"]?.arrayValue ?: region["responseTimes"].arr()
            val regionFields = region.obj().filterKeys { it != "response_times" && it != "responseTimes" }
            samples.forEach { sample ->
                val fields = LinkedHashMap(sample.obj())
                regionFields.forEach { (key, value) -> if (key !in fields) fields[key] = value }
                items += ProviderJsonValue.Obj(mapOf("attributes" to ProviderJsonValue.Obj(fields)))
            }
        }
        return items
    }

    companion object {
        const val HOST: String = "uptime.betterstack.com"
        const val ORIGIN: String = "https://$HOST"
        private const val MAXIMUM_PAGES = 100
        private const val MAXIMUM_MONITORS = 10_000
        private val RESPONSE_FIELDS = listOf(
            "response_time" to "Response time",
            "name_lookup_time" to "DNS lookup",
            "connection_time" to "Connection",
            "tls_handshake_time" to "TLS handshake",
            "data_transfer_time" to "Data transfer",
        )

        /** Swift `capitalized`: first letter of each word upper-cased, the rest lower-cased. */
        fun capitalizedStatus(value: String): String = value.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
