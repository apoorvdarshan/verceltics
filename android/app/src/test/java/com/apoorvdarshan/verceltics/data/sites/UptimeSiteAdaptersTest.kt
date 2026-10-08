package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UptimeSiteAdaptersTest {
    // MARK: UptimeRobot

    @Test
    fun uptimeRobotSnapshotPaginatesWithFormBodyAndMapsStatuses() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("POST", request.method)
            assertEquals("/v2/getMonitors", request.path)
            assertEquals("read-only", request.form["api_key"])
            assertEquals("1-7-30", request.form["custom_uptime_ratios"])
            assertEquals("50", request.form["limit"])
            val offset = request.form.getValue("offset").toInt()
            val monitors = if (offset == 0) {
                (1..50).map { id -> monitor(id, status = if (id == 1) 9 else 2) }
            } else {
                listOf(monitor(51, status = 0), monitor(52, status = 8, ratio = "99.5-99.1-98.0"))
            }
            ok(json("stat" to "ok", "pagination" to mapOf("offset" to offset, "limit" to 50, "total" to 52), "monitors" to monitors))
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.UPTIME_ROBOT, SecretValue.of("read-only")))

        assertEquals(52, snapshot.resources.size)
        assertEquals(2, transport.requests.size)
        assertEquals("50", transport.requests[1].form["offset"])
        assertEquals("Down", snapshot.resources.first().status)
        assertEquals("Paused", snapshot.resources.first { it.id == "51" }.status)
        assertEquals("Seems down", snapshot.resources.first { it.id == "52" }.status)
        assertEquals("2 down", snapshot.status)
        val ratios = snapshot.resources.first { it.id == "52" }.metrics
        assertEquals(listOf("24h Uptime", "7d Uptime", "30d Uptime", "Response Time"), ratios.map { it.label })
        assertEquals(98.0, ratios[2].value, 0.0)
        assertEquals(1_784_000_000_000L, snapshot.resources.first().updatedAtMillis)
        assertEquals(listOf(52.0, 49.0, 2.0, 1.0), snapshot.metrics.map { it.value })
        assertFalse(transport.requests.first().uri.toString().contains("read-only"))
    }

    @Test
    fun uptimeRobotRepeatedPagesStopAndRejectionsSurfaceProviderMessage() = runTest {
        val repeating = FakeProviderTransport {
            ok(json("stat" to "ok", "monitors" to (1..50).map { id -> monitor(id) }))
        }
        val snapshot = testApi(repeating).snapshot(SiteServiceAccount(SiteProvider.UPTIME_ROBOT, SecretValue.of("k")))
        assertEquals(50, snapshot.resources.size)
        assertEquals(2, repeating.requests.size)
        assertEquals("All operational · Partial metrics", snapshot.status)
        assertEquals("Monitors · Partial", snapshot.metrics.first().label)

        val rejected = FakeProviderTransport { ok(json("stat" to "fail", "error" to mapOf("message" to "api_key not found."))) }
        val error = runCatching {
            testApi(rejected).snapshot(SiteServiceAccount(SiteProvider.UPTIME_ROBOT, SecretValue.of("k")))
        }.exceptionOrNull()
        assertEquals("Could not read the provider response: api_key not found.", error?.message)
    }

    @Test
    fun uptimeRobotDetailIncludesHistoryCompanionsAndRedactsConfiguration() = runTest {
        var alertContacts = 0
        var windows = 0
        var pages = 0
        val transport = FakeProviderTransport { request ->
            assertEquals("POST", request.method)
            when (request.path.substringAfterLast('/')) {
                "getMonitors" -> {
                    listOf("custom_http_headers", "custom_http_statuses", "http_request_details", "auth_type", "timezone").forEach {
                        assertTrue(it, request.form.containsKey(it))
                    }
                    assertEquals("42", request.form["monitors"])
                    ok(
                        json(
                            "stat" to "ok",
                            "monitors" to listOf(
                                mapOf(
                                    "id" to 42, "friendly_name" to "Homepage", "status" to 2,
                                    "custom_http_headers" to mapOf("Authorization" to "Bearer monitor-secret"),
                                    "post_value" to "client_secret=monitor-secret",
                                    "timezone" to "UTC",
                                    "logs" to listOf(mapOf("datetime" to 1_768_435_200, "type" to 2)),
                                    "response_times" to listOf(
                                        mapOf("datetime" to 1_768_435_200, "value" to 120),
                                        mapOf("datetime" to 1_768_521_600, "value" to 95),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                "getAccountDetails" -> ok(json("stat" to "ok", "account" to mapOf("email" to "owner@example.com")))
                "getAlertContacts" -> {
                    alertContacts += 1
                    ok(json("stat" to "ok", "offset" to alertContacts - 1, "limit" to 1, "total" to 2, "alert_contacts" to listOf(mapOf("id" to alertContacts, "type" to 2))))
                }
                "getMWindows" -> {
                    windows += 1
                    ok(json("stat" to "ok", "offset" to windows - 1, "limit" to 1, "total" to 2, "mwindows" to listOf(mapOf("id" to windows))))
                }
                "getPSPs" -> {
                    pages += 1
                    ok(json("stat" to "ok", "offset" to pages - 1, "limit" to 1, "total" to 2, "psps" to listOf(mapOf("id" to pages, "friendly_name" to "Status"))))
                }
                else -> FakeResponse(404)
            }
        }
        val range = SiteDetailRange.lastDays(30, 1_771_027_200_000L, TEST_ZONE)
        val payload = testApi(transport).detail(SiteDetailRequest.UptimeRobot("42", SecretValue.of("read-only"), range))

        assertEquals("Homepage", payload.title)
        assertEquals(2, payload.series.single().points.size)
        assertEquals("Response time (ms)", payload.series.single().metricLabels["responseTime"])
        listOf("getAccountDetails", "getAlertContacts", "getMWindows", "getPSPs").forEach { assertTrue(payload.rawResponses.containsKey(it)) }
        assertEquals(2, payload.rawResponses["getAlertContacts"]?.arrayValue?.size)
        assertEquals(2, payload.tables.first { it.id == "uptimerobot.get-alert-contacts" }.rows.size)
        assertEquals(2, payload.tables.first { it.id == "uptimerobot.get-maintenance-windows" }.rows.size)
        assertEquals(2, payload.tables.first { it.id == "uptimerobot.get-public-status-pages" }.rows.size)
        val monitor = payload.rawResponses.getValue("monitor")["monitors"]?.arrayValue?.first()
        assertEquals(ProviderJsonValue.Str("[REDACTED]"), monitor?.get("custom_http_headers"))
        assertEquals(ProviderJsonValue.Str("[REDACTED]"), monitor?.get("post_value"))
        assertEquals("UTC", monitor?.get("timezone")?.stringValue)
        assertTrue(payload.warnings.any { it.contains("seven days") })
        assertEquals(8, transport.requests.size)
        val serialized = ProviderJsonWriter.write(ProviderJsonValue.from(payload.rawResponses))
        assertFalse(serialized.contains("monitor-secret"))
        val history = transport.requests.first().form
        assertEquals((range.endSeconds - 7 * 86_400).toString(), history["response_times_start_date"])
        assertEquals(range.startSeconds.toString(), history["logs_start_date"])
    }

    @Test
    fun uptimeRobotDetailRequiresTheSelectedMonitor() = runTest {
        val transport = FakeProviderTransport { ok(json("stat" to "ok", "monitors" to emptyList<Any>())) }
        val error = runCatching {
            testApi(transport).detail(SiteDetailRequest.UptimeRobot("1", SecretValue.of("k"), testRange()))
        }.exceptionOrNull()
        assertEquals("UptimeRobot did not return the selected monitor.", error?.message)
    }

    // MARK: Better Stack

    @Test
    fun betterStackSnapshotFollowsSameHostPaginationAndCapitalizesStatus() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("better-token", request.bearer)
            if (request.query["page"] == "2") {
                ok(json("data" to listOf(betterMonitor("3", "paused")), "pagination" to mapOf("next" to null)))
            } else {
                ok(
                    json(
                        "data" to listOf(betterMonitor("1", "up"), betterMonitor("2", "down")),
                        "pagination" to mapOf("next" to "https://uptime.betterstack.com/api/v2/monitors?page=2"),
                    ),
                )
            }
        }
        val snapshot = testApi(transport).snapshot(SiteServiceAccount(SiteProvider.BETTER_STACK, SecretValue.of("better-token")))

        assertEquals(listOf("Up", "Down", "Paused"), snapshot.resources.map { it.status })
        assertEquals("1 down", snapshot.status)
        assertEquals(30.0, snapshot.resources.first().metrics.single().value, 0.0)
        assertEquals("Monitor 1", snapshot.resources.first().name)
        assertEquals("ops", snapshot.resources.first().metadata["teamName"])
        assertEquals(listOf(3.0, 1.0, 1.0, 1.0), snapshot.metrics.map { it.value })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun betterStackRejectsOffHostPaginationAndStopsOnRepeatedLinks() = runTest {
        val offHost = FakeProviderTransport {
            ok(json("data" to emptyList<Any>(), "pagination" to mapOf("next" to "https://evil.example/api/v2/monitors?page=2")))
        }
        val error = runCatching {
            testApi(offHost).snapshot(SiteServiceAccount(SiteProvider.BETTER_STACK, SecretValue.of("k")))
        }.exceptionOrNull() as SiteServiceException
        assertEquals(SiteServiceException.Kind.INVALID_RESPONSE, error.kind)
        assertEquals(1, offHost.requests.size)

        val repeating = FakeProviderTransport {
            ok(json("data" to listOf(betterMonitor("1", "up")), "pagination" to mapOf("next" to "https://uptime.betterstack.com/api/v2/monitors")))
        }
        val snapshot = testApi(repeating).snapshot(SiteServiceAccount(SiteProvider.BETTER_STACK, SecretValue.of("k")))
        assertEquals("All operational · Partial metrics", snapshot.status)
        assertTrue(snapshot.warnings.single().contains("repeated a pagination URL"))
        assertEquals(1, repeating.requests.size)
    }

    @Test
    fun betterStackDetailRedactsSecretsAndBuildsRegionalSurfaces() = runTest {
        var incidents = 0
        val transport = FakeProviderTransport { request ->
            assertEquals("better-token", request.bearer)
            when {
                request.path == "/api/v2/monitors/7" -> ok(
                    json(
                        "data" to mapOf(
                            "id" to "7", "type" to "monitor",
                            "attributes" to mapOf(
                                "pronounceable_name" to "API", "status" to "up",
                                "proxy_host" to "proxy-user:proxy-password@proxy.example.com:8080",
                                "request_headers" to listOf(mapOf("name" to "Authorization", "value" to "secret")),
                                "check_frequency" to 30,
                            ),
                        ),
                    ),
                )
                request.path.endsWith("/response-times") -> ok(
                    json(
                        "data" to mapOf(
                            "id" to "7-response-times",
                            "attributes" to mapOf(
                                "regions" to listOf(
                                    mapOf(
                                        "region" to "us",
                                        "response_times" to listOf(
                                            mapOf("at" to "2026-07-15T12:00:00Z", "response_time" to 123, "name_lookup_time" to 12),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                request.path.endsWith("/sla") -> ok(json("data" to mapOf("attributes" to mapOf("uptime" to 99.99, "downtime" to 12))))
                request.path == "/api/v3/incidents" -> {
                    incidents += 1
                    if (incidents == 1) {
                        assertEquals("7", request.query["monitor_id"])
                        ok(
                            json(
                                "data" to listOf(mapOf("id" to "incident-1", "attributes" to mapOf("status" to "resolved", "cause" to "timeout"))),
                                "links" to mapOf("next" to "https://uptime.betterstack.com/api/v3/incidents?page=2"),
                            ),
                        )
                    } else {
                        assertEquals("2", request.query["page"])
                        ok(json("data" to listOf(mapOf("id" to "incident-2", "attributes" to mapOf("status" to "resolved"))), "links" to mapOf("next" to null)))
                    }
                }
                else -> FakeResponse(404)
            }
        }
        val payload = testApi(transport).detail(SiteDetailRequest.BetterStack("7", SecretValue.of("better-token"), testRange()))

        val attributes = payload.rawResponses.getValue("monitor")["data"]?.get("attributes")
        assertEquals(ProviderJsonValue.Str("[REDACTED]"), attributes?.get("request_headers"))
        assertEquals("proxy.example.com:8080", attributes?.get("proxy_host")?.stringValue)
        assertEquals("us", payload.tables.first { it.id == "betterstack.response-times" }.rows.first()["attributes.region"]?.stringValue)
        assertEquals(123.0, payload.series.single().points.single().values["us.response_time"])
        assertEquals("us · Response time", payload.series.single().metricLabels["us.response_time"])
        assertTrue(payload.sections.any { it.id == "betterstack.sla" })
        assertEquals(2, payload.rawResponses["incidents"]?.arrayValue?.size)
        assertEquals(2, payload.tables.first { it.id == "betterstack.incidents" }.rows.size)
        assertEquals("API", payload.title)
        assertEquals(5, transport.requests.size)
    }

    @Test
    fun betterStackDetailRefusesCrossOriginIncidentLinks() = runTest {
        val transport = FakeProviderTransport { request ->
            when {
                request.path == "/api/v3/incidents" -> ok(json("data" to emptyList<Any>(), "links" to mapOf("next" to "https://evil.example/api/v3/incidents?page=2")))
                else -> ok(json("data" to mapOf("attributes" to mapOf("url" to "https://studio.example"))))
            }
        }
        val payload = testApi(transport).detail(SiteDetailRequest.BetterStack("9", SecretValue.of("k"), testRange()))
        assertTrue(payload.warnings.any { it == "Better Stack incidents could not load: The provider returned an unsafe pagination URL." })
        assertEquals("https://studio.example", payload.title)
        assertFalse(transport.requests.any { it.uri.host == "evil.example" })
    }

    private fun monitor(id: Int, status: Int = 2, ratio: String = "100-99.9-99.95"): Map<String, Any?> = mapOf(
        "id" to id,
        "friendly_name" to "Monitor $id",
        "url" to "https://m$id.example",
        "status" to status,
        "custom_uptime_ratio" to ratio,
        "average_response_time" to "182.5",
        "logs" to listOf(mapOf("datetime" to 1_784_000_000, "type" to 2), mapOf("datetime" to 1_700_000_000, "type" to 1)),
    )

    private fun betterMonitor(id: String, status: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "monitor",
        "attributes" to mapOf(
            "pronounceable_name" to "Monitor $id",
            "url" to "https://m$id.example",
            "status" to status,
            "check_frequency" to 30,
            "team_name" to "ops",
            "last_checked_at" to "2026-07-15T00:00:00Z",
        ),
    )
}
