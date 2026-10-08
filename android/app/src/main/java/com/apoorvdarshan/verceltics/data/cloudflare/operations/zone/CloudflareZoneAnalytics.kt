package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.long
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

// Port of the iOS zone traffic analytics: GraphQL dataset planning (`analyticsQueryPlan`),
// queries, variables, and response normalization (`CloudflareAPI.fetchZoneAnalytics*`).

enum class CloudflareAnalyticsGranularity(val bucketSeconds: Long) {
    HOURLY(3_600),
    DAILY(86_400),
    ;

    val displayName: String get() = name
}

/** iOS `CloudflareAnalyticsRange`. */
enum class CloudflareAnalyticsRange(val displayName: String) {
    HOURS_24("24H"),
    DAYS_7("7D"),
    DAYS_30("30D"),
    DAYS_90("90D"),
    YEAR_1("1Y"),
    CUSTOM("CUSTOM"),
    ;

    /** The requested window ending at [end], or null for [CUSTOM]. Uses the device calendar like iOS. */
    fun dates(end: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Instant, Instant>? {
        val local = end.atZone(zone)
        val start = when (this) {
            HOURS_24 -> local.minusHours(24)
            DAYS_7 -> local.minusDays(7)
            DAYS_30 -> local.minusDays(30)
            DAYS_90 -> local.minusDays(90)
            YEAR_1 -> local.minusYears(1)
            CUSTOM -> return null
        }
        return start.toInstant() to end
    }
}

data class CloudflareAnalyticsMetrics(
    val requests: Long,
    val pageViews: Long,
    val bytes: Long,
    val cachedRequests: Long,
    val cachedBytes: Long,
    val threats: Long,
    val encryptedRequests: Long,
    val uniqueVisitors: Long,
) {
    val cacheHitRate: Double? get() = if (requests > 0) cachedRequests.toDouble() / requests * 100 else null
    val encryptedRequestRate: Double? get() = if (requests > 0) encryptedRequests.toDouble() / requests * 100 else null

    companion object {
        val ZERO = CloudflareAnalyticsMetrics(0, 0, 0, 0, 0, 0, 0, 0)

        fun aggregate(values: List<CloudflareAnalyticsMetrics>) = CloudflareAnalyticsMetrics(
            requests = values.sumOf { it.requests },
            pageViews = values.sumOf { it.pageViews },
            bytes = values.sumOf { it.bytes },
            cachedRequests = values.sumOf { it.cachedRequests },
            cachedBytes = values.sumOf { it.cachedBytes },
            threats = values.sumOf { it.threats },
            encryptedRequests = values.sumOf { it.encryptedRequests },
            uniqueVisitors = values.sumOf { it.uniqueVisitors },
        )
    }
}

data class CloudflareZoneAnalyticsPoint(val timestamp: Instant, val metrics: CloudflareAnalyticsMetrics)

data class CloudflareZoneAnalyticsSummary(
    val zoneId: String,
    val requestedFrom: Instant,
    val requestedTo: Instant,
    val from: Instant,
    val to: Instant,
    val granularity: CloudflareAnalyticsGranularity,
    val isWindowLimited: Boolean,
    val totals: CloudflareAnalyticsMetrics,
    val series: List<CloudflareZoneAnalyticsPoint>,
) {
    /** iOS `windowLabel`. */
    val windowLabel: String
        get() {
            if (granularity == CloudflareAnalyticsGranularity.DAILY) {
                val fromDay = from.atZone(ZoneOffset.UTC).toLocalDate()
                val toDay = to.atZone(ZoneOffset.UTC).toLocalDate()
                val days = maxOf(1L, ChronoUnit.DAYS.between(fromDay, toDay) + 1)
                return "LAST $days ${if (days == 1L) "DAY" else "DAYS"}"
            }
            val duration = maxOf(1.0, (to.toEpochMilli() - from.toEpochMilli()) / 1_000.0)
            if (duration >= 86_400) {
                val days = maxOf(1, ceil(duration / 86_400).toInt())
                return "LAST $days ${if (days == 1) "DAY" else "DAYS"}"
            }
            if (duration >= 3_600) {
                val hours = maxOf(1, ceil(duration / 3_600).toInt())
                return "LAST $hours ${if (hours == 1) "HOUR" else "HOURS"}"
            }
            val minutes = maxOf(1, ceil(duration / 60).toInt())
            return "LAST $minutes ${if (minutes == 1) "MINUTE" else "MINUTES"}"
        }

    val chartTitle: String get() = "REQUESTS · $windowLabel"
}

data class CloudflareAnalyticsBreakdownItem(
    val label: String,
    val requests: Long,
    val bytes: Long,
    val threats: Long,
    val pageViews: Long,
)

data class CloudflareZoneAnalyticsBreakdowns(
    val countries: List<CloudflareAnalyticsBreakdownItem>,
    val statusCodes: List<CloudflareAnalyticsBreakdownItem>,
    val contentTypes: List<CloudflareAnalyticsBreakdownItem>,
    val tlsProtocols: List<CloudflareAnalyticsBreakdownItem>,
    val browsers: List<CloudflareAnalyticsBreakdownItem>,
    val ipClasses: List<CloudflareAnalyticsBreakdownItem>,
    val threatTypes: List<CloudflareAnalyticsBreakdownItem>,
    val encryptedBytes: Long,
) {
    companion object {
        val EMPTY = CloudflareZoneAnalyticsBreakdowns(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0,
        )
    }
}

/** Retention and width limits Cloudflare reports for one HTTP analytics dataset. */
data class CloudflareAnalyticsDatasetSettings(
    val enabled: Boolean?,
    val maxDurationSeconds: Long?,
    val maxPageSize: Int?,
    val notOlderThanSeconds: Long?,
) {
    companion object {
        fun parse(value: ProviderJsonValue?): CloudflareAnalyticsDatasetSettings? {
            if (value !is ProviderJsonValue.Obj) return null
            return CloudflareAnalyticsDatasetSettings(
                enabled = value.bool("enabled"),
                maxDurationSeconds = value.long("maxDuration"),
                maxPageSize = value.long("maxPageSize")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
                notOlderThanSeconds = value.long("notOlderThan"),
            )
        }
    }
}

data class CloudflareAnalyticsQueryPlan(
    val granularity: CloudflareAnalyticsGranularity,
    val from: Instant,
    val to: Instant,
    val seriesLimit: Int,
    val isWindowLimited: Boolean,
)

/** Pure analytics planning and GraphQL shaping, unit-tested without the network. */
object CloudflareZoneAnalyticsPlanner {
    const val NO_DATASET_MESSAGE = "Cloudflare did not provide an enabled HTTP analytics dataset for this zone."

    /** iOS `analyticsQueryPlan` after the settings fetch. */
    fun plan(
        hourly: CloudflareAnalyticsDatasetSettings?,
        daily: CloudflareAnalyticsDatasetSettings?,
        requestedFrom: Instant,
        requestedTo: Instant,
        now: Instant,
    ): CloudflareAnalyticsQueryPlan {
        val candidates = listOfNotNull(
            candidate(CloudflareAnalyticsGranularity.HOURLY, hourly, requestedFrom, requestedTo, now),
            candidate(CloudflareAnalyticsGranularity.DAILY, daily, requestedFrom, requestedTo, now),
        ).mapNotNull(::normalizeDaily)
        return candidates.sortedWith { lhs, rhs ->
            when {
                precedes(lhs, rhs) -> -1
                precedes(rhs, lhs) -> 1
                else -> 0
            }
        }.firstOrNull() ?: throw CloudflareOperationException.graphQL(listOf(NO_DATASET_MESSAGE))
    }

    fun candidate(
        granularity: CloudflareAnalyticsGranularity,
        settings: CloudflareAnalyticsDatasetSettings?,
        requestedFrom: Instant,
        requestedTo: Instant,
        now: Instant,
    ): CloudflareAnalyticsQueryPlan? {
        if (settings == null || settings.enabled == false) return null
        val maximumDuration = settings.maxDurationSeconds?.takeIf { it > 0 } ?: return null
        val effectiveEnd = minOf(requestedTo, now)
        var effectiveStart = maxOf(requestedFrom, effectiveEnd.minusSeconds(maximumDuration))
        settings.notOlderThanSeconds?.takeIf { it > 0 }?.let { effectiveStart = maxOf(effectiveStart, now.minusSeconds(it)) }
        val bucket = granularity.bucketSeconds
        settings.maxPageSize?.takeIf { it > 1 }?.let { pageSize ->
            effectiveStart = maxOf(effectiveStart, effectiveEnd.minusSeconds((pageSize - 1).toLong() * bucket))
        }
        if (!effectiveStart.isBefore(effectiveEnd)) return null
        val durationSeconds = (effectiveEnd.toEpochMilli() - effectiveStart.toEpochMilli()) / 1_000.0
        val expectedBuckets = maxOf(1, ceil(durationSeconds / bucket).toInt() + 1)
        val seriesLimit = maxOf(1, minOf(settings.maxPageSize ?: expectedBuckets, expectedBuckets))
        val covers = !effectiveStart.isAfter(requestedFrom.plusSeconds(1)) && !effectiveEnd.isBefore(requestedTo.minusSeconds(1))
        return CloudflareAnalyticsQueryPlan(granularity, effectiveStart, effectiveEnd, seriesLimit, isWindowLimited = !covers)
    }

    /** iOS `analyticsPlanPrecedes`: full coverage first, hourly preferred, then the widest window. */
    fun precedes(lhs: CloudflareAnalyticsQueryPlan, rhs: CloudflareAnalyticsQueryPlan): Boolean {
        if (lhs.isWindowLimited != rhs.isWindowLimited) return !lhs.isWindowLimited
        if (!lhs.isWindowLimited && lhs.granularity != rhs.granularity) return lhs.granularity == CloudflareAnalyticsGranularity.HOURLY
        val lhsDuration = lhs.to.toEpochMilli() - lhs.from.toEpochMilli()
        val rhsDuration = rhs.to.toEpochMilli() - rhs.from.toEpochMilli()
        if (abs(lhsDuration - rhsDuration) > 1_000) return lhsDuration > rhsDuration
        return lhs.granularity == CloudflareAnalyticsGranularity.HOURLY && rhs.granularity == CloudflareAnalyticsGranularity.DAILY
    }

    /** iOS `normalizeDailyAnalyticsPlan`: daily datasets cover whole UTC days only. */
    fun normalizeDaily(plan: CloudflareAnalyticsQueryPlan): CloudflareAnalyticsQueryPlan? {
        if (plan.granularity != CloudflareAnalyticsGranularity.DAILY) return plan
        val startDay = plan.from.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        val firstIncludedDay = if (plan.from.toEpochMilli() - startDay.toEpochMilli() > 500) startDay.plus(1, ChronoUnit.DAYS) else startDay
        val lastIncludedDay = plan.to.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        if (firstIncludedDay.isAfter(lastIncludedDay)) return null
        val dayCount = maxOf(1L, ChronoUnit.DAYS.between(firstIncludedDay, lastIncludedDay) + 1)
        return plan.copy(from = firstIncludedDay, seriesLimit = minOf(plan.seriesLimit.toLong(), dayCount).toInt())
    }

    val settingsQuery: String = """
        query ZoneAnalyticsSettings(${'$'}zoneTag: string) {
          viewer {
            zones(filter: { zoneTag: ${'$'}zoneTag }) {
              settings {
                hourly: httpRequests1hGroups { enabled maxDuration maxPageSize notOlderThan }
                daily: httpRequests1dGroups { enabled maxDuration maxPageSize notOlderThan }
              }
            }
          }
        }
    """.trimIndent()

    fun trafficQuery(granularity: CloudflareAnalyticsGranularity, seriesLimit: Int): String {
        val daily = granularity == CloudflareAnalyticsGranularity.DAILY
        val dataset = if (daily) "httpRequests1dGroups" else "httpRequests1hGroups"
        val dimension = if (daily) "date" else "datetime"
        val scalar = if (daily) "Date" else "Time"
        val lower = if (daily) "date_geq" else "datetime_geq"
        val upper = if (daily) "date_leq" else "datetime_leq"
        val d = '$'
        return """
            query ZoneTraffic(${d}zoneTag: string, ${d}start: $scalar, ${d}end: $scalar) {
              viewer {
                zones(filter: { zoneTag: ${d}zoneTag }) {
                  totals: $dataset(limit: 1, filter: { $lower: ${d}start, $upper: ${d}end }) {
                    sum { requests pageViews bytes cachedRequests cachedBytes threats encryptedRequests }
                    uniq { uniques }
                  }
                  series: $dataset(limit: $seriesLimit, orderBy: [${dimension}_ASC], filter: { $lower: ${d}start, $upper: ${d}end }) {
                    dimensions { $dimension }
                    sum { requests pageViews bytes cachedRequests cachedBytes threats encryptedRequests }
                    uniq { uniques }
                  }
                }
              }
            }
        """.trimIndent()
    }

    fun breakdownQuery(granularity: CloudflareAnalyticsGranularity): String {
        val daily = granularity == CloudflareAnalyticsGranularity.DAILY
        val dataset = if (daily) "httpRequests1dGroups" else "httpRequests1hGroups"
        val scalar = if (daily) "Date" else "Time"
        val lower = if (daily) "date_geq" else "datetime_geq"
        val upper = if (daily) "date_leq" else "datetime_leq"
        val d = '$'
        return """
            query ZoneTrafficBreakdowns(${d}zoneTag: string, ${d}start: $scalar, ${d}end: $scalar) {
              viewer {
                zones(filter: { zoneTag: ${d}zoneTag }) {
                  totals: $dataset(limit: 1, filter: { $lower: ${d}start, $upper: ${d}end }) {
                    sum {
                      encryptedBytes
                      browserMap { pageViews uaBrowserFamily }
                      contentTypeMap { bytes requests edgeResponseContentTypeName }
                      clientSSLMap { requests clientSSLProtocol }
                      countryMap { bytes requests threats clientCountryName }
                      ipClassMap { requests ipType }
                      responseStatusMap { requests edgeResponseStatus }
                      threatPathingMap { requests threatPathingName }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    /** iOS `analyticsVariables`: `yyyy-MM-dd` for daily datasets, RFC 3339 seconds for hourly. */
    fun variables(zoneId: String, from: Instant, to: Instant, granularity: CloudflareAnalyticsGranularity): Map<String, String> {
        val formatter = if (granularity == CloudflareAnalyticsGranularity.DAILY) {
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        }
        return linkedMapOf("zoneTag" to zoneId, "start" to formatter.format(from), "end" to formatter.format(to))
    }

    /** Throws the GraphQL `errors` array when present. */
    fun throwGraphQLErrors(root: ProviderJsonValue) {
        val messages = root.arr("errors").mapNotNull { it.strictStr("message") }
        if (messages.isNotEmpty()) throw CloudflareOperationException.graphQL(messages)
    }

    fun firstZone(root: ProviderJsonValue): ProviderJsonValue? = root.obj("data").obj("viewer").arr("zones").firstOrNull()

    fun parseSettings(root: ProviderJsonValue): Pair<CloudflareAnalyticsDatasetSettings?, CloudflareAnalyticsDatasetSettings?> {
        val settings = firstZone(root).obj("settings")
        val hourly = CloudflareAnalyticsDatasetSettings.parse(settings?.get("hourly"))
        val daily = CloudflareAnalyticsDatasetSettings.parse(settings?.get("daily"))
        if (hourly != null || daily != null) return hourly to daily
        throwGraphQLErrors(root)
        throw CloudflareOperationException.decoding()
    }

    fun metrics(group: ProviderJsonValue?): CloudflareAnalyticsMetrics {
        val sum = group.obj("sum")
        return CloudflareAnalyticsMetrics(
            requests = sum.long("requests") ?: 0,
            pageViews = sum.long("pageViews") ?: 0,
            bytes = sum.long("bytes") ?: 0,
            cachedRequests = sum.long("cachedRequests") ?: 0,
            cachedBytes = sum.long("cachedBytes") ?: 0,
            threats = sum.long("threats") ?: 0,
            encryptedRequests = sum.long("encryptedRequests") ?: 0,
            uniqueVisitors = group.obj("uniq").long("uniques") ?: 0,
        )
    }

    fun parseSummary(
        root: ProviderJsonValue,
        zoneId: String,
        requestedFrom: Instant,
        requestedTo: Instant,
        plan: CloudflareAnalyticsQueryPlan,
    ): CloudflareZoneAnalyticsSummary {
        throwGraphQLErrors(root)
        val zone = firstZone(root)
        val points = zone.arr("series").mapNotNull { group ->
            val dimensions = group.obj("dimensions")
            val timestamp = CloudflareDates.parse(dimensions.strictStr("datetime") ?: dimensions.strictStr("date"))
                ?: return@mapNotNull null
            CloudflareZoneAnalyticsPoint(timestamp, metrics(group))
        }.sortedBy { it.timestamp }
        val totals = when {
            zone == null -> CloudflareAnalyticsMetrics.ZERO
            else -> zone.arr("totals").firstOrNull()?.let(::metrics) ?: CloudflareAnalyticsMetrics.aggregate(points.map { it.metrics })
        }
        return CloudflareZoneAnalyticsSummary(
            zoneId = zoneId,
            requestedFrom = requestedFrom,
            requestedTo = requestedTo,
            from = plan.from,
            to = plan.to,
            granularity = plan.granularity,
            isWindowLimited = plan.isWindowLimited,
            totals = totals,
            series = points,
        )
    }

    fun parseBreakdowns(root: ProviderJsonValue): CloudflareZoneAnalyticsBreakdowns {
        throwGraphQLErrors(root)
        val sum = firstZone(root).arr("totals").firstOrNull().obj("sum") ?: return CloudflareZoneAnalyticsBreakdowns.EMPTY
        fun map(key: String, transform: (ProviderJsonValue) -> CloudflareAnalyticsBreakdownItem) =
            sorted(sum.arr(key).map(transform))
        return CloudflareZoneAnalyticsBreakdowns(
            countries = map("countryMap") {
                CloudflareAnalyticsBreakdownItem(it.strictStr("clientCountryName") ?: "Unknown", it.long("requests") ?: 0, it.long("bytes") ?: 0, it.long("threats") ?: 0, 0)
            },
            statusCodes = map("responseStatusMap") {
                CloudflareAnalyticsBreakdownItem(it.long("edgeResponseStatus")?.toString() ?: "Unknown", it.long("requests") ?: 0, 0, 0, 0)
            },
            contentTypes = map("contentTypeMap") {
                CloudflareAnalyticsBreakdownItem(it.strictStr("edgeResponseContentTypeName") ?: "Unknown", it.long("requests") ?: 0, it.long("bytes") ?: 0, 0, 0)
            },
            tlsProtocols = map("clientSSLMap") {
                CloudflareAnalyticsBreakdownItem(it.strictStr("clientSSLProtocol") ?: "None", it.long("requests") ?: 0, 0, 0, 0)
            },
            browsers = map("browserMap") {
                CloudflareAnalyticsBreakdownItem(it.strictStr("uaBrowserFamily") ?: "Unknown", 0, 0, 0, it.long("pageViews") ?: 0)
            },
            ipClasses = map("ipClassMap") {
                CloudflareAnalyticsBreakdownItem(it.strictStr("ipType") ?: "Unknown", it.long("requests") ?: 0, 0, 0, 0)
            },
            threatTypes = map("threatPathingMap") {
                val requests = it.long("requests") ?: 0
                CloudflareAnalyticsBreakdownItem(it.strictStr("threatPathingName") ?: "Unknown", requests, 0, requests, 0)
            },
            encryptedBytes = sum.long("encryptedBytes") ?: 0,
        )
    }

    /** Most traffic first, ties by label (iOS `localizedCaseInsensitiveCompare`). */
    fun sorted(values: List<CloudflareAnalyticsBreakdownItem>): List<CloudflareAnalyticsBreakdownItem> =
        values.sortedWith { lhs, rhs ->
            val left = lhs.requests + lhs.pageViews
            val right = rhs.requests + rhs.pageViews
            if (left == right) lhs.label.compareTo(rhs.label, ignoreCase = true) else right.compareTo(left)
        }
}
