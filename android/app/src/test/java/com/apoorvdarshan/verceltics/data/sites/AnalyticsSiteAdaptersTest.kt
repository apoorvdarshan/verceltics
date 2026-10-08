package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsSiteAdaptersTest {
    // MARK: Bing Webmaster

    @Test
    fun bingSnapshotLoadsVerifiedSitesTrafficWindowAndLatestCrawl() = runTest {
        val day = 86_400_000L
        val transport = FakeProviderTransport { request ->
            assertEquals("bing-key", request.query["apikey"])
            when (request.path.substringAfterLast('/')) {
                "GetUserSites" -> ok(
                    json(
                        "d" to listOf(
                            mapOf("__type" to "Site", "Url" to "https://Studio.example/", "IsVerified" to true),
                            mapOf("Url" to "https://pending.example/", "IsVerified" to false),
                        ),
                    ),
                )
                "GetRankAndTrafficStats" -> ok(
                    json(
                        "d" to listOf(
                            mapOf("Date" to "/Date(${TEST_NOW_MILLIS - day})/", "Clicks" to 10, "Impressions" to 100),
                            mapOf("Date" to "/Date(${TEST_NOW_MILLIS - 2 * day}-0800)/", "Clicks" to 5, "Impressions" to 100),
                            mapOf("Date" to "/Date(${TEST_NOW_MILLIS - 40 * day})/", "Clicks" to 999, "Impressions" to 999),
                        ),
                    ),
                )
                "GetCrawlStats" -> ok(
                    json(
                        "d" to listOf(
                            mapOf("Date" to "/Date(${TEST_NOW_MILLIS - 3 * day})/", "CrawledPages" to 1, "InIndex" to 1),
                            mapOf("Date" to "/Date(${TEST_NOW_MILLIS - day})/", "CrawledPages" to 42, "CrawlErrors" to 2, "InIndex" to 40, "Code5xx" to 1),
                        ),
                    ),
                )
                else -> FakeResponse(404)
            }
        }
        val snapshot = testApi(transport).snapshot(
            SiteServiceAccount(SiteProvider.BING_WEBMASTER, SecretValue.of("bing-key")),
        )

        assertEquals(listOf("https://studio.example/", "https://pending.example/"), snapshot.resources.map { it.id })
        val verified = snapshot.resources.first()
        assertEquals("Studio.example", verified.name)
        assertEquals("Verified", verified.status)
        assertEquals(15.0, verified.metrics.first { it.key == "bing.clicks" }.value, 0.0)
        assertEquals(7.5, verified.metrics.first { it.key == "bing.ctr" }.value, 0.0001)
        assertEquals(42.0, verified.metrics.first { it.key == "bing.crawled_pages" }.value, 0.0)
        assertEquals(1.0, verified.metrics.first { it.key == "bing.code_5xx" }.value, 0.0)
        assertTrue(verified.metadata.getValue("crawlStatsAt").endsWith("Z"))
        assertEquals("Unverified", snapshot.resources[1].status)
        assertTrue(snapshot.resources[1].metrics.isEmpty())
        assertEquals(2.0, snapshot.metrics.first { it.key == "bing.sites" }.value, 0.0)
        assertEquals(1.0, snapshot.metrics.first { it.key == "bing.verified" }.value, 0.0)
        assertEquals("Connected", snapshot.status)
        assertEquals(3, transport.requests.size)
        assertEquals("https://Studio.example/", transport.requests.last().query["siteUrl"])
    }

    @Test
    fun bingFailuresMarkPartialMetricsAndBadListsAreDecodingErrors() = runTest {
        val transport = FakeProviderTransport { request ->
            when (request.path.substringAfterLast('/')) {
                "GetUserSites" -> ok(json("d" to listOf(mapOf("Url" to "https://a.example/", "IsVerified" to "true"))))
                else -> FakeResponse(400, json("Message" to "nope"))
            }
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.BING_WEBMASTER, SecretValue.of("k")))
        assertEquals("Connected · Partial metrics", snapshot.status)
        assertEquals(2, snapshot.warnings.size)
        assertEquals("Sites", snapshot.metrics.first().label)

        val bad = FakeProviderTransport { ok(json("unexpected" to true)) }
        val error = runCatching {
            testApi(bad).snapshot(SiteServiceAccount(SiteProvider.BING_WEBMASTER, SecretValue.of("k")))
        }.exceptionOrNull() as SiteServiceException
        assertEquals("Could not read the provider response: Bing Webmaster did not return a site list.", error.message)
    }

    @Test
    fun bingDetailLoadsEveryMethodWithSeriesAndWarnings() = runTest {
        val transport = FakeProviderTransport { request ->
            when (val method = request.path.substringAfterLast('/')) {
                "GetRankAndTrafficStats" -> ok(json("d" to listOf(mapOf("Date" to "/Date(1700000000000)/", "Clicks" to 3, "Impressions" to 9))))
                "GetCrawlStats" -> ok(json("d" to listOf(mapOf("Date" to "/Date(1700000000000)/", "CrawledPages" to 30))))
                "GetLinkCounts" -> FakeResponse(500)
                else -> ok(json("d" to listOf(mapOf("Query" to method, "Clicks" to 1))))
            }
        }
        val payload = testApi(transport).detail(
            SiteDetailRequest.BingWebmaster("https://studio.example/", SecretValue.of("bing-key")),
        )
        assertEquals("studio.example", payload.title)
        assertEquals(6, payload.tables.size)
        assertEquals(listOf("bing.GetRankAndTrafficStats.timeline", "bing.GetCrawlStats.timeline"), payload.series.map { it.id })
        assertEquals("2023-11-14T22:13:20Z", payload.series.first().points.single().x)
        assertEquals(mapOf("Clicks" to 3.0, "Impressions" to 9.0), payload.series.first().points.single().values)
        assertEquals(listOf("Link counts could not load: The provider request failed (HTTP 500)."), payload.warnings)
        assertEquals(7, transport.requests.size)
        assertFalse(ProviderJsonWriter.write(payload.rawResponses.getValue("GetCrawlStats")).contains("bing-key"))

        val allFailing = FakeProviderTransport { FakeResponse(401) }
        val error = runCatching {
            testApi(allFailing).detail(SiteDetailRequest.BingWebmaster("https://studio.example/", SecretValue.of("k")))
        }.exceptionOrNull() as SiteServiceException
        assertTrue(error.isUnauthorized)
    }

    // MARK: Microsoft Clarity

    @Test
    fun claritySnapshotAggregatesCountsAndAveragesAndDiscoversTheOrigin() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("clarity-token", request.bearer)
            assertEquals("3", request.query["numOfDays"])
            ok(
                jsonArray(
                    mapOf(
                        "metricName" to "Traffic",
                        "information" to listOf(
                            mapOf("totalSessionCount" to "100", "scrollPercentage" to 2.0, "URL" to "https://Studio.example/a"),
                            mapOf("totalSessionCount" to "50", "scrollPercentage" to 4.0, "URL" to "https://studio.example/b"),
                            mapOf("totalSessionCount" to "1", "URL" to "https://other.example/"),
                        ),
                    ),
                    mapOf("metricName" to "Scroll Depth", "information" to listOf(mapOf("averageScrollDepth" to 40.5))),
                ),
            )
        }
        val snapshot = testApi(transport).snapshot(
            SiteServiceAccount(SiteProvider.CLARITY, SecretValue.of("clarity-token"), mapOf("projectName" to "Studio", "days" to "9")),
        )
        val resource = snapshot.resources.single()
        assertEquals("Studio", resource.name)
        assertEquals("https://studio.example", resource.url)
        assertEquals("3", resource.metadata["reportedURLs"])
        assertEquals("2", resource.metadata["reportedOrigins"])
        assertEquals(151.0, snapshot.metrics.first { it.key == "clarity.traffic.totalsessioncount" }.value, 0.0)
        assertEquals(SiteMetricUnit.COUNT, snapshot.metrics.first { it.key == "clarity.traffic.totalsessioncount" }.unit)
        val pages = snapshot.metrics.first { it.key == "clarity.traffic.scrollpercentage" }
        assertEquals(SiteMetricUnit.PERCENT, pages.unit)
        assertEquals(3.0, pages.value, 0.0)
        assertEquals("Live insights · 3d", snapshot.status)
        assertTrue(resource.metrics.all { it.resourceId == resource.id })
        assertTrue(snapshot.metrics.all { it.resourceId == null })
    }

    @Test
    fun clarityConfiguredSiteWinsAndNonListResponsesFail() = runTest {
        val transport = FakeProviderTransport { ok(jsonArray()) }
        val snapshot = testApi(transport).snapshot(
            SiteServiceAccount(
                SiteProvider.CLARITY,
                SecretValue.of("t"),
                mapOf("projectName" to "Docs", "siteURL" to "https://docs.example/path", "days" to "1"),
            ),
        )
        assertEquals("https://docs.example", snapshot.resources.single().url)
        assertEquals("Live insights · 1d", snapshot.status)

        val bad = FakeProviderTransport { ok(json("error" to "x")) }
        val error = runCatching {
            testApi(bad).snapshot(SiteServiceAccount(SiteProvider.CLARITY, SecretValue.of("t"), mapOf("projectName" to "Docs")))
        }.exceptionOrNull()
        assertTrue(error?.message!!.contains("live-insights list"))
    }

    @Test
    fun clarityDetailValidatesDimensionsAndBuildsTables() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("Browser", request.query["dimension1"])
            assertEquals("Country/Region", request.query["dimension2"])
            assertEquals("2", request.query["numOfDays"])
            ok(jsonArray(mapOf("metricName" to "Traffic", "information" to listOf(mapOf("Browser" to "Chrome", "sessionsCount" to "9")))))
        }
        val payload = testApi(transport).detail(
            SiteDetailRequest.Clarity(SecretValue.of("t"), 2, listOf("Browser", "Country/Region")),
        )
        assertEquals("clarity.traffic.0", payload.tables.single().id)
        assertEquals(listOf("Browser", "sessionsCount"), payload.tables.single().columns)
        assertEquals("clarity.request", payload.sections.single().id)

        val invalid = runCatching {
            testApi(FakeProviderTransport { error("unused") }).detail(SiteDetailRequest.Clarity(SecretValue.of("t"), 3, listOf("Hostname")))
        }.exceptionOrNull() as SiteServiceException
        assertEquals(SiteServiceException.Kind.INVALID_CONFIGURATION, invalid.kind)
    }

    // MARK: Plausible

    @Test
    fun plausibleSnapshotMapsMetricsInRequestOrderAndImportsWarning() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("https://plausible.io/api/v2/query", request.uri.toString())
            assertEquals("example.com", request.json["site_id"]?.stringValue)
            assertEquals("30d", request.json["date_range"]?.stringValue)
            ok(
                json(
                    "results" to listOf(mapOf("dimensions" to emptyList<Any>(), "metrics" to listOf(120, 150, 400, 2.67, 41, 63))),
                    "meta" to mapOf("imports_warning" to "Imported data excluded"),
                ),
            )
        }
        val snapshot = testApi(transport).snapshot(
            SiteServiceAccount(SiteProvider.PLAUSIBLE, SecretValue.of("plausible-key"), mapOf("siteID" to "example.com")),
        )
        assertEquals(
            listOf("plausible.visitors", "plausible.visits", "plausible.pageviews", "plausible.views_per_visit", "plausible.bounce_rate", "plausible.visit_duration"),
            snapshot.metrics.map { it.key },
        )
        assertEquals(SiteMetricUnit.RATIO, snapshot.metrics[3].unit)
        assertEquals("https://example.com", snapshot.resources.single().url)
        assertEquals("30d", snapshot.resources.single().subtitle)
        assertEquals(listOf("Imported data excluded"), snapshot.warnings)
        assertEquals("plausible-key", transport.requests.single().bearer)
    }

    @Test
    fun plausibleRequiresSiteIdAndAResultList() = runTest {
        val missing = runCatching {
            testApi(FakeProviderTransport { error("unused") }).snapshot(SiteServiceAccount(SiteProvider.PLAUSIBLE, SecretValue.of("k")))
        }.exceptionOrNull()
        assertEquals("Enter the Plausible site ID.", missing?.message)
        val bad = runCatching {
            testApi(FakeProviderTransport { ok(json("results" to "nope")) })
                .snapshot(SiteServiceAccount(SiteProvider.PLAUSIBLE, SecretValue.of("k"), mapOf("siteID" to "a.example")))
        }.exceptionOrNull()
        assertTrue(bad?.message!!.contains("query result list"))
    }

    @Test
    fun plausibleDetailPaginatesEveryReportedResult() = runTest {
        var index = 0
        val transport = FakeProviderTransport { request ->
            assertEquals(true, request.json["include"]?.get("total_rows")?.booleanValue)
            if (request.json["dimensions"]?.arrayValue?.map { it.stringValue } == listOf("event:page")) {
                assertEquals(listOf("visitors", "pageviews", "time_on_page"), request.json["metrics"]?.arrayValue?.map { it.stringValue })
            }
            index += 1
            when (index) {
                3 -> ok(json("results" to listOf(mapOf("dimensions" to listOf("Google"), "metrics" to listOf(12, 10, 30))), "meta" to mapOf("total_rows" to 2)))
                4 -> ok(json("results" to listOf(mapOf("dimensions" to listOf("Direct"), "metrics" to listOf(8, 7, 20))), "meta" to mapOf("total_rows" to 2)))
                else -> ok(json("results" to listOf(mapOf("dimensions" to emptyList<Any>(), "metrics" to listOf(1, 2, 3, 4, 5, 6, 7))), "meta" to mapOf("total_rows" to 1)))
            }
        }
        val payload = testApi(transport).detail(SiteDetailRequest.Plausible("example.com", SecretValue.of("plausible-key"), testRange()))

        assertEquals(2, payload.rawResponses["sources"]?.arrayValue?.size)
        assertEquals(2, payload.tables.first { it.id == "plausible.sources" }.rows.size)
        assertEquals(8, transport.requests.size)
        assertEquals(listOf("2026-06-16", "2026-07-15"), transport.requests.first().json["date_range"]?.arrayValue?.map { it.stringValue })
        assertEquals(1, transport.requests[3].json["pagination"]?.get("offset")?.numberValue?.toInt())
        assertTrue(payload.sections.any { it.id == "plausible.overview" })
    }

    @Test
    fun plausibleAggregateBudgetBoundsRowsAndRawBytes() = runTest {
        val transport = FakeProviderTransport { request ->
            val limit = request.json["pagination"]?.get("limit")?.numberValue?.toInt() ?: 0
            val dimensions = request.json["dimensions"]?.arrayValue?.mapNotNull { it.stringValue }.orEmpty()
            val metrics = request.json["metrics"]?.arrayValue?.mapNotNull { it.stringValue }.orEmpty()
            val results = (0 until limit).map { index ->
                mapOf("dimensions" to dimensions.map { "$it-$index" }, "metrics" to metrics.indices.map { index + it + 1 })
            }
            ok(json("results" to results, "meta" to mapOf("total_rows" to 1_000_000)))
        }
        val payload = testApi(transport).detail(SiteDetailRequest.Plausible("high-volume.example", SecretValue.of("k"), testRange()))

        val rows = payload.tables.sumOf { it.rows.size } + payload.series.sumOf { it.points.size }
        assertTrue(rows <= 20_000)
        assertTrue(ProviderJsonWriter.write(com.apoorvdarshan.verceltics.data.network.ProviderJsonValue.from(payload.rawResponses)).length <= 1_048_576)
        assertTrue(payload.warnings.any { it.contains("on-device memory limit") })
        assertTrue(payload.warnings.any { it.contains("Verceltics kept the first") })
        assertEquals(7, transport.requests.size)
    }

    @Test
    fun plausibleOverviewFailureFailsTheWorkspace() = runTest {
        val error = runCatching {
            testApi(FakeProviderTransport { FakeResponse(401) }).detail(SiteDetailRequest.Plausible("a.example", SecretValue.of("k"), testRange()))
        }.exceptionOrNull() as SiteServiceException
        assertTrue(error.isUnauthorized)
    }

    // MARK: Umami

    @Test
    fun umamiCloudSnapshotPaginatesSitesAndUsesTheApiKeyHeader() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("umami-key", request.secretHeaders["x-umami-api-key"])
            assertNull(request.bearer)
            assertEquals("api.umami.is", request.uri.host)
            when {
                request.path == "/v1/websites" && request.query["page"] == "1" -> ok(
                    json(
                        "data" to (1..100).map { mapOf("id" to "site-$it", "name" to "Site $it", "domain" to "s$it.example") },
                        "count" to 101,
                    ),
                )
                request.path == "/v1/websites" -> ok(
                    json("data" to listOf(mapOf("id" to "site-101", "domain" to "last.example", "updatedAt" to "2026-07-01T00:00:00Z")), "count" to 101),
                )
                request.path.endsWith("/stats") -> {
                    assertEquals(TEST_NOW_MILLIS.toString(), request.query["endAt"])
                    assertEquals((TEST_NOW_MILLIS - 30L * 86_400_000L).toString(), request.query["startAt"])
                    if (request.path.contains("site-101")) {
                        ok(json("pageviews" to mapOf("value" to 5, "prev" to 1), "visitors" to 2))
                    } else {
                        ok(json("pageviews" to 10, "visitors" to 4, "visits" to 6, "bounces" to 1, "totaltime" to 120))
                    }
                }
                else -> FakeResponse(404)
            }
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.UMAMI, SecretValue.of("umami-key")))

        assertEquals(101, snapshot.resources.size)
        val last = snapshot.resources.last()
        assertEquals("last.example", last.name)
        assertEquals("https://last.example", last.url)
        assertEquals(5.0, last.metrics.first { it.key == "umami.pageviews" }.value, 0.0)
        assertEquals(1_005.0, snapshot.metrics.first { it.key == "umami.pageviews" }.value, 0.0)
        assertEquals("Connected", snapshot.status)
        assertEquals(2 + 101, transport.requests.size)
    }

    @Test
    fun umamiSelfHostedUsesBearerAndStopsOnRepeatedPages() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("self-token", request.bearer)
            assertTrue(request.secretHeaders.isEmpty())
            assertEquals("analytics.example.com", request.uri.host)
            when {
                request.path == "/api/websites" -> ok(
                    json("data" to (1..100).map { mapOf("id" to "same-$it", "name" to "Same $it") }),
                )
                request.path.endsWith("/stats") -> FakeResponse(500)
                else -> FakeResponse(404)
            }
        }
        val sleeps = ArrayList<Long>()
        val snapshot = testApi(transport, sleeps).snapshot(
            SiteServiceAccount(
                SiteProvider.UMAMI,
                SecretValue.of("self-token"),
                mapOf("authMode" to "selfHosted", "baseURL" to "https://analytics.example.com"),
            ),
        )
        assertEquals(100, snapshot.resources.size)
        assertEquals("Connected · Partial metrics", snapshot.status)
        assertTrue(snapshot.warnings.first().contains("repeated a results page"))
        assertTrue(snapshot.warnings.any { it.contains("stats could not load") })
        assertTrue(snapshot.metrics.isEmpty())
        assertTrue(sleeps.isNotEmpty())
    }

    @Test
    fun umamiIdentityComesFromMeWithASingleShortAttempt() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("/v1/me", request.path)
            ok(json("user" to mapOf("id" to "user-1", "username" to "ops"), "token" to "never-store"))
        }
        val metadata = testApi(transport).connectionMetadata(SiteServiceAccount(SiteProvider.UMAMI, SecretValue.of("k")))
        assertEquals(mapOf("umamiUserID" to "user-1", "umamiEndpoint" to "https://api.umami.is/v1/", "umamiUsername" to "ops"), metadata)

        val failing = FakeProviderTransport { FakeResponse(503) }
        runCatching { testApi(failing).connectionMetadata(SiteServiceAccount(SiteProvider.UMAMI, SecretValue.of("k"))) }
        assertEquals(1, failing.requests.size)
    }

    @Test
    fun umamiDetailPaginatesEveryMetricTypeAndKeepsRawPages() = runTest {
        val documented = setOf(
            "path", "entry", "exit", "title", "query", "referrer", "channel", "domain", "country", "region", "city",
            "browser", "os", "device", "language", "screen", "event", "hostname", "tag", "distinctId",
        )
        val requestedTypes = HashSet<String>()
        val transport = FakeProviderTransport { request ->
            assertEquals("umami-token", request.bearer)
            when {
                request.path.endsWith("/stats") -> ok(json("pageviews" to 10, "visitors" to 4, "visits" to 6))
                request.path.endsWith("/pageviews") -> ok(
                    json("pageviews" to listOf(mapOf("x" to "2026-07-15", "y" to 10)), "sessions" to listOf(mapOf("x" to "2026-07-15", "y" to 6))),
                )
                request.path.endsWith("/metrics") -> {
                    val type = request.query.getValue("type")
                    requestedTypes += type
                    assertEquals("500", request.query["limit"])
                    val offset = request.query["offset"]?.toInt()
                    when {
                        type == "path" && offset == 0 -> ok(jsonArray(*(0 until 500).map { mapOf("x" to "/page-$it", "y" to it + 1) }.toTypedArray()))
                        type == "path" && offset == 500 -> ok(jsonArray(mapOf("x" to "/last", "y" to 1)))
                        else -> ok(jsonArray(mapOf("x" to type, "y" to 1)))
                    }
                }
                request.path.endsWith("/active") -> ok(json("visitors" to 2))
                request.path.endsWith("/events/series") -> ok(jsonArray(mapOf("x" to "signup", "t" to "2026-07-15T12:00:00Z", "y" to 2)))
                else -> FakeResponse(404)
            }
        }
        val payload = testApi(transport).detail(
            SiteDetailRequest.Umami(
                "site-id",
                URI("https://analytics.example.com/api/"),
                UmamiAuthentication.BearerToken(SecretValue.of("umami-token")),
                testRange(),
            ),
        )

        assertEquals(documented, requestedTypes)
        assertEquals(2, payload.rawResponses["metrics.paths"]?.arrayValue?.size)
        assertEquals(501, payload.tables.first { it.id == "umami.paths" }.rows.size)
        assertEquals(25, transport.requests.size)
        assertEquals(mapOf("pageviews" to 10.0, "sessions" to 6.0), payload.series.first { it.id == "umami.pageviews.timeline" }.points.single().values)
        assertTrue(payload.sections.any { it.id == "umami.active" })
    }
}
