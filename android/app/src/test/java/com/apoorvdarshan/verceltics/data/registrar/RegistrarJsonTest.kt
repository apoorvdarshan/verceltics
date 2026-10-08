package com.apoorvdarshan.verceltics.data.registrar

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarJsonTest {
    @Test
    fun parsesNestedObjectsArraysAndScalarsInOrder() {
        val value = RegistrarJson.parse(
            """
            {"status":"SUCCESS","count":2,"ratio":1.5,"big":12345678901234,"flag":true,"none":null,
             "domains":[{"domain":"a.example"},"b.example",[]],"empty":{}}
            """.trimIndent(),
        ) as Map<*, *>

        assertEquals(listOf("status", "count", "ratio", "big", "flag", "none", "domains", "empty"), value.keys.toList())
        assertEquals("SUCCESS", value["status"])
        assertEquals(2L, value["count"])
        assertEquals(1.5, value["ratio"])
        assertEquals(12_345_678_901_234L, value["big"])
        assertEquals(true, value["flag"])
        assertNull(value["none"])
        assertTrue(value.containsKey("none"))
        val domains = value["domains"] as List<*>
        assertEquals(mapOf("domain" to "a.example"), domains[0])
        assertEquals("b.example", domains[1])
        assertEquals(emptyList<Any?>(), domains[2])
        assertEquals(emptyMap<String, Any?>(), value["empty"])
    }

    @Test
    fun decodesEscapesUnicodeAndByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"text":"a\"b\\c\/d\n\té😀 münchen"}""".toByteArray(StandardCharsets.UTF_8)

        val value = RegistrarJson.parse(bytes) as Map<*, *>

        assertEquals("a\"b\\c/d\n\té😀 münchen", value["text"])
    }

    @Test
    fun parsesExponentsAndNegativeNumbersAsDoublesOrLongs() {
        val value = RegistrarJson.parse("[-0, -12, 1e3, 2.5E-1, 9223372036854775808]") as List<*>

        assertEquals(0L, value[0])
        assertEquals(-12L, value[1])
        assertEquals(1000.0, value[2])
        assertEquals(0.25, value[3])
        assertEquals(9.223372036854775808E18, value[4])
    }

    @Test
    fun rejectsMalformedDocuments() {
        listOf(
            "",
            "   ",
            "{",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\" 1}",
            "{'a':1}",
            "[01]",
            "[1.]",
            "[.5]",
            "[+1]",
            "tru",
            "nul",
            "\"unterminated",
            "\"bad \\x escape\"",
            "\"bad \\u12 escape\"",
            "\"raw\ncontrol\"",
            "{} trailing",
            "[NaN]",
        ).forEach { text ->
            assertThrows("Expected rejection of <$text>", RegistrarJsonException::class.java) {
                RegistrarJson.parse(text)
            }
        }
    }

    @Test
    fun rejectsInvalidUtf8AndExcessiveNesting() {
        assertThrows(RegistrarJsonException::class.java) {
            RegistrarJson.parse(byteArrayOf('['.code.toByte(), 0xC3.toByte(), ']'.code.toByte()))
        }
        val deep = "[".repeat(80) + "]".repeat(80)
        assertThrows(RegistrarJsonException::class.java) { RegistrarJson.parse(deep) }
        val acceptable = "[".repeat(40) + "]".repeat(40)
        assertTrue(RegistrarJson.parse(acceptable) is List<*>)
    }
}
