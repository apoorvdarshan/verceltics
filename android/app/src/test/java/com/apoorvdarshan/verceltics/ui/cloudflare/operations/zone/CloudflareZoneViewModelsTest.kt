package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAccessRuleDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAnalyticsRange
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareCachePurgeKind
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecordDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDnsSettingsDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApiTest
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareZoneViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val transport = FakeCloudflareRestTransport()
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val api = CloudflareZoneOperationsApi(FakeCloudflareRestTransport.client(transport)) { now }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Mutating requests; read-only GraphQL analytics POSTs are excluded. */
    private fun writes() = transport.mutations().filterNot { it.path == "/graphql" }

    private fun scriptZoneDetail() {
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z", envelope(ZONE))
        transport.alwaysJson(CloudflareHttpMethod.POST, "/graphql", CloudflareZoneOperationsApiTest.SETTINGS)
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/dns_records", envelope("[$RECORD_A,$RECORD_TXT]", """{"total_pages":1}"""))
    }

    private fun detailModel(mutations: MutableSharedFlow<CloudflareMutationEvent> = MutableSharedFlow()) =
        CloudflareZoneDetailViewModel(api, "z", "example.com", mutations) { now }

    @Test
    fun detailLoadsZoneAnalyticsAndSortedDnsOnce() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.onRefreshSignal(0)
        val state = model.state.value
        assertEquals("example.com", state.zone?.name)
        assertEquals(listOf("txt", "www"), state.dnsRecords.map { it.name.substringBefore('.') })
        assertNotNull("Analytics settings response parses into a plan; traffic query returns no zone", state.analytics)
        val requestsAfterFirstLoad = transport.requests.size

        model.onRefreshSignal(0)
        assertEquals("Re-appearing does not refetch", requestsAfterFirstLoad, transport.requests.size)
        model.onRefreshSignal(1)
        assertTrue("A new top-bar refresh signal forces a reload", transport.requests.size > requestsAfterFirstLoad)
    }

    @Test
    fun deletingADnsRecordWaitsForConfirmation() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.load()
        val record = model.state.value.dnsRecords.first { it.id == "a" }

        model.requestDeleteRecord(record)
        val prompt = model.confirmation.value!!
        assertEquals("Delete this DNS record?", prompt.title)
        assertEquals("www.example.com → 203.0.113.10 will be permanently removed.", prompt.message)
        assertEquals("Delete A Record", prompt.confirmLabel)
        assertTrue(prompt.destructive)
        assertTrue(writes().isEmpty())

        model.dismissPendingMutation()
        assertTrue("Dismissing sends nothing", writes().isEmpty())

        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/a", envelope("""{"id":"a"}"""))
        model.requestDeleteRecord(record)
        model.confirmPendingMutation()
        assertEquals("DELETE /zones/z/dns_records/a", writes().single().toString())
        assertEquals(listOf("t"), model.state.value.dnsRecords.map { it.id })
        assertEquals(CloudflareActionBanner("DNS record deleted.", isError = false), model.banner.value)
    }

    @Test
    fun savingARecordValidatesThenConfirmsThenReconciles() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.load()
        model.openNewRecord()

        model.requestSaveRecord(CloudflareDnsRecordDraft(name = "api"))
        assertEquals("Enter record content or structured data.", model.state.value.editorError)
        assertNull("Invalid input never reaches the confirmation", model.confirmation.value)

        model.requestSaveRecord(CloudflareDnsRecordDraft(type = "CNAME", name = "api", content = "origin.example.net", proxied = true))
        val prompt = model.confirmation.value!!
        assertEquals("Create this DNS record?", prompt.title)
        assertEquals("z", prompt.resourceId)
        assertTrue(prompt.message.startsWith("This changes live DNS for example.com."))
        assertTrue(prompt.message.contains("CNAME api → origin.example.net · TTL Automatic · Proxied"))
        assertTrue(writes().isEmpty())

        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "/zones/z/dns_records",
            envelope("""{"id":"new","type":"CNAME","name":"api.example.com","content":"origin.example.net","proxied":true}"""),
        )
        model.confirmPendingMutation()
        val sent = writes().single()
        assertEquals(CloudflareHttpMethod.POST, sent.method)
        assertEquals("CNAME", sent.bodyJson.str("type"))
        assertNull("Editor closes after a successful save", model.state.value.dnsEditor)
        assertEquals(listOf("api.example.com", "txt.example.com", "www.example.com"), model.state.value.dnsRecords.map { it.name })
        assertEquals("DNS record created.", model.banner.value?.message)
    }

    @Test
    fun failedSaveKeepsTheEditorOpenWithTheProviderError() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.load()
        val existing = model.state.value.dnsRecords.first { it.id == "a" }
        model.openEditRecord(existing)
        model.requestSaveRecord(CloudflareDnsRecordDraft.from(existing).copy(content = "203.0.113.11"))
        assertEquals("a", model.confirmation.value?.resourceId)
        assertEquals("Save Changes", model.confirmation.value?.confirmLabel)

        transport.enqueueJson(CloudflareHttpMethod.PUT, "/zones/z/dns_records/a", failure("An identical record already exists.", 81058), status = 400)
        model.confirmPendingMutation()
        assertEquals("Cloudflare request failed (400): An identical record already exists.", model.state.value.editorError)
        assertNotNull(model.state.value.dnsEditor)
        assertTrue(model.banner.value!!.isError)
    }

    @Test
    fun cachePurgeRequiresValidInputAndConfirmation() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.load()
        model.openPurge()
        model.requestPurge(CloudflareCachePurgeKind.EVERYTHING, "", "wrong")
        assertEquals("Type the exact zone name to confirm a full cache purge.", model.state.value.purgeError)
        assertNull(model.confirmation.value)

        model.requestPurge(CloudflareCachePurgeKind.FILES, "https://example.com/a\nhttps://example.com/b", "")
        val prompt = model.confirmation.value!!
        assertEquals("Purge selected cached content?", prompt.title)
        assertTrue(prompt.message.startsWith("Only the entered urls will be purged for example.com."))
        assertTrue(prompt.message.contains("https://example.com/b"))
        assertTrue(prompt.destructive)

        transport.enqueueJson(CloudflareHttpMethod.POST, "/zones/z/purge_cache", envelope("""{"id":"z"}"""))
        model.confirmPendingMutation()
        assertEquals("""{"files":["https://example.com/a","https://example.com/b"]}""", writes().single().bodyText)
        assertFalse(model.state.value.showingPurge)
        assertEquals("Cache purge accepted by Cloudflare.", model.banner.value?.message)
    }

    @Test
    fun zoneMutationsElsewhereRefreshTheZoneButDnsMutationsDoNot() = runTest(dispatcher) {
        scriptZoneDetail()
        val events = MutableSharedFlow<CloudflareMutationEvent>()
        val model = detailModel(events)
        model.load()
        val zoneFetches = { transport.requests.count { it.method == CloudflareHttpMethod.GET && it.path == "/zones/z" } }
        val before = zoneFetches()
        events.emit(CloudflareMutationEvent(CloudflareHttpMethod.PATCH, "/zones/z/settings/ssl"))
        assertEquals(before + 1, zoneFetches())
        events.emit(CloudflareMutationEvent(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/a"))
        events.emit(CloudflareMutationEvent(CloudflareHttpMethod.PATCH, "/zones/other/settings/ssl"))
        assertEquals(before + 1, zoneFetches())
    }

    @Test
    fun customRangesAreValidated() = runTest(dispatcher) {
        scriptZoneDetail()
        val model = detailModel()
        model.load()
        assertEquals("The start must be earlier than the end.", model.selectCustomRange(now, now.minusSeconds(1)))
        assertNull(model.selectCustomRange(now.minusSeconds(86_400 * 3), now))
        assertEquals(CloudflareAnalyticsRange.CUSTOM, model.state.value.range)
        model.selectRange(CloudflareAnalyticsRange.HOURS_24)
        assertEquals(CloudflareAnalyticsRange.HOURS_24, model.state.value.range)
    }

    // MARK: Zone operations

    private fun scriptZoneOperations() {
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z", envelope(ZONE))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/dnssec", envelope("""{"status":"disabled"}"""))
        transport.alwaysJson(
            CloudflareHttpMethod.GET,
            "/zones/z/settings",
            envelope("""[{"id":"brotli","value":"on","editable":true},{"id":"ssl","value":"full","editable":true},{"id":"minify","value":{"css":"on"},"editable":true}]"""),
        )
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/dns_settings", envelope("""{"flatten_all_cnames":false,"foundation_dns":false,"multi_provider":false,"secondary_overrides":false,"zone_mode":"standard","ns_ttl":86400}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/dns_records/usage", envelope("""{"record_usage":3}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/dns_analytics/report", failure("Not entitled"), status = 403)
    }

    @Test
    fun zoneOperationsLoadEverySectionAndKeepPartialFailures() = runTest(dispatcher) {
        scriptZoneOperations()
        val model = CloudflareZoneOperationsViewModel(api, "z", "example.com") { now }
        model.onRefreshSignal(0)
        val state = model.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf("ssl", "brotli", "minify"), state.settings.map { it.id })
        assertEquals("disabled", state.dnssec?.status)
        assertEquals(3, state.dnsUsage?.recordUsage)
        assertEquals("Not entitled", state.dnsAnalyticsError)
        assertNull(state.dnsSettingsError)
    }

    @Test
    fun dnssecChangesAreConfirmedWithDestructiveStylingForDisable() = runTest(dispatcher) {
        scriptZoneOperations()
        val model = CloudflareZoneOperationsViewModel(api, "z", "example.com") { now }
        model.load()

        model.requestDnssec(enabled = true)
        assertEquals("Enable DNSSEC?", model.confirmation.value?.title)
        assertFalse(model.confirmation.value!!.destructive)
        assertEquals("/zones/z/dnssec", model.confirmation.value?.resourceId)
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/dnssec", envelope("""{"status":"pending"}"""))
        model.confirmPendingMutation()
        assertEquals("""{"status":"active"}""", writes().single().bodyText)
        assertEquals("pending", model.state.value.dnssec?.status)

        model.requestDnssec(enabled = false)
        assertEquals("Disable DNSSEC?", model.confirmation.value?.title)
        assertTrue(model.confirmation.value!!.destructive)
        model.dismissPendingMutation()
        assertEquals(1, writes().size)
    }

    @Test
    fun settingEditsAreValidatedConfirmedAndReconciled() = runTest(dispatcher) {
        scriptZoneOperations()
        val model = CloudflareZoneOperationsViewModel(api, "z", "example.com") { now }
        model.load()
        val ssl = model.state.value.settings.first { it.id == "ssl" }
        val minify = model.state.value.settings.first { it.id == "minify" }
        model.openSettingEditor(minify)
        assertNull("Non-scalar settings have no in-app editor", model.state.value.editingSetting)

        model.openSettingEditor(ssl)
        model.requestSaveSetting(ssl, null)
        assertEquals("Enter a valid value for Ssl.", model.state.value.settingEditorError)
        model.requestSaveSetting(ssl, ProviderJsonValue.Str("strict"))
        assertEquals("Save Ssl?", model.confirmation.value?.title)
        assertEquals("This changes the live Cloudflare setting from full to strict.", model.confirmation.value?.message)
        assertEquals("/zones/z/settings/ssl", model.confirmation.value?.resourceId)

        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/settings/ssl", envelope("""{"id":"ssl","value":"strict","editable":true}"""))
        model.confirmPendingMutation()
        assertEquals(ProviderJsonValue.Str("strict"), model.state.value.settings.first { it.id == "ssl" }.value)
        assertNull(model.state.value.editingSetting)
        assertEquals("Ssl updated.", model.banner.value?.message)
    }

    @Test
    fun dnsSettingsAndActivationChecksAreConfirmed() = runTest(dispatcher) {
        scriptZoneOperations()
        val model = CloudflareZoneOperationsViewModel(api, "z", "example.com") { now }
        model.load()
        model.openDnsSettingsEditor()
        val draft = CloudflareZoneDnsSettingsDraft.from(model.state.value.dnsSettings!!)
        model.requestSaveDnsSettings(draft)
        assertEquals("No DNS settings have changed.", model.state.value.dnsSettingsEditorError)

        model.requestSaveDnsSettings(draft.copy(multiProvider = true))
        assertEquals("Save DNS settings?", model.confirmation.value?.title)
        assertTrue(model.confirmation.value!!.message.startsWith("This updates 1 live DNS configuration field for example.com."))
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/dns_settings", envelope("""{"multi_provider":true}"""))
        model.confirmPendingMutation()
        assertEquals("""{"multi_provider":true}""", writes().last().bodyText)
        assertFalse(model.state.value.showingDnsSettingsEditor)

        model.requestActivationCheck()
        assertEquals("/zones/z/activation_check", model.confirmation.value?.resourceId)
        transport.enqueueJson(CloudflareHttpMethod.PUT, "/zones/z/activation_check", envelope("""{"id":"z"}"""))
        model.confirmPendingMutation()
        assertEquals(CloudflareHttpMethod.PUT, writes().last().method)
        assertEquals("Cloudflare accepted the activation check.", model.banner.value?.message)
    }

    // MARK: Security center

    private fun scriptSecurity() {
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/settings/security_level", envelope("""{"value":"medium"}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/rulesets", envelope("""[{"id":"rs","name":"Managed"}]"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/firewall/access_rules/rules", envelope("""[{"id":"r1","mode":"block","configuration":{"value":"198.51.100.1"}}]"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/rate_limits", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/ssl/certificate_packs", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/custom_certificates", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/page_shield/policies", failure("Page Shield is not enabled"), status = 403)
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/bot_management", envelope("""{"fight_mode":false}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/z/api_gateway/configuration", envelope("null"))
    }

    @Test
    fun securityCenterLoadsAndListsPlanLimitedWarnings() = runTest(dispatcher) {
        scriptSecurity()
        val model = CloudflareSecurityCenterViewModel(api, "z", "example.com")
        model.onRefreshSignal(0)
        val snapshot = model.state.value.snapshot
        assertEquals("medium", snapshot.securityLevel)
        assertEquals(listOf("rs"), snapshot.rulesets.map { it.id })
        assertEquals("198.51.100.1", snapshot.accessRules.single().title)
        assertEquals(listOf("Page Shield: Page Shield is not enabled"), snapshot.warnings)
        assertEquals(3, snapshot.totalItems)
    }

    @Test
    fun securityMutationsAreConfirmed() = runTest(dispatcher) {
        scriptSecurity()
        val model = CloudflareSecurityCenterViewModel(api, "z", "example.com")
        model.load()

        model.requestSecurityLevel("under_attack")
        val levelPrompt = model.confirmation.value!!
        assertEquals("Change the zone security level?", levelPrompt.title)
        assertTrue(levelPrompt.message.contains("Medium → Under Attack"))
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "/zones/z/settings/security_level", envelope("""{"value":"under_attack"}"""))
        model.confirmPendingMutation()
        assertEquals("under_attack", model.state.value.snapshot.securityLevel)
        assertEquals("Security level updated to under attack.", model.banner.value?.message)

        val rule = model.state.value.snapshot.accessRules.single()
        model.requestDeleteAccessRule(rule)
        assertTrue(model.confirmation.value!!.destructive)
        assertEquals("/zones/z/firewall/access_rules/rules/r1", model.confirmation.value?.resourceId)
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/firewall/access_rules/rules/r1", envelope("""{"id":"r1"}"""))
        model.confirmPendingMutation()
        assertTrue(model.state.value.snapshot.accessRules.isEmpty())

        model.openAccessRuleEditor()
        model.requestCreateAccessRule(CloudflareAccessRuleDraft(value = " "))
        assertEquals("Enter a value for the rule target.", model.state.value.accessRuleEditorError)
        model.requestCreateAccessRule(CloudflareAccessRuleDraft(target = "country", value = " XX ", mode = "js_challenge"))
        assertTrue(model.confirmation.value!!.message.startsWith("The js challenge action applies immediately to matching traffic."))
        transport.enqueueJson(CloudflareHttpMethod.POST, "/zones/z/firewall/access_rules/rules", envelope("""{"id":"r2"}"""))
        model.confirmPendingMutation()
        assertEquals("""{"mode":"js_challenge","configuration":{"target":"country","value":"XX"}}""", writes().last().bodyText)
        assertFalse(model.state.value.showingAccessRuleEditor)
        assertEquals(3, writes().size)
    }

    companion object {
        const val ZONE = """{"id":"z","name":"example.com","status":"active","paused":false,"account":{"id":"acc","name":"Studio"},"plan":{"name":"Pro"}}"""
        const val RECORD_A = """{"id":"a","type":"A","name":"www.example.com","content":"203.0.113.10","proxied":true,"proxiable":true}"""
        const val RECORD_TXT = """{"id":"t","type":"TXT","name":"txt.example.com","content":"hello"}"""
    }
}
