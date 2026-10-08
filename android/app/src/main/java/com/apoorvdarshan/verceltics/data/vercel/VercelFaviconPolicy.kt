package com.apoorvdarshan.verceltics.data.vercel

import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Privacy and safety rules for project favicons, ported from iOS `ProjectIcon` and
 * `FaviconHostSafety`. Favicons are fetched only from the project's own public HTTPS origin:
 * no third-party favicon service ever learns which sites a user manages.
 */
object VercelFaviconPolicy {
    const val MAX_IMAGE_BYTES: Int = 2_000_000
    const val MAX_HTML_BYTES: Int = 512_000
    const val MIN_IMAGE_BYTES: Int = 51
    const val MIN_ICON_PIXELS: Int = 32
    const val MAX_ICON_PIXELS: Int = 256
    const val FAILURE_RETRY_MILLIS: Long = 5 * 60 * 1_000L
    const val OVERALL_TIMEOUT_MILLIS: Long = 8_000L
    const val MAX_SCRAPED_ICONS: Int = 2
    private const val MAX_SCANNED_LINKS = 8
    private val DIRECT_ICON_PATHS = listOf("/apple-touch-icon.png", "/favicon.ico")
    private val PRIVATE_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home")
    private val ICON_LINK = Regex(
        "<link[^>]*rel=[\"'][^\"']*icon[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"']" +
            "|<link[^>]*href=[\"']([^\"']+)[\"'][^>]*rel=[\"'][^\"']*icon[^\"']*[\"']",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Normalizes a project domain to a lowercase public host, or null when it is not a safe
     * HTTPS origin (credentials, odd ports, single-label, private suffixes, private IPv4).
     */
    fun publicHost(rawValue: String): String? {
        val trimmed = rawValue.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val candidate = try {
            URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
        } catch (_: Exception) {
            return null
        }
        if (!candidate.scheme.equals("https", ignoreCase = true)) return null
        if (candidate.rawUserInfo != null) return null
        if (candidate.port != -1 && candidate.port != 443) return null
        val host = candidate.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
        if (!host.contains('.') || host.contains(':') || host.startsWith('[')) return null
        if (PRIVATE_SUFFIXES.any(host::endsWith)) return null
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) {
            val values = octets.map { it.toIntOrNull() ?: return null }
            if (values.any { it !in 0..255 }) return null
            if (!isPublicIpv4(values.map(Int::toByte).toByteArray())) return null
        }
        return host
    }

    /** `https://{host}/apple-touch-icon.png` and `/favicon.ico`, raced in parallel. */
    fun directIconUris(host: String): List<URI> = DIRECT_ICON_PATHS.map { URI("https://$host$it") }

    fun homePageUri(host: String): URI = URI("https://$host/")

    /** True only when every resolved address is public; an empty resolution fails closed. */
    fun allPublic(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all(::isPublicAddress)

    fun isPublicAddress(address: InetAddress): Boolean = isPublicIpAddress(address.address)

    /** Raw IPv4 (4 bytes) or IPv6 (16 bytes) address check, matching iOS `isPublicIPAddress`. */
    fun isPublicIpAddress(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> isPublicIpv4(bytes)
        16 -> isPublicIpv6(bytes)
        else -> false
    }

    /** Same-origin, non-redirecting-to-elsewhere check used for every hop and scraped icon. */
    fun isSameOrigin(uri: URI, host: String): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.removeSuffix(".").equals(host, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null

    /**
     * Resolves a redirect `Location` against [current]; null unless it stays on the same HTTPS
     * host and port, so favicon discovery can never be bounced to a third party.
     */
    fun sameOriginRedirect(current: URI, location: String, host: String): URI? {
        if (location.isBlank() || location.length > 4_096) return null
        val target = try {
            current.resolve(URI(encodeHref(location))).normalize()
        } catch (_: Exception) {
            return null
        }
        return target.takeIf { isSameOrigin(it, host) && it.rawFragment == null }
    }

    /**
     * Up to two same-origin, non-SVG icon URLs declared by `<link rel="…icon…" href="…">` tags.
     * `data:` URLs, other hosts, other ports and credentials are ignored.
     */
    fun scrapeIconUris(html: String, pageUri: URI): List<URI> {
        val host = pageUri.host?.lowercase(Locale.ROOT) ?: return emptyList()
        val icons = mutableListOf<URI>()
        for (match in ICON_LINK.findAll(html).take(MAX_SCANNED_LINKS)) {
            val href = match.groupValues[1].ifEmpty { match.groupValues[2] }
                .replace("&amp;", "&")
                .trim()
            if (href.isEmpty() || href.lowercase(Locale.ROOT).startsWith("data:")) continue
            val resolved = try {
                pageUri.resolve(URI(encodeHref(href))).normalize()
            } catch (_: Exception) {
                continue
            }
            if (!isSameOrigin(resolved, host)) continue
            if (resolved.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "")
                    .lowercase(Locale.ROOT).contains("svg")
            ) {
                continue
            }
            if (resolved !in icons) icons += resolved
            if (icons.size == MAX_SCRAPED_ICONS) break
        }
        return icons
    }

    /** SVG favicons are skipped: they would need a script-capable renderer. */
    fun looksLikeSvg(bytes: ByteArray, contentType: String?): Boolean {
        if (contentType?.lowercase(Locale.ROOT)?.contains("svg") == true) return true
        val prefix = String(bytes, 0, minOf(bytes.size, 400), StandardCharsets.UTF_8)
        return prefix.lowercase(Locale.ROOT).contains("<svg")
    }

    /** Largest power-of-two sample size that keeps the decoded icon at least [targetPixels]. */
    fun sampleSizeFor(width: Int, height: Int, targetPixels: Int = MAX_ICON_PIXELS): Int {
        var sample = 1
        while (width / (sample * 2) >= targetPixels && height / (sample * 2) >= targetPixels) sample *= 2
        return sample
    }

    /**
     * Removes a white matte connected to the border, keeping interior white artwork, when at
     * least 65 % of the border is near-white. [argbPixels] are non-premultiplied ARGB and are
     * modified in place. Returns true when any pixel became transparent.
     */
    fun removeWhiteBackground(argbPixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return false
        val borderThreshold = 245
        val cleanupThreshold = 230
        val neutralSpread = 18

        fun isNearWhite(index: Int, threshold: Int): Boolean {
            val pixel = argbPixels[index]
            val alpha = (pixel ushr 24) and 0xff
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            val minimum = minOf(red, green, blue)
            val maximum = maxOf(red, green, blue)
            return alpha > 240 && minimum >= threshold && maximum - minimum <= neutralSpread
        }

        val border = buildList {
            for (x in 0 until width) {
                add(x)
                if (height > 1) add((height - 1) * width + x)
            }
            if (height > 2) {
                for (y in 1 until height - 1) {
                    add(y * width)
                    if (width > 1) add(y * width + width - 1)
                }
            }
        }
        if (border.isEmpty()) return false
        val whiteBorder = border.count { isNearWhite(it, borderThreshold) }
        if (whiteBorder.toDouble() / border.size < 0.65) return false

        val visited = BooleanArray(width * height)
        val stack = ArrayDeque<Int>()
        fun enqueue(index: Int) {
            if (visited[index] || !isNearWhite(index, cleanupThreshold)) return
            visited[index] = true
            stack.addLast(index)
        }
        border.forEach(::enqueue)
        var removed = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            argbPixels[index] = 0
            removed += 1
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }
        return removed > 0
    }

    /** Percent-encodes characters `java.net.URI` rejects (spaces, quotes, non-ASCII…). */
    internal fun encodeHref(href: String): String = buildString {
        href.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            val character = code.toChar()
            if (code in 0x21..0x7e && character !in "\"<>\\^`{|} ") {
                append(character)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 192 && second == 0 && third <= 2 -> false
            first == 198 && (second == 18 || second == 19 || second == 51) -> false
            first == 203 && second == 0 && third == 113 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val unsigned = IntArray(16) { bytes[it].toInt() and 0xff }
        if (unsigned.all { it == 0 }) return false
        if ((0 until 15).all { unsigned[it] == 0 } && unsigned[15] == 1) return false
        if (unsigned[0] and 0xfe == 0xfc) return false // Unique-local fc00::/7.
        if (unsigned[0] == 0xfe && unsigned[1] and 0xc0 == 0x80) return false // Link-local fe80::/10.
        if (unsigned[0] == 0xfe && unsigned[1] and 0xc0 == 0xc0) return false // Deprecated site-local.
        if (unsigned[0] == 0xff) return false // Multicast.
        if (unsigned[0] == 0x20 && unsigned[1] == 0x01 && unsigned[2] == 0x0d && unsigned[3] == 0xb8) {
            return false // Documentation 2001:db8::/32.
        }
        val isIpv4Mapped = (0 until 10).all { unsigned[it] == 0 } && unsigned[10] == 0xff && unsigned[11] == 0xff
        val isIpv4Compatible = (0 until 12).all { unsigned[it] == 0 }
        val nat64Prefix = intArrayOf(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)
        val isWellKnownNat64 = (0 until 12).all { unsigned[it] == nat64Prefix[it] }
        if (isIpv4Mapped || isIpv4Compatible || isWellKnownNat64) {
            return isPublicIpv4(bytes.copyOfRange(12, 16))
        }
        return true
    }
}
