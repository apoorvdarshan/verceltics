package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareGraphQLDatasetsTest {
    @Test
    fun selectionBuildsNestedGraphQLFromUnderscorePaths() {
        assertEquals("__typename", CloudflareGraphQLDatasets.selection(emptyList()))
        assertEquals(
            "count dimensions { date } sum { bytes requests }",
            CloudflareGraphQLDatasets.selection(listOf("sum_requests", "dimensions_date", "count", "sum_bytes")),
        )
        // A direct field wins over nested paths with the same prefix (iOS `hasDirectField`).
        assertEquals("sum", CloudflareGraphQLDatasets.selection(listOf("sum", "sum_requests")))
        assertEquals("a { b { c d } }", CloudflareGraphQLDatasets.selection(listOf("a_b_c", "a_b_d")))
        val many = (1..20).map { "field$it" }
        assertEquals(12, CloudflareGraphQLDatasets.selection(many).split(' ').size)
    }

    @Test
    fun datasetPresetsAreReadOnlyGraphQLQueriesForTheExplorer() {
        val dataset = CloudflareGraphQLDataset("httpRequests1dGroups", "", true, listOf("sum_requests", "dimensions_date"), 1, 2, 3, 4)
        val zone = CloudflareGraphQLDatasets.preset(dataset, CloudflareGraphQLScope.ZONE, "acc", "zone-1")
        assertEquals("graphql-httpRequests1dGroups", zone.id)
        assertEquals(CloudflareHttpMethod.POST, zone.method)
        assertEquals("/graphql", zone.path)
        assertTrue(zone.readOnlyGraphQL)
        assertEquals(listOf("Analytics Read"), zone.permissions)
        val query = ProviderJsonParser.parse(zone.body)["query"]?.stringValue
        assertEquals(
            "query { viewer { zones(filter: { zoneTag: \"zone-1\" }) { httpRequests1dGroups(limit: 10) { dimensions { date } sum { requests } } } } }",
            query,
        )
        assertFalse(CloudflareExplorerRequestBuilder.requiresWriteConfirmation(CloudflareExplorerDraft.from(zone)))

        val missingZone = CloudflareGraphQLDatasets.preset(dataset, CloudflareGraphQLScope.ZONE, "acc", null)
        assertTrue(missingZone.body.contains("ZONE_ID"))
        val account = CloudflareGraphQLDatasets.preset(dataset, CloudflareGraphQLScope.ACCOUNT, "acc-9", "zone-1")
        assertTrue(account.body.contains("accounts(filter: { accountTag: \\\"acc-9\\\" })"))
        assertEquals(listOf("Account Analytics Read"), account.permissions)
    }

    @Test
    fun settingsQueriesAndVariablesFollowScope() {
        val zoneQuery = CloudflareGraphQLDatasets.settingsQuery(CloudflareGraphQLScope.ZONE, listOf("a", "b"))
        assertTrue(zoneQuery.contains("query DatasetSettings(\$tag: string)"))
        assertTrue(zoneQuery.contains("zones(filter: { zoneTag: \$tag })"))
        assertTrue(zoneQuery.contains("a { enabled availableFields maxDuration notOlderThan maxPageSize maxNumberOfFields }"))
        assertTrue(zoneQuery.contains("b { enabled"))
        assertTrue(
            CloudflareGraphQLDatasets.settingsQuery(CloudflareGraphQLScope.ACCOUNT, listOf("a"))
                .contains("accounts(filter: { accountTag: \$tag })"),
        )
        assertEquals("z", CloudflareGraphQLDatasets.settingsVariables(CloudflareGraphQLScope.ZONE, "z", "acc")["tag"]?.stringValue)
        assertEquals("", CloudflareGraphQLDatasets.settingsVariables(CloudflareGraphQLScope.ZONE, null, "acc")["tag"]?.stringValue)
        assertEquals("acc", CloudflareGraphQLDatasets.settingsVariables(CloudflareGraphQLScope.ACCOUNT, "z", "acc")["tag"]?.stringValue)
    }

    @Test
    fun introspectionAndSettingsParsing() {
        val fields = CloudflareGraphQLDatasets.parseIntrospection(
            ProviderJsonParser.parse(
                """{"data":{"__type":{"fields":[
                    {"name":"settings","description":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"OBJECT","name":"ZoneSettings"}}},
                    {"name":"httpRequests1dGroups","description":"Daily","type":{"kind":"LIST","name":null,"ofType":{"kind":"NON_NULL","name":null,"ofType":{"kind":"OBJECT","name":"Group"}}}},
                    {"description":"nameless"}
                ]}}}""",
            ),
            "Zone",
        )
        assertEquals(listOf("settings", "httpRequests1dGroups"), fields.map { it.name })
        assertEquals("ZoneSettings", fields[0].typeName)
        assertEquals("Group", fields[1].typeName)
        assertEquals("", fields[0].description)
        assertEquals(
            "Cloudflare did not return fields for GraphQL type Zone.",
            assertThrows(CloudflareToolsException::class.java) {
                CloudflareGraphQLDatasets.parseIntrospection(ProviderJsonParser.parse("""{"data":{"__type":null}}"""), "Zone")
            }.message,
        )
        assertNull(CloudflareGraphQLDatasets.deepTypeName(null))

        val settings = CloudflareGraphQLDatasets.parseSettings(
            ProviderJsonParser.parse(
                """{"data":{"viewer":{"zones":[{"settings":{"b":{"enabled":true,"availableFields":["x","y"],"maxDuration":86400,"notOlderThan":2678400,"maxPageSize":10000,"maxNumberOfFields":30},"a":{"enabled":false}}}]}}}""",
            ),
            CloudflareGraphQLScope.ZONE,
        )
        val datasets = CloudflareGraphQLDatasets.sorted(
            listOf(
                CloudflareGraphQLDatasets.dataset(CloudflareGraphQLIntrospectionField("a", "A", null), settings["a"]),
                CloudflareGraphQLDatasets.dataset(CloudflareGraphQLIntrospectionField("c", "C", null), settings["c"]),
                CloudflareGraphQLDatasets.dataset(CloudflareGraphQLIntrospectionField("b", "B", null), settings["b"]),
            ),
        )
        assertEquals(listOf("b", "a", "c"), datasets.map { it.name })
        assertEquals(CloudflareGraphQLDataset("b", "B", true, listOf("x", "y"), 86_400, 2_678_400, 10_000, 30), datasets[0])
        assertTrue(datasets[1].isLocked)
        assertNull(datasets[2].enabled)
        assertTrue(CloudflareGraphQLDatasets.parseSettings(ProviderJsonParser.parse("{}"), CloudflareGraphQLScope.ACCOUNT).isEmpty())
        assertTrue(datasets[0].matches("Y"))
        assertTrue(datasets[0].matches("  "))
        assertFalse(datasets[0].matches("zzz"))
    }

    @Test
    fun durationLabelsMatchIosAbbreviations() {
        assertEquals("Not returned", CloudflareGraphQLDatasets.durationLabel(null))
        assertEquals("Not returned", CloudflareGraphQLDatasets.durationLabel(0))
        assertEquals("30s", CloudflareGraphQLDatasets.durationLabel(30))
        assertEquals("1h", CloudflareGraphQLDatasets.durationLabel(3_600))
        assertEquals("1h 30m", CloudflareGraphQLDatasets.durationLabel(5_400))
        assertEquals("1d", CloudflareGraphQLDatasets.durationLabel(86_400))
        assertEquals("1d 1h", CloudflareGraphQLDatasets.durationLabel(90_000))
        assertEquals("31d", CloudflareGraphQLDatasets.durationLabel(2_678_400))
    }

    @Test
    fun loaderIntrospectsThenReadsSettingsInChunksOfTwenty() {
        val datasetNames = (1..25).map { "dataset$it" }
        val transport = RecordingToolsTransport { request ->
            val body = ProviderJsonParser.parse(String(request.bodyCopy()!!))
            val query = body["query"]!!.stringValue!!
            val name = body["variables"]?.get("name")?.stringValue
            when {
                name == "Account" -> jsonResponse(
                    200,
                    """{"data":{"__type":{"fields":[{"name":"workers","type":{"name":"W"}},{"name":"settings","type":{"kind":"NON_NULL","ofType":{"name":"AccountSettings"}}}]}}}""",
                )
                name == "AccountSettings" -> jsonResponse(
                    200,
                    """{"data":{"__type":{"fields":[{"name":"__typename"},""" +
                        datasetNames.joinToString(",") { """{"name":"$it","description":"d"}""" } + "]}}}",
                )
                query.contains("DatasetSettings") -> {
                    val requested = datasetNames.filter { Regex("\\b$it \\{").containsMatchIn(query) }
                    jsonResponse(
                        200,
                        """{"data":{"viewer":{"accounts":[{"settings":{""" +
                            requested.joinToString(",") { """"$it":{"enabled":${it != "dataset3"},"availableFields":["f"]}""" } +
                            "}}]}}}",
                    )
                }
                else -> jsonResponse(500, "{}")
            }
        }
        val datasets = CloudflareGraphQLDatasetLoader(CloudflareToolsApi(transport))
            .newLoadCall(CloudflareCredential.apiToken("token"), CloudflareGraphQLScope.ACCOUNT, "acc-1", null)
            .execute()
        assertEquals(25, datasets.size)
        assertEquals("dataset3", datasets.last().name)
        assertTrue(datasets.last().isLocked)
        assertEquals(4, transport.requests.size)
        val settingsVariables = transport.requests.drop(2).map {
            ProviderJsonParser.parse(String(it.bodyCopy()!!))["variables"]?.get("tag")?.stringValue
        }
        assertEquals(listOf("acc-1", "acc-1"), settingsVariables)
    }

    @Test
    fun loaderFailsClearlyWithoutSettingsType() {
        val transport = RecordingToolsTransport {
            jsonResponse(200, """{"data":{"__type":{"fields":[{"name":"httpRequests1dGroups","type":{"name":"G"}}]}}}""")
        }
        val error = assertThrows(CloudflareToolsException::class.java) {
            CloudflareGraphQLDatasetLoader(CloudflareToolsApi(transport))
                .newLoadCall(CloudflareCredential.apiToken("token"), CloudflareGraphQLScope.ZONE, "acc", "zone")
                .execute()
        }
        assertEquals("Cloudflare did not expose a settings type for zone analytics.", error.message)
    }
}
