package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAnalyticsSiteAdapterTest {
    private val token = SecretValue.of("oauth-token")

    @Test
    fun snapshotPaginatesSummariesAttributesStreamsAndAggregates() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("oauth-token", request.bearer)
            when {
                request.path == "/v1beta/accountSummaries" && request.query["pageToken"] == null -> ok(
                    json(
                        "accountSummaries" to listOf(
                            mapOf(
                                "account" to "accounts/1",
                                "displayName" to "Studio",
                                "propertySummaries" to listOf(
                                    mapOf("property" to "properties/111", "displayName" to "Website", "propertyType" to "PROPERTY_TYPE_ORDINARY"),
                                    mapOf("property" to "properties/not-numeric", "displayName" to "Ignored"),
                                ),
                            ),
                        ),
                        "nextPageToken" to "page-2",
                    ),
                )
                request.path == "/v1beta/accountSummaries" -> ok(
                    json(
                        "accountSummaries" to listOf(
                            mapOf(
                                "account" to "accounts/2",
                                "propertySummaries" to listOf(mapOf("property" to "properties/222", "canEdit" to true)),
                            ),
                        ),
                    ),
                )
                request.path == "/v1beta/properties/111/dataStreams" -> ok(
                    json(
                        "dataStreams" to listOf(
                            mapOf(
                                "name" to "properties/111/dataStreams/1",
                                "type" to "WEB_DATA_STREAM",
                                "displayName" to "Website",
                                "webStreamData" to mapOf("measurementId" to "G-111", "defaultUri" to "https://studio.example"),
                            ),
                        ),
                    ),
                )
                request.path == "/v1beta/properties/222/dataStreams" -> FakeResponse(403, json("error" to mapOf("message" to "No access")))
                request.path.endsWith(":runReport") -> {
                    val property = request.path.substringAfter("properties/").substringBefore(":")
                    assertEquals("30daysAgo", request.json["dateRanges"]?.arrayValue?.first()?.get("startDate")?.stringValue)
                    val base = if (property == "111") 100 else 50
                    ok(
                        json(
                            "rows" to listOf(
                                mapOf(
                                    "metricValues" to listOf(
                                        mapOf("value" to "$base"),
                                        mapOf("value" to "${base * 2}"),
                                        mapOf("value" to "${base * 3}"),
                                        mapOf("value" to if (property == "111") "0.5" else "0.8"),
                                        mapOf("value" to "${base * 4}"),
                                        mapOf("value" to "61.5"),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                request.path.endsWith(":runRealtimeReport") -> ok(json("rows" to listOf(mapOf("metricValues" to listOf(mapOf("value" to "7"))))))
                else -> FakeResponse(404)
            }
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.GOOGLE_ANALYTICS, null, googleAccessToken = token))

        assertEquals(listOf("properties/111", "properties/222"), snapshot.resources.map { it.id })
        val website = snapshot.resources.first()
        assertEquals("https://studio.example", website.url)
        assertEquals("Reporting", website.status)
        assertEquals("111", website.metadata["propertyID"])
        assertEquals("G-111", website.metadata["measurementID"])
        assertEquals(50.0, website.metrics.first { it.key == "ga4.engagementRate" }.value, 0.0001)
        assertEquals(7.0, website.metrics.first { it.key == "ga4.realtime_active_users" }.value, 0.0)
        val second = snapshot.resources[1]
        assertEquals("GA4 property 222", second.name)
        assertEquals("Reporting · Property-wide", second.status)
        assertEquals("accounts/2", second.subtitle)
        assertEquals(listOf("GA4 property 222 data streams could not load: Request failed (HTTP 403): No access"), snapshot.warnings)
        assertEquals("Connected", snapshot.status)
        assertEquals(2.0, snapshot.metrics.first { it.key == "ga4.properties" }.value, 0.0)
        assertEquals(150.0, snapshot.metrics.first { it.key == "ga4.activeUsers" }.value, 0.0)
        // Engagement is session-weighted: (0.5*100*200 + 0.8*100*100) / 300.
        assertEquals((50.0 * 200 + 80.0 * 100) / 300, snapshot.metrics.first { it.key == "ga4.engagementRate" }.value, 0.0001)
        assertEquals(14.0, snapshot.metrics.first { it.key == "ga4.realtime_active_users" }.value, 0.0)
    }

    @Test
    fun repeatedSummaryTokenStopsSafelyAndMarksMetricsPartial() = runTest {
        val transport = FakeProviderTransport { request ->
            when {
                request.path == "/v1beta/accountSummaries" -> ok(json("accountSummaries" to emptyList<Any>(), "nextPageToken" to "same"))
                else -> FakeResponse(404)
            }
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.GOOGLE_ANALYTICS, null, googleAccessToken = token))
        assertEquals(2, transport.requests.size)
        assertEquals("No GA4 properties", snapshot.status)
        assertEquals("Properties · Partial", snapshot.metrics.single().label)
        assertTrue(snapshot.warnings.single().contains("repeated a pagination token"))
    }

    @Test
    fun unauthorizedPropertyRequestsPropagateSoTheTokenCanRefresh() = runTest {
        val transport = FakeProviderTransport { request ->
            if (request.path == "/v1beta/accountSummaries") {
                ok(json("accountSummaries" to listOf(mapOf("propertySummaries" to listOf(mapOf("property" to "properties/1"))))))
            } else {
                FakeResponse(401, json("error" to mapOf("message" to "Expired")))
            }
        }
        val error = runCatching {
            testApi(transport).snapshot(SiteServiceAccount(SiteProvider.GOOGLE_ANALYTICS, null, googleAccessToken = token))
        }.exceptionOrNull() as SiteServiceException
        assertTrue(error.isUnauthorized)
    }

    @Test
    fun missingGoogleTokenFailsBeforeNetworkWithConfigurationMessage() = runTest {
        val transport = FakeProviderTransport { error("must not be called") }
        val error = runCatching {
            testApi(transport).snapshot(SiteServiceAccount(SiteProvider.GOOGLE_ANALYTICS, null))
        }.exceptionOrNull() as SiteServiceException
        assertEquals(SiteServiceException.Kind.OAUTH_NOT_CONFIGURED, error.kind)
        assertTrue(error.message!!.contains("analytics.readonly"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun detailBuildsTimelineBreakdownsAuxiliarySurfacesAndRefillsTheRowBudget() = runTest {
        val transport = FakeProviderTransport { request -> analyticsDetailResponse(request) }
        val partials = ArrayList<SiteDetailPayload>()
        val range = SiteDetailRange.of(1_768_435_200_000L, 1_771_027_200_000L, TEST_ZONE)

        val payload = testApi(transport).detail(SiteDetailRequest.GoogleAnalytics("1234", token, range)) { partials += it }

        assertEquals(SiteProvider.GOOGLE_ANALYTICS, payload.provider)
        assertEquals("Main property", payload.title)
        assertNotNull(payload.rawResponses["overview"])
        assertNotNull(payload.rawResponses["technology"])
        assertEquals(2, payload.rawResponses["acquisition"]?.arrayValue?.size)
        assertEquals(2, payload.rawResponses["dataStreams"]?.arrayValue?.size)
        assertEquals("2026-07-15", payload.series.first { it.id == "ga4.timeline" }.points.first().x)
        assertTrue(
            setOf("ga4.acquisition", "ga4.pages", "ga4.events", "ga4.geography", "ga4.technology")
                .all { id -> payload.tables.any { it.id == id } },
        )
        assertEquals(2, payload.tables.first { it.id == "ga4.acquisition" }.rows.size)
        assertEquals(4_100, payload.tables.first { it.id == "ga4.technology" }.rows.size)
        assertEquals(2, payload.tables.first { it.id == "ga4.dataStreams" }.rows.size)
        assertTrue(payload.warnings.any { it.contains("on-device memory limit") })
        assertTrue(payload.sections.any { it.id == "ga4.realtime.overview" })
        assertTrue(payload.sections.any { it.id == "ga4.dataRetentionSettings" })
        assertTrue(payload.sections.any { it.id == "ga4.reportingIdentitySettings" })
        assertTrue(payload.sections.any { it.id == "ga4.userProvidedDataSettings" })

        val partial = partials.single()
        assertTrue(partial.sections.any { it.id == "ga4.overview" })
        assertTrue(partial.series.any { it.id == "ga4.timeline" })
        assertTrue(partial.tables.isEmpty())
        assertEquals(setOf("overview", "timeline"), partial.rawResponses.keys)

        val technologyLimits = transport.requests.filter { request ->
            request.path.endsWith(":runReport") && dimensions(request) == listOf("deviceCategory", "browser", "operatingSystem")
        }.map { it.json["limit"]?.stringValue?.toInt() }
        assertEquals(listOf(3_999, 4_100), technologyLimits)
        assertEquals(20, transport.requests.size)
    }

    @Test
    fun detailAuxiliaryFailuresBecomeWarningsButCoreFailuresThrow() = runTest {
        val transport = FakeProviderTransport { request ->
            when {
                request.path.endsWith(":runReport") -> analyticsDetailResponse(request)
                else -> FakeResponse(500)
            }
        }
        val payload = testApi(transport).detail(SiteDetailRequest.GoogleAnalytics("properties/1234", token, testRange(7)))
        assertTrue(payload.warnings.any { it == "Google Analytics property metadata could not load: The provider request failed (HTTP 500)." })
        assertEquals("Google Analytics · properties/1234", payload.title)

        val failing = FakeProviderTransport { FakeResponse(401) }
        val error = runCatching {
            testApi(failing).detail(SiteDetailRequest.GoogleAnalytics("1234", token, testRange(7)))
        }.exceptionOrNull() as SiteServiceException
        assertTrue(error.isUnauthorized)
    }

    @Test
    fun reportRequestsOrderEveryDimensionForStablePaging() = runTest {
        val transport = FakeProviderTransport { request -> analyticsDetailResponse(request) }
        testApi(transport).detail(SiteDetailRequest.GoogleAnalytics("1234", token, testRange(7)))
        transport.requests.filter { it.path.endsWith(":runReport") }.forEach { request ->
            val dimensions = dimensions(request)
            val orderBys = request.json["orderBys"]?.arrayValue
            if (dimensions.isEmpty()) {
                assertNull(orderBys)
            } else {
                assertEquals(dimensions, orderBys?.map { it["dimension"]?.get("dimensionName")?.stringValue })
            }
        }
        assertFalse(transport.requests.any { it.bearer != "oauth-token" })
    }

    private fun dimensions(request: RecordedRequest): List<String> =
        request.json["dimensions"]?.arrayValue.orEmpty().mapNotNull { it["name"]?.stringValue }

    private fun analyticsDetailResponse(request: RecordedRequest): FakeResponse {
        val path = request.path
        if (path.endsWith(":runReport")) {
            val dimensions = dimensions(request)
            val metrics = request.json["metrics"]?.arrayValue.orEmpty().mapNotNull { it["name"]?.stringValue }
            val offset = request.json["offset"]?.stringValue?.toInt() ?: 0
            val limit = request.json["limit"]?.stringValue?.toInt() ?: 1
            val (sample, rowCount) = when (dimensions) {
                emptyList<String>() -> "overview" to 1
                listOf("date") -> "20260715" to 1
                listOf("sessionDefaultChannelGroup", "sessionSource", "sessionMedium") -> (if (offset == 0) "organic" else "direct") to 2
                listOf("deviceCategory", "browser", "operatingSystem") -> "tech" to 4_100
                else -> "other" to 1
            }
            val returned = if (rowCount == 4_100) maxOf(0, minOf(limit, rowCount - offset)) else 1
            val rows = (0 until returned).map { index ->
                mapOf(
                    "dimensionValues" to dimensions.map { name ->
                        mapOf("value" to if (name == "date") sample else "$sample-$name-${offset + index}")
                    },
                    "metricValues" to metrics.indices.map { mapOf("value" to "${it + 1}") },
                )
            }
            return ok(
                json(
                    "dimensionHeaders" to dimensions.map { mapOf("name" to it) },
                    "metricHeaders" to metrics.map { mapOf("name" to it, "type" to "TYPE_INTEGER") },
                    "rows" to rows,
                    "rowCount" to rowCount,
                    "metadata" to mapOf("currencyCode" to "USD", "timeZone" to "UTC"),
                ),
            )
        }
        if (path.endsWith(":runRealtimeReport")) {
            val dimensions = dimensions(request)
            if (dimensions == listOf("minutesAgo")) {
                assertEquals("minutesAgo", request.json["orderBys"]?.arrayValue?.first()?.get("dimension")?.get("dimensionName")?.stringValue)
            }
            val metrics = listOf("activeUsers", "eventCount", "screenPageViews")
            return ok(
                json(
                    "dimensionHeaders" to dimensions.map { mapOf("name" to it) },
                    "metricHeaders" to metrics.map { mapOf("name" to it, "type" to "TYPE_INTEGER") },
                    "rows" to listOf(
                        mapOf(
                            "dimensionValues" to dimensions.map { mapOf("value" to "5") },
                            "metricValues" to metrics.indices.map { mapOf("value" to "${it + 3}") },
                        ),
                    ),
                    "rowCount" to 1,
                    "propertyQuota" to mapOf("tokensPerDay" to mapOf("remaining" to 99)),
                ),
            )
        }
        return when {
            path == "/v1beta/properties/1234" -> ok(
                json("name" to "properties/1234", "displayName" to "Main property", "timeZone" to "UTC", "industryCategory" to "TECHNOLOGY"),
            )
            path == "/v1beta/properties/1234/dataStreams" -> if (request.query["pageToken"] == null) {
                assertEquals("200", request.query["pageSize"])
                ok(
                    json(
                        "dataStreams" to listOf(
                            mapOf(
                                "name" to "properties/1234/dataStreams/1", "type" to "WEB_DATA_STREAM",
                                "displayName" to "Website", "webStreamData" to mapOf("measurementId" to "G-ONE"),
                            ),
                        ),
                        "nextPageToken" to "stream-2",
                    ),
                )
            } else {
                assertEquals("stream-2", request.query["pageToken"])
                assertEquals("200", request.query["pageSize"])
                ok(
                    json(
                        "dataStreams" to listOf(
                            mapOf(
                                "name" to "properties/1234/dataStreams/2", "type" to "IOS_APP_DATA_STREAM",
                                "displayName" to "iOS", "iosAppStreamData" to mapOf("bundleId" to "com.example.app"),
                            ),
                        ),
                    ),
                )
            }
            path == "/v1beta/properties/1234/metadata" -> ok(
                json(
                    "dimensions" to listOf(mapOf("apiName" to "country", "uiName" to "Country", "category" to "Geo")),
                    "metrics" to listOf(mapOf("apiName" to "activeUsers", "uiName" to "Active users", "category" to "User")),
                ),
            )
            path.endsWith("/dataRetentionSettings") -> ok(json("eventDataRetention" to "FIFTY_MONTHS", "resetUserDataOnNewActivity" to true))
            path.endsWith("/googleSignalsSettings") -> ok(json("state" to "GOOGLE_SIGNALS_ENABLED"))
            path.endsWith("/attributionSettings") -> ok(json("acquisitionConversionEventLookbackWindow" to "ACQUISITION_CONVERSION_EVENT_LOOKBACK_WINDOW_30_DAYS"))
            path.endsWith("/reportingIdentitySettings") -> ok(json("reportingIdentity" to "BLENDED"))
            path.endsWith("/userProvidedDataSettings") -> ok(json("userProvidedDataCollectionEnabled" to true))
            else -> FakeResponse(404)
        }
    }
}
