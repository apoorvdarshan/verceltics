package com.apoorvdarshan.verceltics.data.registrar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrarValuesTest {
    @Test
    fun boolMatchesIosVocabulary() {
        listOf("1", "true", "YES", "enabled", "On", "auto", "renew").forEach {
            assertEquals(it, true, RegistrarValues.bool(it))
        }
        listOf("0", "false", "No", "disabled", "off", "none", "MANUAL").forEach {
            assertEquals(it, false, RegistrarValues.bool(it))
        }
        assertEquals(true, RegistrarValues.bool(true))
        assertEquals(true, RegistrarValues.bool(2L))
        assertEquals(false, RegistrarValues.bool(0L))
        assertEquals(false, RegistrarValues.bool(0.0))
        assertNull(RegistrarValues.bool("full"))
        assertNull(RegistrarValues.bool("no renew option"))
        assertNull(RegistrarValues.bool(null))
        assertNull(RegistrarValues.bool(listOf(true)))
    }

    @Test
    fun stringPrefersFirstNonEmptyKeyAndRendersNumbersLikeNsNumber() {
        val value = mapOf<String, Any?>("empty" to "", "id" to 1002111L, "ratio" to 1.5, "whole" to 300.0, "name" to "a")

        assertEquals("a", RegistrarValues.string(value, "empty", "name"))
        assertEquals("1002111", RegistrarValues.string(value, "id"))
        assertEquals("1.5", RegistrarValues.string(value, "ratio"))
        assertEquals("300", RegistrarValues.string(value, "whole"))
        assertNull(RegistrarValues.string(value, "missing", "empty"))
    }

    @Test
    fun intAcceptsNumbersAndStrictIntegerStrings() {
        assertEquals(2, RegistrarValues.int(2L))
        assertEquals(2, RegistrarValues.int(2.9))
        assertEquals(12, RegistrarValues.int("12"))
        assertEquals(-1, RegistrarValues.int("-1"))
        assertNull(RegistrarValues.int(" 12"))
        assertNull(RegistrarValues.int("12a"))
        assertNull(RegistrarValues.int(null))
    }

    @Test
    fun stringsSplitDelimitedTextAndFilterArrays() {
        assertEquals(listOf("ns1.a", "ns2.a", "ns3.a"), RegistrarValues.strings("ns1.a, ns2.a;ns3.a"))
        assertEquals(listOf("ns1.a", "ns2.a"), RegistrarValues.strings(listOf("ns1.a", 4L, null, "ns2.a")))
        assertEquals(emptyList<String>(), RegistrarValues.strings(mapOf("hosts" to listOf("x"))))
    }

    @Test
    fun dateSupportsEveryRegistrarFormat() {
        val expected = utcMillis("2026-03-10T21:12:07Z")
        // Name.com / Gandi ISO-8601 with Z and with an offset.
        assertEquals(expected, RegistrarValues.date("2026-03-10T21:12:07Z"))
        assertEquals(expected, RegistrarValues.date("2026-03-10T23:12:07+02:00"))
        // Spaceship / GoDaddy fractional seconds.
        assertEquals(expected + 398, RegistrarValues.date("2026-03-10T21:12:07.398Z"))
        // Namecheap MM/dd/yyyy and NameSilo yyyy-MM-dd at UTC midnight.
        assertEquals(utcMillis("2027-02-15T00:00:00Z"), RegistrarValues.date("02/15/2027"))
        assertEquals(utcMillis("2026-03-01T00:00:00Z"), RegistrarValues.date("2026-03-01"))
        // Porkbun "yyyy-MM-dd HH:mm:ss" interpreted as UTC.
        assertEquals(utcMillis("2023-08-20T17:52:51Z"), RegistrarValues.date("2023-08-20 17:52:51"))
        // Dynadot millisecond strings and numeric seconds/milliseconds.
        assertEquals(1_767_225_600_000L, RegistrarValues.date("1767225600000"))
        assertEquals(1_767_225_600_000L, RegistrarValues.date(1_767_225_600L))
        assertEquals(1_767_225_600_000L, RegistrarValues.date(1_767_225_600_000L))
        assertEquals(1_767_225_600_500L, RegistrarValues.date(1_767_225_600.5))
    }

    @Test
    fun dateRejectsUnknownAndInvalidValues() {
        listOf("", "not a date", "2026-13-01", "02/30/2026", "2026-03-10T21:12:07", "NaN", "Infinity").forEach {
            assertNull(it, RegistrarValues.date(it))
        }
        assertNull(RegistrarValues.date(null))
        assertNull(RegistrarValues.date(true))
        assertNull(RegistrarValues.date(Double.NaN))
    }

    @Test
    fun findArrayPrefersKeysAtEachLevelBeforeDescending() {
        val value = mapOf(
            "ListDomainInfoResponse" to mapOf(
                "ResponseCode" to 0L,
                "Other" to mapOf("Domain" to listOf("ignored-deeper")),
                "MainDomains" to listOf(mapOf("Name" to "a.example")),
            ),
        )

        assertEquals(
            listOf(mapOf("Name" to "a.example")),
            RegistrarValues.findArray(value, setOf("MainDomains", "Domain")),
        )
        assertEquals(listOf("x"), RegistrarValues.findArray(listOf("x"), setOf("MainDomains")))
        assertEquals(emptyList<Any?>(), RegistrarValues.findArray(mapOf("MainDomains" to emptyList<Any?>()), setOf("MainDomains")))
    }
}
