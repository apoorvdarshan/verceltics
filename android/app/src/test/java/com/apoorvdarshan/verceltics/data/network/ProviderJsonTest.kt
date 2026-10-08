package com.apoorvdarshan.verceltics.data.network

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderJsonTest {
    @Test
    fun parsesNestedDocumentsEscapesAndSurrogatePairs() {
        val value = ProviderJsonParser.parse(
            """
            {"name":"Studio \"site\"","nested":{"list":[1,true,null,"a\/b"]},
             "emoji":"🚀","tab":"a\tb","empty":{},"none":[]}
            """.trimIndent(),
        )

        assertEquals("Studio \"site\"", value["name"]?.stringValue)
        assertEquals(4, value["nested"]?.get("list")?.arrayValue?.size)
        assertEquals(true, value["nested"]?.get("list")?.arrayValue?.get(1)?.booleanValue)
        assertTrue(value["nested"]?.get("list")?.arrayValue?.get(2)?.isNull == true)
        assertEquals("a/b", value["nested"]?.get("list")?.arrayValue?.get(3)?.stringValue)
        assertEquals("🚀", value["emoji"]?.stringValue)
        assertEquals("a\tb", value["tab"]?.stringValue)
        assertEquals(emptyMap<String, ProviderJsonValue>(), value["empty"]?.objectValue)
        assertEquals(emptyList<ProviderJsonValue>(), value["none"]?.arrayValue)
    }

    @Test
    fun numbersKeepExactIntegerAndHighPrecisionText() {
        val value = ProviderJsonParser.parse(
            """{"id":9007199254740993,"unsigned":18446744073709551615,"ratio":0.1234567890123456789012345678,"exp":1.5E3}""",
        )

        assertEquals("9007199254740993", value["id"]?.stringValue)
        assertEquals("18446744073709551615", value["unsigned"]?.stringValue)
        assertEquals("0.1234567890123456789012345678", value["ratio"]?.stringValue)
        assertEquals(1500.0, value["exp"]?.numberValue)
        val roundTrip = ProviderJsonParser.parse(ProviderJsonWriter.write(value))
        assertEquals(value, roundTrip)
        assertEquals("9007199254740993", roundTrip["id"]?.stringValue)
    }

    @Test
    fun numericEqualityIgnoresTextualForm() {
        assertEquals(ProviderJsonValue.Num.parse("1.0"), ProviderJsonValue.Num.of(1))
        assertEquals(ProviderJsonValue.Num.parse("1.0").hashCode(), ProviderJsonValue.Num.of(1).hashCode())
        assertEquals("42", ProviderJsonValue.Num.of(42.0).text)
        assertEquals("0.25", ProviderJsonValue.Num.of(0.25).text)
    }

    @Test
    fun numericStringsAndBooleansExposeTypedViews() {
        assertEquals(12.5, ProviderJsonValue.Str(" 12.5 ").numberValue)
        assertNull(ProviderJsonValue.Str("NaN").numberValue)
        assertNull(ProviderJsonValue.Str("Infinity").numberValue)
        assertEquals(true, ProviderJsonValue.Str("yes").booleanValue)
        assertEquals(false, ProviderJsonValue.Num.of(0).booleanValue)
        assertNull(ProviderJsonValue.Num.of(2).booleanValue)
    }

    @Test
    fun rejectsMalformedAndOversizedDocuments() {
        listOf(
            "{\"a\":1} trailing",
            "01",
            "1.",
            "-",
            "[1,]",
            "{\"a\" 1}",
            "\"unterminated",
            "\"bad \\q escape\"",
            "\"raw\u0001control\"",
            "tru",
        ).forEach { text ->
            assertThrows(text, ProviderJsonException::class.java) { ProviderJsonParser.parse(text) }
        }
        val deep = "[".repeat(200) + "]".repeat(200)
        assertThrows(ProviderJsonException::class.java) { ProviderJsonParser.parse(deep) }
        assertThrows(ProviderJsonException::class.java) {
            ProviderJsonParser.parse("[1]", maximumCharacters = 2)
        }
    }

    @Test
    fun byteOrderMarkAndWhitespaceAreAccepted() {
        assertEquals(3.0, ProviderJsonParser.parse("﻿ \n {\"a\": 3}\t ")["a"]?.numberValue)
    }

    @Test
    fun writerEscapesControlCharactersAndConvertsKotlinValues() {
        val value = ProviderJsonValue.from(
            linkedMapOf("text" to "line\nbreak \"quoted\" \u0002", "list" to listOf(1, 2.5, false, null)),
        )
        val text = ProviderJsonWriter.write(value)

        assertEquals("""{"text":"line\nbreak \"quoted\" \u0002","list":[1,2.5,false,null]}""", text)
        assertEquals(value, ProviderJsonParser.parse(text))
    }

    @Test
    fun sanitizerRedactsSecretsRecursivelyAndKeepsOrdinaryFields() {
        val value = ProviderJsonSanitizer.sanitize(
            ProviderJsonParser.parse(
                """{"status":"up","count":17,"config":{"request_headers":{"Authorization":"Bearer private"},"ordinary_value":"preserved"},
                    "rows":[{"token":"private","value":3}]}""",
            ),
        )

        assertEquals("up", value["status"]?.stringValue)
        assertEquals(ProviderJsonValue.Str("[REDACTED]"), value["config"]?.get("request_headers"))
        assertEquals("preserved", value["config"]?.get("ordinary_value")?.stringValue)
        assertEquals(ProviderJsonValue.Str("[REDACTED]"), value["rows"]?.arrayValue?.first()?.get("token"))
        assertEquals(3.0, value["rows"]?.arrayValue?.first()?.get("value")?.numberValue)
    }

    @Test
    fun sanitizerRedactsCamelCaseSeparatorAndHeaderVariants() {
        val secretKeys = listOf(
            "accessToken", "access_token", "access-token", "Access Token", "refreshToken", "clientSecret",
            "requestHeaders", "verificationToken", "httpPassword", "apiSecret", "privateKey", "clientCredentials",
            "proxy-authorization", "environmentVariables", "set_cookie", "X-Api-Key", "X-Auth-Token", "Authorization-Header",
        )
        val fields = LinkedHashMap<String, ProviderJsonValue>()
        secretKeys.forEach { fields[it] = ProviderJsonValue.Str("private") }
        fields["tokenType"] = ProviderJsonValue.Str("Bearer")
        fields["publicIdentifier"] = ProviderJsonValue.Str("keep-me")

        val sanitized = ProviderJsonSanitizer.sanitize(ProviderJsonValue.Obj(fields))

        secretKeys.forEach { key -> assertEquals(key, "[REDACTED]", sanitized[key]?.stringValue) }
        assertEquals("Bearer", sanitized["tokenType"]?.stringValue)
        assertEquals("keep-me", sanitized["publicIdentifier"]?.stringValue)
    }

    @Test
    fun sanitizerStripsUrlCredentialsAndSecretQueryValues() {
        val sanitized = ProviderJsonSanitizer.sanitize(
            ProviderJsonValue.from(
                mapOf(
                    "callbackURL" to "https://alice:password@example.com/callback?token=private&sealed_token=sealed&api_key=key&mode=full",
                    "ordinaryText" to "alice:password@example.com is not a URL",
                ),
            ),
        )
        val url = URI(checkNotNull(sanitized["callbackURL"]?.stringValue))
        assertNull(url.rawUserInfo)
        val query = url.rawQuery.split('&').associate {
            val (name, value) = it.split('=', limit = 2)
            name to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
        assertEquals("[REDACTED]", query["token"])
        assertEquals("[REDACTED]", query["sealed_token"])
        assertEquals("[REDACTED]", query["api_key"])
        assertEquals("full", query["mode"])
        assertEquals("alice:password@example.com is not a URL", sanitized["ordinaryText"]?.stringValue)
    }

    @Test
    fun httpsRequestRejectsUnsafeTargetsAndNeverPrintsSecrets() {
        assertThrows(IllegalArgumentException::class.java) { ProviderHttpsRequest("GET", URI("http://example.com/")) }
        assertThrows(IllegalArgumentException::class.java) { ProviderHttpsRequest("GET", URI("https://user:pw@example.com/")) }
        assertThrows(IllegalArgumentException::class.java) { ProviderHttpsRequest("DELETE", URI("https://example.com/")) }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpsRequest("GET", URI("https://example.com/"), headers = mapOf("Authorization" to "Bearer x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpsRequest("GET", URI("https://example.com/"), headers = mapOf("X-Test" to "a\r\nInjected: b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpsRequest("GET", URI("https://example.com/"), body = byteArrayOf(1))
        }

        val request = ProviderHttpsRequest(
            method = "POST",
            uri = URI("https://ssl.bing.com/webmaster/api.svc/json/GetUserSites?apikey=query-secret"),
            bearerToken = SecretValue.of("bearer-secret"),
            secretHeaders = mapOf("x-umami-api-key" to SecretValue.of("header-secret")),
            body = "api_key=body-secret".toByteArray(),
        )
        val printed = request.toString()
        listOf("query-secret", "bearer-secret", "header-secret", "body-secret").forEach {
            assertFalse(printed, printed.contains(it))
        }
        assertEquals("api_key=body-secret", request.peekBody()?.toString(Charsets.UTF_8))
        assertEquals("api_key=body-secret", request.takeBody()?.toString(Charsets.UTF_8))
        assertTrue(request.takeBody()?.all { it == 0.toByte() } == true)
    }
}
