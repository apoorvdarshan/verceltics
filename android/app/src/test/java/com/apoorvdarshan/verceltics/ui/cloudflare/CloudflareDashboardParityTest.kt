package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareApiPreset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareProductCatalog
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.catalogCredentialWarning
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.isProductOperationLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareDashboardParityTest {
    private val worker = CloudflareWorkerUi(
        id = "edge-api",
        modifiedOn = null,
        compatibilityDate = null,
        handlers = listOf("fetch"),
        hasAssets = false,
        hasModules = true,
        routes = listOf("shop.example.com/api/*", "*.example.net/v2/*"),
        tags = listOf("billing", "team:payments"),
    )

    @Test
    fun workerSearchMatchesNameRoutesAndTagsLikeIos() {
        assertTrue(worker.matches(""))
        assertTrue(worker.matches("EDGE"))
        assertTrue(worker.matches("shop.example.com"))
        assertTrue(worker.matches("/v2/"))
        assertTrue(worker.matches("billing"))
        assertTrue(worker.matches("payments"))
        assertTrue(worker.matches("fetch"))
        assertFalse(worker.matches("scheduled"))
        assertFalse(worker.copy(routes = emptyList(), tags = emptyList()).matches("shop.example.com"))
    }

    @Test
    fun summaryCopyNeverClaimsReadOnlyAndLabelsTheCredentialLikeIos() {
        val token = CloudflareProfileUi("token", "Studio", "Active")
        val global = CloudflareProfileUi("user", "Ada", "active", CloudflareAuthMode.GLOBAL_API_KEY, "owner@example.com")

        assertEquals("Scoped API token active · writes confirmed", cloudflareCredentialSubtitle(token))
        assertEquals("owner@example.com · Global API Key · writes confirmed", cloudflareCredentialSubtitle(global))
        listOf(token, global).forEach { assertFalse(cloudflareCredentialSubtitle(it).contains("read-only")) }
        assertEquals("Scoped API token", token.credentialLabel)
        assertEquals("owner@example.com", global.credentialLabel)
    }

    @Test
    fun connectionCopyMatchesIosStepsAndPermissions() {
        assertEquals("https://dash.cloudflare.com/profile/api-tokens", CloudflareConnectCopy.API_TOKENS_URL)
        val tokenSteps = CloudflareConnectCopy.steps(CloudflareAuthMode.API_TOKEN)
        assertTrue(tokenSteps.any { it.contains("product permissions you need") })
        assertTrue(tokenSteps.any { it.contains("Account Read") })
        assertTrue(tokenSteps.none { it.contains(":Read") })
        val keySteps = CloudflareConnectCopy.steps(CloudflareAuthMode.GLOBAL_API_KEY)
        assertTrue(keySteps.any { it.contains("Global API Key") })
        assertTrue(CloudflareConnectCopy.storageNote(CloudflareAuthMode.GLOBAL_API_KEY).contains("write access"))
        assertEquals("Connect with Global API Key", CloudflareConnectCopy.title(CloudflareAuthMode.GLOBAL_API_KEY))
    }

    @Test
    fun tokenOnlyProductOperationsLockForGlobalApiKeysOnly() {
        val tokenOnly = CloudflareProductCatalog.products.flatMap { it.operations }.first { it.requiresApiToken }
        val open = CloudflareApiPreset("open", "Open", "Summary", CloudflareHttpMethod.GET, "/zones")
        assertTrue(isProductOperationLocked(tokenOnly, CloudflareAuthMode.GLOBAL_API_KEY))
        assertFalse(isProductOperationLocked(tokenOnly, CloudflareAuthMode.API_TOKEN))
        assertFalse(isProductOperationLocked(open, CloudflareAuthMode.GLOBAL_API_KEY))
        assertTrue(catalogCredentialWarning(CloudflareAuthMode.GLOBAL_API_KEY).contains("API-token only"))
        assertTrue(catalogCredentialWarning(CloudflareAuthMode.API_TOKEN).contains("Global API Key"))
    }
}
