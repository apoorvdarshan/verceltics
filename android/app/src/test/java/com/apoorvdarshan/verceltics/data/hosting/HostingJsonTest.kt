package com.apoorvdarshan.verceltics.data.hosting

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingJsonTest {
    @Test
    fun parsesNestedProviderPayloadAndKeepsNumbersExact() {
        val value = HostingJson.parse(
            """
            {"apps":[{"id":"a1","machine_count":3,"big":12345678901234567890,"ratio":-1.5e3,
              "ok":true,"none":null,"name":"caf\u00e9 \"quoted\" \\ \/"}],"empty":{}}
            """.trimIndent(),
        ).asObject()

        val app = value["apps"].asArray().single().asObject()
        assertEquals("a1", app.string("id"))
        assertEquals(3L, app["machine_count"].integer())
        assertEquals("12345678901234567890", (app["big"] as JsonNumber).raw)
        assertEquals(-1500.0, (app["ratio"] as JsonNumber).toDoubleOrNull()!!, 0.0)
        assertEquals(true, app.bool("ok"))
        assertEquals(JsonNull, app["none"])
        assertEquals("café \"quoted\" \\ /", app.string("name"))
        assertTrue(value["empty"].asObject().isEmpty)
    }

    @Test
    fun rejectsMalformedTrailingAndOverDeepInput() {
        listOf(
            "{\"a\":1,}",
            "[1,2",
            "{\"a\" 1}",
            "01",
            "1.",
            "\"unterminated",
            "{\"a\":1} trailing",
            "\"tab\tinside\"",
            "nul",
            "",
        ).forEach { input ->
            assertThrows(input, HostingResponseFormatException::class.java) { HostingJson.parse(input) }
        }
        val deep = "[".repeat(HostingJson.MAX_DEPTH + 2) + "]".repeat(HostingJson.MAX_DEPTH + 2)
        assertThrows(HostingResponseFormatException::class.java) { HostingJson.parse(deep) }
        assertThrows(HostingResponseFormatException::class.java) {
            HostingJson.parse(byteArrayOf(0x22, 0xC3.toByte(), 0x28, 0x22))
        }
    }

    @Test
    fun writerEscapesAndRoundTripsWithSortedKeysForFingerprints() {
        val value = JsonObject(
            linkedMapOf(
                "z" to JsonString("line\nbreak \"q\" \u0001"),
                "a" to JsonArray(listOf(JsonNumber("1"), JsonBoolean(false), JsonNull)),
            ),
        )
        val written = HostingJson.write(value)
        assertEquals(value, HostingJson.parse(written))
        assertEquals("{\"a\":[1,false,null],\"z\":\"line\\nbreak \\\"q\\\" \\u0001\"}", HostingJson.write(value, sortedKeys = true))

        val reordered = JsonObject(linkedMapOf("a" to value["a"]!!, "z" to value["z"]!!))
        assertEquals(jsonFingerprint(value), jsonFingerprint(reordered))
        assertEquals(64, jsonFingerprint(value).length)
    }

    @Test
    fun iosStringAccessorReadsFallbackKeysDottedPathsAndNumbers() {
        val app = HostingJson.parse(
            """{"spec":{"name":"from-spec"},"name":"","count":42,"blank":"  ","flag":true}""",
        ).asObject()

        assertEquals("from-spec", app.string("spec.name", "name"))
        assertEquals("42", app.string("missing", "count"))
        assertEquals("  ", app.string("blank"))
        assertNull(app.string("name"))
        assertNull(app.string("flag"))
        assertEquals(true, app.bool("flag"))
        assertEquals(true, HostingJson.parse("""{"n":1}""").asObject().bool("n"))
    }

    @Test
    fun datesAcceptIsoWithAndWithoutFractionsAndEpochSecondsOrMillis() {
        assertEquals(1_700_000_000_000L, JsonString("2023-11-14T22:13:20Z").dateMillis())
        assertEquals(1_700_000_000_123L, JsonString("2023-11-14T22:13:20.123Z").dateMillis())
        assertEquals(1_700_000_000_123L, JsonString("2023-11-14T22:13:20.123456789Z").dateMillis())
        assertEquals(1_700_000_000_000L, JsonString("2023-11-14T23:13:20+01:00").dateMillis())
        assertEquals(1_700_000_000_500L, JsonNumber("1700000000.5").dateMillis())
        assertEquals(1_700_000_000_000L, JsonNumber("1700000000000").dateMillis())
        assertEquals(1_700_000_000_000L, JsonString("1700000000").dateMillis())
        assertNull(JsonString("yesterday").dateMillis())
        assertNull(JsonNumber("-5").dateMillis())
        assertNull(JsonNull.dateMillis())
    }

    @Test
    fun stableIdentifierPrefersProviderIdOtherwiseHashesIdentifyingFields() {
        assertEquals("srv-1", stableIdentifier("srv-1", "render-service", listOf("x")))
        val hashed = stableIdentifier(null, "render-service", listOf(" web ", null, "https://web.example"))
        assertTrue(hashed!!.matches(Regex("render-service-[0-9a-f]{20}")))
        assertEquals(hashed, stableIdentifier("", "render-service", listOf("web", "https://web.example")))
        assertNull(stableIdentifier(null, "render-service", listOf(null, "  ")))
    }

    @Test
    fun displayJsonRendersStructuresDeterministically() {
        assertEquals("plain", displayJson(JsonString("plain")))
        assertNull(displayJson(JsonNull))
        assertEquals(
            "{\"a\":1,\"b\":\"x\"}",
            displayJson(HostingJson.parse("""{"b":"x","a":1}""")),
        )
        assertFalse(sha256Hex("abc".toByteArray(StandardCharsets.UTF_8)).isEmpty())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
