package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.markPartialMetrics
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedFields
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.flattenedObject
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.rows
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class UptimeRobotPaginationAction {
    LOAD_NEXT_PAGE,
    COMPLETE,
    NO_PROGRESS,
}

/** UptimeRobot v2 adapter. Only read-only methods are called; the API key is sent in the POST form. */
internal class UptimeRobotSiteAdapter(private val context: SiteApiContext) {
    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val apiKey = account.requiredCredential("UptimeRobot read-only API key")
        val monitors = ArrayList<ProviderJsonValue>()
        val seenIds = HashSet<String>()
        val warnings = ArrayList<String>()
        var offset = 0
        var pageCount = 0
        var partial = false
        pages@ while (true) {
            currentCoroutineContext().ensureActive()
            val root = context.http.requestJson(
                SiteHttpRequest(
                    url = URI("$BASE/getMonitors"),
                    method = "POST",
                    secretFormFields = listOf("api_key" to apiKey),
                    formFields = listOf(
                        "format" to "json",
                        "logs" to "1",
                        "logs_limit" to "1",
                        "response_times" to "1",
                        "custom_uptime_ratios" to "1-7-30",
                        "limit" to PAGE_SIZE.toString(),
                        "offset" to offset.toString(),
                    ),
                ),
            )
            if (root["stat"].str()?.lowercase() == "fail") {
                throw SiteServiceException.decoding(root["error"]?.get("message").str() ?: "UptimeRobot rejected the request.")
            }
            val page = root["monitors"]?.arrayValue
                ?: throw SiteServiceException.decoding("UptimeRobot did not return a monitor list.")
            var newCount = 0
            page.forEach { monitor ->
                val id = monitor["id"].str() ?: return@forEach
                if (seenIds.add(id)) {
                    monitors += monitor
                    newCount += 1
                }
            }
            offset += page.size
            pageCount += 1
            when (paginationAction(page.size, newCount, seenIds.size, root["pagination"]?.get("total").int(), PAGE_SIZE)) {
                UptimeRobotPaginationAction.COMPLETE -> break@pages
                UptimeRobotPaginationAction.NO_PROGRESS -> {
                    warnings += "UptimeRobot repeated a results page, so loading stopped to avoid an endless request loop."
                    partial = true
                    break@pages
                }
                UptimeRobotPaginationAction.LOAD_NEXT_PAGE -> if (pageCount >= MAXIMUM_PAGES) {
                    warnings += "UptimeRobot loading stopped after 10,000 monitors to protect the device."
                    partial = true
                    break@pages
                }
            }
        }

        val resources = monitors.mapNotNull { monitor ->
            val id = monitor["id"].str() ?: return@mapNotNull null
            val urlValue = monitor["url"].str()
            val statusCode = monitor["status"].int()
            val metrics = ArrayList<SiteMetric>()
            (monitor["custom_uptime_ratio"] ?: monitor["custom_uptime_ratios"]).str()?.let { ratios ->
                val parts = ratios.split('-').mapNotNull { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
                parts.take(3).forEachIndexed { index, value ->
                    metrics += metric(
                        "uptimerobot.uptime.${UPTIME_KEYS[index]}", UPTIME_LABELS[index], value,
                        SiteMetricUnit.PERCENT, resourceId = id,
                    )
                }
            }
            monitor["average_response_time"].num()?.let {
                metrics += metric("uptimerobot.response_time", "Response Time", it, SiteMetricUnit.MILLISECONDS, resourceId = id)
            }
            SiteResource(
                id = id,
                provider = SiteProvider.UPTIME_ROBOT,
                name = monitor["friendly_name"].str() ?: "Monitor $id",
                subtitle = urlValue,
                url = urlValue?.takeIf { runCatching { URI(it) }.isSuccess },
                status = statusLabel(statusCode),
                updatedAtMillis = monitor["logs"].arr().mapNotNull { it["datetime"].num() }.maxOrNull()?.let { (it * 1_000).toLong() },
                metrics = metrics,
                metadata = mapOf("statusCode" to (statusCode?.toString() ?: "")),
            )
        }
        val up = resources.count { it.status == "Up" }
        val down = resources.count { it.status == "Down" || it.status == "Seems down" }
        val paused = resources.count { it.status == "Paused" }
        val status = when {
            resources.isEmpty() -> "No monitors"
            down > 0 -> "$down down"
            up == resources.size -> "All operational"
            else -> "$up up · ${resources.size - up} paused or checking"
        }
        return SiteSnapshot(
            provider = SiteProvider.UPTIME_ROBOT,
            resources = resources,
            metrics = markPartialMetrics(
                listOf(
                    metric("uptimerobot.monitors", "Monitors", resources.size.toDouble(), SiteMetricUnit.COUNT),
                    metric("uptimerobot.up", "Up", up.toDouble(), SiteMetricUnit.COUNT),
                    metric("uptimerobot.down", "Down", down.toDouble(), SiteMetricUnit.COUNT),
                    metric("uptimerobot.paused", "Paused", paused.toDouble(), SiteMetricUnit.COUNT),
                ),
                partial,
            ),
            status = if (partial) "$status · Partial metrics" else status,
            fetchedAtMillis = context.nowMillis(),
            warnings = warnings,
        )
    }

    // MARK: Detail

    suspend fun detail(request: SiteDetailRequest.UptimeRobot): SiteDetailPayload {
        val range = request.range
        val historyStart = maxOf(range.startMillis, range.endMillis - 7L * 86_400_000L)
        val history = range.withStart(historyStart)
        val monitor = call(
            "getMonitors",
            request.readOnlyApiKey,
            listOf(
                "monitors" to request.monitorId,
                "logs" to "1",
                "logs_start_date" to range.startSeconds.toString(),
                "logs_end_date" to range.endSeconds.toString(),
                "response_times" to "1",
                "response_times_start_date" to history.startSeconds.toString(),
                "response_times_end_date" to history.endSeconds.toString(),
                "response_times_average" to "30",
                "alert_contacts" to "1",
                "mwindows" to "1",
                "ssl" to "1",
                "custom_http_headers" to "1",
                "custom_http_statuses" to "1",
                "http_request_details" to "true",
                "auth_type" to "true",
                "timezone" to "1",
                "custom_uptime_ratios" to "1-7-30",
            ),
        )
        val selected = monitor["monitors"].arr().firstOrNull()
            ?: throw SiteServiceException.detailDecoding("UptimeRobot did not return the selected monitor.")
        val raw = linkedMapOf("monitor" to monitor)
        val tables = ArrayList<SiteDetailTable>()
        val warnings = ArrayList<String>()

        val logs = rows(selected["logs"] ?: ProviderJsonValue.Null)
        tables += SiteDetailTable("uptimerobot.logs", "Monitor logs", orderedColumns(logs, listOf("datetime", "type", "duration", "reason")), logs)
        val responseTimes = rows(selected["response_times"] ?: ProviderJsonValue.Null)
        val responseSeries = SiteDetailSupport.simpleXYSeries(
            "uptimerobot.response-times", "Response time", responseTimes,
            listOf("datetime", "x", "date"), listOf("value", "y", "response_time"),
            metricKey = "responseTime", metricLabel = "Response time (ms)",
        )
        tables += SiteDetailTable(
            "uptimerobot.response-times.table", "Response-time samples",
            orderedColumns(responseTimes, listOf("datetime", "value")), responseTimes,
        )

        try {
            val response = call("getAccountDetails", request.readOnlyApiKey, emptyList())
            raw["getAccountDetails"] = response
            val accountRows = rows(response["account"] ?: ProviderJsonValue.Null)
            if (accountRows.isNotEmpty()) {
                tables += SiteDetailTable("uptimerobot.get-account-details", "Account details", orderedColumns(accountRows), accountRows)
            }
        } catch (error: Exception) {
            SiteApiSupport.rethrowIfCancellation(error)
            warnings += "UptimeRobot account details could not load: ${errorText(error)}"
        }

        COMPANION_METHODS.forEach { (method, id, title, itemKey) ->
            try {
                val result = pages(method, request.readOnlyApiKey, itemKey)
                raw[method] = ProviderJsonValue.Arr(result.pages)
                if (result.truncated) {
                    warnings += "UptimeRobot ${title.lowercase()} reached the on-device pagination limit; " +
                        "additional rows may be available."
                }
                val itemRows = result.items.map(::flattenedObject)
                if (itemRows.isNotEmpty()) {
                    tables += SiteDetailTable("uptimerobot.$id", title, orderedColumns(itemRows), itemRows)
                }
            } catch (error: Exception) {
                SiteApiSupport.rethrowIfCancellation(error)
                warnings += "UptimeRobot ${title.lowercase()} could not load: ${errorText(error)}"
            }
        }
        if (history.startMillis > range.startMillis) {
            warnings += "UptimeRobot response-time samples are limited to the latest seven days; logs use the full requested range."
        }
        return SiteDetailPayload(
            provider = SiteProvider.UPTIME_ROBOT,
            resourceId = request.monitorId,
            title = selected["friendly_name"].str() ?: "UptimeRobot monitor ${request.monitorId}",
            sections = listOf(
                SiteDetailSection("uptimerobot.monitor", "Monitor configuration and status", flattenedFields(selected, 2)),
            ),
            series = listOfNotNull(responseSeries),
            tables = tables,
            rawResponses = raw,
            warnings = warnings,
            fetchedAtMillis = context.nowMillis(),
        )
    }

    private suspend fun call(method: String, apiKey: SecretValue, fields: List<Pair<String, String>>): ProviderJsonValue {
        val response = context.detailJson(
            SiteHttpRequest(
                url = URI("$BASE/$method"),
                method = "POST",
                secretFormFields = listOf("api_key" to apiKey),
                formFields = (fields + ("format" to "json")).sortedBy { it.first },
            ),
        )
        if (response["stat"].str()?.lowercase() == "fail") throw SiteServiceException.invalidResponse()
        return response
    }

    /** Legacy v2 methods expose limit/offset/total either nested in `pagination` or at the root. */
    private suspend fun pages(method: String, apiKey: SecretValue, itemKey: String): SitePagedCollection {
        var offset = 0
        val pages = ArrayList<ProviderJsonValue>()
        val items = ArrayList<ProviderJsonValue>()
        val seenOffsets = HashSet<Int>()
        var pageCount = 0
        var truncated = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!seenOffsets.add(offset)) {
                throw SiteServiceException.detailDecoding("UptimeRobot returned a repeated pagination offset for $method.")
            }
            val response = call(method, apiKey, listOf("limit" to "50", "offset" to offset.toString()))
            pageCount += 1
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            val pageItems = response[itemKey].arr()
            val remaining = (SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS - items.size).coerceAtLeast(0)
            items += pageItems.take(remaining)
            if (pageItems.size > remaining) truncated = true
            val pagination = response["pagination"] ?: response
            val total = (pagination["total"].num() ?: items.size.toDouble()).toInt().coerceAtLeast(0)
            if (items.size >= total) break
            if (items.size >= SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS ||
                pageCount >= SiteDetailSupport.MAXIMUM_PAGINATION_PAGES
            ) {
                truncated = true
                break
            }
            if (pageItems.isEmpty()) {
                throw SiteServiceException.detailDecoding("UptimeRobot stopped returning $itemKey before the reported total was reached.")
            }
            val pageOffset = (pagination["offset"].num() ?: offset.toDouble()).toInt().coerceAtLeast(0)
            offset = pageOffset + pageItems.size
        }
        return SitePagedCollection(pages, items, truncated)
    }

    companion object {
        const val BASE: String = "https://api.uptimerobot.com/v2"
        private const val PAGE_SIZE = 50
        private const val MAXIMUM_PAGES = 200
        private val UPTIME_KEYS = listOf("1d", "7d", "30d")
        private val UPTIME_LABELS = listOf("24h Uptime", "7d Uptime", "30d Uptime")

        private data class CompanionMethod(val method: String, val id: String, val title: String, val itemKey: String)

        private val COMPANION_METHODS = listOf(
            CompanionMethod("getAlertContacts", "get-alert-contacts", "Alert contacts", "alert_contacts"),
            CompanionMethod("getMWindows", "get-maintenance-windows", "Maintenance windows", "mwindows"),
            CompanionMethod("getPSPs", "get-public-status-pages", "Public status pages", "psps"),
        )

        fun statusLabel(code: Long?): String = when (code) {
            0L -> "Paused"
            1L -> "Not checked"
            2L -> "Up"
            8L -> "Seems down"
            9L -> "Down"
            else -> "Unknown"
        }

        /** Distinguishes legitimate completion from a provider repeating the same page. */
        fun paginationAction(
            pageItemCount: Int,
            newUniqueMonitorCount: Int,
            loadedUniqueMonitorCount: Int,
            reportedTotal: Long?,
            pageSize: Int,
        ): UptimeRobotPaginationAction = when {
            pageItemCount == 0 -> UptimeRobotPaginationAction.COMPLETE
            newUniqueMonitorCount == 0 -> UptimeRobotPaginationAction.NO_PROGRESS
            reportedTotal?.let { loadedUniqueMonitorCount >= it } == true ||
                (reportedTotal == null && pageItemCount < pageSize) -> UptimeRobotPaginationAction.COMPLETE
            else -> UptimeRobotPaginationAction.LOAD_NEXT_PAGE
        }
    }
}
