package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareZoneDraftsAndPlannerTest {
    private fun record(json: String) = CloudflareDnsRecord.parse(ProviderJsonParser.parse(json))

    private fun messageOf(block: () -> Unit): String =
        (runCatching(block).exceptionOrNull() as CloudflareOperationException).userMessage

    // MARK: DNS record editor

    @Test
    fun dnsDraftValidatesNameContentPriorityAndJson() {
        assertEquals("Enter a DNS record name.", messageOf { CloudflareDnsRecordDraft(name = "  ", content = "x").toInput(null) })
        assertEquals("Enter record content or structured data.", messageOf { CloudflareDnsRecordDraft(name = "www").toInput(null) })
        assertEquals(
            "Priority must be a non-negative whole number.",
            messageOf { CloudflareDnsRecordDraft(type = "MX", name = "@", content = "mx.example.com", priority = "-1").toInput(null) },
        )
        assertEquals(
            "Priority must be a non-negative whole number.",
            messageOf { CloudflareDnsRecordDraft(type = "MX", name = "@", content = "mx.example.com", priority = "ten").toInput(null) },
        )
        assertEquals("Structured data must be a valid JSON object.", messageOf { CloudflareDnsRecordDraft(name = "x", dataJson = "[1]").toInput(null) })
        assertEquals("Settings must be a valid JSON object.", messageOf { CloudflareDnsRecordDraft(name = "x", content = "a", settingsJson = "{").toInput(null) })
    }

    @Test
    fun dnsDraftBuildsTheIosInput() {
        val input = CloudflareDnsRecordDraft(
            type = "MX",
            name = " @ ",
            content = " mx.example.com ",
            ttl = 3_600,
            proxied = true,
            comment = "  mail  ",
            tags = "a:1, , b:2 ",
            priority = "10",
        ).toInput(null)
        assertEquals("@", input.name)
        assertEquals("mx.example.com", input.content)
        assertNull("MX records cannot be proxied", input.proxied)
        assertEquals("mail", input.comment)
        assertEquals(listOf("a:1", "b:2"), input.tags)
        assertEquals(10, input.priority)
        assertNull(input.privateRouting)

        val structured = CloudflareDnsRecordDraft(type = "SRV", name = "_sip._tcp", dataJson = """{"port":5060,"weight":1}""").toInput(null)
        assertNull(structured.content)
        assertEquals("5060", structured.data.str("port"))
        assertNull(structured.tags)
    }

    @Test
    fun proxyAndPrivateRoutingFollowRecordTypes() {
        val a = CloudflareDnsRecordDraft(type = "A", name = "www", content = "203.0.113.1", proxied = true)
        assertEquals(true, a.toInput(null).proxied)
        assertNull("Private routing is omitted unless enabled or previously returned", a.toInput(null).privateRouting)
        assertEquals(true, a.copy(privateRouting = true).toInput(null).privateRouting)

        val txt = a.withType("TXT", null)
        assertFalse("Switching to a non-proxiable type clears the proxy flag", txt.proxied)
        assertNull(txt.toInput(null).proxied)

        val existing = record("""{"id":"r","type":"A","name":"www","content":"1.1.1.1","proxiable":false,"private_routing":false}""")
        assertFalse(a.canProxy(existing))
        assertNull(a.toInput(existing).proxied)
        assertEquals("Previously returned private routing is preserved", false, a.toInput(existing).privateRouting)
    }

    @Test
    fun draftFromRecordPrettyPrintsStructuredJson() {
        val existing = record(
            """{"id":"r","type":"CAA","name":"example.com","ttl":300,"tags":["x","y"],"priority":5,"data":{"tag":"issue","flags":0},"comment":"ca"}""",
        )
        val draft = CloudflareDnsRecordDraft.from(existing)
        assertEquals("CAA", draft.type)
        assertEquals(300, draft.ttl)
        assertEquals("x, y", draft.tags)
        assertEquals("5", draft.priority)
        assertEquals("{\n  \"flags\": 0,\n  \"tag\": \"issue\"\n}", draft.dataJson)
        assertEquals("", draft.settingsJson)
        assertEquals("1 hour", CloudflareDnsRecordDraft.ttlLabel(3_600))
        assertEquals("45 seconds", CloudflareDnsRecordDraft.ttlLabel(45))
        assertEquals(20, CloudflareDnsRecordDraft.RECORD_TYPES.size)
    }

    @Test
    fun dnsRecordSearchAndSortMatchIos() {
        val records = listOf(
            record("""{"id":"1","type":"TXT","name":"b.example.com","content":"hello","comment":"marketing"}"""),
            record("""{"id":"2","type":"A","name":"A.example.com","content":"1.1.1.1","tags":["team:web"]}"""),
            record("""{"id":"3","type":"AAAA","name":"A.example.com","content":"::1"}"""),
        )
        assertEquals(listOf("2", "3", "1"), records.sortedWith(CloudflareDnsRecord.DISPLAY_ORDER).map { it.id })
        assertTrue(records[0].matches("MARKETING"))
        assertTrue(records[1].matches("team:"))
        assertTrue(records[2].matches("aaaa"))
        assertFalse(records[2].matches("hello"))
    }

    // MARK: Cache purge

    @Test
    fun purgeKindsParseValuesAndRequireTheZoneNameForEverything() {
        assertEquals(
            CloudflareCachePurge.Files(listOf("https://a", "https://b", "https://c")),
            CloudflareCachePurgeKind.FILES.purge("https://a,\n https://b \n\nhttps://c", "", "example.com"),
        )
        assertEquals(CloudflareCachePurge.Tags(listOf("t")), CloudflareCachePurgeKind.TAGS.purge("t", "", "example.com"))
        assertEquals(CloudflareCachePurge.Hosts(listOf("h")), CloudflareCachePurgeKind.HOSTS.purge("h", "", "example.com"))
        assertEquals(CloudflareCachePurge.Prefixes(listOf("p")), CloudflareCachePurgeKind.PREFIXES.purge("p", "", "example.com"))
        assertEquals("Enter at least one urls.", messageOf { CloudflareCachePurgeKind.FILES.purge(" ,\n", "", "example.com") })
        assertEquals(
            "Type the exact zone name to confirm a full cache purge.",
            messageOf { CloudflareCachePurgeKind.EVERYTHING.purge("", "Example.com", "example.com") },
        )
        assertEquals(CloudflareCachePurge.Everything, CloudflareCachePurgeKind.EVERYTHING.purge("", "example.com", "example.com"))
        assertEquals(
            "Every cached object for example.com will be removed from Cloudflare’s edge.",
            CloudflareCachePurgeKind.EVERYTHING.confirmationMessage("example.com"),
        )
        assertEquals("Only the entered cache tags will be purged for example.com.", CloudflareCachePurgeKind.TAGS.confirmationMessage("example.com"))
    }

    // MARK: Zone settings and DNS settings editors

    @Test
    fun zoneSettingProposalsKeepTheValueType() {
        val onOff = ProviderJsonValue.Str("on")
        assertTrue(CloudflareZoneSettingEditing.isOnOffString(onOff))
        assertEquals(ProviderJsonValue.Str("off"), CloudflareZoneSettingEditing.proposedValue(onOff, "on", toggle = false))
        assertEquals(ProviderJsonValue.Str("strict"), CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.Str("full"), "strict", false))
        assertEquals(ProviderJsonValue.Num.of(7200), CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.Num.of(14400), " 7200 ", false))
        assertNull(CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.Num.of(14400), "1.5", false))
        assertEquals(ProviderJsonValue.Num.of(0.25), CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.Num.parse("0.5"), "0.25", false))
        assertEquals(ProviderJsonValue.Bool(true), CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.Bool(false), "", true))
        assertNull(CloudflareZoneSettingEditing.proposedValue(ProviderJsonValue.from(mapOf("a" to 1)), "x", true))
        assertEquals(listOf("off", "flexible", "full", "strict", "origin_pull"), CloudflareZoneSettingEditing.options("ssl"))
        assertTrue(CloudflareZoneSettingEditing.options("brotli").isEmpty())
        assertEquals(
            "This changes the live Cloudflare setting from full to strict.",
            CloudflareZoneSettingEditing.confirmationMessage(ProviderJsonValue.Str("full"), ProviderJsonValue.Str("strict")),
        )
        assertEquals("Min Tls Version", CloudflareZoneSetting.displayName("min_tls_version"))
    }

    @Test
    fun zoneSettingsSortCommonSettingsFirst() {
        val settings = listOf("zzz", "http3", "ssl", "aaa", "development_mode").map { CloudflareZoneSetting(it, ProviderJsonValue.Null, true, null) }
        assertEquals(listOf("ssl", "http3", "development_mode", "aaa", "zzz"), settings.sortedWith(CloudflareZoneSetting.DISPLAY_ORDER).map { it.id })
    }

    @Test
    fun dnsSettingsDraftSendsOnlyChangesAndValidatesTtl() {
        val original = CloudflareZoneDnsSettings(false, false, false, false, null, null, 86_400.0, "standard", null, null)
        val draft = CloudflareZoneDnsSettingsDraft.from(original)
        assertEquals("86400", draft.nameServerTtl)
        assertEquals("No DNS settings have changed.", messageOf { draft.validatedChanges(original) })

        val changed = draft.copy(flattenAllCnames = true, zoneMode = "dns_only", nameServerTtl = "3600")
        val changes = changed.validatedChanges(original)
        assertEquals(
            mapOf(
                "flatten_all_cnames" to ProviderJsonValue.Bool(true),
                "zone_mode" to ProviderJsonValue.Str("dns_only"),
                "ns_ttl" to ProviderJsonValue.Num.of(3600),
            ),
            changes,
        )
        assertEquals(
            "Nameserver TTL must be between 30 and 86,400 seconds.",
            messageOf { draft.copy(flattenAllCnames = true, nameServerTtl = "10").validatedChanges(original) },
        )
        assertFalse(draft.copy(nameServerTtl = "").ttlIsValid(original))

        val unreported = CloudflareZoneDnsSettings(null, null, null, null, null, null, null, null, null, null)
        val fromUnreported = CloudflareZoneDnsSettingsDraft.from(unreported)
        assertTrue(fromUnreported.ttlIsValid(unreported))
        assertEquals(
            "Unreturned booleans are sent as explicit values, as on iOS",
            setOf("flatten_all_cnames", "foundation_dns", "multi_provider", "secondary_overrides", "zone_mode"),
            fromUnreported.changes(unreported).keys,
        )
    }

    @Test
    fun accessRuleDraftPlaceholdersAndValidation() {
        assertEquals("IP address, e.g. 203.0.113.10", CloudflareAccessRuleDraft().valuePlaceholder)
        assertEquals("CIDR range, e.g. 203.0.113.0/24", CloudflareAccessRuleDraft(target = "ip_range").valuePlaceholder)
        assertEquals("ASN number", CloudflareAccessRuleDraft(target = "asn").valuePlaceholder)
        assertEquals("Two-letter country code", CloudflareAccessRuleDraft(target = "country").valuePlaceholder)
        assertFalse(CloudflareAccessRuleDraft(value = "  ").canSubmit)
        assertNull(CloudflareAccessRuleDraft(notes = "").notesOrNull)
        assertEquals("Js Challenge", cloudflareHumanize("js_challenge"))
    }

    // MARK: Security parsing

    @Test
    fun securityItemsUseIosTitleSubtitleAndStatusFallbacks() {
        val access = CloudflareSecurityParser.item(
            ProviderJsonParser.parse("""{"uuid":"u1","configuration":{"target":"ip","value":"198.51.100.4"},"mode":"challenge","notes":"x"}"""),
            CloudflareSecurityCategory.ACCESS_RULES,
            3,
        )
        assertEquals("u1", access.id)
        assertEquals("198.51.100.4", access.title)
        assertEquals("challenge", access.status)

        val described = CloudflareSecurityParser.item(
            ProviderJsonParser.parse("""{"id":"abcdefghijklmnopqrstuvwxyz","description":"Desc","type":"Desc","kind":"zone"}"""),
            CloudflareSecurityCategory.WAF_RULESETS,
            0,
        )
        assertEquals("Desc", described.title)
        assertEquals("Subtitle drops values equal to the title", "zone", described.subtitle)
        assertNull(described.status)

        val bare = CloudflareSecurityParser.item(ProviderJsonParser.parse("""{"id":"abcdefghijklmnopqrstuvwxyz","enabled":false}"""), CloudflareSecurityCategory.API_SHIELD, 0)
        assertEquals("abcdefghijklmnopqrst", bare.title)
        assertEquals("Disabled", bare.status)

        val scalar = CloudflareSecurityParser.item(ProviderJsonValue.Num.of(3), CloudflareSecurityCategory.RATE_LIMITS, 7)
        assertEquals("Rate limits-7", scalar.id)
        assertEquals("Rate limits", scalar.title)
        assertEquals("3", scalar.subtitle)

        val nested = CloudflareSecurityParser.items(ProviderJsonParser.parse("""{"items":[{"id":"a"},{"id":"b"}]}"""), CloudflareSecurityCategory.PAGE_SHIELD, 2)
        assertEquals(listOf("a", "b"), nested.map { it.id })
        assertEquals("2 items", CloudflareSecurityParser.displayValue(ProviderJsonParser.parse("[1,2]")))
        assertEquals("1 properties", CloudflareSecurityParser.displayValue(ProviderJsonParser.parse("""{"a":1}""")))
        assertEquals("Not returned", CloudflareSecurityParser.displayValue(ProviderJsonValue.Null))
    }

    // MARK: Analytics planning

    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private fun settings(maxDuration: Long?, maxPageSize: Int? = null, notOlderThan: Long? = null, enabled: Boolean? = true) =
        CloudflareAnalyticsDatasetSettings(enabled, maxDuration, maxPageSize, notOlderThan)

    @Test
    fun hourlyWinsWhenBothDatasetsCoverTheWindow() {
        val plan = CloudflareZoneAnalyticsPlanner.plan(
            hourly = settings(259_200, 10_000, 2_678_400),
            daily = settings(31_536_000, 10_000, 31_536_000),
            requestedFrom = now.minusSeconds(86_400),
            requestedTo = now,
            now = now,
        )
        assertEquals(CloudflareAnalyticsGranularity.HOURLY, plan.granularity)
        assertFalse(plan.isWindowLimited)
        assertEquals(25, plan.seriesLimit)
    }

    @Test
    fun dailyWinsWhenOnlyItCoversTheWindowAndIsNormalizedToWholeUtcDays() {
        val from = now.minusSeconds(30L * 86_400)
        val plan = CloudflareZoneAnalyticsPlanner.plan(
            hourly = settings(259_200, 10_000, 2_678_400),
            daily = settings(31_536_000, 10_000, 31_536_000),
            requestedFrom = from,
            requestedTo = now,
            now = now,
        )
        assertEquals(CloudflareAnalyticsGranularity.DAILY, plan.granularity)
        assertFalse(plan.isWindowLimited)
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), plan.from)
        assertEquals(30, plan.seriesLimit)
    }

    @Test
    fun windowsAreLimitedByRetentionAndPageWidth() {
        val limited = CloudflareZoneAnalyticsPlanner.candidate(
            CloudflareAnalyticsGranularity.HOURLY,
            settings(maxDuration = 604_800, maxPageSize = 25, notOlderThan = 86_400 * 3),
            requestedFrom = now.minusSeconds(86_400L * 365),
            requestedTo = now.plusSeconds(3_600),
            now = now,
        )!!
        assertTrue(limited.isWindowLimited)
        assertEquals("End is clamped to now", now, limited.to)
        assertEquals("Page width (24 hourly buckets) wins over retention", now.minusSeconds(24L * 3_600), limited.from)
        assertEquals(25, limited.seriesLimit)

        assertNull(CloudflareZoneAnalyticsPlanner.candidate(CloudflareAnalyticsGranularity.HOURLY, settings(0), now.minusSeconds(60), now, now))
        assertNull(CloudflareZoneAnalyticsPlanner.candidate(CloudflareAnalyticsGranularity.HOURLY, settings(600, enabled = false), now.minusSeconds(60), now, now))
        assertNull(CloudflareZoneAnalyticsPlanner.candidate(CloudflareAnalyticsGranularity.HOURLY, null, now.minusSeconds(60), now, now))
    }

    @Test
    fun widerLimitedWindowWinsAndDailyNormalizationDropsPartialDays() {
        val narrow = CloudflareAnalyticsQueryPlan(CloudflareAnalyticsGranularity.HOURLY, now.minusSeconds(3_600), now, 2, true)
        val wide = CloudflareAnalyticsQueryPlan(CloudflareAnalyticsGranularity.DAILY, now.minusSeconds(86_400 * 3), now, 3, true)
        assertTrue(CloudflareZoneAnalyticsPlanner.precedes(wide, narrow))
        assertFalse(CloudflareZoneAnalyticsPlanner.precedes(narrow, wide))
        val sameWidth = wide.copy(granularity = CloudflareAnalyticsGranularity.HOURLY)
        assertTrue(CloudflareZoneAnalyticsPlanner.precedes(sameWidth, wide))

        val partial = CloudflareAnalyticsQueryPlan(CloudflareAnalyticsGranularity.DAILY, Instant.parse("2026-10-09T01:00:00Z"), now, 5, false)
        assertNull("A window inside one partial day has no complete day", CloudflareZoneAnalyticsPlanner.normalizeDaily(partial))
        val midnight = partial.copy(from = Instant.parse("2026-10-08T00:00:00Z"))
        assertEquals(2, CloudflareZoneAnalyticsPlanner.normalizeDaily(midnight)!!.seriesLimit)
    }

    @Test
    fun variablesAndWindowLabelsMatchIos() {
        val from = Instant.parse("2026-10-02T06:30:15.700Z")
        assertEquals(
            mapOf("zoneTag" to "z", "start" to "2026-10-02", "end" to "2026-10-09"),
            CloudflareZoneAnalyticsPlanner.variables("z", from, now, CloudflareAnalyticsGranularity.DAILY),
        )
        assertEquals(
            mapOf("zoneTag" to "z", "start" to "2026-10-02T06:30:15Z", "end" to "2026-10-09T12:00:00Z"),
            CloudflareZoneAnalyticsPlanner.variables("z", from, now, CloudflareAnalyticsGranularity.HOURLY),
        )
        fun label(granularity: CloudflareAnalyticsGranularity, seconds: Long) = CloudflareZoneAnalyticsSummary(
            "z", now.minusSeconds(seconds), now, now.minusSeconds(seconds), now, granularity, false, CloudflareAnalyticsMetrics.ZERO, emptyList(),
        ).windowLabel
        assertEquals("LAST 8 DAYS", label(CloudflareAnalyticsGranularity.DAILY, 7L * 86_400))
        assertEquals("LAST 1 DAY", label(CloudflareAnalyticsGranularity.HOURLY, 86_400))
        assertEquals("LAST 6 HOURS", label(CloudflareAnalyticsGranularity.HOURLY, 6L * 3_600))
        assertEquals("LAST 1 MINUTE", label(CloudflareAnalyticsGranularity.HOURLY, 30))
    }

    @Test
    fun analyticsRangesSubtractCalendarUnits() {
        val end = Instant.parse("2026-03-10T12:00:00Z")
        assertEquals(end.minusSeconds(86_400), CloudflareAnalyticsRange.HOURS_24.dates(end, ZoneOffset.UTC)!!.first)
        assertEquals(Instant.parse("2025-03-10T12:00:00Z"), CloudflareAnalyticsRange.YEAR_1.dates(end, ZoneOffset.UTC)!!.first)
        assertNull(CloudflareAnalyticsRange.CUSTOM.dates(end))
        assertEquals(listOf("24H", "7D", "30D", "90D", "1Y", "CUSTOM"), CloudflareAnalyticsRange.entries.map { it.displayName })
    }

    @Test
    fun breakdownSortIsByTrafficThenLabel() {
        val sorted = CloudflareZoneAnalyticsPlanner.sorted(
            listOf(
                CloudflareAnalyticsBreakdownItem("b", 1, 0, 0, 0),
                CloudflareAnalyticsBreakdownItem("a", 0, 0, 0, 1),
                CloudflareAnalyticsBreakdownItem("C", 5, 0, 0, 0),
            ),
        )
        assertEquals(listOf("C", "a", "b"), sorted.map { it.label })
    }
}
