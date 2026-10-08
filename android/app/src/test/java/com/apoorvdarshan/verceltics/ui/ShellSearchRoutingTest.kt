package com.apoorvdarshan.verceltics.ui

import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedConnectionStatus
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedUiState
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarAccountUi
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarCacheState
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarConnectionStatus
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarDashboardUi
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarDomainUi
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarProviderUiState
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarUiState
import com.apoorvdarshan.verceltics.ui.registrar.connectedProviderIds as connectedRegistrars
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleAccountUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleConnectionStatus
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleOAuthReadinessUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleUiState
import com.apoorvdarshan.verceltics.ui.sites.SiteGoogleOAuthReadinessUi
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceConnectionStatus
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceOperation
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceProviderIds
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceState
import com.apoorvdarshan.verceltics.ui.sites.SiteServicesUiState
import com.apoorvdarshan.verceltics.ui.sites.connectedProviderIds as connectedSiteServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSearchRoutingTest {
    private val namecheap = "namecheap"
    private val porkbun = "porkbun"

    @Test
    fun `root search opens the catalog only when nothing is connected`() {
        assertEquals(RootSearchTarget.ConnectionCatalog, rootSearchTarget(emptyList()))
    }

    @Test
    fun `a single connected provider opens that provider's own search`() {
        assertEquals(RootSearchTarget.Provider("pageSpeed"), rootSearchTarget(listOf("pageSpeed")))
        assertEquals(RootSearchTarget.Provider(namecheap), rootSearchTarget(setOf(namecheap)))
    }

    @Test
    fun `several connected providers focus the hub search instead of jumping into one`() {
        assertEquals(
            RootSearchTarget.HubSearch,
            rootSearchTarget(listOf(SEARCH_CONSOLE_PROVIDER_ID, "pageSpeed")),
        )
        assertFalse(usesHubSearch(0))
        assertFalse(usesHubSearch(1))
        assertTrue(usesHubSearch(2))
    }

    @Test
    fun `blank hub queries keep every card in order`() {
        val text = linkedMapOf("b" to "Beta", "a" to "Alpha")

        assertEquals(listOf("b", "a"), hubSearchMatches(text, "").toList())
        assertEquals(listOf("b", "a"), hubSearchMatches(text, "   ").toList())
    }

    @Test
    fun `hub queries need every term and ignore case, accents, and punctuation`() {
        val text = linkedMapOf(
            "one" to "Namecheap Acme apoorv.dev",
            "two" to "Porkbun Café example.com",
        )

        assertEquals(setOf("one"), hubSearchMatches(text, "APOORV.dev"))
        assertEquals(setOf("two"), hubSearchMatches(text, "cafe example"))
        assertEquals(emptySet<String>(), hubSearchMatches(text, "acme example"))
    }

    @Test
    fun `registrar hub search covers registrar, account, and domain names`() {
        val state = registrarState(
            namecheap to listOf("apoorv.dev", "verceltics.com"),
            porkbun to listOf("example.org"),
        )

        val text = registrarHubSearchText(state)

        assertEquals(listOf(namecheap, porkbun), text.keys.toList())
        assertEquals(setOf(namecheap), hubSearchMatches(text, "verceltics"))
        assertEquals(setOf(porkbun), hubSearchMatches(text, "porkbun"))
        assertEquals(setOf(porkbun), hubSearchMatches(text, "Account $porkbun"))
    }

    @Test
    fun `filtered registrar state only shows matching cards and keeps the original when all match`() {
        val state = registrarState(namecheap to listOf("a.dev"), porkbun to listOf("b.dev"))

        assertEquals(setOf(porkbun), state.onlyProviders(setOf(porkbun)).connectedRegistrars)
        assertTrue(state.onlyProviders(setOf(namecheap, porkbun)) === state)
    }

    @Test
    fun `sites hub lists search console, pagespeed, then site services`() {
        val siteServices = siteServicesState(
            "plausible" to SiteServiceState(
                providerId = "plausible",
                status = SiteServiceConnectionStatus.CONNECTED,
                savedAccountName = "Marketing site",
                operation = null,
            ),
        )

        val text = siteHubSearchText(connectedPageSpeed(), connectedSearchConsole(), siteServices)

        assertEquals(listOf(SEARCH_CONSOLE_PROVIDER_ID, PAGE_SPEED_PROVIDER_ID, "plausible"), text.keys.toList())
        assertEquals(setOf(PAGE_SPEED_PROVIDER_ID), hubSearchMatches(text, "speed.example"))
        assertEquals(setOf("plausible"), hubSearchMatches(text, "marketing"))
        assertEquals(setOf(SEARCH_CONSOLE_PROVIDER_ID), hubSearchMatches(text, "owner@example.com"))
        assertEquals(setOf("plausible"), siteServices.onlyServices(setOf("plausible")).connectedSiteServices)
    }

    @Test
    fun `sites hub omits disconnected services`() {
        val text = siteHubSearchText(PageSpeedUiState(status = PageSpeedConnectionStatus.DISCONNECTED), disconnectedSearchConsole(), null)

        assertTrue(text.isEmpty())
        assertEquals(RootSearchTarget.ConnectionCatalog, rootSearchTarget(text.keys))
    }

    @Test
    fun `registrar banner appears only when restore failed`() {
        assertNull(registrarRestoreFailureMessage(null))
        assertNull(registrarRestoreFailureMessage(RegistrarUiState()))
        assertEquals(
            "Saved registrar credentials could not be read.",
            registrarRestoreFailureMessage(RegistrarUiState(restoreError = "Saved registrar credentials could not be read.")),
        )
    }

    @Test
    fun `site services banner appears when every saved service failed to restore`() {
        val failed = siteServicesState(
            *SiteServiceProviderIds.map { id ->
                id to SiteServiceState(
                    providerId = id,
                    status = SiteServiceConnectionStatus.DISCONNECTED,
                    operation = null,
                    error = "Saved site services could not be read.",
                )
            }.toTypedArray(),
        )

        assertEquals("Saved site services could not be read.", siteServicesRestoreFailureMessage(failed))
    }

    @Test
    fun `site services banner uses generic copy when restore errors differ per service`() {
        val failed = siteServicesState(
            *SiteServiceProviderIds.map { id ->
                id to SiteServiceState(
                    providerId = id,
                    status = SiteServiceConnectionStatus.DISCONNECTED,
                    operation = null,
                    error = "$id could not complete this request.",
                )
            }.toTypedArray(),
        )

        assertEquals(SITE_SERVICES_RESTORE_FAILED_MESSAGE, siteServicesRestoreFailureMessage(failed))
    }

    @Test
    fun `a single failed connect or a restore in progress is not a restore failure`() {
        val oneConnectFailure = siteServicesState(
            *SiteServiceProviderIds.mapIndexed { index, id ->
                id to SiteServiceState(
                    providerId = id,
                    status = SiteServiceConnectionStatus.DISCONNECTED,
                    operation = null,
                    error = if (index == 0) "Invalid API key." else null,
                )
            }.toTypedArray(),
        )
        val restoring = SiteServicesUiState(googleOAuthReadiness = SiteGoogleOAuthReadinessUi.Ready)

        assertNull(siteServicesRestoreFailureMessage(oneConnectFailure))
        assertNull(siteServicesRestoreFailureMessage(restoring))
        assertNull(siteServicesRestoreFailureMessage(null))
        assertTrue(restoring.services.values.all { it.operation == SiteServiceOperation.RESTORING })
    }

    private fun registrarState(vararg connected: Pair<String, List<String>>): RegistrarUiState {
        val providers = RegistrarProvider.ids.associateWith { id ->
            val domains = connected.firstOrNull { it.first == id }?.second
            if (domains == null) {
                RegistrarProviderUiState(id, RegistrarConnectionStatus.DISCONNECTED)
            } else {
                RegistrarProviderUiState(
                    providerId = id,
                    status = RegistrarConnectionStatus.CONNECTED,
                    dashboard = RegistrarDashboardUi(
                        account = RegistrarAccountUi(providerId = id, displayName = "Account $id"),
                        domains = domains.map { name ->
                            RegistrarDomainUi(
                                id = name,
                                name = name,
                                status = null,
                                createdAtMillis = null,
                                expiresAtMillis = null,
                                autoRenew = null,
                                locked = null,
                                privacyEnabled = null,
                                nameservers = emptyList(),
                            )
                        },
                        inventoryComplete = true,
                        warnings = emptyList(),
                        fetchedAtMillis = 0L,
                        cacheState = RegistrarCacheState.LIVE,
                    ),
                )
            }
        }
        return RegistrarUiState(providers = providers)
    }

    private fun siteServicesState(vararg services: Pair<String, SiteServiceState>): SiteServicesUiState {
        val overrides = services.toMap()
        return SiteServicesUiState(
            googleOAuthReadiness = SiteGoogleOAuthReadinessUi.Ready,
            services = SiteServiceProviderIds.associateWith { id ->
                overrides[id] ?: SiteServiceState(id, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null)
            },
        )
    }

    private fun connectedPageSpeed() = PageSpeedUiState(
        status = PageSpeedConnectionStatus.SAVED_UNAVAILABLE,
        savedSiteUrl = "https://speed.example",
        operation = null,
    )

    private fun connectedSearchConsole() = SearchConsoleUiState(
        oauthReadiness = SearchConsoleOAuthReadinessUi.Ready,
        status = SearchConsoleConnectionStatus.SAVED_UNAVAILABLE,
        savedAccount = SearchConsoleAccountUi(id = "google", email = "owner@example.com"),
        operation = null,
    )

    private fun disconnectedSearchConsole() = SearchConsoleUiState(
        oauthReadiness = SearchConsoleOAuthReadinessUi.Ready,
        status = SearchConsoleConnectionStatus.DISCONNECTED,
        operation = null,
    )
}
