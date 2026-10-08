package com.apoorvdarshan.verceltics.ui.vercel

import com.apoorvdarshan.verceltics.ui.VercelAnalyticsPointUi
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

enum class VercelChartMetric(val label: String) {
    VISITORS("Visitors"),
    PAGE_VIEWS("Page Views"),
    BOUNCE_RATE("Bounce Rate"),
}

data class VercelChartPoint(
    val timeMillis: Long,
    val value: Double,
)

/** A cubic Bézier segment from one chart point to the next. */
data class VercelChartSegment(
    val control1X: Float,
    val control1Y: Float,
    val control2X: Float,
    val control2Y: Float,
    val endX: Float,
    val endY: Float,
)

/**
 * Pure math behind the analytics chart, ported from iOS `AnalyticsChart`: metric availability,
 * hourly-to-daily roll-up past 48 points, headline/peak/average values, axis ticks, scrubbing
 * hit-testing and Catmull-Rom smoothing.
 */
object VercelChartModel {
    const val DAILY_ROLLUP_THRESHOLD: Int = 48

    /** Bounce rate is offered only when at least one point reports it. */
    fun availableMetrics(points: List<VercelAnalyticsPointUi>): List<VercelChartMetric> =
        if (points.any { it.bounceRate != null }) {
            VercelChartMetric.entries
        } else {
            listOf(VercelChartMetric.VISITORS, VercelChartMetric.PAGE_VIEWS)
        }

    /** Vercel keys are ISO-8601 instants (with or without fractions) or UTC calendar dates. */
    fun parseKey(key: String): Long? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        parseOrNull { Instant.parse(trimmed).toEpochMilli() }?.let { return it }
        parseOrNull { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }?.let { return it }
        return parseOrNull { LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    }

    fun series(
        points: List<VercelAnalyticsPointUi>,
        metric: VercelChartMetric,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<VercelChartPoint> {
        val raw = points.mapNotNull { point ->
            val time = parseKey(point.key) ?: return@mapNotNull null
            val value = when (metric) {
                VercelChartMetric.VISITORS -> point.visitors.toDouble()
                VercelChartMetric.PAGE_VIEWS -> point.pageViews.toDouble()
                VercelChartMetric.BOUNCE_RATE -> point.bounceRate ?: return@mapNotNull null
            }
            VercelChartPoint(time, value)
        }.sortedBy(VercelChartPoint::timeMillis)
        return if (raw.size > DAILY_ROLLUP_THRESHOLD) aggregateDaily(raw, metric, zone) else raw
    }

    /** Sums each local calendar day (bounce rate is averaged), keyed at the day's start. */
    fun aggregateDaily(
        points: List<VercelChartPoint>,
        metric: VercelChartMetric,
        zone: ZoneId,
    ): List<VercelChartPoint> = points
        .groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
        .map { (day, dayPoints) ->
            val total = dayPoints.sumOf(VercelChartPoint::value)
            VercelChartPoint(
                timeMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                value = if (metric == VercelChartMetric.BOUNCE_RATE) total / dayPoints.size else total,
            )
        }
        .sortedBy(VercelChartPoint::timeMillis)

    /** The total for counts; the mean for bounce rate. */
    fun headline(series: List<VercelChartPoint>, metric: VercelChartMetric): Double {
        if (series.isEmpty()) return 0.0
        val total = series.sumOf(VercelChartPoint::value)
        return if (metric == VercelChartMetric.BOUNCE_RATE) total / series.size else total
    }

    fun average(series: List<VercelChartPoint>): Double =
        if (series.isEmpty()) 0.0 else series.sumOf(VercelChartPoint::value) / series.size

    /** The first highest point. */
    fun peak(series: List<VercelChartPoint>): VercelChartPoint? = series.maxByOrNull(VercelChartPoint::value)

    /** Point nearest in time to a horizontal position expressed as 0..1 of the plot width. */
    fun nearestIndex(series: List<VercelChartPoint>, fraction: Float): Int? {
        if (series.isEmpty()) return null
        if (series.size == 1) return 0
        val start = series.first().timeMillis
        val end = series.last().timeMillis
        if (end <= start) return 0
        val target = start + ((end - start) * fraction.coerceIn(0f, 1f).toDouble()).roundToLong()
        return series.indices.minByOrNull { abs(series[it].timeMillis - target) }
    }

    /** Horizontal position (0..1) of a point, by time. */
    fun xFraction(series: List<VercelChartPoint>, index: Int): Float {
        if (series.size <= 1) return 0.5f
        val start = series.first().timeMillis
        val end = series.last().timeMillis
        if (end <= start) return index.toFloat() / (series.size - 1)
        return ((series[index].timeMillis - start).toDouble() / (end - start)).toFloat()
    }

    /**
     * "Nice" y-axis ticks from zero covering [maxValue] with about [desiredCount] intervals.
     * Steps never drop below one because every metric is a whole count or percentage.
     */
    fun yTicks(maxValue: Double, desiredCount: Int = 4): List<Double> {
        if (maxValue <= 0.0 || maxValue.isNaN() || maxValue.isInfinite()) return listOf(0.0, 1.0)
        val rawStep = maxValue / desiredCount.coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(rawStep)))
        val residual = rawStep / magnitude
        val niceResidual = when {
            residual <= 1.0 -> 1.0
            residual <= 2.0 -> 2.0
            residual <= 2.5 -> 2.5
            residual <= 5.0 -> 5.0
            else -> 10.0
        }
        val step = (niceResidual * magnitude).coerceAtLeast(1.0)
        val top = ceil(maxValue / step) * step
        val count = (top / step).roundToLong().toInt()
        return (0..count).map { it * step }
    }

    /** Up to [desiredCount] evenly spread label positions, always including both ends. */
    fun xLabelIndices(count: Int, desiredCount: Int = 5): List<Int> {
        if (count <= 0) return emptyList()
        if (count <= desiredCount) return (0 until count).toList()
        val last = count - 1
        return (0 until desiredCount)
            .map { (it.toDouble() * last / (desiredCount - 1)).roundToLong().toInt() }
            .distinct()
    }

    /** Whether the series spans at most two days, so axis labels should show times. */
    fun isIntraday(series: List<VercelChartPoint>): Boolean {
        if (series.size < 2) return false
        return series.last().timeMillis - series.first().timeMillis <= 2 * 86_400_000L
    }

    /** Uniform Catmull-Rom segments through [xs]/[ys]; control points are clamped to [minY]..[maxY]. */
    fun catmullRomSegments(xs: List<Float>, ys: List<Float>, minY: Float, maxY: Float): List<VercelChartSegment> {
        require(xs.size == ys.size) { "Coordinate lists must have the same size." }
        if (xs.size < 2) return emptyList()
        val last = xs.lastIndex
        return (0 until last).map { index ->
            val previous = (index - 1).coerceAtLeast(0)
            val next = (index + 2).coerceAtMost(last)
            VercelChartSegment(
                control1X = xs[index] + (xs[index + 1] - xs[previous]) / 6f,
                control1Y = (ys[index] + (ys[index + 1] - ys[previous]) / 6f).coerceIn(minY, maxY),
                control2X = xs[index + 1] - (xs[next] - xs[index]) / 6f,
                control2Y = (ys[index + 1] - (ys[next] - ys[index]) / 6f).coerceIn(minY, maxY),
                endX = xs[index + 1],
                endY = ys[index + 1],
            )
        }
    }

    /** Whole numbers for counts, `42%` for bounce rate. */
    fun formatValue(value: Double, metric: VercelChartMetric): String {
        val rounded = value.roundToLong()
        return if (metric == VercelChartMetric.BOUNCE_RATE) "$rounded%" else rounded.toString()
    }

    /** Compact axis labels: `1.2K`, `3M`, `40%`. */
    fun formatAxisValue(value: Double, metric: VercelChartMetric): String =
        if (metric == VercelChartMetric.BOUNCE_RATE) {
            "${value.roundToLong()}%"
        } else {
            formatVercelMetric(value.roundToLong())
        }

    private inline fun parseOrNull(block: () -> Long): Long? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
