package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareProductCatalogTest {
    private val products = CloudflareProductCatalog.products
    private val now = Instant.parse("2026-10-09T12:34:56.789Z")

    @Test
    fun catalogMirrorsIosProductsAndOperations() {
        assertEquals(23, products.size)
        assertEquals(
            listOf("analytics", "accounts", "zones", "dns", "security", "pages", "workers", "d1-kv", "r2"),
            products.take(9).map { it.id },
        )
        assertEquals(
            mapOf(
                "analytics" to 6, "accounts" to 11, "zones" to 5, "dns" to 11, "security" to 20,
                "pages" to 5, "workers" to 9, "d1-kv" to 10, "r2" to 7,
            ),
            products.take(9).associate { it.id to it.operations.size },
        )
        assertTrue(products.drop(9).all { it.operations.size == 2 })
        assertEquals(112, CloudflareProductCatalog.operationCount)
        val ids = products.flatMap { product -> product.operations.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(products.size, products.map { it.id }.toSet().size)
        assertNotNull(CloudflareProductCatalog.preset("dns-batch"))
        assertNull(CloudflareProductCatalog.preset("missing"))
    }

    @Test
    fun graphQLPresetsAreVerifiedReadOnlyAndEverythingElseWritingNeedsConfirmation() {
        products.flatMap { it.operations }.forEach { operation ->
            val resolved = operation.resolved("acc-1", "zone-1", now)
            val draft = CloudflareExplorerDraft.from(resolved)
            val needsConfirmation = CloudflareExplorerRequestBuilder.requiresWriteConfirmation(draft)
            when {
                operation.path == "/graphql" -> {
                    assertTrue(operation.id, operation.readOnlyGraphQL)
                    assertFalse(operation.id, needsConfirmation)
                }
                operation.method.isMutation -> assertTrue(operation.id, needsConfirmation)
                else -> assertFalse(operation.id, needsConfirmation)
            }
        }
    }

    @Test
    fun everyResolvedPresetBuildsAValidPinnedRequest() {
        products.flatMap { it.operations }.forEach { operation ->
            val resolved = operation.resolved("acc-1", "zone-1", now)
            assertFalse(operation.id, resolved.path.contains("{"))
            assertFalse(operation.id, resolved.body.contains("{account_id}") || resolved.body.contains("{zone_id}"))
            val draft = CloudflareExplorerDraft.from(resolved)
            val request = CloudflareExplorerRequestBuilder.build(draft, CloudflareMutationConfirmation(draft.path))
            assertEquals(operation.id, "api.cloudflare.com", request.uri.host)
            assertTrue(operation.id, request.uri.rawPath.startsWith("/client/v4/"))
        }
    }

    @Test
    fun resolvedSubstitutesAccountZoneAndAnalyticsWindow() {
        val traffic = CloudflareProductCatalog.preset("analytics-zone-http")!!.resolved("acc-1", "zone-1", now)
        val variables = ProviderJsonParser.parse(traffic.body)["variables"]!!
        assertEquals("zone-1", variables["zoneTag"]?.stringValue)
        assertEquals("2026-10-02", variables["start"]?.stringValue)
        assertEquals("2026-10-09", variables["end"]?.stringValue)

        val security = CloudflareProductCatalog.preset("analytics-security")!!.resolved("acc-1", null, now)
        val securityVariables = ProviderJsonParser.parse(security.body)["variables"]!!
        assertEquals("ZONE_ID", securityVariables["zoneTag"]?.stringValue)
        assertEquals("2026-10-02T12:34:56Z", securityVariables["start"]?.stringValue)
        assertEquals("2026-10-09T12:34:56Z", securityVariables["end"]?.stringValue)

        val zones = CloudflareProductCatalog.preset("zones-list")!!.resolved("acc-1", "zone-1", now)
        assertEquals("account.id=acc-1\npage=1\nper_page=50", zones.query)
        val zoneCreate = CloudflareProductCatalog.preset("zone-create")!!.resolved("acc-1", "zone-1", now)
        assertTrue(zoneCreate.body.contains("\"id\": \"acc-1\""))
        assertEquals("/zones/ZONE_ID", CloudflareProductCatalog.preset("zone-update")!!.resolved("acc", null, now).path)
        assertEquals("/accounts/acc", CloudflareProductCatalog.preset("account-details")!!.resolved("acc", null, now).path)
    }

    @Test
    fun presetsKeepMethodsBodiesAndTokenRequirements() {
        val upload = CloudflareProductCatalog.preset("worker-upload")!!
        assertEquals(CloudflareHttpMethod.PUT, upload.method)
        assertEquals("multipart/form-data; boundary=BOUNDARY", upload.contentType)
        assertEquals(CloudflareHttpMethod.PATCH, CloudflareProductCatalog.preset("d1-update")!!.method)
        assertEquals(CloudflareHttpMethod.DELETE, CloudflareProductCatalog.preset("zone-delete")!!.method)
        assertTrue(CloudflareProductCatalog.preset("r2-buckets")!!.requiresApiToken)
        assertTrue(CloudflareProductCatalog.preset("tunnels-list")!!.requiresApiToken)
        assertFalse(CloudflareProductCatalog.preset("queues-list")!!.requiresApiToken)
        assertEquals("Read configuration", CloudflareProductCatalog.preset("zaraz-list")!!.title)
        assertEquals("/zones/{zone_id}/settings/zaraz/config", CloudflareProductCatalog.preset("zaraz-list")!!.path)
        products.flatMap { it.operations }
            .filter { it.contentType == "application/json" && it.body.isNotBlank() }
            .forEach { ProviderJsonParser.parse(it.resolved("a", "z", now).body) }
    }

    @Test
    fun searchKeepsWholeProductsOrMatchingOperations() {
        assertEquals(products, CloudflareProductCatalog.filtered("  "))
        val dns = CloudflareProductCatalog.filtered("DNS")
        val dnsProduct = dns.first { it.id == "dns" }
        assertEquals(11, dnsProduct.operations.size)
        // A product-level match keeps every operation of that product.
        assertEquals(7, CloudflareProductCatalog.filtered("bucket").single { it.id == "r2" }.operations.size)
        // Otherwise only matching operations remain.
        val cors = CloudflareProductCatalog.filtered("CORS policy")
        assertEquals(listOf("r2"), cors.map { it.id })
        assertEquals(listOf("r2-cors"), cors.single().operations.map { it.id })
        assertTrue(CloudflareProductCatalog.filtered("tunnel").any { it.id == "tunnels" })
        assertTrue(CloudflareProductCatalog.filtered("qqqq-nothing").isEmpty())
    }
}
