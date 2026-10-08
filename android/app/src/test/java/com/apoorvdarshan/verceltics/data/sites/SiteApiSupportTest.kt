package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteApiSupportTest {
    @Test
    fun httpsBaseUrlValidationMatchesIos() {
        assertThrows(SiteServiceException::class.java) { SiteApiSupport.normalizedHttpsBaseUrl("http://analytics.example.com") }
        assertThrows(SiteServiceException::class.java) {
            SiteApiSupport.normalizedHttpsBaseUrl("https://user:password@analytics.example.com")
        }
        assertThrows(SiteServiceException::class.java) { SiteApiSupport.normalizedHttpsBaseUrl("analytics.example.com") }
        assertEquals(
            "https://analytics.example.com/root",
            SiteApiSupport.normalizedHttpsBaseUrl("https://analytics.example.com/root?token=secret#fragment").toString(),
        )
        val normalized = SiteApiSupport.normalizedHttpsBaseUrl("  HTTPS://Analytics.Example.COM/Team/CaseSensitive?token=secret#fragment  ")
        assertEquals("https", normalized.scheme)
        assertEquals("analytics.example.com", normalized.host)
        assertEquals("/Team/CaseSensitive", normalized.path)
        assertNull(normalized.query)
        assertNull(normalized.fragment)
    }

    @Test
    fun endpointIdentityCanonicalizesDefaultPortAndTrailingSlash() {
        assertEquals(
            "https://api.umami.is/v1/",
            SiteApiSupport.canonicalEndpointIdentity("https://API.UMAMI.IS:443/v1///?token=ignored#fragment"),
        )
        assertEquals(
            "https://analytics.example.com:8443/Team/API/",
            SiteApiSupport.canonicalEndpointIdentity("https://analytics.example.com:8443/Team/API"),
        )
        assertEquals("https://example.com/", SiteApiSupport.canonicalEndpointIdentity("https://example.com/"))
        assertNull(SiteApiSupport.canonicalEndpointIdentity("http://api.umami.is/v1"))
        assertNull(SiteApiSupport.canonicalEndpointIdentity("https://user:secret@api.umami.is/v1"))
    }

    @Test
    fun originDropsPathQueryAndCredentials() {
        assertEquals("https://example.com:8443", SiteApiSupport.originUrl("https://user:password@example.com:8443/path?q=1#part"))
        assertEquals("https://example.com", SiteApiSupport.originUrl("https://EXAMPLE.com/a/b"))
        assertNull(SiteApiSupport.originUrl("http://example.com/"))
    }

    @Test
    fun resourcePathComponentEncodesSeparatorsAndKeepsCase() {
        assertEquals("site%2Fid%20%3F%23", SiteApiSupport.pathComponent("site/id ?#"))
        assertEquals("https%3A%2F%2Fexample.com%2F", SiteApiSupport.pathComponent("https://example.com/"))
        assertEquals("Site%2FProduction", SiteApiSupport.pathComponent("Site/Production"))
        assertEquals("caf%C3%A9", SiteApiSupport.pathComponent("café"))
        assertThrows(SiteServiceException::class.java) { SiteApiSupport.pathComponent("") }
    }

    @Test
    fun endpointResolutionStaysOnTheConfiguredOrigin() {
        val base = URI("https://analytics.example.com/api/")
        assertEquals(
            "https://analytics.example.com/api/websites/abc/stats?startAt=1&endAt=2",
            SiteApiSupport.endpoint(base, "websites/abc/stats", listOf("startAt" to "1", "endAt" to "2")).toString(),
        )
        assertEquals(
            "https://uptime.betterstack.com/api/v3/incidents?monitor_id=a%20b",
            SiteApiSupport.endpoint(URI("https://uptime.betterstack.com/"), "api/v3/incidents", listOf("monitor_id" to "a b")).toString(),
        )
        assertThrows(SiteServiceException::class.java) { SiteApiSupport.endpoint(URI("http://example.com/"), "x") }
    }

    @Test
    fun safeIntegerRejectsNonFiniteAndOutOfRangeValues() {
        assertNull(SiteApiSupport.safeInteger(Double.NaN))
        assertNull(SiteApiSupport.safeInteger(Double.POSITIVE_INFINITY))
        assertNull(SiteApiSupport.safeInteger(Double.NEGATIVE_INFINITY))
        assertNull(SiteApiSupport.safeInteger(Double.MAX_VALUE))
        assertEquals(42L, SiteApiSupport.safeInteger(42.9))
        assertEquals(-42L, SiteApiSupport.safeInteger(-42.9))
    }

    @Test
    fun detailBatchesCoverEveryResource() {
        val batches = SiteApiSupport.detailIndexBatches(77, 4)
        assertEquals((0 until 77).toList(), batches.flatten())
        assertTrue(batches.all { it.isNotEmpty() && it.size <= 4 })
        assertEquals(20, batches.size)
        assertTrue(SiteApiSupport.detailIndexBatches(0).isEmpty())
    }

    @Test
    fun boundedConcurrentMapKeepsOrderAndLimitsConcurrency() = runTest {
        var active = 0
        var peak = 0
        val result = SiteApiSupport.boundedConcurrentMap((1..10).toList(), maximumConcurrent = 3) { value ->
            active += 1
            peak = maxOf(peak, active)
            delay((11 - value) * 10L)
            active -= 1
            value * 2
        }
        assertEquals((1..10).map { it * 2 }, result)
        assertEquals(3, peak)
    }

    @Test
    fun stableResourceIdLowercasesSchemeAndHostOnly() {
        assertEquals("https://example.com/CaseSensitive", SiteApiSupport.stableSiteResourceId(" HTTPS://Example.COM/CaseSensitive#frag "))
        assertEquals("https://example.com", SiteApiSupport.stableSiteResourceId("Example.com"))
        assertEquals("sc-domain:example.com", SiteApiSupport.stableSiteResourceId("sc-domain:EXAMPLE.com"))
    }

    @Test
    fun datesAcceptSecondsMillisecondsIsoAndWcfFormats() {
        assertEquals(1_700_000_000_000L, SiteApiSupport.dateMillis(ProviderJsonValue.Num.of(1_700_000_000L)))
        assertEquals(1_700_000_000_123L, SiteApiSupport.dateMillis(ProviderJsonValue.Num.of(1_700_000_000_123L)))
        assertEquals(1_784_073_600_000L, SiteApiSupport.dateMillis(ProviderJsonValue.Str("2026-07-15T00:00:00Z")))
        assertEquals(1_784_073_600_500L, SiteApiSupport.dateMillis(ProviderJsonValue.Str("2026-07-15T05:30:00.500+05:30")))
        assertNull(SiteApiSupport.dateMillis(ProviderJsonValue.Str("yesterday")))
        assertEquals(1_700_000_000_000L, BingWebmasterSiteAdapter.bingDate(ProviderJsonValue.Str("/Date(1700000000000-0800)/")))
        assertEquals("2023-11-14T22:13:20Z", BingWebmasterSiteAdapter.normalizedBingDate("/Date(1700000000000)/"))
        assertEquals("2023-11-14T22:13:20Z", BingWebmasterSiteAdapter.normalizedBingDate("/Date(1700000000000-0800)/"))
        assertEquals("2026-01-01", BingWebmasterSiteAdapter.normalizedBingDate("2026-01-01"))
    }

    @Test
    fun labelsSlugsAndUnitsFollowIosRules() {
        assertEquals("Total Session Count", SiteApiSupport.humanizedSnapshotLabel("totalSessionCount"))
        assertEquals("Pages Per Session", SiteApiSupport.humanizedSnapshotLabel("pages_per_session"))
        assertEquals("scroll-depth", SiteApiSupport.slug("Scroll Depth!"))
        assertEquals(SiteMetricUnit.RATIO, SiteApiSupport.inferredUnit("PagesPerSession"))
        assertEquals(SiteMetricUnit.PERCENT, SiteApiSupport.inferredUnit("averageScrollDepth"))
        assertEquals(SiteMetricUnit.MILLISECONDS, SiteApiSupport.inferredUnit("loadMs"))
        assertEquals(SiteMetricUnit.SECONDS, SiteApiSupport.inferredUnit("totalTime"))
        assertEquals(SiteMetricUnit.COUNT, SiteApiSupport.inferredUnit("sessionsCount"))
        assertEquals("Average Session Duration", SiteDetailSupport.humanized("metrics.averageSessionDuration"))
        assertEquals("Bounce Rate", SiteDetailSupport.humanized("bounce_rate"))
    }

    @Test
    fun partialMetricsAreLabelledExceptCompleteCounts() {
        val metrics = listOf(
            SiteApiSupport.metric("bing.sites", "Sites", 2.0, SiteMetricUnit.COUNT),
            SiteApiSupport.metric("bing.clicks", "Clicks", 5.0, SiteMetricUnit.COUNT),
        )
        assertEquals(metrics, SiteApiSupport.markPartialMetrics(metrics, isPartial = false))
        val partial = SiteApiSupport.markPartialMetrics(metrics, isPartial = true, excluding = setOf("bing.sites"))
        assertEquals(listOf("Sites", "Clicks · Partial"), partial.map { it.label })
    }

    @Test
    fun umamiBaseUrlsNormalizeCloudAndSelfHostedModes() {
        assertEquals("https://api.umami.is/v1/", UmamiSiteAdapter.apiBaseUrl(emptyMap()).toString())
        assertEquals(
            "https://analytics.example.com/api/",
            UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "selfHosted", "baseURL" to "https://Analytics.Example.com/")).toString(),
        )
        assertEquals(
            "https://analytics.example.com:8443/team/api/",
            UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "selfHosted", "baseURL" to "https://analytics.example.com:8443/team/api/")).toString(),
        )
        assertThrows(SiteServiceException::class.java) {
            UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "selfHosted", "baseURL" to "https://api.umami.is/v1"))
        }
        assertThrows(SiteServiceException::class.java) {
            UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "selfHosted", "baseURL" to "http://analytics.example.com"))
        }
        assertThrows(SiteServiceException::class.java) { UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "other")) }
        assertThrows(SiteServiceException::class.java) { UmamiSiteAdapter.apiBaseUrl(mapOf("authMode" to "selfHosted")) }
    }

    @Test
    fun umamiIdentityUsesStableUserAndEndpointWithoutSecrets() {
        val metadata = UmamiSiteAdapter.connectionMetadata(
            ProviderJsonParser.parse(
                """{"token":"session-value","authKey":"auth-value","user":{"id":"user-123","username":"operator"}}""",
            ),
            URI("https://API.UMAMI.IS:443/v1/"),
        )
        assertEquals("user-123", metadata["umamiUserID"])
        assertEquals("operator", metadata["umamiUsername"])
        assertEquals("https://api.umami.is/v1/", metadata["umamiEndpoint"])
        assertFalse(metadata.values.any { it.contains("session-value") || it.contains("auth-value") })
        val error = runCatching {
            UmamiSiteAdapter.connectionMetadata(ProviderJsonParser.parse("""{"user":{"username":"missing-id"}}"""), URI("https://api.umami.is/v1/"))
        }.exceptionOrNull()
        assertTrue(error?.message!!.contains("stable account identity"))
    }

    @Test
    fun uptimeRobotPaginationDistinguishesRepeatsFromCompletion() {
        assertEquals(UptimeRobotPaginationAction.NO_PROGRESS, UptimeRobotSiteAdapter.paginationAction(50, 0, 50, 100, 50))
        assertEquals(UptimeRobotPaginationAction.NO_PROGRESS, UptimeRobotSiteAdapter.paginationAction(10, 0, 50, null, 50))
        assertEquals(UptimeRobotPaginationAction.LOAD_NEXT_PAGE, UptimeRobotSiteAdapter.paginationAction(50, 10, 60, 100, 50))
        assertEquals(UptimeRobotPaginationAction.COMPLETE, UptimeRobotSiteAdapter.paginationAction(50, 50, 100, 100, 50))
        assertEquals(UptimeRobotPaginationAction.COMPLETE, UptimeRobotSiteAdapter.paginationAction(0, 0, 0, 0, 50))
        assertEquals(UptimeRobotPaginationAction.COMPLETE, UptimeRobotSiteAdapter.paginationAction(10, 10, 10, null, 50))
    }

    @Test
    fun ga4SingleWebStreamUsesItsWebsiteAsIdentity() {
        val attribution = GoogleAnalyticsSiteAdapter.streamAttribution(
            listOf(GoogleAnalyticsDataStream("properties/123/dataStreams/456", "WEB_DATA_STREAM", "Production", "G-EXAMPLE", "https://example.com")),
            "Example Account",
        )
        assertEquals("https://example.com", attribution.url)
        assertEquals("https://example.com", attribution.subtitle)
        assertFalse(attribution.isPropertyWide)
        assertEquals("G-EXAMPLE", attribution.metadata["measurementID"])
        assertEquals("Single data stream", attribution.metadata["metricsScope"])
    }

    @Test
    fun ga4MultipleOrMixedStreamsStayPropertyWide() {
        val webOnly = GoogleAnalyticsSiteAdapter.streamAttribution(
            listOf(
                GoogleAnalyticsDataStream("properties/123/dataStreams/one", "WEB_DATA_STREAM", "Main", "G-ONE", "https://one.example"),
                GoogleAnalyticsDataStream("properties/123/dataStreams/two", "WEB_DATA_STREAM", "Docs", "G-TWO", "https://docs.example"),
            ),
            "Example Account",
        )
        assertNull(webOnly.url)
        assertEquals("2 web streams · Example Account", webOnly.subtitle)
        assertTrue(webOnly.isPropertyWide)
        assertEquals("G-ONE, G-TWO", webOnly.metadata["measurementIDs"])

        val mixed = GoogleAnalyticsSiteAdapter.streamAttribution(
            listOf(
                GoogleAnalyticsDataStream("properties/123/dataStreams/web", "WEB_DATA_STREAM", "Website", "G-WEB", "https://example.com"),
                GoogleAnalyticsDataStream("properties/123/dataStreams/ios", "IOS_APP_DATA_STREAM", "iOS", null, null),
            ),
            "Example Account",
        )
        assertNull(mixed.url)
        assertEquals("2 data streams · Example Account", mixed.subtitle)
        assertEquals("2", mixed.metadata["dataStreamCount"])
        assertEquals("Example Account", GoogleAnalyticsSiteAdapter.streamAttribution(emptyList(), "Example Account").subtitle)
    }

    @Test
    fun detailRangePreservesTheSelectedCalendarDayInTheUserZone() {
        val india = ZoneId.of("Asia/Kolkata")
        val selected = LocalDate.of(2026, 7, 16).atTime(0, 15).atZone(india).toInstant().toEpochMilli()
        assertEquals("2026-07-16", SiteDetailRange.dateString(selected, india))

        val range = SiteDetailRange.lastDays(7, TEST_NOW_MILLIS, TEST_ZONE)
        assertEquals("2026-07-09", range.startDate)
        assertEquals("2026-07-15", range.endDate)
        assertEquals(7, range.dayCount)
        val custom = SiteDetailRange.custom(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 1), TEST_NOW_MILLIS, TEST_ZONE)
        assertEquals("2026-07-01", custom.startDate)
        assertEquals("2026-07-15", custom.endDate)
        assertEquals(TEST_NOW_MILLIS, custom.endMillis)
    }

    @Test
    fun cacheLifetimesMatchIosPolicy() {
        assertEquals(15 * 60_000L, SiteProvider.GOOGLE_ANALYTICS.snapshotCacheLifetimeMillis)
        assertEquals(15 * 60_000L, SiteProvider.UPTIME_ROBOT.snapshotCacheLifetimeMillis)
        assertEquals(6 * 60 * 60_000L, SiteProvider.CLARITY.snapshotCacheLifetimeMillis)
        assertEquals(2 * 60_000L, SiteProvider.BETTER_STACK.detailCacheLifetimeMillis)
        assertEquals(setOf("googleAnalytics", "bingWebmaster", "clarity", "plausible", "umami", "uptimeRobot", "betterStack"), SiteProvider.ids)
        assertEquals(GoogleAnalyticsScopesExpected, SiteProvider.GOOGLE_ANALYTICS.oauthScopes)
        assertTrue(SiteProvider.PLAUSIBLE.oauthScopes.isEmpty())
    }

    private companion object {
        val GoogleAnalyticsScopesExpected = setOf("openid", "email", "https://www.googleapis.com/auth/analytics.readonly")
    }
}
