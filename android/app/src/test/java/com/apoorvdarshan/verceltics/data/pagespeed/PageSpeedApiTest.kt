package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class PageSpeedApiTest {
    @Test
    fun completeSnapshotUsesMobileDesktopAndCruxAndWorstPerformanceStatus() {
        val transport = FakeTransport()
        val parser = FakeParser()
        val credentials = PageSpeedCredentials.create("secret-key", "https://Example.com/path")

        val result = PageSpeedApi(transport, parser, nowMillis = { 42L })
            .newSnapshotCall(credentials)
            .execute()

        assertTrue(result is PageSpeedFetchResult.Complete)
        val complete = result as PageSpeedFetchResult.Complete
        assertEquals(listOf(PageSpeedStrategy.MOBILE, PageSpeedStrategy.DESKTOP), transport.insights)
        assertEquals(1, transport.cruxCalls)
        assertEquals("Needs work", complete.snapshot.status)
        assertEquals(3, complete.snapshot.metrics.size)
        assertEquals(42L, complete.snapshot.fetchedAtMillis)
        assertEquals("example.com", complete.snapshot.siteName)
        assertEquals(emptyList<String>(), complete.snapshot.warnings)
    }

    @Test
    fun desktopAndCruxFailuresReturnTruthfulPartialSnapshot() {
        val transport = FakeTransport(
            desktopStatus = 503,
            cruxStatus = 404,
        )

        val result = PageSpeedApi(transport, FakeParser())
            .newSnapshotCall(PageSpeedCredentials.create("secret-key", "https://example.com"))
            .execute()

        assertTrue(result is PageSpeedFetchResult.Partial)
        val partial = result as PageSpeedFetchResult.Partial
        assertEquals(PageSpeedSourceState.UNAVAILABLE, partial.snapshot.availability.desktop)
        assertEquals(PageSpeedSourceState.UNAVAILABLE, partial.snapshot.availability.crux)
        assertEquals(2, partial.snapshot.warnings.size)
        assertFalse(partial.snapshot.warnings.joinToString().contains("secret-key"))
        assertEquals("Good", partial.snapshot.status)
    }

    @Test
    fun requiredMobileFailureReturnsFailureWithoutTryingOptionalSourcesOrLeakingBody() {
        val secret = "never-leak-this-google-key"
        val transport = FakeTransport(
            mobileStatus = 403,
            responseBody = secret.encodeToByteArray(),
        )

        val result = PageSpeedApi(transport, FakeParser())
            .newSnapshotCall(PageSpeedCredentials.create(secret, "https://example.com"))
            .execute()

        assertTrue(result is PageSpeedFetchResult.Failure)
        val failure = (result as PageSpeedFetchResult.Failure).failure
        assertEquals(PageSpeedFailureKind.AUTHENTICATION, failure.kind)
        assertEquals(403, failure.statusCode)
        assertEquals(listOf(PageSpeedStrategy.MOBILE), transport.insights)
        assertEquals(0, transport.cruxCalls)
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun liveAuditBuildsTheFullReportFromTheSameResponsesPlusCruxHistory() {
        val transport = FakeTransport(
            insightsBody = PageSpeedReportParserTest.LIGHTHOUSE.encodeToByteArray(),
            cruxBody = PageSpeedReportParserTest.CRUX_CURRENT.encodeToByteArray(),
            historyBody = PageSpeedReportParserTest.cruxHistoryJson().encodeToByteArray(),
        )

        val result = PageSpeedApi(transport, FakeParser(), nowMillis = { 42L })
            .newSnapshotCall(PageSpeedCredentials.create("secret-key", "https://example.com"))
            .execute() as PageSpeedFetchResult.Complete
        val report = checkNotNull(result.report)

        assertEquals(listOf(PageSpeedStrategy.MOBILE, PageSpeedStrategy.DESKTOP), transport.insights)
        assertEquals(1, transport.historyCalls)
        assertEquals(listOf(PageSpeedStrategy.MOBILE, PageSpeedStrategy.DESKTOP), report.strategies.map { it.strategy })
        assertEquals(4, report.strategy(PageSpeedStrategy.MOBILE)!!.audits.size)
        assertEquals(4, report.crux!!.metrics.size)
        assertEquals(40, report.cruxHistory!!.periods.size)
        assertEquals(
            setOf("pagespeed.mobile", "pagespeed.desktop", "crux.current", "crux.history"),
            report.rawResponses.keys,
        )
        assertTrue(report.warnings.isEmpty())
        assertEquals(42L, report.fetchedAtMillis)
        assertFalse(report.toString().contains("secret-key"))
    }

    @Test
    fun historyFailureOnlyWarnsInsideTheReportAndKeepsTheSummaryComplete() {
        val transport = FakeTransport(historyStatus = 404)

        val result = PageSpeedApi(transport, FakeParser())
            .newSnapshotCall(PageSpeedCredentials.create("secret-key", "https://example.com"))
            .execute()

        assertTrue(result is PageSpeedFetchResult.Complete)
        val report = checkNotNull((result as PageSpeedFetchResult.Complete).report)
        assertTrue(report.warnings.any { it.startsWith("Chrome UX history is unavailable") })
        assertTrue("Unreadable Lighthouse JSON is reported, not fatal", report.warnings.any { it.contains("Lighthouse report") })
        assertTrue(result.snapshot.warnings.isEmpty())
    }

    @Test
    fun summaryOnlyModeSkipsTheHistoryRequest() {
        val transport = FakeTransport()

        val result = PageSpeedApi(transport, FakeParser(), includeFullReport = false)
            .newSnapshotCall(PageSpeedCredentials.create("secret-key", "https://example.com"))
            .execute() as PageSpeedFetchResult.Complete

        assertEquals(0, transport.historyCalls)
        assertEquals(null, result.report)
    }

    @Test
    fun cancellationPropagatesAndCancelsActiveProviderCall() {
        val transport = FakeTransport()
        val call = PageSpeedApi(transport, FakeParser()).newSnapshotCall(
            PageSpeedCredentials.create("key", "https://example.com"),
        )

        call.cancel()

        assertThrows(CancellationException::class.java) { call.execute() }
    }

    private class FakeTransport(
        private val mobileStatus: Int = 200,
        private val desktopStatus: Int = 200,
        private val cruxStatus: Int = 200,
        private val responseBody: ByteArray = byteArrayOf(1),
        private val historyStatus: Int = 200,
        private val insightsBody: ByteArray? = null,
        private val cruxBody: ByteArray? = null,
        private val historyBody: ByteArray? = null,
    ) : PageSpeedHttpTransport {
        val insights = mutableListOf<PageSpeedStrategy>()
        var cruxCalls: Int = 0
        var historyCalls: Int = 0

        override fun newInsightsCall(
            credentials: PageSpeedCredentials,
            strategy: PageSpeedStrategy,
        ): CancelableCall<HttpResponse> {
            insights += strategy
            val status = if (strategy == PageSpeedStrategy.MOBILE) mobileStatus else desktopStatus
            return FixedCall(HttpResponse(status, insightsBody?.copyOf() ?: responseBody.copyOf(), emptyMap()))
        }

        override fun newCruxCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> {
            cruxCalls += 1
            return FixedCall(HttpResponse(cruxStatus, cruxBody?.copyOf() ?: responseBody.copyOf(), emptyMap()))
        }

        override fun newCruxHistoryCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> {
            historyCalls += 1
            return FixedCall(HttpResponse(historyStatus, historyBody?.copyOf() ?: responseBody.copyOf(), emptyMap()))
        }
    }

    private class FixedCall(private val response: HttpResponse) : CancelableCall<HttpResponse> {
        override fun execute(): HttpResponse = response

        override fun cancel() = Unit
    }

    private class FakeParser : PageSpeedJsonParser {
        override fun parseInsights(
            bytes: ByteArray,
            strategy: PageSpeedStrategy,
        ): List<PageSpeedMetric> = listOf(
            PageSpeedMetric(
                key = "pagespeed.${strategy.wireValue}.performance",
                label = "${strategy.label} Performance",
                value = if (strategy == PageSpeedStrategy.MOBILE) 96.0 else 72.0,
                unit = PageSpeedMetricUnit.SCORE,
            ),
        )

        override fun parseCrux(bytes: ByteArray): List<PageSpeedMetric> = listOf(
            PageSpeedMetric(
                key = "crux.largest_contentful_paint",
                label = "LCP (Page field p75)",
                value = 1_850.0,
                unit = PageSpeedMetricUnit.MILLISECONDS,
            ),
        )
    }
}
