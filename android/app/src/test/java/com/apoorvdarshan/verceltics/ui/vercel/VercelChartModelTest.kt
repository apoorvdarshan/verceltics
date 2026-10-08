package com.apoorvdarshan.verceltics.ui.vercel

import com.apoorvdarshan.verceltics.ui.VercelAnalyticsPointUi
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelChartModelTest {
    @Test
    fun keysParseAsInstantsOffsetsOrUtcDates() {
        assertEquals(Instant.parse("2026-08-26T00:00:00Z").toEpochMilli(), VercelChartModel.parseKey("2026-08-26T00:00:00Z"))
        assertEquals(Instant.parse("2026-08-26T10:15:30.250Z").toEpochMilli(), VercelChartModel.parseKey("2026-08-26T10:15:30.250Z"))
        assertEquals(Instant.parse("2026-08-26T08:00:00Z").toEpochMilli(), VercelChartModel.parseKey("2026-08-26T10:00:00+02:00"))
        assertEquals(Instant.parse("2026-08-26T00:00:00Z").toEpochMilli(), VercelChartModel.parseKey("2026-08-26"))
        assertNull(VercelChartModel.parseKey("yesterday"))
        assertNull(VercelChartModel.parseKey(""))
    }

    @Test
    fun bounceRateIsOfferedOnlyWhenReported() {
        assertEquals(
            listOf(VercelChartMetric.VISITORS, VercelChartMetric.PAGE_VIEWS),
            VercelChartModel.availableMetrics(listOf(point("2026-08-26", 10, 5))),
        )
        assertEquals(
            VercelChartMetric.entries,
            VercelChartModel.availableMetrics(listOf(point("2026-08-26", 10, 5), point("2026-08-27", 10, 5, bounce = 40.0))),
        )
    }

    @Test
    fun seriesPicksTheMetricSortsAndSkipsUnparseableOrMissingValues() {
        val points = listOf(
            point("2026-08-27", pageViews = 20, visitors = 8, bounce = 50.0),
            point("not-a-date", pageViews = 99, visitors = 99),
            point("2026-08-26", pageViews = 10, visitors = 4),
        )

        assertEquals(listOf(4.0, 8.0), VercelChartModel.series(points, VercelChartMetric.VISITORS, ZoneOffset.UTC).map { it.value })
        assertEquals(listOf(10.0, 20.0), VercelChartModel.series(points, VercelChartMetric.PAGE_VIEWS, ZoneOffset.UTC).map { it.value })
        assertEquals(listOf(50.0), VercelChartModel.series(points, VercelChartMetric.BOUNCE_RATE, ZoneOffset.UTC).map { it.value })
    }

    @Test
    fun hourlySeriesLongerThanFortyEightPointsRollUpIntoDays() {
        val start = Instant.parse("2026-08-24T00:00:00Z")
        val hourly = (0 until 72).map { hour ->
            point(start.plusSeconds(hour * 3_600L).toString(), pageViews = 2, visitors = 1, bounce = if (hour < 24) 40.0 else 60.0)
        }

        val visitors = VercelChartModel.series(hourly, VercelChartMetric.VISITORS, ZoneOffset.UTC)
        val bounce = VercelChartModel.series(hourly, VercelChartMetric.BOUNCE_RATE, ZoneOffset.UTC)

        assertEquals(3, visitors.size)
        assertEquals(listOf(24.0, 24.0, 24.0), visitors.map { it.value })
        assertEquals(start.toEpochMilli(), visitors.first().timeMillis)
        assertEquals("Bounce rate is averaged per day.", listOf(40.0, 60.0, 60.0), bounce.map { it.value })

        val fortyEight = hourly.take(48)
        assertEquals("Up to 48 points stay hourly.", 48, VercelChartModel.series(fortyEight, VercelChartMetric.VISITORS, ZoneOffset.UTC).size)
    }

    @Test
    fun dailyRollupUsesTheLocalCalendarDay() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val points = listOf(
            VercelChartPoint(Instant.parse("2026-08-24T14:00:00Z").toEpochMilli(), 1.0), // Aug 24 23:00 JST
            VercelChartPoint(Instant.parse("2026-08-24T16:00:00Z").toEpochMilli(), 2.0), // Aug 25 01:00 JST
        )

        val days = VercelChartModel.aggregateDaily(points, VercelChartMetric.VISITORS, tokyo)

        assertEquals(2, days.size)
        assertEquals(Instant.parse("2026-08-24T15:00:00Z").toEpochMilli(), days[1].timeMillis)
    }

    @Test
    fun headlineAveragePeakAndScrubbing() {
        val series = listOf(
            VercelChartPoint(0L, 10.0),
            VercelChartPoint(1_000L, 30.0),
            VercelChartPoint(2_000L, 30.0),
            VercelChartPoint(4_000L, 10.0),
        )

        assertEquals(80.0, VercelChartModel.headline(series, VercelChartMetric.VISITORS), 1e-9)
        assertEquals(20.0, VercelChartModel.headline(series, VercelChartMetric.BOUNCE_RATE), 1e-9)
        assertEquals(20.0, VercelChartModel.average(series), 1e-9)
        assertEquals("The first highest point is the peak.", 1_000L, VercelChartModel.peak(series)?.timeMillis)
        assertEquals(0.0, VercelChartModel.headline(emptyList(), VercelChartMetric.VISITORS), 1e-9)
        assertNull(VercelChartModel.peak(emptyList()))

        assertEquals(0, VercelChartModel.nearestIndex(series, -0.5f))
        assertEquals(1, VercelChartModel.nearestIndex(series, 0.24f))
        assertEquals(2, VercelChartModel.nearestIndex(series, 0.6f))
        assertEquals(3, VercelChartModel.nearestIndex(series, 2f))
        assertNull(VercelChartModel.nearestIndex(emptyList(), 0.5f))
        assertEquals(0.5f, VercelChartModel.xFraction(series, 2), 1e-6f)
        assertEquals(0.5f, VercelChartModel.xFraction(series.take(1), 0), 1e-6f)
    }

    @Test
    fun axisTicksAreNiceWholeNumbers() {
        assertEquals(listOf(0.0, 1.0), VercelChartModel.yTicks(0.0))
        assertEquals(listOf(0.0, 1.0), VercelChartModel.yTicks(1.0))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), VercelChartModel.yTicks(3.0))
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), VercelChartModel.yTicks(97.0))
        assertEquals(listOf(0.0, 500.0, 1_000.0, 1_500.0), VercelChartModel.yTicks(1_234.0))
        VercelChartModel.yTicks(12_806.0).let { ticks ->
            assertTrue(ticks.last() >= 12_806.0)
            assertTrue(ticks.size in 3..6)
        }

        assertEquals(listOf(0, 1, 2), VercelChartModel.xLabelIndices(3))
        assertEquals(listOf(0, 7, 15, 22, 29), VercelChartModel.xLabelIndices(30))
        assertTrue(VercelChartModel.xLabelIndices(0).isEmpty())
    }

    @Test
    fun intradayDetectionDrivesTimeLabels() {
        val hour = 3_600_000L
        assertTrue(VercelChartModel.isIntraday(listOf(VercelChartPoint(0L, 1.0), VercelChartPoint(23 * hour, 1.0))))
        assertTrue(!VercelChartModel.isIntraday(listOf(VercelChartPoint(0L, 1.0), VercelChartPoint(7 * 24 * hour, 1.0))))
        assertTrue(!VercelChartModel.isIntraday(listOf(VercelChartPoint(0L, 1.0))))
    }

    @Test
    fun catmullRomSegmentsPassThroughEveryPointAndStayInsideThePlot() {
        val xs = listOf(0f, 10f, 20f, 30f)
        val ys = listOf(100f, 0f, 100f, 0f)

        val segments = VercelChartModel.catmullRomSegments(xs, ys, minY = 0f, maxY = 100f)

        assertEquals(3, segments.size)
        assertEquals(listOf(10f, 20f, 30f), segments.map { it.endX })
        assertEquals(listOf(0f, 100f, 0f), segments.map { it.endY })
        segments.forEach {
            assertTrue(it.control1Y in 0f..100f)
            assertTrue(it.control2Y in 0f..100f)
        }
        assertTrue(VercelChartModel.catmullRomSegments(listOf(1f), listOf(1f), 0f, 1f).isEmpty())
    }

    @Test
    fun valuesFormatAsCountsOrPercentages() {
        assertEquals("12806", VercelChartModel.formatValue(12_806.4, VercelChartMetric.PAGE_VIEWS))
        assertEquals("42%", VercelChartModel.formatValue(41.5, VercelChartMetric.BOUNCE_RATE))
        assertEquals("1.5K", VercelChartModel.formatAxisValue(1_500.0, VercelChartMetric.VISITORS))
        assertEquals("25%", VercelChartModel.formatAxisValue(25.0, VercelChartMetric.BOUNCE_RATE))
    }

    private fun point(key: String, pageViews: Long, visitors: Long, bounce: Double? = null) =
        VercelAnalyticsPointUi(key, pageViews, visitors, bounce)
}
