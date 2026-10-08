package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarPresentationTest {
    @Test
    fun portfolioSummaryMatchesIosExpiryHealthRules() {
        val domains = listOf(
            domain("healthy.example", days = 200, autoRenew = true),
            domain("soon.example", days = 30, autoRenew = false),
            domain("expired.example", days = -3, autoRenew = true),
            domain("unknown.example", days = null, autoRenew = null),
        )

        val summary = RegistrarPortfolioSummary.of(domains, NOW)

        assertEquals(4, summary.domainCount)
        assertEquals(2, summary.attentionCount)
        assertEquals(2, summary.autoRenewCount)
        assertEquals(1, summary.unknownExpiryCount)
        assertEquals(0.5f, summary.healthFraction)
        assertEquals("2 need attention", summary.healthLabel)
        assertEquals(RegistrarTone.WARNING, summary.healthTone)

        val unknownOnly = RegistrarPortfolioSummary.of(listOf(domain("a", 90), domain("b", null)), NOW)
        assertEquals("1 unknown", unknownOnly.healthLabel)
        assertEquals(RegistrarTone.NEUTRAL, unknownOnly.healthTone)
        val clear = RegistrarPortfolioSummary.of(listOf(domain("a", 90)), NOW)
        assertEquals("Clear", clear.healthLabel)
        assertEquals(RegistrarTone.SUCCESS, clear.healthTone)
        assertEquals(1f, clear.healthFraction)
        val empty = RegistrarPortfolioSummary.of(emptyList(), NOW)
        assertEquals("No data", empty.healthLabel)
        assertEquals(0f, empty.healthFraction)
    }

    @Test
    fun expiryTilesAndTonesMatchIosRows() {
        assertEquals("18" to "DAYS", registrarExpiryValue(expires(18), NOW))
        assertEquals("3" to "EXPIRED", registrarExpiryValue(expires(-3), NOW))
        assertEquals("—" to "UNKNOWN", registrarExpiryValue(null, NOW))
        assertEquals(RegistrarTone.SUCCESS, registrarExpiryTone(expires(31), NOW))
        assertEquals(RegistrarTone.WARNING, registrarExpiryTone(expires(30), NOW))
        assertEquals(RegistrarTone.WARNING, registrarExpiryTone(expires(0), NOW))
        assertEquals(RegistrarTone.DANGER, registrarExpiryTone(expires(-1), NOW))
        assertEquals(RegistrarTone.NEUTRAL, registrarExpiryTone(null, NOW))
    }

    @Test
    fun statusToneFollowsIosAppStatusTone() {
        assertEquals(RegistrarTone.SUCCESS, registrarStatusTone("ACTIVE"))
        assertEquals(RegistrarTone.NEUTRAL, registrarStatusTone("registered"))
        assertEquals(RegistrarTone.DANGER, registrarStatusTone("Expired"))
        assertEquals(RegistrarTone.DANGER, registrarStatusTone("clientHold, suspended"))
        assertEquals(RegistrarTone.DANGER, registrarStatusTone("inactive"))
        assertEquals(RegistrarTone.WARNING, registrarStatusTone("pendingTransfer"))
        assertEquals(RegistrarTone.PROGRESS, registrarStatusTone("in progress"))
        assertEquals(RegistrarTone.NEUTRAL, registrarStatusTone("clientTransferProhibited"))
        assertEquals(RegistrarTone.NEUTRAL, registrarStatusTone(""))
    }

    @Test
    fun searchMatchesNameOrStatusCaseInsensitively() {
        val domains = listOf(
            domain("studio.example", 10).copy(status = "Active"),
            domain("legacy.example", 10).copy(status = "Expired"),
        )

        assertEquals(domains, filterRegistrarDomains(domains, "  "))
        assertEquals(listOf("studio.example"), filterRegistrarDomains(domains, "STUDIO").map { it.name })
        assertEquals(listOf("legacy.example"), filterRegistrarDomains(domains, "expired").map { it.name })
        assertTrue(filterRegistrarDomains(domains, "nothing").isEmpty())
    }

    @Test
    fun domainLinksOnlyOpenPlainHostnamesOverHttps() {
        assertEquals("https://studio.example", registrarDomainUrl("Studio.Example"))
        assertEquals("https://xn--exmple-cua.com", registrarDomainUrl("exämple.com"))
        assertNull(registrarDomainUrl("bad domain.com"))
        assertNull(registrarDomainUrl("localhost"))
        assertNull(registrarDomainUrl("evil.com/@other.com"))
        assertNull(registrarDomainUrl(""))
        assertTrue(isOpenableRegistrarUrl("https://porkbun.com/account/api"))
        assertFalse(isOpenableRegistrarUrl("http://porkbun.com"))
        assertFalse(isOpenableRegistrarUrl("javascript:alert(1)"))
        assertFalse(isOpenableRegistrarUrl("https://a.example/\nx"))
        RegistrarProvider.entries.forEach {
            assertTrue(isOpenableRegistrarUrl(it.credentialUrl))
            assertTrue(isOpenableRegistrarUrl(it.dashboardUrl))
        }
    }

    @Test
    fun connectButtonRequirementsMatchIosCanConnect() {
        RegistrarProvider.entries.forEach { provider ->
            assertFalse(provider.id, canConnectRegistrar(provider, false, true, "alice", "8.8.8.8"))
        }
        assertFalse(canConnectRegistrar(RegistrarProvider.NAME_DOT_COM, true, false, " ", ""))
        assertTrue(canConnectRegistrar(RegistrarProvider.NAME_DOT_COM, true, false, "alice", ""))
        assertFalse(canConnectRegistrar(RegistrarProvider.NAMECHEAP, true, false, "alice", "10.0.0.1"))
        assertFalse(canConnectRegistrar(RegistrarProvider.NAMECHEAP, true, false, "", "8.8.8.8"))
        assertTrue(canConnectRegistrar(RegistrarProvider.NAMECHEAP, true, false, "alice", " 8.8.8.8 "))
        listOf(RegistrarProvider.PORKBUN, RegistrarProvider.SPACESHIP, RegistrarProvider.GO_DADDY).forEach {
            assertFalse(canConnectRegistrar(it, true, false, "", ""))
            assertTrue(canConnectRegistrar(it, true, true, "", ""))
        }
        listOf(RegistrarProvider.DYNADOT, RegistrarProvider.NAME_SILO, RegistrarProvider.GANDI).forEach {
            assertTrue(canConnectRegistrar(it, true, false, "", ""))
        }
    }

    @Test
    fun connectionCopyIsPortedFromIos() {
        assertTrue(registrarConnectionNote(RegistrarProvider.NAME_DOT_COM).startsWith("Use a CORE API username"))
        assertTrue(registrarConnectionNote(RegistrarProvider.NAMECHEAP).contains("whitelist the same public IPv4"))
        assertTrue(registrarConnectionNote(RegistrarProvider.GO_DADDY).contains("Discount Domain Club"))
        assertTrue(registrarConnectionNote(RegistrarProvider.GANDI).contains("scoped personal access token"))
        assertTrue(registrarConnectionNote(RegistrarProvider.PORKBUN).startsWith("Create a key with read access"))
        assertEquals("API token", registrarPrimaryCredentialLabel(RegistrarProvider.NAME_DOT_COM))
        assertEquals("Personal access token", registrarPrimaryCredentialLabel(RegistrarProvider.GANDI))
        assertEquals("API key", registrarPrimaryCredentialLabel(RegistrarProvider.DYNADOT))
        assertEquals("Namecheap username / API user", registrarUsernameLabel(RegistrarProvider.NAMECHEAP))
        assertEquals("API username", registrarUsernameLabel(RegistrarProvider.NAME_DOT_COM))
        assertTrue(registrarPublicIpv4Explanation(RegistrarProvider.NAMECHEAP).contains("Use this IP"))
        assertTrue(registrarPublicIpv4Explanation(RegistrarProvider.NAME_DOT_COM).contains("optional IP allowlist"))
        assertEquals("On", registrarBooleanText(true))
        assertEquals("Off", registrarBooleanText(false))
        assertEquals("Not returned", registrarBooleanText(null))
        assertEquals("Live", registrarCacheLabel(RegistrarCacheState.LIVE))
        assertEquals("Saved", registrarCacheLabel(RegistrarCacheState.CACHED_FRESH))
        assertEquals("Stale", registrarCacheLabel(RegistrarCacheState.CACHED_STALE))
    }

    @Test
    fun layoutsStackForNarrowWidthsAndLargeFonts() {
        assertFalse(shouldStackRegistrarConnectionCard(1f))
        assertTrue(shouldStackRegistrarConnectionCard(1.3f))
        assertFalse(shouldStackRegistrarStats(360f, 1f))
        assertTrue(shouldStackRegistrarStats(280f, 1f))
        assertTrue(shouldStackRegistrarStats(400f, 1.5f))
    }

    @Test
    fun sampleGatewayIsOfflineAndFictional() = runBlocking {
        val restored = SampleRegistrarUiGateway.restore().getOrThrow().providers
        val connected = restored.filterValues { it is RegistrarProviderRestoreUi.Available }.keys

        assertEquals(setOf("nameDotCom", "namecheap"), connected)
        assertEquals(setOf("nameDotCom", "namecheap"), SampleRegistrarUiGateway.sampleProviderIds)
        restored.values.filterIsInstance<RegistrarProviderRestoreUi.Available>().forEach { available ->
            assertTrue(available.dashboard.domains.all { it.name.endsWith(".example") })
            assertEquals(available.dashboard.domains.sortedBy { it.name.lowercase() }, available.dashboard.domains)
        }
        val nameDotCom = (restored.getValue("nameDotCom") as RegistrarProviderRestoreUi.Available).dashboard
        assertEquals("Studio · Name.com", nameDotCom.account.displayName)
        val soon = nameDotCom.domains.first { it.name == "studio-design.example" }
        assertEquals(18, ((soon.expiresAtMillis!! - System.currentTimeMillis()) / 86_400_000L).toInt())
        assertEquals(
            "Exit sample data to connect GoDaddy.",
            SampleRegistrarUiGateway.connect(
                RegistrarConnectRequest("goDaddy", com.apoorvdarshan.verceltics.data.account.SecretValue.of("k")),
            ).exceptionOrNull()?.message,
        )
        assertTrue(SampleRegistrarUiGateway.detectPublicIpv4().isFailure)
        assertTrue(SampleRegistrarUiGateway.refresh("namecheap").isSuccess)
        assertTrue(SampleRegistrarUiGateway.refresh("porkbun").isFailure)
    }

    private fun expires(days: Int): Long = NOW + days * DAY + if (days >= 0) DAY / 2 else -DAY / 2

    private fun domain(name: String, days: Int?, autoRenew: Boolean? = true) = RegistrarDomainUi(
        id = name,
        name = name,
        status = "Active",
        createdAtMillis = null,
        expiresAtMillis = days?.let(::expires),
        autoRenew = autoRenew,
        locked = null,
        privacyEnabled = null,
        nameservers = emptyList(),
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 86_400_000L
    }
}
