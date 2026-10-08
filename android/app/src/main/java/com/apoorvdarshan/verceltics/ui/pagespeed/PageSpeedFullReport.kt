package com.apoorvdarshan.verceltics.ui.pagespeed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedAudit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedAuditOutcome
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxHistory
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxMetric
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxRecord
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReport
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReportField
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedStrategy
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedStrategyReport
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.sites.SiteChoiceChip
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceFormat
import com.apoorvdarshan.verceltics.ui.sites.SiteTimelineChart
import com.apoorvdarshan.verceltics.ui.sites.TimelineChartPoint
import com.apoorvdarshan.verceltics.ui.sites.flattenRaw
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Good = Color(0xFF0CCE6B)
private val NeedsWork = Color(0xFFFFA400)
private val Poor = Color(0xFFFF4E42)

enum class PageSpeedAuditFilter { ALL, FAILED, NEEDS_WORK, PASSED, INFORMATIVE, NOT_APPLICABLE }

enum class PageSpeedAuditSort { IMPACT, TITLE }

/** Report controls that survive recomposition and rotation; data itself stays in the ViewModel. */
@Stable
class PageSpeedReportViewState(
    strategy: PageSpeedStrategy = PageSpeedStrategy.MOBILE,
    auditSearch: String = "",
    auditFilter: PageSpeedAuditFilter = PageSpeedAuditFilter.ALL,
    auditSort: PageSpeedAuditSort = PageSpeedAuditSort.IMPACT,
    historyMetric: String = "",
) {
    var strategy by mutableStateOf(strategy)
    var auditSearch by mutableStateOf(auditSearch)
    var auditFilter by mutableStateOf(auditFilter)
    var auditSort by mutableStateOf(auditSort)
    var auditLimit by mutableIntStateOf(AUDIT_BATCH)
    var historyMetric by mutableStateOf(historyMetric)
    var historyIndex by mutableStateOf<Int?>(null)
    var expandedAudit by mutableStateOf<String?>(null)
    var showRaw by mutableStateOf(false)

    companion object {
        const val AUDIT_BATCH = 40

        val Saver: Saver<PageSpeedReportViewState, Any> = Saver(
            save = {
                listOf(it.strategy.name, it.auditSearch, it.auditFilter.name, it.auditSort.name, it.historyMetric)
            },
            restore = { saved ->
                val values = saved as List<*>
                PageSpeedReportViewState(
                    strategy = runCatching { PageSpeedStrategy.valueOf(values[0] as String) }.getOrDefault(PageSpeedStrategy.MOBILE),
                    auditSearch = values[1] as String,
                    auditFilter = runCatching { PageSpeedAuditFilter.valueOf(values[2] as String) }.getOrDefault(PageSpeedAuditFilter.ALL),
                    auditSort = runCatching { PageSpeedAuditSort.valueOf(values[3] as String) }.getOrDefault(PageSpeedAuditSort.IMPACT),
                    historyMetric = values[4] as String,
                )
            },
        )
    }
}

@Composable
fun rememberPageSpeedReportViewState(): PageSpeedReportViewState =
    rememberSaveable(saver = PageSpeedReportViewState.Saver) { PageSpeedReportViewState() }

// MARK: - Pure presentation logic (unit tested)

internal fun PageSpeedAudit.matches(filter: PageSpeedAuditFilter): Boolean = when (filter) {
    PageSpeedAuditFilter.ALL -> true
    PageSpeedAuditFilter.FAILED -> outcome == PageSpeedAuditOutcome.FAILED || outcome == PageSpeedAuditOutcome.ERROR
    PageSpeedAuditFilter.NEEDS_WORK -> outcome == PageSpeedAuditOutcome.AVERAGE
    PageSpeedAuditFilter.PASSED -> outcome == PageSpeedAuditOutcome.PASSED
    PageSpeedAuditFilter.INFORMATIVE -> outcome == PageSpeedAuditOutcome.INFORMATIVE || outcome == PageSpeedAuditOutcome.MANUAL
    PageSpeedAuditFilter.NOT_APPLICABLE -> outcome == PageSpeedAuditOutcome.NOT_APPLICABLE
}

private fun PageSpeedAuditOutcome.impactRank(): Int = when (this) {
    PageSpeedAuditOutcome.FAILED -> 0
    PageSpeedAuditOutcome.ERROR -> 1
    PageSpeedAuditOutcome.AVERAGE -> 2
    PageSpeedAuditOutcome.INFORMATIVE -> 3
    PageSpeedAuditOutcome.MANUAL -> 4
    PageSpeedAuditOutcome.PASSED -> 5
    PageSpeedAuditOutcome.NOT_APPLICABLE -> 6
}

/** Searches every visible audit field, like iOS' searchable provider table. */
internal fun visiblePageSpeedAudits(
    audits: List<PageSpeedAudit>,
    search: String,
    filter: PageSpeedAuditFilter,
    sort: PageSpeedAuditSort,
): List<PageSpeedAudit> {
    val needle = search.trim().lowercase(Locale.ROOT)
    val matching = audits.filter { audit ->
        audit.matches(filter) && (
            needle.isEmpty() || listOfNotNull(
                audit.id, audit.title, audit.description, audit.displayValue, audit.errorMessage,
                audit.scoreDisplayMode, audit.detailsType, audit.categoryIds.joinToString(" "),
            ).any { it.lowercase(Locale.ROOT).contains(needle) }
            )
    }
    return when (sort) {
        PageSpeedAuditSort.IMPACT -> matching.sortedWith(
            compareBy<PageSpeedAudit>({ it.outcome.impactRank() }, { it.score ?: 2.0 }, { it.title.lowercase(Locale.ROOT) }),
        )
        PageSpeedAuditSort.TITLE -> matching.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
}

internal enum class CruxRating { GOOD, NEEDS_IMPROVEMENT, POOR }

private val CRUX_THRESHOLDS = mapOf(
    "largest_contentful_paint" to (2_500.0 to 4_000.0),
    "interaction_to_next_paint" to (200.0 to 500.0),
    "cumulative_layout_shift" to (0.1 to 0.25),
    "first_contentful_paint" to (1_800.0 to 3_000.0),
    "experimental_time_to_first_byte" to (800.0 to 1_800.0),
    "first_input_delay" to (100.0 to 300.0),
)

/** Google's published Core Web Vitals thresholds for a p75 value. */
internal fun cruxRating(key: String, p75: Double): CruxRating? {
    val (good, poor) = CRUX_THRESHOLDS[key] ?: return null
    return when {
        p75 <= good -> CruxRating.GOOD
        p75 <= poor -> CruxRating.NEEDS_IMPROVEMENT
        else -> CruxRating.POOR
    }
}

internal fun formatCruxValue(value: Double, unit: PageSpeedCruxUnit, locale: Locale = Locale.getDefault()): String =
    when (unit) {
        PageSpeedCruxUnit.UNITLESS -> String.format(locale, "%.2f", value)
        PageSpeedCruxUnit.MILLISECONDS -> if (value >= 1_000) {
            String.format(locale, "%.2f s", value / 1_000)
        } else {
            "${NumberFormat.getIntegerInstance(locale).format(value.roundToInt())} ms"
        }
    }

internal fun cruxBinLabel(
    start: Double,
    end: Double?,
    unit: PageSpeedCruxUnit,
    index: Int,
    binCount: Int,
): String {
    val range = if (end == null) {
        "≥ ${formatCruxValue(start, unit)}"
    } else if (start <= 0.0) {
        "< ${formatCruxValue(end, unit)}"
    } else {
        "${formatCruxValue(start, unit)}–${formatCruxValue(end, unit)}"
    }
    val name = if (binCount == 3) listOf("Good", "Needs improvement", "Poor")[index] else null
    return listOfNotNull(name, range).joinToString(" · ")
}

private fun binColor(index: Int, count: Int): Color = when {
    count == 3 -> listOf(Good, NeedsWork, Poor)[index]
    else -> listOf(Good, NeedsWork, Poor, Color(0xFF7C8CF8), Color(0xFF39B5E0))[index % 5]
}

private fun scoreColor(score: Double?): Color = when {
    score == null -> Color(0xFF8A8A8A)
    score >= 0.9 -> Good
    score >= 0.5 -> NeedsWork
    else -> Poor
}

private fun outcomeLabel(outcome: PageSpeedAuditOutcome): String = when (outcome) {
    PageSpeedAuditOutcome.FAILED -> "Failed"
    PageSpeedAuditOutcome.AVERAGE -> "Needs work"
    PageSpeedAuditOutcome.PASSED -> "Passed"
    PageSpeedAuditOutcome.INFORMATIVE -> "Informative"
    PageSpeedAuditOutcome.NOT_APPLICABLE -> "Not applicable"
    PageSpeedAuditOutcome.MANUAL -> "Manual check"
    PageSpeedAuditOutcome.ERROR -> "Error"
}

private fun filterLabel(filter: PageSpeedAuditFilter): String = when (filter) {
    PageSpeedAuditFilter.ALL -> "All"
    PageSpeedAuditFilter.FAILED -> "Failed"
    PageSpeedAuditFilter.NEEDS_WORK -> "Needs work"
    PageSpeedAuditFilter.PASSED -> "Passed"
    PageSpeedAuditFilter.INFORMATIVE -> "Informative"
    PageSpeedAuditFilter.NOT_APPLICABLE -> "N/A"
}

private fun formatReportTimestamp(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: return value
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

private fun periodLabel(date: LocalDate?): String =
    date?.let { DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).format(it) } ?: "Unknown period"

/** Lighthouse markdown links `[text](url)` read better as plain text in a dense table. */
private fun plainMarkdown(text: String): String =
    text.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1").replace("`", "")

// MARK: - Lazy list content

internal fun LazyListScope.pageSpeedFullReportItems(
    report: PageSpeedReport,
    view: PageSpeedReportViewState,
) {
    report.warnings.forEachIndexed { index, warning ->
        item(key = "report-warning-$index") { ReportNotice("PARTIAL REPORT", warning) }
    }
    val available = report.strategies.map(PageSpeedStrategyReport::strategy)
    val strategyReport = report.strategy(view.strategy) ?: report.strategies.firstOrNull()
    item(key = "report-devices") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportSectionTitle(
                "FULL LIGHTHOUSE REPORT",
                "${report.strategies.size} device${if (report.strategies.size == 1) "" else "s"}",
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageSpeedStrategy.entries.forEach { strategy ->
                    SiteChoiceChip(
                        label = strategy.label,
                        selected = strategyReport?.strategy == strategy,
                        accent = PageSpeedReportAccent,
                        enabled = strategy in available,
                        onClick = {
                            view.strategy = strategy
                            view.auditLimit = PageSpeedReportViewState.AUDIT_BATCH
                            view.expandedAudit = null
                        },
                        testTag = "pagespeed.report.device.${strategy.wireValue}",
                    )
                }
            }
        }
    }
    if (strategyReport == null) {
        item(key = "report-no-lighthouse") {
            ReportNotice("LIGHTHOUSE", "Google did not return a readable Lighthouse report for this audit.")
        }
    } else {
        item(key = "report-categories-${strategyReport.strategy}") { CategoryScoresCard(strategyReport) }
        item(key = "report-metadata-${strategyReport.strategy}") { ReportMetadataCard(strategyReport) }
    }
    item(key = "report-crux") { CruxFieldCard(report.crux) }
    item(key = "report-history") { CruxHistoryCard(report.cruxHistory, view) }
    if (strategyReport != null) auditItems(strategyReport, view)
    if (report.rawResponses.isNotEmpty()) {
        item(key = "report-raw") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                onClick = { view.showRaw = true },
                testTag = "pagespeed.report.raw",
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DataObject, contentDescription = null, tint = PageSpeedReportAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Complete API response", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Browse every returned field across ${report.rawResponses.size} endpoints",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val PageSpeedReportAccent = Color(0xFF4FBD7A)

@Composable
private fun ReportSectionTitle(title: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        detail?.let { LabelChip(it) }
    }
}

@Composable
private fun ReportNotice(title: String, message: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = NeedsWork.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = NeedsWork,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReportCard(testTag: String, content: @Composable () -> Unit) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        borderColor = PageSpeedReportAccent.copy(alpha = 0.22f),
        testTag = testTag,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun CategoryScoresCard(report: PageSpeedStrategyReport) {
    ReportCard("pagespeed.report.categories") {
        ReportSectionTitle("${report.strategy.label.uppercase()} SCORES")
        val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
        report.categories.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { category ->
                    val score = category.score
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 70.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = scoreColor(score).copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, scoreColor(score).copy(alpha = 0.4f)),
                    ) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                score?.let { (it * 100).roundToInt().toString() } ?: "—",
                                style = MaterialTheme.typography.headlineSmall,
                                color = scoreColor(score),
                            )
                            Text(
                                "${category.title} · ${category.auditIds.size} audits",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReportMetadataCard(report: PageSpeedStrategyReport) {
    ReportCard("pagespeed.report.metadata") {
        ReportSectionTitle("${report.strategy.label.uppercase()} REPORT")
        report.metadata.forEach { field -> ReportField(field) }
        if (report.runWarnings.isNotEmpty()) {
            Text("RUN WARNINGS", style = MaterialTheme.typography.labelSmall, color = NeedsWork)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.runWarnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ReportField(field: PageSpeedReportField) {
    val value = when (field.key) {
        "fetchTime", "analysisUTCTimestamp" -> formatReportTimestamp(field.value)
        else -> field.value
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(field.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = if (field.key.contains("Url") || field.key.contains("Agent")) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
            )
        }
    }
}

@Composable
private fun CruxFieldCard(record: PageSpeedCruxRecord?) {
    ReportCard("pagespeed.report.crux") {
        ReportSectionTitle("CHROME UX FIELD DATA", record?.metrics?.size?.let { "$it metrics" })
        if (record == null) {
            Text(
                "Chrome UX Report has no field data for this page yet. Lab results above still apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ReportCard
        }
        val period = listOfNotNull(record.firstDate, record.lastDate)
        if (period.size == 2) {
            Text(
                "Collection period ${periodLabel(record.firstDate)} – ${periodLabel(record.lastDate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        record.key.forEach { ReportField(it) }
        record.metrics.forEach { metric -> CruxMetricRow(metric) }
    }
}

@Composable
private fun CruxMetricRow(metric: PageSpeedCruxMetric) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("pagespeed.report.crux.${metric.key}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metric.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            metric.p75?.let { p75 ->
                val rating = cruxRating(metric.key, p75)
                Text(
                    "p75 ${formatCruxValue(p75, metric.unit)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = when (rating) {
                        CruxRating.GOOD -> Good
                        CruxRating.NEEDS_IMPROVEMENT -> NeedsWork
                        CruxRating.POOR -> Poor
                        null -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        metric.percentiles.filter { it.first != "p75" }.forEach { (name, value) ->
            Text("${name.uppercase()} ${formatCruxValue(value, metric.unit)}", style = MaterialTheme.typography.labelSmall)
        }
        if (metric.histogram.isNotEmpty()) {
            DistributionBar(
                segments = metric.histogram.mapIndexed { index, bin ->
                    Triple(
                        cruxBinLabel(bin.start, bin.end, metric.unit, index, metric.histogram.size),
                        bin.density ?: 0.0,
                        binColor(index, metric.histogram.size),
                    )
                },
            )
        }
        if (metric.fractions.isNotEmpty()) {
            DistributionBar(
                segments = metric.fractions.mapIndexed { index, (name, value) ->
                    Triple(PageSpeedReportFormat.humanized(name), value, binColor(index + 3, 99))
                },
            )
        }
    }
}

/** Stacked distribution with a readable legend; densities are 0–1 fractions. */
@Composable
private fun DistributionBar(segments: List<Triple<String, Double, Color>>) {
    val total = segments.sumOf { it.second.coerceAtLeast(0.0) }.takeIf { it > 0.0 } ?: 1.0
    val description = segments.joinToString(", ") { (label, value, _) -> "$label ${percent(value)}" }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                .semantics { contentDescription = description },
        ) {
            segments.forEach { (_, value, color) ->
                val weight = (value.coerceAtLeast(0.0) / total).toFloat()
                if (weight > 0f) {
                    Box(
                        Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(color),
                    )
                }
            }
        }
        segments.forEach { (label, value, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(percent(value), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun percent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100)

@Composable
private fun CruxHistoryCard(history: PageSpeedCruxHistory?, view: PageSpeedReportViewState) {
    ReportCard("pagespeed.report.history") {
        ReportSectionTitle("CHROME UX HISTORY", history?.let { "${it.periods.size} periods" })
        if (history == null) {
            Text(
                "Chrome UX history is unavailable for this page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ReportCard
        }
        val charted = history.metrics.filter { metric -> metric.p75s.any { it != null } }
        if (charted.isEmpty()) {
            Text(
                "Google returned no p75 values in the history window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ReportCard
        }
        val selected = charted.firstOrNull { it.key == view.historyMetric } ?: charted.first()
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            charted.forEach { metric ->
                SiteChoiceChip(
                    label = "${metric.label.substringBefore(" (")} p75",
                    selected = metric.key == selected.key,
                    accent = PageSpeedReportAccent,
                    onClick = {
                        view.historyMetric = metric.key
                        view.historyIndex = null
                    },
                    testTag = "pagespeed.report.history.metric.${metric.key}",
                )
            }
        }
        val indexed = history.periods.indices.mapNotNull { index ->
            selected.p75s.getOrNull(index)?.let { index to it }
        }
        val points = indexed.map { (index, value) ->
            TimelineChartPoint(
                history.periods[index].lastDate?.let { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(it) } ?: "#${index + 1}",
                value,
            )
        }
        val scrubbed = view.historyIndex?.let(indexed::getOrNull)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (headlineIndex, headlineValue) = scrubbed ?: indexed.last()
            Text(formatCruxValue(headlineValue, selected.unit), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                "Period ending ${periodLabel(history.periods[headlineIndex].lastDate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SiteTimelineChart(
            points = points,
            accent = PageSpeedReportAccent,
            formatValue = { formatCruxValue(it, selected.unit) },
            description = "${selected.label} p75 across ${points.size} Chrome UX collection periods",
            modifier = Modifier.fillMaxWidth(),
            chartHeight = 200.dp,
            includeZero = true,
            selectedIndex = view.historyIndex,
            onSelectedIndexChange = { view.historyIndex = it },
            testTag = "pagespeed.report.history.chart",
        )
        val periodIndex = (scrubbed ?: indexed.last()).first
        val densities = selected.histogram.map { bin -> bin.densities.getOrNull(periodIndex) }
        if (densities.any { it != null }) {
            Text("DISTRIBUTION · ${periodLabel(history.periods[periodIndex].lastDate)}", style = MaterialTheme.typography.labelSmall)
            DistributionBar(
                segments = selected.histogram.mapIndexed { index, bin ->
                    Triple(
                        cruxBinLabel(bin.start, bin.end, selected.unit, index, selected.histogram.size),
                        bin.densities.getOrNull(periodIndex) ?: 0.0,
                        binColor(index, selected.histogram.size),
                    )
                },
            )
        }
        Text(
            "${points.size} of ${history.periods.size} periods reported · drag the chart to inspect a period",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.auditItems(report: PageSpeedStrategyReport, view: PageSpeedReportViewState) {
    val visible = visiblePageSpeedAudits(report.audits, view.auditSearch, view.auditFilter, view.auditSort)
    item(key = "audits-header-${report.strategy}") {
        Column(
            Modifier.testTag("pagespeed.report.audits"),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ReportSectionTitle("${report.strategy.label.uppercase()} LIGHTHOUSE AUDITS", "${visible.size}/${report.audits.size}")
            ControlSearchField(
                value = view.auditSearch,
                onValueChange = {
                    view.auditSearch = it.take(200)
                    view.auditLimit = PageSpeedReportViewState.AUDIT_BATCH
                },
                placeholder = "Search ${report.audits.size} audits",
                modifier = Modifier.fillMaxWidth(),
                testTag = "pagespeed.report.audits.search",
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageSpeedAuditFilter.entries.forEach { filter ->
                    val count = report.audits.count { it.matches(filter) }
                    SiteChoiceChip(
                        label = "${filterLabel(filter)} $count",
                        selected = view.auditFilter == filter,
                        accent = PageSpeedReportAccent,
                        onClick = {
                            view.auditFilter = filter
                            view.auditLimit = PageSpeedReportViewState.AUDIT_BATCH
                        },
                        testTag = "pagespeed.report.audits.filter.${filter.name}",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SORT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SiteChoiceChip(
                    label = "Impact",
                    selected = view.auditSort == PageSpeedAuditSort.IMPACT,
                    accent = PageSpeedReportAccent,
                    onClick = { view.auditSort = PageSpeedAuditSort.IMPACT },
                    testTag = "pagespeed.report.audits.sort.impact",
                )
                SiteChoiceChip(
                    label = "A–Z",
                    selected = view.auditSort == PageSpeedAuditSort.TITLE,
                    accent = PageSpeedReportAccent,
                    onClick = { view.auditSort = PageSpeedAuditSort.TITLE },
                    testTag = "pagespeed.report.audits.sort.title",
                )
            }
            if (visible.isEmpty()) {
                Text(
                    if (view.auditSearch.isBlank()) "No audits in this group." else "No audits match “${view.auditSearch}”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(visible.take(view.auditLimit), key = { "audit-${report.strategy}-${it.id}" }) { audit ->
        AuditRow(
            audit = audit,
            expanded = view.expandedAudit == audit.id,
            onToggle = { view.expandedAudit = if (view.expandedAudit == audit.id) null else audit.id },
        )
    }
    if (visible.size > view.auditLimit) {
        item(key = "audits-more-${report.strategy}") {
            ThemedActionButton(
                text = "SHOW ${minOf(PageSpeedReportViewState.AUDIT_BATCH, visible.size - view.auditLimit)} MORE AUDITS",
                onClick = { view.auditLimit += PageSpeedReportViewState.AUDIT_BATCH },
                modifier = Modifier.fillMaxWidth(),
                tone = ThemedActionTone.NEUTRAL,
                testTag = "pagespeed.report.audits.more",
            )
        }
    }
}

@Composable
private fun AuditRow(audit: PageSpeedAudit, expanded: Boolean, onToggle: () -> Unit) {
    val outcome = audit.outcome
    val tint = when (outcome) {
        PageSpeedAuditOutcome.PASSED -> Good
        PageSpeedAuditOutcome.AVERAGE -> NeedsWork
        PageSpeedAuditOutcome.FAILED, PageSpeedAuditOutcome.ERROR -> Poor
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = "${outcomeLabel(outcome)}, ${if (expanded) "expanded" else "collapsed"}" }
            .testTag("pagespeed.report.audit.${audit.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(tint, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    plainMarkdown(audit.title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    audit.score?.let { (it * 100).roundToInt().toString() } ?: outcomeLabel(outcome),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            audit.displayValue?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        audit.description?.let { Text(plainMarkdown(it), style = MaterialTheme.typography.bodySmall) }
                        audit.errorMessage?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall, color = Poor) }
                        audit.warnings.forEach { Text("Warning: $it", style = MaterialTheme.typography.bodySmall, color = NeedsWork) }
                        val facts = listOfNotNull(
                            "ID ${audit.id}",
                            "Status ${outcomeLabel(outcome)}",
                            audit.scoreDisplayMode?.let { "Mode $it" },
                            audit.numericValue?.let { value ->
                                "Value ${SiteServiceFormat.display(ProviderJsonValue.Num.of(value))}${audit.numericUnit?.let { " $it" }.orEmpty()}"
                            },
                            audit.detailsType?.let { type ->
                                "Details $type${audit.detailsItemCount?.let { " · $it items" }.orEmpty()}"
                            },
                            audit.categoryIds.takeIf(List<String>::isNotEmpty)?.let { "Categories ${it.joinToString(", ")}" },
                        )
                        facts.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Raw explorer

private data class RawRow(val path: String, val value: String)

/** iOS `SiteDetailRawResponseExplorer` for PageSpeed: every returned leaf, per endpoint, searchable. */
@Composable
internal fun PageSpeedRawExplorerDialog(report: PageSpeedReport, onDismiss: () -> Unit) {
    val endpoints = remember(report) { report.rawResponses.keys.toList() }
    var endpoint by rememberSaveable { mutableStateOf(endpoints.firstOrNull().orEmpty()) }
    var search by rememberSaveable { mutableStateOf("") }
    val chosen = endpoint.takeIf { it in endpoints } ?: endpoints.firstOrNull().orEmpty()
    val rows by produceState<List<RawRow>?>(initialValue = null, report, chosen) {
        value = null
        value = withContext(Dispatchers.Default) {
            report.rawResponses[chosen]?.let(::flattenRaw).orEmpty().map { leaf ->
                val text = SiteServiceFormat.display(leaf.value)
                RawRow(
                    leaf.path,
                    if (text.length > RAW_VALUE_LIMIT) {
                        text.take(RAW_VALUE_LIMIT) + "… (${text.length - RAW_VALUE_LIMIT} more characters)"
                    } else {
                        text
                    },
                )
            }
        }
    }
    val needle = search.trim().lowercase(Locale.ROOT)
    val visible = remember(rows, needle) {
        rows.orEmpty().filter {
            needle.isEmpty() || it.path.lowercase(Locale.ROOT).contains(needle) || it.value.lowercase(Locale.ROOT).contains(needle)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("pagespeed.raw"),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "raw-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = PageSpeedReportAccent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Complete API response", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Google responses as returned. The API key is never part of a response.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ThemedGlassControl(
                            modifier = Modifier.size(48.dp),
                            onClick = onDismiss,
                            testTag = "pagespeed.raw.close",
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Cancel, contentDescription = "Close raw explorer")
                            }
                        }
                    }
                }
                item(key = "raw-endpoints") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        endpoints.forEach { key ->
                            SiteChoiceChip(
                                label = key,
                                selected = key == chosen,
                                accent = PageSpeedReportAccent,
                                onClick = { endpoint = key },
                                testTag = "pagespeed.raw.endpoint.$key",
                            )
                        }
                    }
                }
                item(key = "raw-search") {
                    ControlSearchField(
                        value = search,
                        onValueChange = { search = it.take(200) },
                        placeholder = "Search field paths and values",
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "pagespeed.raw.search",
                    )
                }
                item(key = "raw-count") {
                    if (rows == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Reading fields…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        StatusPill("${visible.size} fields", PageSpeedReportAccent)
                    }
                }
                items(visible.size, key = { "raw-$chosen-$it" }) { index ->
                    val row = visible[index]
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        SelectionContainer {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    row.path,
                                    color = PageSpeedReportAccent,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                Text(row.value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val RAW_VALUE_LIMIT = 2_000

/** Missing full report (restored cache or Pro preview): run the live audit to build it. */
@Composable
internal fun FullReportPendingPanel(isBusy: Boolean, onRunAudit: () -> Unit) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        borderColor = PageSpeedReportAccent.copy(alpha = 0.22f),
        testTag = "pagespeed.report.pending",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Full Lighthouse report", style = MaterialTheme.typography.titleMedium)
            Text(
                "Run a live audit to load every Lighthouse audit, report metadata, all Chrome UX metrics with distributions and the 40-period CrUX history. Saved audits keep only the summary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThemedActionButton(
                text = if (isBusy) "AUDITING…" else "RUN LIVE AUDIT",
                onClick = onRunAudit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                isBusy = isBusy,
                testTag = "pagespeed.report.run",
            )
        }
    }
}

internal object PageSpeedReportFormat {
    fun humanized(key: String): String = com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReportParser.humanizedKey(key)
}
