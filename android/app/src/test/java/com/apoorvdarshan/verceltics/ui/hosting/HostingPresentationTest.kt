package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.RailwayTokenType
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.providerLogoProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingPresentationTest {
    @Test
    fun everyHostingProviderMatchesTheCatalogAndHasALogo() {
        HostingProvider.entries.forEach { provider ->
            val catalog = checkNotNull(IntegrationCatalog.provider(provider.id))
            assertEquals(provider.displayName, catalog.displayName)
            assertTrue(provider.id in providerLogoProviderIds)
            assertTrue(provider.credentialPageUrl.startsWith("https://"))
        }
        assertEquals(
            setOf("railway", "render", "digitalOcean", "heroku", "fly", "firebase", "awsAmplify"),
            HostingProvider.ids,
        )
        assertNull(HostingProvider.FIREBASE.primaryActionLabel)
        assertEquals("Start release", HostingProvider.AWS_AMPLIFY.primaryActionLabel)
        assertEquals("Machines", HostingProvider.FLY.historyTitle)
    }

    @Test
    fun statusTonesMirrorIosAppStatusTone() {
        assertEquals(HostingStatusTone.DANGER, hostingStatusTone("Inactive"))
        assertEquals(HostingStatusTone.DANGER, hostingStatusTone("FAILED"))
        assertEquals(HostingStatusTone.DANGER, hostingStatusTone("Suspended"))
        assertEquals(HostingStatusTone.DANGER, hostingStatusTone("stopped"))
        assertEquals(HostingStatusTone.PROGRESS, hostingStatusTone("BUILDING"))
        assertEquals(HostingStatusTone.PROGRESS, hostingStatusTone("IN_PROGRESS"))
        assertEquals(HostingStatusTone.WARNING, hostingStatusTone("Starting"))
        assertEquals(HostingStatusTone.WARNING, hostingStatusTone("PENDING_DEPLOY"))
        assertEquals(HostingStatusTone.SUCCESS, hostingStatusTone("Active"))
        assertEquals(HostingStatusTone.SUCCESS, hostingStatusTone("Running"))
        assertEquals(HostingStatusTone.SUCCESS, hostingStatusTone("SUCCEED"))
        assertEquals(HostingStatusTone.SUCCESS, hostingStatusTone("live"))
        assertEquals(HostingStatusTone.NEUTRAL, hostingStatusTone("Degraded"))
        assertEquals(HostingStatusTone.NEUTRAL, hostingStatusTone("Unknown"))
    }

    @Test
    fun searchMatchesNameSubtitleStatusKindAndRegion() {
        val resources = dashboard("render", resourceCount = 3).resources
        assertEquals(resources, filterResources(resources, "  "))
        assertEquals(listOf("render-2"), filterResources(resources, "subtitle 2").map { it.id })
        assertEquals(listOf("render-1"), filterResources(resources, "RUNNING").map { it.id })
        assertEquals(3, filterResources(resources, "iad").size)
        assertEquals(3, filterResources(resources, "app").size)
        assertTrue(filterResources(resources, "nothing-like-this").isEmpty())
    }

    @Test
    fun captionsCountsAndCacheLabels() {
        assertEquals("App · iad · Subtitle 1", resourceCaption(dashboard("fly").resources.first()))
        assertNull(resourceCaption(dashboard("fly").resources.first().copy(kind = null, region = " ", subtitle = null)))
        assertEquals("1 app", countLabel(1, "Apps"))
        assertEquals("3 services", countLabel(3, "Services"))
        assertEquals("1 build job", countLabel(1, "Build jobs"))
        assertEquals("0 projects", countLabel(0, "Projects"))
        assertEquals("Stale", cacheLabel(HostingCacheState.CACHED_STALE))
    }

    @Test
    fun connectionCardsSummarizeInventoryAndAttention() {
        val connected = HostingProviderUiState("render", HostingConnectionStatus.CONNECTED, dashboard = dashboard("render"), operation = null)
        assertEquals("2 services · Live data", connectionCardSubtitle(HostingProvider.RENDER, connected))
        assertEquals("Connected", connectionCardStatus(connected))
        assertEquals("Attention", connectionCardStatus(connected.copy(error = "Render is temporarily unavailable.")))
        assertEquals("Attention", connectionCardStatus(connected.copy(status = HostingConnectionStatus.SAVED_UNAVAILABLE)))
        val saved = HostingProviderUiState(
            "fly",
            HostingConnectionStatus.SAVED_UNAVAILABLE,
            savedAccount = HostingAccountUi("personal", "Fly.io Personal", null),
            operation = null,
        )
        assertEquals("Saved connection", connectionCardSubtitle(HostingProvider.FLY, saved))
        assertFalse(shouldStackHostingConnectionCard(1.29f))
        assertTrue(shouldStackHostingConnectionCard(1.3f))
        assertFalse(shouldStackDetailActions(availableWidthDp = 343f, buttonCount = 2, fontScale = 1f))
        assertTrue(shouldStackDetailActions(availableWidthDp = 343f, buttonCount = 3, fontScale = 1f))
        assertFalse(shouldStackDetailActions(availableWidthDp = 600f, buttonCount = 3, fontScale = 1f))
        assertTrue(shouldStackDetailActions(availableWidthDp = 600f, buttonCount = 2, fontScale = 1.3f))
    }

    @Test
    fun onlyHttpsLinksLeaveTheApp() {
        val opened = mutableListOf<String>()
        listOf(
            "https://dashboard.render.com/srv-1",
            "http://insecure.example",
            "javascript:alert(1)",
            "https://user:pass@evil.example",
            "intent://scan/#Intent;scheme=zxing;end",
            "not a url",
        ).forEach { openHttpsLink(it, opened::add) }
        assertEquals(listOf("https://dashboard.render.com/srv-1"), opened)
        openHttpsLink("https://example.com") { throw IllegalStateException("no browser") }
    }

    @Test
    fun formCopyMatchesIos() {
        assertEquals("Fly.io access token", credentialLabel(HostingProvider.FLY))
        assertEquals("Heroku API token", credentialLabel(HostingProvider.HEROKU))
        assertEquals("Create an IAM access key with Amplify permissions", instructionOne(HostingProvider.AWS_AMPLIFY))
        assertEquals("Open Render’s token or API key page", instructionOne(HostingProvider.RENDER))
        assertTrue(instructionTwo(HostingProvider.RAILWAY, RailwayTokenType.PROJECT).startsWith("Copy a project token"))
        assertTrue(instructionTwo(HostingProvider.RAILWAY, RailwayTokenType.ACCOUNT).startsWith("Create an account or workspace token"))
        assertTrue(credentialStorageMessage(HostingProvider.DIGITAL_OCEAN).contains("only to DigitalOcean’s official API"))
        assertTrue(credentialStorageMessage(HostingProvider.FIREBASE).contains("Firebase Hosting endpoints"))
    }
}
