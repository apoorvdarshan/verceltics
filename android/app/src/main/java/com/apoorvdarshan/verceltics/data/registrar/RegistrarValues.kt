package com.apoorvdarshan.verceltics.data.registrar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Faithful port of the loose value accessors in iOS `RegistrarAPI` (`object`, `array`,
 * `string`, `bool`, `int`, `strings`, `date`). Registrars disagree on field types, so every
 * accessor tolerates strings, numbers and booleans exactly like `JSONSerialization` + `NSNumber`.
 */
internal object RegistrarValues {
    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun array(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    /** First non-empty string (or number rendered like `NSNumber.stringValue`) for [keys]. */
    fun string(value: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            when (val candidate = value[key]) {
                is String -> if (candidate.isNotEmpty()) return candidate
                is Boolean -> return if (candidate) "1" else "0"
                is Number -> return numberString(candidate)
            }
        }
        return null
    }

    fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> when (value.lowercase(Locale.ROOT)) {
            "1", "true", "yes", "enabled", "on", "auto", "renew" -> true
            "0", "false", "no", "disabled", "off", "none", "manual" -> false
            else -> null
        }
        else -> null
    }

    fun int(value: Any?): Int? = when (value) {
        is Boolean -> if (value) 1 else 0
        is Long -> value.toInt()
        is Int -> value
        is Number -> value.toDouble().takeIf(Double::isFinite)?.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.filterIsInstance<String>()
        is String -> value.split(',', ';', ' ').filter(String::isNotEmpty)
        else -> emptyList()
    }

    /** Epoch milliseconds, or null when the value is not one of the iOS-supported formats. */
    fun date(value: Any?): Long? {
        if (value is Number) return unixMillis(value.toDouble())
        val text = value as? String ?: return null
        if (text.isEmpty()) return null
        if (NUMERIC.matches(text)) text.toDoubleOrNull()?.let { return unixMillis(it) }
        parseOffsetDateTime(text)?.let { return it }
        for (formatter in DATE_FORMATS) {
            runCatching { LocalDate.parse(text, formatter) }.getOrNull()?.let {
                return it.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        for (formatter in DATE_TIME_FORMATS) {
            runCatching { LocalDateTime.parse(text, formatter) }.getOrNull()?.let {
                return it.toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        return null
    }

    /** iOS `unixDate`: values above 100 billion are milliseconds, otherwise seconds. */
    fun unixMillis(value: Double): Long? {
        if (!value.isFinite()) return null
        val seconds = if (value > 100_000_000_000.0) value / 1_000.0 else value
        val millis = seconds * 1_000.0
        if (millis > Long.MAX_VALUE.toDouble() || millis < Long.MIN_VALUE.toDouble()) return null
        return millis.toLong()
    }

    fun numberString(value: Number): String = when (value) {
        is Long, is Int, is Short, is Byte -> value.toString()
        else -> {
            val double = value.toDouble()
            if (double.isFinite() && double == Math.floor(double) && kotlin.math.abs(double) < 1e15) {
                double.toLong().toString()
            } else {
                double.toString()
            }
        }
    }

    /**
     * Port of iOS `findArray`: the value itself when it is a non-empty array; otherwise the first
     * array under one of [keys] at this level, then a depth-first search of nested values.
     */
    fun findArray(value: Any?, keys: Set<String>, depth: Int = 0): List<Any?> {
        if (depth > MAX_SEARCH_DEPTH) return emptyList()
        if (value is List<*> && value.isNotEmpty()) return array(value)
        val map = value as? Map<*, *> ?: return emptyList()
        for ((key, nested) in map) {
            if (key in keys && nested is List<*>) return array(nested)
        }
        for (nested in map.values) {
            val result = findArray(nested, keys, depth + 1)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun parseOffsetDateTime(text: String): Long? {
        for (formatter in OFFSET_FORMATS) {
            runCatching { OffsetDateTime.parse(text, formatter) }.getOrNull()?.let {
                return it.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private const val MAX_SEARCH_DEPTH = 32
    private val NUMERIC = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")

    private val OFFSET_FORMATS = listOf(
        // ISO8601DateFormatter (internet date time) and yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX.
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XX", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT),
    )

    private val DATE_FORMATS = listOf(
        // MM/dd/yyyy then yyyy-MM-dd, interpreted at UTC midnight like the iOS POSIX formatters.
        DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu-M-d", Locale.US).withResolverStyle(ResolverStyle.STRICT),
    )

    private val DATE_TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("uuuu-M-d H:m:s", Locale.US).withResolverStyle(ResolverStyle.STRICT),
    )
}
