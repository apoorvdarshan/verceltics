package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Typed, null-tolerant readers over the lossless provider JSON tree. Every Cloudflare model parser in
// the operations packages uses these so missing or mistyped optional fields degrade to null instead
// of failing an entire screen (iOS `decodeIfPresent`).

fun ProviderJsonValue?.str(key: String): String? = when (val value = this?.get(key)) {
    is ProviderJsonValue.Str -> value.value
    is ProviderJsonValue.Num -> value.text
    is ProviderJsonValue.Bool -> value.value.toString()
    else -> null
}

/** Only a JSON string (not a number or boolean rendered as text). */
fun ProviderJsonValue?.strictStr(key: String): String? = (this?.get(key) as? ProviderJsonValue.Str)?.value

fun ProviderJsonValue?.bool(key: String): Boolean? = when (val value = this?.get(key)) {
    is ProviderJsonValue.Bool -> value.value
    else -> null
}

fun ProviderJsonValue?.long(key: String): Long? = this?.get(key)?.decimalValue?.let {
    runCatching { it.setScale(0, RoundingMode.DOWN).longValueExact() }.getOrNull()
}

fun ProviderJsonValue?.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

fun ProviderJsonValue?.double(key: String): Double? = this?.get(key)?.numberValue

fun ProviderJsonValue?.obj(key: String): ProviderJsonValue.Obj? = this?.get(key) as? ProviderJsonValue.Obj

fun ProviderJsonValue?.arr(key: String): List<ProviderJsonValue> = (this?.get(key) as? ProviderJsonValue.Arr)?.items.orEmpty()

fun ProviderJsonValue?.strings(key: String): List<String> = arr(key).mapNotNull { it.stringValue }

/** Builds a JSON object from Kotlin values, omitting null entries (iOS `encodeIfPresent`). */
fun cloudflareJsonObject(vararg pairs: Pair<String, Any?>): ProviderJsonValue.Obj =
    ProviderJsonValue.from(linkedMapOf(*pairs.filter { it.second != null }.toTypedArray())) as ProviderJsonValue.Obj

/** iOS `CloudflareJSONValue.operationsDisplayText`. */
val ProviderJsonValue.cloudflareDisplayText: String
    get() = when (this) {
        is ProviderJsonValue.Str -> value
        is ProviderJsonValue.Num -> CloudflareFormat.decimal(decimal)
        is ProviderJsonValue.Bool -> if (value) "On" else "Off"
        is ProviderJsonValue.Obj -> fields.entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key.replace('_', ' ')}: ${it.value.cloudflareDisplayText}" }
        is ProviderJsonValue.Arr -> items.joinToString(", ") { it.cloudflareDisplayText }
        ProviderJsonValue.Null -> "Not set"
    }

/** iOS `CloudflareDateParser`: RFC 3339 with or without fractional seconds, or a bare UTC date. */
object CloudflareDates {
    fun parse(value: String?): Instant? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        runCatching { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        runCatching { return Instant.parse(text) }
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
        return null
    }

    fun iso8601(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /** "Oct 9, 2026, 3:04 PM" in the device time zone (iOS `.abbreviated` + `.shortened`). */
    fun formatDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    fun formatDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

    /** Formats a Cloudflare timestamp string, or returns it unchanged when it cannot be parsed. */
    fun display(value: String?): String? = value?.let { text -> parse(text)?.let(::formatDateTime) ?: text }

    /** "5 min ago" style relative label used in deployment and version lists. */
    fun relative(instant: Instant, now: Instant = Instant.now()): String {
        val seconds = (now.epochSecond - instant.epochSecond).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} hr ago"
            seconds < 30 * 86_400 -> "${seconds / 86_400} days ago".replace("1 days", "1 day")
            else -> formatDate(instant)
        }
    }
}

/** Number and size formatting shared by Cloudflare operations screens. */
object CloudflareFormat {
    /** iOS `ByteCountFormatter` `.file` style (decimal units). */
    fun bytes(count: Long): String {
        if (count < 1_000) return "$count bytes".replace("1 bytes", "1 byte")
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var value = count.toDouble()
        var unit = -1
        while (value >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit += 1
        }
        val rounded = if (value >= 100) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)
        return "${rounded.removeSuffix(".0")} ${units[unit]}"
    }

    /** iOS `.number.notation(.compactName)`: 950, 1.2K, 3.4M, 5B. */
    fun compact(value: Long): String {
        val magnitude = kotlin.math.abs(value)
        val (divisor, suffix) = when {
            magnitude >= 1_000_000_000_000 -> 1_000_000_000_000.0 to "T"
            magnitude >= 1_000_000_000 -> 1_000_000_000.0 to "B"
            magnitude >= 1_000_000 -> 1_000_000.0 to "M"
            magnitude >= 1_000 -> 1_000.0 to "K"
            else -> return value.toString()
        }
        val scaled = value / divisor
        val text = if (kotlin.math.abs(scaled) >= 100) String.format(Locale.US, "%.0f", scaled) else String.format(Locale.US, "%.1f", scaled)
        return text.removeSuffix(".0") + suffix
    }

    fun grouped(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    /** Up to two fraction digits without trailing zeros (iOS `.precision(.fractionLength(0...2))`). */
    fun decimal(value: BigDecimal): String {
        val scaled = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (scaled.scale() <= 0 && scaled.abs() < BigDecimal(1_000_000_000_000_000L)) {
            grouped(scaled.toLong())
        } else {
            scaled.toPlainString()
        }
    }

    fun percent(value: Double, fractionDigits: Int = 1): String = String.format(Locale.US, "%.${fractionDigits}f%%", value)
}
