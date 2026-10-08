package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteApiSupport.metric
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSupport.orderedColumns
import java.util.Locale

/**
 * Microsoft Clarity Data Export adapter. The export API allows only a few requests per project
 * per day, so callers keep the iOS six-hour cache lifetime.
 */
internal class ClaritySiteAdapter(private val context: SiteApiContext) {
    private class Accumulator(val label: String, val unit: SiteMetricUnit, var value: Double, var samples: Int)

    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot {
        val token = account.requiredCredential("Clarity API token")
        val days = (account.metadata["days"]?.toIntOrNull() ?: 3).coerceIn(1, 3)
        val value = context.http.requestJson(
            SiteHttpRequest(
                url = SiteApiSupport.url(LIVE_INSIGHTS, listOf("numOfDays" to days.toString())),
                bearerToken = token,
            ),
        )
        val groups = value.arrayValue
            ?: throw SiteServiceException.decoding("Microsoft Clarity did not return a live-insights list.")
        val accumulated = HashMap<String, Accumulator>()
        val discoveredUrls = HashSet<String>()
        val discoveredOrigins = HashMap<String, Int>()
        groups.forEach { group ->
            val metricName = group["metricName"].str() ?: "Insight"
            group["information"].arr().forEach { rawInformation ->
                val information = rawInformation.obj()
                (information["URL"] ?: information["url"]).str()?.takeIf(String::isNotEmpty)?.let { site ->
                    discoveredUrls += site
                    SiteApiSupport.originUrl(site)?.let { origin ->
                        discoveredOrigins[origin] = (discoveredOrigins[origin] ?: 0) + 1
                    }
                }
                information.forEach { (field, rawValue) ->
                    if (field.lowercase(Locale.ROOT) == "url") return@forEach
                    val number = rawValue.num() ?: return@forEach
                    val key = "clarity.${SiteApiSupport.slug(metricName)}.${SiteApiSupport.slug(field)}"
                    val existing = accumulated[key]
                    if (existing == null) {
                        accumulated[key] = Accumulator(
                            SiteApiSupport.humanizedSnapshotLabel(field),
                            SiteApiSupport.inferredUnit(field),
                            number,
                            1,
                        )
                    } else {
                        existing.value += number
                        existing.samples += 1
                    }
                }
            }
        }
        val metrics = accumulated.keys.sorted().map { key ->
            val entry = accumulated.getValue(key)
            val aggregate = if (entry.unit == SiteMetricUnit.COUNT) entry.value else entry.value / entry.samples
            metric(key, entry.label, aggregate, entry.unit)
        }
        val projectName = account.metadata["projectName"].nonEmpty() ?: "Microsoft Clarity"
        val configuredUrl = account.metadata["siteURL"].nonEmpty()?.let(SiteApiSupport::originUrl)
        val discoveredUrl = discoveredOrigins.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            ?.key
        val resourceUrl = configuredUrl ?: discoveredUrl
        val resourceId = resourceUrl?.let(SiteApiSupport::stableSiteResourceId) ?: projectName.lowercase(Locale.ROOT)
        val metadata = LinkedHashMap<String, String>().apply {
            put("days", days.toString())
            if (discoveredUrls.isNotEmpty()) put("reportedURLs", discoveredUrls.size.toString())
            if (discoveredOrigins.isNotEmpty()) put("reportedOrigins", discoveredOrigins.size.toString())
            resourceUrl?.let { put("siteURL", it) }
        }
        val resource = SiteResource(
            id = resourceId,
            provider = SiteProvider.CLARITY,
            name = projectName,
            subtitle = resourceUrl ?: "Last ${days * 24} hours",
            url = resourceUrl,
            status = "Connected",
            updatedAtMillis = context.nowMillis(),
            metrics = metrics.map { it.copy(resourceId = resourceId) },
            metadata = metadata,
        )
        return SiteSnapshot(
            provider = SiteProvider.CLARITY,
            resources = listOf(resource),
            metrics = metrics,
            status = "Live insights · ${days}d",
            fetchedAtMillis = context.nowMillis(),
        )
    }

    suspend fun detail(request: SiteDetailRequest.Clarity): SiteDetailPayload {
        val selected = request.dimensions.take(3)
        if (!selected.all { it in ClarityDimensionOptions }) {
            throw SiteServiceException.invalidConfiguration(
                "Clarity dimensions must be Browser, Device, Country/Region, OS, Source, Medium, Campaign, Channel, or URL.",
            )
        }
        val days = request.days.coerceIn(1, 3)
        val query = buildList {
            add("numOfDays" to days.toString())
            selected.forEachIndexed { index, dimension -> add("dimension${index + 1}" to dimension) }
        }
        val response = context.detailJson(
            SiteHttpRequest(url = SiteApiSupport.url(LIVE_INSIGHTS, query), bearerToken = request.apiToken),
        )
        val groups = response.arrayValue
            ?: throw SiteServiceException.detailDecoding("Clarity did not return a live-insights list.")
        val tables = groups.mapIndexed { index, group ->
            val name = group["metricName"].str() ?: "Insight ${index + 1}"
            val rows = group["information"].arr().mapNotNull(ProviderJsonValue::objectValue)
            SiteDetailTable("clarity.${SiteDetailSupport.slug(name)}.$index", name, orderedColumns(rows), rows)
        }
        val section = SiteDetailSection(
            id = "clarity.request",
            title = "Live insights",
            fields = listOf(
                SiteDetailField("days", "Days", ProviderJsonValue.Num.of(days)),
                SiteDetailField("dimensions", "Dimensions", ProviderJsonValue.Arr(selected.map(ProviderJsonValue::Str))),
            ),
        )
        return SiteDetailPayload(
            provider = SiteProvider.CLARITY,
            resourceId = "clarity.live",
            title = "Microsoft Clarity",
            sections = listOf(section),
            tables = tables,
            rawResponses = mapOf("liveInsights" to response),
            fetchedAtMillis = context.nowMillis(),
        )
    }

    companion object {
        const val LIVE_INSIGHTS: String = "https://www.clarity.ms/export-data/api/v1/project-live-insights"
    }
}
