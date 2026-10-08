package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareZoneOperationsApiTest {
    private val transport = FakeCloudflareRestTransport()
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val api = CloudflareZoneOperationsApi(FakeCloudflareRestTransport.client(transport)) { now }

    private val record = """{"id":"rec-1","type":"A","name":"www.example.com","content":"203.0.113.10","proxied":true,"proxiable":true,"ttl":1,"tags":["env:prod"]}"""

    @Test
    fun fetchZoneParsesTheFullZone() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/zones/zone-1",
            envelope(
                """{"id":"zone-1","name":"example.com","status":"active","paused":false,"development_mode":0,
                "name_servers":["a.ns.cloudflare.com","b.ns.cloudflare.com"],"account":{"id":"acc","name":"Studio"},
                "plan":{"id":"free","name":"Free Website","price":0,"currency":"USD"},"meta":{"phishing_detected":false},
                "permissions":["#zone:read"],"original_registrar":"namecheap"}""",
            ),
        )
        val zone = api.fetchZone("zone-1")
        assertEquals("example.com", zone.name)
        assertTrue(zone.isActive)
        assertEquals(listOf("a.ns.cloudflare.com", "b.ns.cloudflare.com"), zone.nameServers)
        assertEquals("acc", zone.accountId)
        assertEquals("Free Website", zone.plan?.name)
        assertEquals("namecheap", zone.originalRegistrar)
        assertEquals(setOf("phishing_detected"), zone.meta.keys)
    }

    @Test
    fun dnsRecordsAreFetchedWithOneHundredPerPage() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/dns_records", envelope("[$record]", """{"page":1,"total_pages":1}"""))
        val records = api.fetchDnsRecords("z")
        assertEquals("www.example.com", records.single().name)
        assertEquals(listOf("env:prod"), records.single().tags)
        assertEquals("100", transport.requests.single().query.first { it.first == "per_page" }.second)
    }

    @Test
    fun createDnsRecordPostsTheInputAndRequiresZoneConfirmation() = runTest {
        val input = CloudflareDnsRecordInput(type = "a", name = "www", content = "203.0.113.10", ttl = 300, proxied = true, tags = listOf("env:prod"))
        assertConfirmationRequired { api.createDnsRecord("z", input, CloudflareMutationConfirmation("other")) }
        assertTrue(transport.requests.isEmpty())

        transport.enqueueJson(CloudflareHttpMethod.POST, "/zones/z/dns_records", envelope(record))
        val created = api.createDnsRecord("z", input, CloudflareMutationConfirmation("z"))
        assertEquals("rec-1", created.id)
        val sent = transport.requests.single()
        assertEquals(CloudflareHttpMethod.POST, sent.method)
        val body = sent.bodyJson!!
        assertEquals("A", body.str("type"))
        assertEquals("www", body.str("name"))
        assertEquals("300", body.str("ttl"))
        assertEquals(true, body.bool("proxied"))
        assertEquals(listOf("env:prod"), body.arr("tags").map { it.stringValue })
        assertFalse("Null optionals are omitted", body.objectValue!!.containsKey("comment"))
        assertFalse(body.objectValue!!.containsKey("priority"))
    }

    @Test
    fun updateDnsRecordPutsToTheRecordAndRequiresRecordConfirmation() = runTest {
        val input = CloudflareDnsRecordInput(type = "TXT", name = "@", content = "v=spf1 -all")
        assertConfirmationRequired { api.updateDnsRecord("z", "rec-1", input, CloudflareMutationConfirmation("z")) }
        transport.enqueueJson(CloudflareHttpMethod.PUT, "/zones/z/dns_records/rec-1", envelope(record))
        api.updateDnsRecord("z", "rec-1", input, CloudflareMutationConfirmation("rec-1"))
        val sent = transport.requests.single()
        assertEquals(CloudflareHttpMethod.PUT, sent.method)
        assertEquals("v=spf1 -all", sent.bodyJson.str("content"))
    }

    @Test
    fun deleteDnsRecordSendsDeleteOnlyWithItsConfirmation() = runTest {
        assertConfirmationRequired { api.deleteDnsRecord("z", "rec-1", CloudflareMutationConfirmation("rec-2")) }
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/rec-1", envelope("""{"id":"rec-1"}"""))
        api.deleteDnsRecord("z", "rec-1", CloudflareMutationConfirmation("rec-1"))
        assertEquals("DELETE /zones/z/dns_records/rec-1", transport.requests.single().toString())
        assertNull(transport.requests.single().bodyText)
    }

    @Test
    fun dnsValidationBlocksRequestsWithIosCopy() = runTest {
        val cases = mapOf(
            CloudflareDnsRecordInput(type = " ", name = "www", content = "x") to "Choose a DNS record type.",
            CloudflareDnsRecordInput(type = "A", name = " ", content = "x") to "Enter a DNS record name.",
            CloudflareDnsRecordInput(type = "A", name = "www", content = "x", ttl = 10) to
                "DNS TTL must be Automatic (1) or between 30 and 86,400 seconds.",
            CloudflareDnsRecordInput(type = "A", name = "www", content = "x", ttl = 90_000) to
                "DNS TTL must be Automatic (1) or between 30 and 86,400 seconds.",
            CloudflareDnsRecordInput(type = "A", name = "www") to "Enter record content or structured record data.",
        )
        cases.forEach { (input, message) ->
            val error = runCatching { api.createDnsRecord("z", input, CloudflareMutationConfirmation("z")) }.exceptionOrNull()
            assertEquals(message, (error as CloudflareOperationException).userMessage)
        }
        validateCloudflareDnsRecord(CloudflareDnsRecordInput(type = "A", name = "www", content = "x", ttl = 30))
        validateCloudflareDnsRecord(
            CloudflareDnsRecordInput(type = "SRV", name = "_sip._tcp", data = ProviderJsonValue.from(mapOf("port" to 5060)) as ProviderJsonValue.Obj),
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun cachePurgeBodiesMatchEachScope() = runTest {
        val bodies = listOf(
            CloudflareCachePurge.Everything to """{"purge_everything":true}""",
            CloudflareCachePurge.Files(listOf("https://example.com/a")) to """{"files":["https://example.com/a"]}""",
            CloudflareCachePurge.Tags(listOf("t1", "t2")) to """{"tags":["t1","t2"]}""",
            CloudflareCachePurge.Hosts(listOf("www.example.com")) to """{"hosts":["www.example.com"]}""",
            CloudflareCachePurge.Prefixes(listOf("example.com/blog")) to """{"prefixes":["example.com/blog"]}""",
        )
        bodies.forEach { (purge, body) ->
            transport.enqueueJson(CloudflareHttpMethod.POST, "/zones/z/purge_cache", envelope("""{"id":"z"}"""))
            api.purgeCache("z", purge, CloudflareMutationConfirmation("z"))
            assertEquals(body, transport.requests.last().bodyText)
        }
        assertConfirmationRequired { api.purgeCache("z", CloudflareCachePurge.Everything, CloudflareMutationConfirmation("x")) }
        val invalid = runCatching { api.purgeCache("z", CloudflareCachePurge.Files(listOf("a", " ")), CloudflareMutationConfirmation("z")) }
        assertEquals("Cache purge values cannot be empty.", (invalid.exceptionOrNull() as CloudflareOperationException).userMessage)
        assertEquals(5, transport.requests.size)
    }

    @Test
    fun dnssecEnableAndDisableUseThePathConfirmation() = runTest {
        val resource = CloudflareZoneResourceIds.dnssec("z")
        assertEquals("/zones/z/dnssec", resource)
        assertConfirmationRequired { api.enableDnssec("z", CloudflareMutationConfirmation("z")) }
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/dnssec", envelope("""{"status":"active","key_tag":42,"ds":"example.com. 3600 IN DS 2371 13 2 ABC"}"""))
        val status = api.enableDnssec("z", CloudflareMutationConfirmation(resource))
        assertTrue(status.isActive)
        assertEquals(42, status.keyTag)
        assertEquals("""{"status":"active"}""", transport.requests.last().bodyText)

        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/dnssec", envelope("\"2026-10-09\""))
        api.disableDnssec("z", CloudflareMutationConfirmation(resource))
        assertEquals(CloudflareHttpMethod.DELETE, transport.requests.last().method)
    }

    @Test
    fun zoneSettingsAreParsedAndPatchedWithTheSettingConfirmation() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/zones/z/settings",
            envelope("""[{"id":"ssl","value":"full","editable":true},{"value":"no-id"},{"id":"minify","value":{"css":"on"},"editable":true}]"""),
        )
        val settings = api.fetchZoneSettings("z")
        assertEquals(listOf("ssl", "minify"), settings.map { it.id })
        assertTrue(settings[0].isScalar)
        assertFalse(settings[1].isScalar)

        assertConfirmationRequired {
            api.updateZoneSetting("z", "ssl", ProviderJsonValue.Str("strict"), CloudflareMutationConfirmation("/zones/z/settings/tls"))
        }
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/settings/ssl", envelope("""{"id":"ssl","value":"strict","editable":true}"""))
        val updated = api.updateZoneSetting(
            "z",
            "ssl",
            ProviderJsonValue.Str("strict"),
            CloudflareMutationConfirmation(CloudflareZoneResourceIds.setting("z", "ssl")),
        )
        assertEquals(ProviderJsonValue.Str("strict"), updated.value)
        assertEquals("""{"value":"strict"}""", transport.requests.last().bodyText)
    }

    @Test
    fun dnsSettingsPatchOnlyChangedFieldsAndRejectEmptyChanges() = runTest {
        val confirmation = CloudflareMutationConfirmation("/zones/z/dns_settings")
        val empty = runCatching { api.updateDnsSettings("z", emptyMap(), confirmation) }.exceptionOrNull()
        assertEquals("Change at least one DNS setting before saving.", (empty as CloudflareOperationException).userMessage)
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/dns_settings", envelope("""{"flatten_all_cnames":true,"ns_ttl":3600}"""))
        val updated = api.updateDnsSettings(
            "z",
            linkedMapOf("flatten_all_cnames" to ProviderJsonValue.Bool(true), "ns_ttl" to ProviderJsonValue.Num.of(3600)),
            confirmation,
        )
        assertEquals(true, updated.flattenAllCnames)
        assertEquals("""{"flatten_all_cnames":true,"ns_ttl":3600}""", transport.requests.single().bodyText)
    }

    @Test
    fun activationCheckIsAnEmptyPut() = runTest {
        assertConfirmationRequired { api.requestActivationCheck("z", CloudflareMutationConfirmation("z")) }
        transport.enqueueJson(CloudflareHttpMethod.PUT, "/zones/z/activation_check", envelope("""{"id":"z"}"""))
        assertEquals("z", api.requestActivationCheck("z", CloudflareMutationConfirmation("/zones/z/activation_check")))
        assertNull(transport.requests.single().bodyText)
    }

    @Test
    fun dnsUsageAndAnalyticsReportAreParsed() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/dns_records/usage", envelope("""{"record_usage":120,"record_quota":200}"""))
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/zones/z/dns_analytics/report",
            envelope("""{"rows":1,"totals":{"queryCount":1500,"uncachedCount":20,"staleCount":0},"data_lag":60}"""),
        )
        val usage = api.fetchDnsUsage("z")
        assertEquals(80, usage.available)
        val report = api.fetchDnsAnalytics("z", now.minusSeconds(86_400), now)
        assertEquals(1500.0, report.total("queryCount")!!, 0.0)
        assertEquals(60.0, report.dataLag, 0.0)
        val query = transport.requests.last().query.toMap()
        assertEquals("queryCount,uncachedCount,staleCount", query["metrics"])
        assertEquals("2026-10-08T12:00:00Z", query["since"])
        assertEquals("2026-10-09T12:00:00Z", query["until"])
        assertEquals("1", query["limit"])
        val invalid = runCatching { api.fetchDnsAnalytics("z", now, now) }.exceptionOrNull()
        assertEquals("The DNS analytics start date must be before the end date.", (invalid as CloudflareOperationException).userMessage)
    }

    @Test
    fun securityLevelAndAccessRuleMutationsRequireTheirPaths() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/settings/security_level", envelope("""{"id":"security_level","value":"medium"}"""))
        assertEquals("medium", api.fetchSecurityLevel("z"))

        assertConfirmationRequired { api.updateSecurityLevel("z", "high", CloudflareMutationConfirmation("z")) }
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/settings/security_level", envelope("""{"value":"high"}"""))
        assertEquals("high", api.updateSecurityLevel("z", "high", CloudflareMutationConfirmation("/zones/z/settings/security_level")))
        assertEquals("""{"value":"high"}""", transport.requests.last().bodyText)

        assertConfirmationRequired {
            api.createAccessRule("z", "ip", "203.0.113.1", "block", null, CloudflareMutationConfirmation("/zones/z/settings/security_level"))
        }
        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "/zones/z/firewall/access_rules/rules",
            envelope("""{"id":"rule-1","mode":"block","configuration":{"target":"ip","value":"203.0.113.1"}}"""),
        )
        val created = api.createAccessRule("z", "ip", "203.0.113.1", "block", "  ", CloudflareMutationConfirmation("/zones/z/firewall/access_rules/rules"))
        assertEquals("203.0.113.1", created.title)
        assertEquals("block", created.status)
        assertEquals("""{"mode":"block","configuration":{"target":"ip","value":"203.0.113.1"}}""", transport.requests.last().bodyText)

        transport.enqueueJson(CloudflareHttpMethod.POST, "/zones/z/firewall/access_rules/rules", envelope("""{"id":"rule-2"}"""))
        api.createAccessRule("z", "country", "XX", "challenge", " spam ", CloudflareMutationConfirmation("/zones/z/firewall/access_rules/rules"))
        assertEquals("spam", transport.requests.last().bodyJson.str("notes"))

        assertConfirmationRequired { api.deleteAccessRule("z", "rule-1", CloudflareMutationConfirmation("/zones/z/firewall/access_rules/rules")) }
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/firewall/access_rules/rules/rule-1", envelope("""{"id":"rule-1"}"""))
        api.deleteAccessRule("z", "rule-1", CloudflareMutationConfirmation("/zones/z/firewall/access_rules/rules/rule-1"))
        assertEquals(CloudflareHttpMethod.DELETE, transport.requests.last().method)
    }

    @Test
    fun securityItemsFollowPagesButWafRulesetsDoNot() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/rate_limits", envelope("""[{"id":"r1","description":"Login"}]""", """{"page":1,"total_pages":2}"""))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/rate_limits", envelope("""[{"id":"r2","description":"API"}]""", """{"page":2,"total_pages":2}"""))
        val limits = api.fetchSecurityItems("z", CloudflareSecurityCategory.RATE_LIMITS)
        assertEquals(listOf("r1", "r2"), limits.map { it.id })
        assertEquals(listOf("1", "2"), transport.requests.map { request -> request.query.first { it.first == "page" }.second })

        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/rulesets", envelope("""[{"id":"rs","name":"Managed","phase":"http_request_firewall_managed","kind":"managed"}]""", """{"total_pages":5}"""))
        val rulesets = api.fetchSecurityItems("z", CloudflareSecurityCategory.WAF_RULESETS)
        assertEquals("Managed", rulesets.single().title)
        assertEquals("http_request_firewall_managed · managed", rulesets.single().subtitle)
        assertTrue(transport.requests.last().query.none { it.first == "page" })
    }

    @Test
    fun securityCursorsAreFollowedAndRepeatsStopSafely() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/page_shield/policies", envelope("""[{"id":"p1"}]""", """{"cursors":{"after":"c1"}}"""))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/page_shield/policies", envelope("""[{"id":"p2"}]""", """{}"""))
        assertEquals(listOf("p1", "p2"), api.fetchSecurityItems("z", CloudflareSecurityCategory.PAGE_SHIELD).map { it.id })
        assertEquals("c1", transport.requests.last().query.toMap()["cursor"])

        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/firewall/access_rules/rules", envelope("""[{"id":"a"}]""", """{"cursors":{"after":"loop"}}"""))
        val error = runCatching { api.fetchSecurityItems("z", CloudflareSecurityCategory.ACCESS_RULES) }.exceptionOrNull()
        assertTrue(error is CloudflareOperationException)
    }

    @Test
    fun certificatesCombineEdgePacksAndOptionalCustomCertificates() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/ssl/certificate_packs", envelope("""[{"id":"pack","type":"universal","status":"active"}]"""))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/custom_certificates", failure("Not entitled"), status = 403)
        val certificates = api.fetchSecurityItems("z", CloudflareSecurityCategory.CERTIFICATES)
        assertEquals(listOf("pack"), certificates.map { it.id })
        assertEquals("all", transport.requests.first().query.toMap()["status"])

        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/ssl/certificate_packs", envelope("[]"))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/custom_certificates", "oops", status = 500)
        assertTrue(runCatching { api.fetchSecurityItems("z", CloudflareSecurityCategory.CERTIFICATES) }.isFailure)
    }

    @Test
    fun singleObjectSecurityResultsBecomeOneItem() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/bot_management", envelope("""{"fight_mode":true,"enabled":true}"""))
        val bots = api.fetchSecurityItems("z", CloudflareSecurityCategory.BOT_MANAGEMENT).single()
        assertEquals("Bot management-0", bots.id)
        assertEquals("Bot management-0", bots.title)
        assertEquals("Enabled", bots.status)
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/api_gateway/configuration", "")
        assertTrue(api.fetchSecurityItems("z", CloudflareSecurityCategory.API_SHIELD).isEmpty())
    }

    @Test
    fun rulesetRulesAreParsed() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/zones/z/rulesets/rs",
            envelope("""{"id":"rs","rules":[{"id":"rule","description":"Block bad bots","action":"block"},{"action":"log"}]}"""),
        )
        val rules = api.fetchRulesetRules("z", "rs")
        assertEquals("Block bad bots", rules[0].title)
        assertEquals("block", rules[0].status)
        assertEquals("WAF rulesets-1", rules[1].id)
    }

    @Test
    fun zoneAnalyticsPlansQueriesAndParsesGraphQl() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.POST, "/graphql", SETTINGS)
        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "/graphql",
            """{"data":{"viewer":{"zones":[{"totals":[{"sum":{"requests":300,"pageViews":30,"bytes":4096,"cachedRequests":150,"cachedBytes":2048,"threats":2,"encryptedRequests":270},"uniq":{"uniques":12}}],
            "series":[{"dimensions":{"datetime":"2026-10-09T11:00:00Z"},"sum":{"requests":200},"uniq":{"uniques":7}},{"dimensions":{"datetime":"2026-10-09T10:00:00Z"},"sum":{"requests":100},"uniq":{"uniques":5}},{"dimensions":{}}]}]}},"errors":null}""",
        )
        val from = now.minusSeconds(86_400)
        val summary = api.fetchZoneAnalytics("zone-1", from, now)
        assertEquals(CloudflareAnalyticsGranularity.HOURLY, summary.granularity)
        assertFalse(summary.isWindowLimited)
        assertEquals(300, summary.totals.requests)
        assertEquals(50.0, summary.totals.cacheHitRate!!, 0.001)
        assertEquals(90.0, summary.totals.encryptedRequestRate!!, 0.001)
        assertEquals(listOf(100L, 200L), summary.series.map { it.metrics.requests })
        assertEquals("REQUESTS · LAST 1 DAY", summary.chartTitle)

        val settingsBody = transport.requests[0].bodyJson!!
        assertTrue(settingsBody.str("query")!!.contains("httpRequests1hGroups { enabled maxDuration maxPageSize notOlderThan }"))
        assertEquals("zone-1", settingsBody.obj("variables").str("zoneTag"))
        val trafficBody = transport.requests[1].bodyJson!!
        assertTrue(trafficBody.str("query")!!.contains("series: httpRequests1hGroups(limit: 25, orderBy: [datetime_ASC]"))
        assertEquals("2026-10-08T12:00:00Z", trafficBody.obj("variables").str("start"))
        assertEquals("2026-10-09T12:00:00Z", trafficBody.obj("variables").str("end"))
    }

    @Test
    fun zoneAnalyticsSurfacesGraphQlErrorsAndMissingDatasets() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.POST, "/graphql", """{"data":null,"errors":[{"message":"zone not authorized"}]}""")
        val unauthorized = runCatching { api.fetchZoneAnalytics("z", now.minusSeconds(3_600), now) }.exceptionOrNull()
        assertEquals("zone not authorized", (unauthorized as CloudflareOperationException).userMessage)
        assertEquals(CloudflareOperationException.Kind.GRAPHQL, unauthorized.kind)

        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "/graphql",
            """{"data":{"viewer":{"zones":[{"settings":{"hourly":{"enabled":false,"maxDuration":3600},"daily":{"enabled":true,"maxDuration":0}}}]}}}""",
        )
        val none = runCatching { api.fetchZoneAnalytics("z", now.minusSeconds(3_600), now) }.exceptionOrNull()
        assertEquals(CloudflareZoneAnalyticsPlanner.NO_DATASET_MESSAGE, (none as CloudflareOperationException).userMessage)

        val inverted = runCatching { api.fetchZoneAnalytics("z", now, now) }.exceptionOrNull()
        assertEquals("The analytics start date must be before the end date.", (inverted as CloudflareOperationException).userMessage)
    }

    @Test
    fun breakdownsAreSortedByTraffic() = runTest {
        val plan = CloudflareAnalyticsQueryPlan(CloudflareAnalyticsGranularity.DAILY, now.minusSeconds(86_400 * 7), now, 8, false)
        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "/graphql",
            """{"data":{"viewer":{"zones":[{"totals":[{"sum":{"encryptedBytes":512,
            "countryMap":[{"clientCountryName":"IN","requests":5,"bytes":10,"threats":1},{"clientCountryName":"US","requests":9,"bytes":20,"threats":0},{"clientCountryName":"ae","requests":5}],
            "responseStatusMap":[{"edgeResponseStatus":200,"requests":40},{"edgeResponseStatus":404,"requests":2}],
            "browserMap":[{"uaBrowserFamily":"Chrome","pageViews":8}],
            "threatPathingMap":[{"threatPathingName":"bot","requests":3}]}}]}]}}}""",
        )
        val breakdowns = api.fetchZoneAnalyticsBreakdowns("z", now.minusSeconds(86_400 * 7), now, plan)
        assertEquals(listOf("US", "ae", "IN"), breakdowns.countries.map { it.label })
        assertEquals(listOf("200", "404"), breakdowns.statusCodes.map { it.label })
        assertEquals(8, breakdowns.browsers.single().pageViews)
        assertEquals(3, breakdowns.threatTypes.single().threats)
        assertEquals(512, breakdowns.encryptedBytes)
        assertTrue(transport.requests.single().bodyJson.str("query")!!.contains("httpRequests1dGroups(limit: 1, filter: { date_geq: \$start, date_leq: \$end })"))
        assertEquals("2026-10-02", transport.requests.single().bodyJson.obj("variables").str("start"))
    }

    private suspend fun assertConfirmationRequired(block: suspend () -> Unit) {
        val before = transport.requests.size
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(CloudflareOperationException.Kind.CONFIRMATION_REQUIRED, (error as CloudflareOperationException).kind)
        assertEquals("A mismatched confirmation must not send a request", before, transport.requests.size)
    }

    companion object {
        const val SETTINGS = """{"data":{"viewer":{"zones":[{"settings":{"hourly":{"enabled":true,"maxDuration":259200,"maxPageSize":10000,"notOlderThan":2678400},"daily":{"enabled":true,"maxDuration":31536000,"maxPageSize":10000,"notOlderThan":31536000}}}]}}}"""
    }
}
