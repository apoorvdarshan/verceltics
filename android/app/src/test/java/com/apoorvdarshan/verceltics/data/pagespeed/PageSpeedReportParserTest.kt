package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.ui.pagespeed.CruxRating
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedAuditFilter
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedAuditSort
import com.apoorvdarshan.verceltics.ui.pagespeed.cruxBinLabel
import com.apoorvdarshan.verceltics.ui.pagespeed.cruxRating
import com.apoorvdarshan.verceltics.ui.pagespeed.formatCruxValue
import com.apoorvdarshan.verceltics.ui.pagespeed.visiblePageSpeedAudits
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSpeedReportParserTest {
    @Test
    fun lighthouseReportKeepsEveryCategoryAuditAndMetadataField() {
        val report = PageSpeedReportParser.lighthouse(
            PageSpeedReportParser.parseJson(LIGHTHOUSE.encodeToByteArray()),
            PageSpeedStrategy.DESKTOP,
        )

        assertEquals(PageSpeedStrategy.DESKTOP, report.strategy)
        assertEquals(listOf("performance", "seo"), report.categories.map { it.id })
        assertEquals(0.72, report.categories.first().score!!, 0.0)
        assertNull("Lighthouse may leave a category unscored", report.categories.last().score)
        assertEquals(listOf("largest-contentful-paint", "unused-javascript"), report.categories.first().auditIds)
        assertEquals(listOf("document-title", "largest-contentful-paint", "screenshot-thumbnails", "unused-javascript"), report.audits.map { it.id })

        val lcp = report.audits.first { it.id == "largest-contentful-paint" }
        assertEquals(1_850.5, lcp.numericValue!!, 0.0)
        assertEquals("1.9 s", lcp.displayValue)
        assertEquals(PageSpeedAuditOutcome.AVERAGE, lcp.outcome)
        assertEquals(listOf("performance"), lcp.categoryIds)

        val unused = report.audits.first { it.id == "unused-javascript" }
        assertEquals(PageSpeedAuditOutcome.FAILED, unused.outcome)
        assertEquals("opportunity", unused.detailsType)
        assertEquals(2, unused.detailsItemCount)
        assertEquals(PageSpeedAuditOutcome.PASSED, report.audits.first { it.id == "document-title" }.outcome)
        assertEquals(PageSpeedAuditOutcome.INFORMATIVE, report.audits.first { it.id == "screenshot-thumbnails" }.outcome)

        val metadata = report.metadata.associate { it.key to it.value }
        assertEquals("https://example.com/", metadata["finalUrl"])
        assertEquals("12.6.0", metadata["lighthouseVersion"])
        assertEquals("desktop", metadata["formFactor"])
        assertEquals("2026-10-09T10:15:00.000Z", metadata["fetchTime"])
        assertEquals(listOf("The page loaded too slowly."), report.runWarnings)
    }

    @Test
    fun missingLighthouseResultIsAFormatError() {
        assertThrows(PageSpeedResponseFormatException::class.java) {
            PageSpeedReportParser.lighthouse(PageSpeedReportParser.parseJson("{\"id\":\"x\"}".encodeToByteArray()), PageSpeedStrategy.MOBILE)
        }
        assertThrows(PageSpeedResponseFormatException::class.java) {
            PageSpeedReportParser.parseJson("not json".encodeToByteArray())
        }
    }

    @Test
    fun currentCruxRecordKeepsAllMetricsPercentilesDistributionsAndFractions() {
        val record = PageSpeedReportParser.cruxRecord(PageSpeedReportParser.parseJson(CRUX_CURRENT.encodeToByteArray()))

        assertEquals(LocalDate.of(2026, 9, 1), record.firstDate)
        assertEquals(LocalDate.of(2026, 9, 28), record.lastDate)
        assertEquals("https://example.com/", record.key.single().value)
        assertEquals(
            listOf("largest_contentful_paint", "cumulative_layout_shift", "form_factors", "navigation_types"),
            record.metrics.map { it.key },
        )
        val lcp = record.metrics.first()
        assertEquals("Largest Contentful Paint (LCP)", lcp.label)
        assertEquals(2_150.0, lcp.p75!!, 0.0)
        assertEquals(3, lcp.histogram.size)
        assertEquals(0.7, lcp.histogram.first().density!!, 0.0)
        assertNull(lcp.histogram.last().end)
        val cls = record.metrics[1]
        assertEquals(PageSpeedCruxUnit.UNITLESS, cls.unit)
        assertEquals(0.05, cls.p75!!, 0.0)
        assertEquals(0.1, cls.histogram.first().end!!, 0.0)
        val formFactors = record.metrics.first { it.key == "form_factors" }
        assertEquals(listOf("desktop" to 0.4, "phone" to 0.55, "tablet" to 0.05), formFactors.fractions)
        assertTrue(formFactors.histogram.isEmpty())
    }

    @Test
    fun cruxHistoryAlignsFortyPeriodsAndKeepsGapsAsNull() {
        val history = PageSpeedReportParser.cruxHistory(PageSpeedReportParser.parseJson(cruxHistoryJson().encodeToByteArray()))

        assertEquals(40, history.periods.size)
        assertEquals(LocalDate.of(2025, 12, 1), history.periods.first().firstDate)
        assertEquals(LocalDate.of(2025, 12, 1).plusWeeks(39).plusDays(27), history.periods.last().lastDate)
        val lcp = history.metrics.first { it.key == "largest_contentful_paint" }
        assertEquals(40, lcp.p75s.size)
        assertNull("NaN means the period had too few samples", lcp.p75s[3])
        assertEquals(1_000.0, lcp.p75s[0]!!, 0.0)
        assertEquals(1_039.0, lcp.p75s[39]!!, 0.0)
        assertEquals(3, lcp.histogram.size)
        assertNull(lcp.histogram.first().densities[3])
        val cls = history.metrics.first { it.key == "cumulative_layout_shift" }
        assertEquals(0.12, cls.p75s[1]!!, 1e-9)
        val formFactors = history.metrics.first { it.key == "form_factors" }
        assertEquals("phone", formFactors.fractions.first { it.first == "phone" }.first)
        assertEquals(40, formFactors.fractions.first().second.size)
    }

    @Test
    fun auditTableSearchesFiltersAndOrdersByImpact() {
        val audits = PageSpeedReportParser.lighthouse(
            PageSpeedReportParser.parseJson(LIGHTHOUSE.encodeToByteArray()),
            PageSpeedStrategy.MOBILE,
        ).audits

        assertEquals(
            listOf("unused-javascript", "largest-contentful-paint", "screenshot-thumbnails", "document-title"),
            visiblePageSpeedAudits(audits, "", PageSpeedAuditFilter.ALL, PageSpeedAuditSort.IMPACT).map { it.id },
        )
        assertEquals(
            listOf("unused-javascript"),
            visiblePageSpeedAudits(audits, "", PageSpeedAuditFilter.FAILED, PageSpeedAuditSort.IMPACT).map { it.id },
        )
        assertEquals(
            listOf("largest-contentful-paint"),
            visiblePageSpeedAudits(audits, "LARGEST", PageSpeedAuditFilter.ALL, PageSpeedAuditSort.TITLE).map { it.id },
        )
        assertEquals(
            listOf("unused-javascript"),
            visiblePageSpeedAudits(audits, "opportunity", PageSpeedAuditFilter.ALL, PageSpeedAuditSort.TITLE).map { it.id },
        )
    }

    @Test
    fun cruxFormattingAndRatingsUseCoreWebVitalThresholds() {
        assertEquals(CruxRating.GOOD, cruxRating("largest_contentful_paint", 2_500.0))
        assertEquals(CruxRating.NEEDS_IMPROVEMENT, cruxRating("interaction_to_next_paint", 300.0))
        assertEquals(CruxRating.POOR, cruxRating("cumulative_layout_shift", 0.3))
        assertNull(cruxRating("round_trip_time", 120.0))
        assertEquals("1.85 s", formatCruxValue(1_850.0, PageSpeedCruxUnit.MILLISECONDS, Locale.US))
        assertEquals("120 ms", formatCruxValue(120.0, PageSpeedCruxUnit.MILLISECONDS, Locale.US))
        assertEquals("0.05", formatCruxValue(0.05, PageSpeedCruxUnit.UNITLESS, Locale.US))
        assertTrue(cruxBinLabel(0.0, 2_500.0, PageSpeedCruxUnit.MILLISECONDS, 0, 3).startsWith("Good"))
        assertTrue(cruxBinLabel(4_000.0, null, PageSpeedCruxUnit.MILLISECONDS, 2, 3).startsWith("Poor · ≥"))
    }

    companion object {
        val LIGHTHOUSE = """
            {
              "id": "https://example.com/",
              "analysisUTCTimestamp": "2026-10-09T10:15:30.000Z",
              "lighthouseResult": {
                "requestedUrl": "https://example.com",
                "finalUrl": "https://example.com/",
                "finalDisplayedUrl": "https://example.com/",
                "fetchTime": "2026-10-09T10:15:00.000Z",
                "lighthouseVersion": "12.6.0",
                "userAgent": "Mozilla/5.0 Lighthouse",
                "runWarnings": ["The page loaded too slowly."],
                "configSettings": {"formFactor": "desktop", "locale": "en-US"},
                "environment": {"benchmarkIndex": 2150, "networkUserAgent": "Mozilla/5.0"},
                "timing": {"total": 12345.6},
                "categories": {
                  "performance": {
                    "id": "performance", "title": "Performance", "score": 0.72,
                    "auditRefs": [{"id": "largest-contentful-paint", "weight": 25}, {"id": "unused-javascript", "weight": 0}]
                  },
                  "seo": {"id": "seo", "title": "SEO", "score": null, "auditRefs": [{"id": "document-title"}]}
                },
                "audits": {
                  "largest-contentful-paint": {
                    "id": "largest-contentful-paint", "title": "Largest Contentful Paint",
                    "description": "LCP marks when the largest element is painted. [Learn more](https://web.dev/lcp/)",
                    "score": 0.62, "scoreDisplayMode": "numeric", "numericValue": 1850.5,
                    "numericUnit": "millisecond", "displayValue": "1.9 s"
                  },
                  "unused-javascript": {
                    "id": "unused-javascript", "title": "Reduce unused JavaScript", "score": 0.2,
                    "scoreDisplayMode": "metricSavings", "displayValue": "Est savings of 120 KiB",
                    "details": {"type": "opportunity", "items": [{"url": "a.js"}, {"url": "b.js"}]}
                  },
                  "document-title": {
                    "id": "document-title", "title": "Document has a <title> element", "score": 1,
                    "scoreDisplayMode": "binary"
                  },
                  "screenshot-thumbnails": {
                    "id": "screenshot-thumbnails", "title": "Screenshot Thumbnails", "score": null,
                    "scoreDisplayMode": "informative", "details": {"type": "filmstrip", "items": []}
                  }
                }
              }
            }
        """.trimIndent()

        val CRUX_CURRENT = """
            {
              "record": {
                "key": {"url": "https://example.com/"},
                "metrics": {
                  "navigation_types": {"fractions": {"navigate": 0.8, "reload": 0.2}},
                  "form_factors": {"fractions": {"desktop": 0.4, "phone": 0.55, "tablet": 0.05}},
                  "cumulative_layout_shift": {
                    "histogram": [
                      {"start": "0.00", "end": "0.10", "density": 0.9},
                      {"start": "0.10", "end": "0.25", "density": 0.06},
                      {"start": "0.25", "density": 0.04}
                    ],
                    "percentiles": {"p75": "0.05"}
                  },
                  "largest_contentful_paint": {
                    "histogram": [
                      {"start": 0, "end": 2500, "density": 0.7},
                      {"start": 2500, "end": 4000, "density": 0.2},
                      {"start": 4000, "density": 0.1}
                    ],
                    "percentiles": {"p75": 2150}
                  }
                },
                "collectionPeriod": {
                  "firstDate": {"year": 2026, "month": 9, "day": 1},
                  "lastDate": {"year": 2026, "month": 9, "day": 28}
                }
              }
            }
        """.trimIndent()

        fun cruxHistoryJson(): String {
            val starts = (0 until 40).map { LocalDate.of(2025, 12, 1).plusWeeks(it.toLong()) }
            val periods = starts.joinToString(",") { start ->
                val end = start.plusDays(27)
                """{"firstDate":{"year":${start.year},"month":${start.monthValue},"day":${start.dayOfMonth}},""" +
                    """"lastDate":{"year":${end.year},"month":${end.monthValue},"day":${end.dayOfMonth}}}"""
            }
            val p75s = (0 until 40).joinToString(",") { if (it == 3) "\"NaN\"" else "${1_000 + it}" }
            val densities = (0 until 40).joinToString(",") { if (it == 3) "\"NaN\"" else "0.8" }
            val cls = (0 until 40).joinToString(",") { if (it == 1) "\"0.12\"" else "\"0.05\"" }
            val phone = (0 until 40).joinToString(",") { "0.6" }
            return """
                {"record":{
                  "key":{"url":"https://example.com/"},
                  "metrics":{
                    "largest_contentful_paint":{
                      "histogramTimeseries":[
                        {"start":0,"end":2500,"densities":[$densities]},
                        {"start":2500,"end":4000,"densities":[$densities]},
                        {"start":4000,"densities":[$densities]}
                      ],
                      "percentilesTimeseries":{"p75s":[$p75s]}
                    },
                    "cumulative_layout_shift":{"percentilesTimeseries":{"p75s":[$cls]}},
                    "form_factors":{"fractionTimeseries":{"phone":{"fractions":[$phone]},"desktop":{"fractions":[$phone]}}}
                  },
                  "collectionPeriods":[$periods]
                }}
            """.trimIndent()
        }
    }
}
