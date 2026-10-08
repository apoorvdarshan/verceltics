package com.apoorvdarshan.verceltics.data.pagespeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

class PageSpeedHttpTransportTest {
    @Test
    fun preparesOnlyTheTwoExactGoogleOriginsAndCanonicalPaths() {
        val transport = SecurePageSpeedHttpTransport()
        val credentials = PageSpeedCredentials.create("api key/secret", "https://Example.com/a%20b?q=x")

        val insights = transport.prepareInsightsUri(credentials, PageSpeedStrategy.MOBILE)
        val crux = transport.prepareCruxUri(credentials)

        assertEquals("https", insights.scheme)
        assertEquals("www.googleapis.com", insights.host)
        assertEquals("/pagespeedonline/v5/runPagespeed", insights.path)
        assertEquals("chromeuxreport.googleapis.com", crux.host)
        assertEquals("/v1/records:queryRecord", crux.path)
        assertEquals(443, effectivePort(insights))
        assertEquals(443, effectivePort(crux))
        assertEquals(4, insights.rawQuery.split('&').count { it.startsWith("category=") })
        assertTrueQueryContains(insights.rawQuery, "strategy=mobile")
        assertTrueQueryContains(insights.rawQuery, "key=api%20key%2Fsecret")
        assertTrueQueryContains(crux.rawQuery, "key=api%20key%2Fsecret")
    }

    @Test
    fun cruxBodyContainsOnlyTheNormalizedSiteUrl() {
        val site = PageSpeedCredentials.normalizeSiteUrl("https://EXAMPLE.com/path?q=1#ignored")
        val body = SecurePageSpeedHttpTransport.jsonUrlBody(site)
        val text = try {
            String(body, StandardCharsets.UTF_8)
        } finally {
            body.fill(0)
        }

        assertEquals("{\"url\":\"https://example.com/path?q=1\"}", text)
        assertFalse(text.contains("key"))
    }

    @Test
    fun cruxHistoryUsesTheSamePinnedOriginAndAsksForFortyPeriods() {
        val transport = SecurePageSpeedHttpTransport()
        val credentials = PageSpeedCredentials.create("api key/secret", "https://example.com/")

        val history = transport.prepareCruxHistoryUri(credentials)
        val body = SecurePageSpeedHttpTransport.jsonUrlBody(credentials.siteUrl, PageSpeedReportParser.HISTORY_PERIOD_COUNT)
        val text = try {
            String(body, StandardCharsets.UTF_8)
        } finally {
            body.fill(0)
        }

        assertEquals("https", history.scheme)
        assertEquals("chromeuxreport.googleapis.com", history.host)
        assertEquals("/v1/records:queryHistoryRecord", history.path)
        assertEquals(443, effectivePort(history))
        assertTrueQueryContains(history.rawQuery, "key=api%20key%2Fsecret")
        assertEquals("{\"url\":\"https://example.com/\",\"collectionPeriodCount\":40}", text)
        assertFalse(text.contains("secret"))
    }

    private fun assertTrueQueryContains(query: String?, expected: String) {
        checkNotNull(query)
        assertEquals(true, query.split('&').contains(expected))
    }

    private fun effectivePort(uri: java.net.URI): Int = if (uri.port < 0) 443 else uri.port
}
