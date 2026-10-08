package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRegistrarHttpTransportTest {
    private val transport = SecureRegistrarHttpTransport()

    @Test
    fun allowListsExactlyTheEightRegistrarOriginsAndThePublicIpService() {
        assertEquals(
            setOf(
                "https://api.name.com/",
                "https://api.namecheap.com/",
                "https://api.porkbun.com/",
                "https://spaceship.dev/",
                "https://api.dynadot.com/",
                "https://www.namesilo.com/",
                "https://api.gandi.net/",
                "https://api.godaddy.com/",
                "https://api.ipify.org/",
            ),
            SecureRegistrarHttpTransport.ALLOWED_ORIGINS,
        )
        listOf("https://evil.example/", "http://api.name.com/", "https://api.name.com.evil.example/").forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) {
                transport.newGetCall(RegistrarHttpRequest(origin = origin, path = "/core/v1/domains"))
            }
        }
    }

    @Test
    fun resolvesProviderPathsAndEncodesPublicAndSecretQueryParameters() {
        val request = RegistrarApi.buildRequest(
            credentials(RegistrarProvider.NAMECHEAP, key = "key with/space&amp", username = "alice b"),
            "/xml.response",
            listOf("Command" to "namecheap.domains.getList", "Page" to "2"),
        )

        val uri = transport.prepareUri(request)

        assertEquals("https", uri.scheme)
        assertEquals("api.namecheap.com", uri.host)
        assertEquals("/xml.response", uri.path)
        val query = uri.rawQuery.split('&')
        assertTrue(query.contains("Command=namecheap.domains.getList"))
        assertTrue(query.contains("ApiUser=alice%20b"))
        assertTrue(query.contains("ApiKey=key%20with%2Fspace%26amp"))
        assertTrue(query.contains("ClientIp=8.8.4.4"))
    }

    @Test
    fun keepsTheDocumentedApiPrefixForPorkbunAndSpaceship() {
        val porkbun = transport.prepareUri(
            RegistrarApi.buildRequest(credentials(RegistrarProvider.PORKBUN), "/domain/listAll", listOf("start" to "0")),
        )
        val spaceship = transport.prepareUri(
            RegistrarApi.buildRequest(credentials(RegistrarProvider.SPACESHIP), "/v1/domains", emptyList()),
        )

        assertEquals("https://api.porkbun.com/api/json/v3/domain/listAll?start=0", porkbun.toString())
        assertEquals("https://spaceship.dev/api/v1/domains", spaceship.toString())
    }

    @Test
    fun rejectsPathInjectionAndTraversal() {
        listOf("/v1/domains?limit=1", "//evil.example/x", "/v1/../admin", "https://evil.example/", "/a%2Fb").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                transport.newGetCall(RegistrarHttpRequest(origin = "https://api.godaddy.com/", path = path))
            }
        }
    }

    @Test
    fun restrictsPlainAndSecretHeaders() {
        listOf("Authorization", "X-API-Key", "Host", "User-Agent", "Accept").forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) {
                transport.newGetCall(
                    RegistrarHttpRequest(origin = "https://api.gandi.net/", path = "/v5/domain/domains", headers = mapOf(name to "x")),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            transport.newGetCall(
                RegistrarHttpRequest(
                    origin = "https://api.gandi.net/",
                    path = "/v5/domain/domains",
                    headers = mapOf("X-Debug" to "a\r\nInjected: yes"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            transport.newGetCall(
                RegistrarHttpRequest(
                    origin = "https://api.gandi.net/",
                    path = "/v5/domain/domains",
                    secretHeaders = listOf("Cookie" to SecretValue.of("session")),
                ),
            )
        }
        val call = transport.newGetCall(
            RegistrarHttpRequest(
                origin = "https://api.gandi.net/",
                path = "/v5/domain/domains",
                secretHeaders = listOf("Authorization" to SecretValue.of("Bearer secret-token")),
            ),
        )
        assertFalse(call.toString().contains("secret-token"))
        call.cancel()
    }
}
