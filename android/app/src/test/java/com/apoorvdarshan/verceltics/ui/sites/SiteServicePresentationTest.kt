package com.apoorvdarshan.verceltics.ui.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteDetailTable
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.Workspace
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteServicePresentationTest {
    private val us = Locale.US

    @Test
    fun everyHandledProviderExistsInTheCatalogSitesWorkspace() {
        val catalogSites = IntegrationCatalog.providers(Workspace.SITES).map { it.id }.toSet()
        assertTrue(catalogSites.containsAll(SiteServiceProviderIds))
        assertEquals(7, SiteServiceProviderIds.size)
        assertFalse("googleSearchConsole" in SiteServiceProviderIds)
        assertFalse("pageSpeed" in SiteServiceProviderIds)
        SiteServiceProviderIds.forEach { id ->
            assertTrue(SiteServiceCopy.subtitle(id).isNotBlank())
            assertTrue(SiteServiceCopy.isHttpsUrl(SiteServiceCopy.credentialPageUrl(id)))
            assertTrue(SiteServiceCopy.isHttpsUrl(SiteServiceCopy.providerDashboardUrl(id)))
        }
    }

    @Test
    fun metricFormattingMatchesIosUnits() {
        fun format(value: Double, unit: SiteMetricUnit, formatted: String? = null) =
            SiteServiceFormat.metric(SiteMetricUi("k", "L", value, unit, formatted), us)
        assertEquals("12,345", format(12_345.4, SiteMetricUnit.COUNT))
        assertEquals("45.3%", format(45.26, SiteMetricUnit.PERCENT))
        assertEquals("120 ms", format(119.6, SiteMetricUnit.MILLISECONDS))
        assertEquals("61.5 s", format(61.5, SiteMetricUnit.SECONDS))
        assertEquals("1.5 MB", format(1_500_000.0, SiteMetricUnit.BYTES))
        assertEquals("900 bytes", format(900.0, SiteMetricUnit.BYTES))
        assertEquals("92", format(0.92, SiteMetricUnit.SCORE))
        assertEquals("88", format(88.0, SiteMetricUnit.SCORE))
        assertEquals("2.67", format(2.666, SiteMetricUnit.RATIO))
        assertEquals("7.4", format(7.44, SiteMetricUnit.POSITION))
        assertEquals("3.14", format(3.14159, SiteMetricUnit.NONE))
        assertEquals("custom", format(1.0, SiteMetricUnit.COUNT, "custom"))
        assertEquals("—", format(Double.NaN, SiteMetricUnit.COUNT))
    }

    @Test
    fun jsonValuesDisplayLikeTheIosFormatter() {
        val value = ProviderJsonParser.parse(
            """{"id":9007199254740993,"ratio":0.12345,"whole":1200,"flag":true,"none":null,"text":"","list":[1,"a"],"obj":{"b_key":2,"a":"x"}}""",
        )
        assertEquals("9007199254740993", SiteServiceFormat.display(value["id"]!!, us))
        assertEquals("0.123", SiteServiceFormat.display(value["ratio"]!!, us))
        assertEquals("1,200", SiteServiceFormat.display(value["whole"]!!, us))
        assertEquals("Yes", SiteServiceFormat.display(value["flag"]!!, us))
        assertEquals("—", SiteServiceFormat.display(value["none"]!!, us))
        assertEquals("—", SiteServiceFormat.display(value["text"]!!, us))
        assertEquals("1, a", SiteServiceFormat.display(value["list"]!!, us))
        assertEquals("A: x · B Key: 2", SiteServiceFormat.display(value["obj"]!!, us))
        assertEquals("[]", SiteServiceFormat.display(ProviderJsonValue.Arr(emptyList()), us))
        assertEquals("{}", SiteServiceFormat.display(ProviderJsonValue.Obj(emptyMap()), us))
    }

    @Test
    fun columnNamesAndComparisonsFollowIosRules() {
        assertEquals("Attributes Response Time", SiteServiceFormat.humanized("attributes.response_time"))
        assertEquals("CTR Value", SiteServiceFormat.humanized("CTR-value"))
        assertEquals("Web Stream Data", SiteServiceFormat.humanized("web_stream_data"))
        assertTrue(SiteServiceFormat.compare(ProviderJsonValue.Str("10"), ProviderJsonValue.Num.of(9)) > 0)
        assertTrue(SiteServiceFormat.compare(ProviderJsonValue.Str("apple"), ProviderJsonValue.Str("Banana")) < 0)
        assertEquals(0, SiteServiceFormat.compare(ProviderJsonValue.Num.parse("1.0"), ProviderJsonValue.Num.of(1)))
    }

    @Test
    fun statusTonesMatchIos() {
        assertEquals(SiteStatusTone.DANGER, SiteServiceFormat.tone("2 down"))
        assertEquals(SiteStatusTone.WARNING, SiteServiceFormat.tone("Unverified"))
        assertEquals(SiteStatusTone.SUCCESS, SiteServiceFormat.tone("All operational"))
        assertEquals(SiteStatusTone.WARNING, SiteServiceFormat.tone("1 up · 1 paused or checking"))
        assertEquals(SiteStatusTone.SUCCESS, SiteServiceFormat.tone("Up"))
        assertEquals(SiteStatusTone.SUCCESS, SiteServiceFormat.tone("Live insights · 3d"))
        assertEquals(SiteStatusTone.NEUTRAL, SiteServiceFormat.tone("Property found"))
        assertEquals(SiteStatusTone.PROGRESS, SiteServiceFormat.tone("Checking"))
    }

    @Test
    fun serviceStatusPrefersErrorsThenDangerThenWarnings() {
        val dashboard = dashboard(status = "Connected")
        assertEquals("Connected", SiteServiceFormat.serviceStatus(service(dashboard)).text)
        assertEquals("Needs review", SiteServiceFormat.serviceStatus(service(dashboard.copy(warnings = listOf("w")))).text)
        assertEquals("2 down", SiteServiceFormat.serviceStatus(service(dashboard.copy(status = "2 down", warnings = listOf("w")))).text)
        assertEquals("Refresh failed", SiteServiceFormat.serviceStatus(service(dashboard).copy(error = "x")).text)
        assertEquals(
            "Restoring",
            SiteServiceFormat.serviceStatus(SiteServiceState("plausible")).text,
        )
        assertEquals(
            "Attention",
            SiteServiceFormat.serviceStatus(
                SiteServiceState("plausible", status = SiteServiceConnectionStatus.SAVED_UNAVAILABLE, operation = null),
            ).text,
        )
    }

    @Test
    fun headlineMetricsSkipResourceCounts() {
        val metrics = listOf(
            SiteMetricUi("ga4.properties", "Properties · Partial", 2.0, SiteMetricUnit.COUNT),
            SiteMetricUi("ga4.activeUsers", "Active Users", 10.0, SiteMetricUnit.COUNT),
            SiteMetricUi("ga4.sessions", "Sessions", 20.0, SiteMetricUnit.COUNT),
            SiteMetricUi("ga4.screenPageViews", "Page Views", 30.0, SiteMetricUnit.COUNT),
        )
        assertEquals(listOf("Active Users", "Sessions"), SiteServiceFormat.headlineMetrics(dashboard(metrics = metrics)).map { it.label })
        assertTrue(SiteServiceFormat.headlineMetrics(null).isEmpty())
    }

    @Test
    fun resourceSearchCoversNamesStatusesSubtitlesAndMetricsAndSortsByName() {
        val resources = listOf(
            resource("2", "beta.example", "Down", "https://beta.example", SiteMetricUi("rt", "Response Time", 812.0, SiteMetricUnit.MILLISECONDS)),
            resource("1", "Alpha.example", "Up", "https://alpha.example"),
            resource("3", "gamma.example", "Paused", "https://docs.gamma.example"),
        )
        assertEquals(listOf("1", "2", "3"), SiteServiceFormat.filteredResources("uptimeRobot", resources, "", us).map { it.id })
        assertEquals(listOf("2"), SiteServiceFormat.filteredResources("uptimeRobot", resources, "down", us).map { it.id })
        assertEquals(listOf("3"), SiteServiceFormat.filteredResources("uptimeRobot", resources, "docs", us).map { it.id })
        assertEquals(listOf("2"), SiteServiceFormat.filteredResources("uptimeRobot", resources, "812 ms", us).map { it.id })
        assertEquals(3, SiteServiceFormat.filteredResources("uptimeRobot", resources, "uptimerobot", us).size)
    }

    @Test
    fun connectFormFieldsAndValidationMatchIos() {
        assertEquals(listOf("credential"), SiteServiceCopy.fields("bingWebmaster").map { it.key })
        assertEquals(listOf("projectName", "siteURL", "credential"), SiteServiceCopy.fields("clarity").map { it.key })
        assertEquals(listOf("credential"), SiteServiceCopy.fields("umami", "cloud").map { it.key })
        assertEquals("Umami Cloud API key", SiteServiceCopy.fields("umami", "cloud").single().label)
        assertEquals(listOf("baseURL", "credential"), SiteServiceCopy.fields("umami", "selfHosted").map { it.key })
        assertEquals("Self-hosted bearer token", SiteServiceCopy.fields("umami", "selfHosted").last().label)
        assertTrue(SiteServiceCopy.fields("googleAnalytics").isEmpty())
        assertTrue(SiteServiceCopy.fields("plausible").last().isSecret)

        assertFalse(SiteServiceCopy.canConnect("bingWebmaster", false, emptyMap(), "cloud"))
        assertTrue(SiteServiceCopy.canConnect("bingWebmaster", true, emptyMap(), "cloud"))
        assertFalse(SiteServiceCopy.canConnect("clarity", true, mapOf("projectName" to " "), "cloud"))
        assertTrue(SiteServiceCopy.canConnect("clarity", true, mapOf("projectName" to "Site"), "cloud"))
        assertFalse(SiteServiceCopy.canConnect("clarity", true, mapOf("projectName" to "Site", "siteURL" to "http://x.example"), "cloud"))
        assertFalse(SiteServiceCopy.canConnect("plausible", true, emptyMap(), "cloud"))
        assertTrue(SiteServiceCopy.canConnect("umami", true, emptyMap(), "cloud"))
        assertFalse(SiteServiceCopy.canConnect("umami", true, mapOf("baseURL" to "analytics.example.com"), "selfHosted"))
        assertTrue(SiteServiceCopy.canConnect("umami", true, mapOf("baseURL" to "https://analytics.example.com"), "selfHosted"))
        assertFalse(SiteServiceCopy.canConnect("googleAnalytics", true, emptyMap(), "cloud"))
        assertEquals("Properties", SiteServiceCopy.resourceNoun("googleAnalytics", 2))
        assertEquals("Monitor", SiteServiceCopy.resourceNoun("betterStack", 1))
        assertEquals("Sites", SiteServiceCopy.resourceNoun("plausible", 0))
    }

    @Test
    fun tableViewsSearchSortAndPageByIndex() {
        val rows = (1..450).map { mapOf("name" to ProviderJsonValue.Str("row-$it"), "value" to ProviderJsonValue.Num.of(it % 7)) }
        val table = SiteDetailTable("t", "T", listOf("name"), rows)

        val first = tableView(table, "", null, 200)
        assertEquals(listOf("name", "value"), first.columns)
        assertEquals(200, first.rowIndices.size)
        assertTrue(first.hasMore)
        assertEquals(0, first.rowIndices.first())

        val searched = tableView(table, "ROW-44", null, 200)
        assertEquals(listOf(43) + (439 until 449).toList(), searched.rowIndices)
        assertFalse(searched.hasMore)

        val sorted = tableView(table, "", "value" to false, 450)
        assertEquals(6.0, rows[sorted.rowIndices.first()]["value"]!!.numberValue)
        assertEquals(0.0, rows[sorted.rowIndices.last()]["value"]!!.numberValue)
        assertTrue(sorted.rowIndices.zipWithNext().all { (a, b) -> rows[a]["value"]!!.numberValue!! >= rows[b]["value"]!!.numberValue!! })
        assertFalse(sorted.hasMore)
    }

    @Test
    fun rawExplorerFlattensEveryLeafWithPaths() {
        val leaves = flattenRaw(ProviderJsonParser.parse("""{"b":[1,{"c":null}],"a":{},"d":[]}"""))
        assertEquals(listOf("$.a", "$.b[0]", "$.b[1].c", "$.d"), leaves.map { it.path })
    }

    @Test
    fun sampleGatewayIsOfflineAndRefusesToConnect() = runTest {
        val restored = SampleSiteServicesUiGateway.restore().getOrThrow()
        assertEquals(SiteServiceProviderIds.toSet(), restored.services.keys)
        assertTrue(restored.services.values.all { it is SiteServiceRestoreUi.Available })
        assertEquals(
            "Exit sample data to connect Plausible.",
            SampleSiteServicesUiGateway.connectGoogle("plausible").exceptionOrNull()?.message,
        )
        val dashboard = SampleSiteServicesUiGateway.dashboard("uptimeRobot")
        val detail = SampleSiteServicesUiGateway.loadDetail(
            SiteServiceDetailRequestUi("uptimeRobot", dashboard.resources[1].id, SiteServiceDetailQueryUi(preset = SiteDetailRangePresetUi.DAYS_7)),
        ).getOrThrow()
        assertEquals(dashboard.resources[1].name, detail.title)
        assertEquals(7, detail.series.single().points.size)
        assertTrue(dashboard.resources.all { it.url == null || SiteServiceCopy.isHttpsUrl(it.url) })
    }

    private fun service(dashboard: SiteServiceDashboardUi) =
        SiteServiceState("plausible", status = SiteServiceConnectionStatus.CONNECTED, dashboard = dashboard, operation = null)

    private fun dashboard(status: String? = "Connected", metrics: List<SiteMetricUi> = emptyList()) = SiteServiceDashboardUi(
        providerId = "plausible",
        accountName = "example.com",
        accountDetail = null,
        status = status,
        resources = emptyList(),
        metrics = metrics,
        warnings = emptyList(),
        fetchedAtMillis = 0,
        cacheState = SiteServiceCacheState.LIVE,
    )

    private fun resource(id: String, name: String, status: String, subtitle: String, vararg metrics: SiteMetricUi) =
        SiteResourceUi(id, name, subtitle, subtitle, status, null, metrics.toList())
}
