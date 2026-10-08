package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.LocalDate
import java.util.Locale

/*
 * Full PageSpeed + Chrome UX report, the Android counterpart of iOS `fetchPageSpeed` in
 * SiteIntegrationDetailAPI: every Lighthouse category and audit per device, report metadata, all
 * CrUX field metrics with their distributions, the 40-period CrUX history and the raw responses.
 * It is rebuilt from live responses and never persisted; the encrypted cache keeps only the summary.
 */

data class PageSpeedReportField(
    val key: String,
    val label: String,
    val value: String,
)

/** A Lighthouse category. [score] is Google's 0–1 value, or null when Lighthouse could not score it. */
data class PageSpeedCategoryScore(
    val id: String,
    val title: String,
    val score: Double?,
    val auditIds: List<String>,
)

enum class PageSpeedAuditOutcome {
    FAILED,
    AVERAGE,
    PASSED,
    INFORMATIVE,
    NOT_APPLICABLE,
    MANUAL,
    ERROR,
}

data class PageSpeedAudit(
    val id: String,
    val title: String,
    val description: String?,
    val score: Double?,
    val scoreDisplayMode: String?,
    val displayValue: String?,
    val numericValue: Double?,
    val numericUnit: String?,
    val errorMessage: String?,
    val warnings: List<String>,
    val detailsType: String?,
    val detailsItemCount: Int?,
    val categoryIds: List<String>,
) {
    /** Lighthouse's own buckets: ≥0.9 passes, ≥0.5 needs work, below fails. */
    val outcome: PageSpeedAuditOutcome
        get() = when {
            scoreDisplayMode == "error" || errorMessage != null -> PageSpeedAuditOutcome.ERROR
            scoreDisplayMode == "notApplicable" -> PageSpeedAuditOutcome.NOT_APPLICABLE
            scoreDisplayMode == "manual" -> PageSpeedAuditOutcome.MANUAL
            scoreDisplayMode == "informative" || score == null -> PageSpeedAuditOutcome.INFORMATIVE
            score >= 0.9 -> PageSpeedAuditOutcome.PASSED
            score >= 0.5 -> PageSpeedAuditOutcome.AVERAGE
            else -> PageSpeedAuditOutcome.FAILED
        }
}

data class PageSpeedStrategyReport(
    val strategy: PageSpeedStrategy,
    val categories: List<PageSpeedCategoryScore>,
    val metadata: List<PageSpeedReportField>,
    val runWarnings: List<String>,
    val audits: List<PageSpeedAudit>,
)

enum class PageSpeedCruxUnit { MILLISECONDS, UNITLESS }

data class PageSpeedCruxBin(
    val start: Double,
    val end: Double?,
    val density: Double?,
)

/** One CrUX metric: percentiles, its good / needs-improvement / poor histogram, or category fractions. */
data class PageSpeedCruxMetric(
    val key: String,
    val label: String,
    val unit: PageSpeedCruxUnit,
    val percentiles: List<Pair<String, Double>>,
    val histogram: List<PageSpeedCruxBin>,
    val fractions: List<Pair<String, Double>>,
) {
    val p75: Double? get() = percentiles.firstOrNull { it.first == "p75" }?.second
}

data class PageSpeedCruxRecord(
    val key: List<PageSpeedReportField>,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val metrics: List<PageSpeedCruxMetric>,
)

data class PageSpeedCruxPeriod(
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

data class PageSpeedCruxHistoryBin(
    val start: Double,
    val end: Double?,
    val densities: List<Double?>,
)

/** A CrUX history metric aligned to [PageSpeedCruxHistory.periods]; gaps stay null. */
data class PageSpeedCruxHistoryMetric(
    val key: String,
    val label: String,
    val unit: PageSpeedCruxUnit,
    val p75s: List<Double?>,
    val histogram: List<PageSpeedCruxHistoryBin>,
    val fractions: List<Pair<String, List<Double?>>>,
)

data class PageSpeedCruxHistory(
    val periods: List<PageSpeedCruxPeriod>,
    val metrics: List<PageSpeedCruxHistoryMetric>,
)

/**
 * Full report held in memory for the Pro breakdown. Identity equality keeps state-flow
 * comparisons cheap even though the raw Lighthouse trees are large.
 */
class PageSpeedReport(
    val strategies: List<PageSpeedStrategyReport>,
    val crux: PageSpeedCruxRecord?,
    val cruxHistory: PageSpeedCruxHistory?,
    val rawResponses: Map<String, ProviderJsonValue>,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
) {
    fun strategy(strategy: PageSpeedStrategy): PageSpeedStrategyReport? =
        strategies.firstOrNull { it.strategy == strategy }

    override fun toString(): String =
        "PageSpeedReport(strategies=${strategies.map { it.strategy }}, crux=${crux != null}, " +
            "history=${cruxHistory?.periods?.size}, raw=${rawResponses.keys}, warnings=${warnings.size})"
}

/** Pure-Kotlin report parser (JVM testable). Shapes follow Google's documented response schemas. */
object PageSpeedReportParser {
    const val HISTORY_PERIOD_COUNT = 40
    private const val MAX_AUDITS = 1_000
    private const val MAX_TEXT = 4_000

    fun parseJson(bytes: ByteArray): ProviderJsonValue = try {
        ProviderJsonParser.parse(bytes)
    } catch (error: Exception) {
        throw PageSpeedResponseFormatException("Could not read the provider response.", error)
    }

    fun lighthouse(root: ProviderJsonValue, strategy: PageSpeedStrategy): PageSpeedStrategyReport {
        val lighthouse = root["lighthouseResult"]?.objectValue
            ?: throw PageSpeedResponseFormatException("PageSpeed Insights did not return a Lighthouse report.")
        val categoriesObject = lighthouse["categories"]?.objectValue.orEmpty()
        val categories = categoriesObject.entries.map { (key, value) ->
            PageSpeedCategoryScore(
                id = value["id"]?.stringValue ?: key,
                title = value["title"]?.stringValue ?: humanizedKey(key),
                score = value["score"]?.numberValue,
                auditIds = value["auditRefs"]?.arrayValue.orEmpty().mapNotNull { it["id"]?.stringValue },
            )
        }
        val membership = HashMap<String, MutableList<String>>()
        categories.forEach { category ->
            category.auditIds.forEach { membership.getOrPut(it) { mutableListOf() } += category.id }
        }
        val audits = lighthouse["audits"]?.objectValue.orEmpty().entries
            .take(MAX_AUDITS)
            .map { (key, value) -> audit(key, value, membership[key].orEmpty()) }
            .distinctBy { it.id }
            .sortedBy { it.id }
        val metadata = buildList {
            fun add(key: String, label: String, value: ProviderJsonValue?) {
                value?.let(::scalarText)?.takeIf(String::isNotBlank)?.let { add(PageSpeedReportField(key, label, it.take(MAX_TEXT))) }
            }
            add("requestedUrl", "Requested URL", lighthouse["requestedUrl"])
            add("finalUrl", "Final URL", lighthouse["finalUrl"])
            add("finalDisplayedUrl", "Final displayed URL", lighthouse["finalDisplayedUrl"])
            add("mainDocumentUrl", "Main document URL", lighthouse["mainDocumentUrl"])
            add("fetchTime", "Fetch time", lighthouse["fetchTime"])
            add("analysisUTCTimestamp", "Analysis time", root["analysisUTCTimestamp"])
            add("lighthouseVersion", "Lighthouse version", lighthouse["lighthouseVersion"])
            add("formFactor", "Form factor", lighthouse["configSettings"]?.get("formFactor"))
            add("locale", "Locale", lighthouse["configSettings"]?.get("locale"))
            add("benchmarkIndex", "Benchmark index", lighthouse["environment"]?.get("benchmarkIndex"))
            add("totalTiming", "Audit duration (ms)", lighthouse["timing"]?.get("total"))
            add("userAgent", "User agent", lighthouse["userAgent"])
            add("networkUserAgent", "Network user agent", lighthouse["environment"]?.get("networkUserAgent"))
        }
        return PageSpeedStrategyReport(
            strategy = strategy,
            categories = categories,
            metadata = metadata,
            runWarnings = lighthouse["runWarnings"]?.arrayValue.orEmpty().mapNotNull { it.stringValue?.take(MAX_TEXT) },
            audits = audits,
        )
    }

    private fun audit(key: String, value: ProviderJsonValue, categories: List<String>): PageSpeedAudit {
        val details = value["details"]
        return PageSpeedAudit(
            id = value["id"]?.stringValue ?: key,
            title = (value["title"]?.stringValue ?: humanizedKey(key)).take(MAX_TEXT),
            description = value["description"]?.stringValue?.take(MAX_TEXT),
            score = value["score"]?.numberValue,
            scoreDisplayMode = value["scoreDisplayMode"]?.stringValue,
            displayValue = value["displayValue"]?.stringValue?.take(MAX_TEXT),
            numericValue = value["numericValue"]?.numberValue,
            numericUnit = value["numericUnit"]?.stringValue,
            errorMessage = value["errorMessage"]?.stringValue?.take(MAX_TEXT),
            warnings = value["warnings"]?.arrayValue.orEmpty().mapNotNull { it.stringValue?.take(MAX_TEXT) },
            detailsType = details?.get("type")?.stringValue,
            detailsItemCount = details?.get("items")?.arrayValue?.size,
            categoryIds = categories,
        )
    }

    fun cruxRecord(root: ProviderJsonValue): PageSpeedCruxRecord {
        val record = root["record"]?.objectValue
            ?: throw PageSpeedResponseFormatException("Chrome UX Report did not return a record for this page.")
        val metrics = record["metrics"]?.objectValue.orEmpty().entries.map { (key, value) ->
            PageSpeedCruxMetric(
                key = key,
                label = cruxLabel(key),
                unit = cruxUnit(key),
                percentiles = value["percentiles"]?.objectValue.orEmpty().entries
                    .mapNotNull { (name, number) -> number.numberValue?.let { name to it } },
                histogram = value["histogram"]?.arrayValue.orEmpty().mapNotNull { bin ->
                    val start = bin["start"]?.numberValue ?: return@mapNotNull null
                    PageSpeedCruxBin(start, bin["end"]?.numberValue, bin["density"]?.numberValue)
                },
                fractions = value["fractions"]?.objectValue.orEmpty().entries
                    .mapNotNull { (name, number) -> number.numberValue?.let { name to it } },
            )
        }.sortedWith(compareBy({ cruxOrder(it.key) }, { it.key }))
        val period = record["collectionPeriod"]
        return PageSpeedCruxRecord(
            key = record["key"]?.objectValue.orEmpty().entries.mapNotNull { (name, value) ->
                scalarText(value)?.let { PageSpeedReportField(name, humanizedKey(name), it) }
            },
            firstDate = googleDate(period?.get("firstDate")),
            lastDate = googleDate(period?.get("lastDate")),
            metrics = metrics,
        )
    }

    fun cruxHistory(root: ProviderJsonValue): PageSpeedCruxHistory {
        val record = root["record"]?.objectValue
            ?: throw PageSpeedResponseFormatException("Chrome UX Report did not return a history record.")
        val periods = record["collectionPeriods"]?.arrayValue.orEmpty().map { period ->
            PageSpeedCruxPeriod(googleDate(period["firstDate"]), googleDate(period["lastDate"]))
        }
        if (periods.isEmpty()) {
            throw PageSpeedResponseFormatException("Chrome UX Report returned no history periods.")
        }
        fun aligned(values: List<ProviderJsonValue>?): List<Double?> =
            List(periods.size) { index -> values?.getOrNull(index)?.numberValue }
        val metrics = record["metrics"]?.objectValue.orEmpty().entries.map { (key, value) ->
            PageSpeedCruxHistoryMetric(
                key = key,
                label = cruxLabel(key),
                unit = cruxUnit(key),
                p75s = aligned(value["percentilesTimeseries"]?.get("p75s")?.arrayValue),
                histogram = value["histogramTimeseries"]?.arrayValue.orEmpty().mapNotNull { bin ->
                    val start = bin["start"]?.numberValue ?: return@mapNotNull null
                    PageSpeedCruxHistoryBin(start, bin["end"]?.numberValue, aligned(bin["densities"]?.arrayValue))
                },
                fractions = value["fractionTimeseries"]?.objectValue.orEmpty().entries.map { (name, series) ->
                    name to aligned(series["fractions"]?.arrayValue)
                },
            )
        }.sortedWith(compareBy({ cruxOrder(it.key) }, { it.key }))
        return PageSpeedCruxHistory(periods, metrics)
    }

    /** CrUX encodes dates as {year, month, day}. */
    internal fun googleDate(value: ProviderJsonValue?): LocalDate? {
        val year = value?.get("year")?.numberValue?.toInt() ?: return null
        val month = value["month"]?.numberValue?.toInt() ?: return null
        val day = value["day"]?.numberValue?.toInt() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun scalarText(value: ProviderJsonValue): String? = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> value.text
        is ProviderJsonValue.Bool -> if (value.value) "Yes" else "No"
        else -> null
    }

    private val CRUX_LABELS = mapOf(
        "largest_contentful_paint" to "Largest Contentful Paint (LCP)",
        "interaction_to_next_paint" to "Interaction to Next Paint (INP)",
        "cumulative_layout_shift" to "Cumulative Layout Shift (CLS)",
        "first_contentful_paint" to "First Contentful Paint (FCP)",
        "experimental_time_to_first_byte" to "Time to First Byte (TTFB)",
        "first_input_delay" to "First Input Delay (FID)",
        "round_trip_time" to "Round Trip Time (RTT)",
        "form_factors" to "Form factors",
        "navigation_types" to "Navigation types",
        "largest_contentful_paint_resource_type" to "LCP resource type",
        "largest_contentful_paint_image_time_to_first_byte" to "LCP image · time to first byte",
        "largest_contentful_paint_image_resource_load_delay" to "LCP image · resource load delay",
        "largest_contentful_paint_image_resource_load_duration" to "LCP image · resource load duration",
        "largest_contentful_paint_image_element_render_delay" to "LCP image · element render delay",
    )

    private val CRUX_ORDER = listOf(
        "largest_contentful_paint", "interaction_to_next_paint", "cumulative_layout_shift",
        "first_contentful_paint", "experimental_time_to_first_byte", "round_trip_time",
    )

    private fun cruxOrder(key: String): Int = CRUX_ORDER.indexOf(key).let { if (it < 0) CRUX_ORDER.size else it }

    fun cruxLabel(key: String): String = CRUX_LABELS[key] ?: humanizedKey(key)

    fun cruxUnit(key: String): PageSpeedCruxUnit =
        if ("layout_shift" in key) PageSpeedCruxUnit.UNITLESS else PageSpeedCruxUnit.MILLISECONDS

    fun humanizedKey(key: String): String {
        val spaced = StringBuilder()
        key.replace('_', ' ').replace('-', ' ').forEach { character ->
            val previous = spaced.lastOrNull()
            if (character.isUpperCase() && previous != null && previous.isLowerCase()) spaced.append(' ')
            spaced.append(character)
        }
        return spaced.toString().trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
