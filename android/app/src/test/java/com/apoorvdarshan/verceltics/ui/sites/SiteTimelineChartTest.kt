package com.apoorvdarshan.verceltics.ui.sites

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteTimelineChartTest {
    @Test
    fun countsStartAtZeroAndLandOnRoundTicks() {
        val scale = chartAxisScale(listOf(12.0, 87.0, 143.0))

        assertEquals(0.0, scale.minimum, 0.0)
        assertTrue(scale.maximum >= 143.0)
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), scale.ticks)
        assertEquals(0f, scale.fraction(0.0), 0f)
        assertEquals(1f, scale.fraction(150.0), 0f)
    }

    @Test
    fun rankedMetricsCanScaleToTheirOwnRange() {
        val scale = chartAxisScale(listOf(8.2, 9.1, 11.7), includeZero = false)

        assertTrue(scale.minimum <= 8.2 && scale.minimum > 0.0)
        assertTrue(scale.maximum >= 11.7)
        assertTrue(scale.ticks.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun decimalTicksAvoidFloatingPointNoise() {
        val scale = chartAxisScale(listOf(0.01, 0.034))

        scale.ticks.forEach { tick ->
            val text = tick.toString()
            assertTrue("$text should not carry binary noise", text.length <= 6)
        }
        assertTrue(scale.maximum >= 0.034)
    }

    @Test
    fun flatAndEmptySeriesStillGetAVisibleDomain() {
        val flat = chartAxisScale(listOf(5.0, 5.0), includeZero = false)
        assertTrue(flat.maximum > flat.minimum)
        assertTrue(flat.minimum <= 5.0 && flat.maximum >= 5.0)

        val zeros = chartAxisScale(listOf(0.0, 0.0))
        assertEquals(0.0, zeros.minimum, 0.0)
        assertTrue(zeros.maximum > 0.0)

        val empty = chartAxisScale(emptyList())
        assertEquals(listOf(0.0, 0.5, 1.0), empty.ticks)

        val nonFinite = chartAxisScale(listOf(Double.NaN, 3.0, Double.POSITIVE_INFINITY))
        assertTrue(nonFinite.maximum >= 3.0)
    }

    @Test
    fun negativeValuesKeepZeroInsideTheDomain() {
        val scale = chartAxisScale(listOf(-40.0, 25.0))

        assertTrue(scale.minimum <= -40.0)
        assertTrue(scale.maximum >= 25.0)
        assertTrue(0.0 in scale.ticks)
    }

    @Test
    fun touchPositionsSnapToTheNearestPoint() {
        assertEquals(0, chartIndexForPosition(0f, 300f, 4))
        assertEquals(1, chartIndexForPosition(110f, 300f, 4))
        assertEquals(3, chartIndexForPosition(400f, 300f, 4))
        assertEquals(0, chartIndexForPosition(-20f, 300f, 4))
        assertEquals(0, chartIndexForPosition(150f, 300f, 1))
        assertNull(chartIndexForPosition(10f, 300f, 0))
        assertNull(chartIndexForPosition(10f, 0f, 4))
    }

    @Test
    fun axisLabelsAreCompact() {
        assertEquals("0", formatCompactAxisValue(0.0, Locale.US))
        assertEquals("1.5K", formatCompactAxisValue(1_500.0, Locale.US))
        assertEquals("2M", formatCompactAxisValue(2_000_000.0, Locale.US))
        assertEquals("0.25", formatCompactAxisValue(0.25, Locale.US))
        assertEquals("12.5", formatCompactAxisValue(12.5, Locale.US))
        assertEquals("—", formatCompactAxisValue(Double.NaN, Locale.US))
    }
}
