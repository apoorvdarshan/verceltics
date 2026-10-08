package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarCredentialsTest {
    @Test
    fun providerIdsMatchTheIntegrationCatalogAndIosRawValues() {
        assertEquals(
            listOf("nameDotCom", "namecheap", "porkbun", "spaceship", "dynadot", "nameSilo", "gandi", "goDaddy"),
            RegistrarProvider.ids,
        )
        RegistrarProvider.entries.forEach { provider ->
            val catalog = checkNotNull(com.apoorvdarshan.verceltics.domain.IntegrationCatalog.provider(provider.id))
            assertEquals(provider.displayName, catalog.displayName)
            assertEquals(provider.apiDescription, catalog.description)
            assertTrue(provider.credentialUrl.startsWith("https://"))
            assertTrue(provider.dashboardUrl.startsWith("https://"))
            assertTrue(provider.origin.startsWith("https://") && provider.origin.endsWith("/"))
        }
        assertEquals(RegistrarProvider.GO_DADDY, RegistrarProvider.fromId("goDaddy"))
        assertNull(RegistrarProvider.fromId("godaddy"))
        assertNull(RegistrarProvider.fromId(null))
    }

    @Test
    fun formInputIsTrimmedAndProviderMetadataMatchesIos() {
        val nameDotCom = RegistrarCredentials.fromInput(
            RegistrarProvider.NAME_DOT_COM,
            SecretValue.of("  token  "),
            apiSecret = SecretValue.of("ignored"),
            username = " alice ",
            clientIp = "8.8.8.8",
        )
        assertEquals(SecretValue.of("token"), nameDotCom.primary)
        assertNull(nameDotCom.secondary)
        assertEquals(mapOf("username" to "alice"), nameDotCom.metadata)

        val namecheap = RegistrarCredentials.fromInput(
            RegistrarProvider.NAMECHEAP,
            SecretValue.of("key"),
            apiSecret = null,
            username = "bob",
            clientIp = " 008.008.004.004 ",
        )
        assertEquals(mapOf("username" to "bob", "clientIP" to "8.8.4.4"), namecheap.metadata)

        val porkbun = RegistrarCredentials.fromInput(
            RegistrarProvider.PORKBUN,
            SecretValue.of("pk"),
            SecretValue.of(" sk "),
            username = "unused",
        )
        assertEquals(SecretValue.of("sk"), porkbun.secondary)
        assertTrue(porkbun.metadata.isEmpty())

        val gandi = RegistrarCredentials.fromInput(RegistrarProvider.GANDI, SecretValue.of("pat"), null, organization = "  ")
        assertTrue(gandi.metadata.isEmpty())
    }

    @Test
    fun missingRequiredInputUsesIosCopy() {
        fun message(block: () -> Unit) = assertThrows(RegistrarApiException::class.java, block).message

        assertEquals("Enter the Name.com API username.", message {
            RegistrarCredentials.fromInput(RegistrarProvider.NAME_DOT_COM, SecretValue.of("t"), null, username = " ")
        })
        assertEquals("Enter the Namecheap API username.", message {
            RegistrarCredentials.fromInput(RegistrarProvider.NAMECHEAP, SecretValue.of("t"), null, clientIp = "8.8.8.8")
        })
        assertEquals("Enter a valid public IPv4 address that is whitelisted in Namecheap.", message {
            RegistrarCredentials.fromInput(RegistrarProvider.NAMECHEAP, SecretValue.of("t"), null, "alice", "10.0.0.1")
        })
        listOf(RegistrarProvider.PORKBUN, RegistrarProvider.SPACESHIP, RegistrarProvider.GO_DADDY).forEach { provider ->
            assertEquals("Enter the API secret.", message {
                RegistrarCredentials.fromInput(provider, SecretValue.of("key"), null)
            })
        }
        // Blank secrets cannot even be wrapped, so the form reports them before reaching the API.
        assertThrows(IllegalArgumentException::class.java) { SecretValue.of("   ") }
    }

    @Test
    fun credentialsAreNeverPrintedAndCompareSecretsSafely() {
        val credentials = credentials(RegistrarProvider.GO_DADDY, key = "gd-visible-key", secret = "gd-visible-secret")
        val request = RegistrarApi.buildRequest(credentials, "/v1/domains", emptyList())

        listOf(credentials.toString(), request.toString()).forEach { text ->
            assertFalse(text.contains("gd-visible-key"))
            assertFalse(text.contains("gd-visible-secret"))
        }
        assertTrue(credentials.sameAs(credentials(RegistrarProvider.GO_DADDY, key = "gd-visible-key", secret = "gd-visible-secret")))
        assertFalse(credentials.sameAs(credentials(RegistrarProvider.GO_DADDY, key = "gd-visible-key", secret = "other")))
    }

    @Test
    fun domainExpiryDaysTruncateTowardZeroLikeCalendarComponents() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        assertEquals(30, domain("a", expiresAtMillis = now + 30 * day + 5).daysUntilExpiry(now))
        assertEquals(0, domain("a", expiresAtMillis = now + day - 1).daysUntilExpiry(now))
        assertEquals(0, domain("a", expiresAtMillis = now - day + 1).daysUntilExpiry(now))
        assertEquals(-2, domain("a", expiresAtMillis = now - 2 * day - 1).daysUntilExpiry(now))
        assertNull(domain("a", expiresAtMillis = null).daysUntilExpiry(now))
        assertEquals("mixed.example", domain("Mixed.Example").id)
    }
}
