package com.apoorvdarshan.verceltics.data.vercel

import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelFaviconPolicyTest {
    @Test
    fun publicHostAcceptsPlainProjectDomainsAndNormalizesThem() {
        assertEquals("studio.example", VercelFaviconPolicy.publicHost("studio.example"))
        assertEquals("studio.example", VercelFaviconPolicy.publicHost("  Studio.Example  "))
        assertEquals("studio.example", VercelFaviconPolicy.publicHost("https://studio.example/path?q=1"))
        assertEquals("studio.example", VercelFaviconPolicy.publicHost("https://studio.example:443"))
        assertEquals("app-a1b2.vercel.app", VercelFaviconPolicy.publicHost("app-a1b2.vercel.app"))
        assertEquals("studio.example", VercelFaviconPolicy.publicHost("studio.example."))
        assertEquals("8.8.8.8", VercelFaviconPolicy.publicHost("8.8.8.8"))
    }

    @Test
    fun publicHostRejectsUnsafeOrigins() {
        listOf(
            "",
            "   ",
            "http://studio.example",
            "ftp://studio.example",
            "https://user:pass@studio.example",
            "https://studio.example:8443",
            "localhost",
            "intranet",
            "printer.local",
            "router.lan",
            "nas.home",
            "db.internal",
            "dev.localhost",
            "10.0.0.8",
            "127.0.0.1",
            "169.254.169.254",
            "172.20.1.1",
            "192.168.1.1",
            "100.64.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "999.1.1.1",
            "[::1]",
            "https://[2606:4700::1111]",
            "studio example.com",
            "studio.exa\tmple",
        ).forEach { raw ->
            assertNull("'$raw' must not be fetched.", VercelFaviconPolicy.publicHost(raw))
        }
    }

    @Test
    fun ipv4ClassificationMatchesIos() {
        val blocked = listOf(
            "0.1.2.3", "10.1.2.3", "127.0.0.1", "224.0.0.1", "255.255.255.255", "100.64.0.1", "100.127.255.255",
            "169.254.1.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.0.0.1", "192.0.2.1",
            "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1",
        )
        val public = listOf("8.8.8.8", "1.1.1.1", "76.76.21.21", "100.63.0.1", "100.128.0.1", "172.15.0.1", "172.32.0.1", "192.0.3.1", "198.20.0.1")

        blocked.forEach { assertFalse("$it is not public.", VercelFaviconPolicy.isPublicIpAddress(bytes(it))) }
        public.forEach { assertTrue("$it is public.", VercelFaviconPolicy.isPublicIpAddress(bytes(it))) }
    }

    @Test
    fun ipv6ClassificationMatchesIos() {
        val blocked = listOf(
            "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1",
            "::10.0.0.1", "64:ff9b::192.168.1.1",
        )
        val public = listOf("2606:4700:4700::1111", "2a00:1450:4001:80b::200e", "::8.8.8.8", "64:ff9b::8.8.8.8")

        blocked.forEach { assertFalse("$it is not public.", VercelFaviconPolicy.isPublicIpAddress(ipv6(it))) }
        public.forEach { assertTrue("$it is public.", VercelFaviconPolicy.isPublicIpAddress(ipv6(it))) }
        assertFalse(VercelFaviconPolicy.isPublicIpAddress(ByteArray(5)))
    }

    @Test
    fun resolutionMustBeEntirelyPublicAndNonEmpty() {
        assertFalse(VercelFaviconPolicy.allPublic(emptyList()))
        assertTrue(VercelFaviconPolicy.allPublic(listOf(address("76.76.21.21"), address("2606:4700::1"))))
        assertFalse(
            "One private answer fails the whole host (DNS rebinding).",
            VercelFaviconPolicy.allPublic(listOf(address("76.76.21.21"), address("10.0.0.1"))),
        )
    }

    @Test
    fun directIconsAndHomePageStayOnTheProjectOrigin() {
        assertEquals(
            listOf(URI("https://studio.example/apple-touch-icon.png"), URI("https://studio.example/favicon.ico")),
            VercelFaviconPolicy.directIconUris("studio.example"),
        )
        assertEquals(URI("https://studio.example/"), VercelFaviconPolicy.homePageUri("studio.example"))
    }

    @Test
    fun scrapedIconsAreSameOriginRasterAndCappedAtTwo() {
        val html = """
            <html><head>
              <link rel="icon" href="data:image/png;base64,AAAA">
              <link rel="icon" type="image/svg+xml" href="/icon.svg">
              <link rel="icon" href="https://tracker.example/favicon.png">
              <link rel="icon" href="//cdn.example/favicon.png">
              <link rel="icon" href="https://studio.example:8443/favicon.png">
              <link rel="stylesheet" href="/styles.css">
              <link href="/assets/calorie logo.png" rel="shortcut icon">
              <LINK REL="apple-touch-icon" HREF="icons/touch.png?v=1&amp;x=2">
              <link rel="icon" href="/third.png">
            </head></html>
        """.trimIndent()

        val icons = VercelFaviconPolicy.scrapeIconUris(html, URI("https://studio.example/"))

        assertEquals(
            listOf(
                URI("https://studio.example/assets/calorie%20logo.png"),
                URI("https://studio.example/icons/touch.png?v=1&x=2"),
            ),
            icons,
        )
    }

    @Test
    fun scrapingIgnoresHtmlWithoutIconLinks() {
        assertTrue(VercelFaviconPolicy.scrapeIconUris("<html><body>Hello</body></html>", URI("https://studio.example/")).isEmpty())
        assertTrue(VercelFaviconPolicy.scrapeIconUris("<link rel=\"icon\" href=\"https://user@studio.example/x.png\">", URI("https://studio.example/")).isEmpty())
    }

    @Test
    fun redirectsAreFollowedOnlyWithinTheSameHttpsHost() {
        val current = URI("https://studio.example/favicon.ico")

        assertEquals(URI("https://studio.example/static/favicon.ico"), VercelFaviconPolicy.sameOriginRedirect(current, "/static/favicon.ico", "studio.example"))
        assertEquals(URI("https://studio.example/a.png"), VercelFaviconPolicy.sameOriginRedirect(current, "https://STUDIO.example/a.png", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "https://www.studio.example/favicon.ico", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "http://studio.example/favicon.ico", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "https://studio.example:444/favicon.ico", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "https://user@studio.example/x", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "", "studio.example"))
        assertNull(VercelFaviconPolicy.sameOriginRedirect(current, "//evil.example/x", "studio.example"))
    }

    @Test
    fun svgFaviconsAreDetectedByTypeOrContent() {
        assertTrue(VercelFaviconPolicy.looksLikeSvg(ByteArray(10), "image/svg+xml"))
        assertTrue(VercelFaviconPolicy.looksLikeSvg("<?xml version=\"1.0\"?><SVG xmlns=…>".encodeToByteArray(), "text/plain"))
        assertFalse(VercelFaviconPolicy.looksLikeSvg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A), "image/png"))
        assertFalse(VercelFaviconPolicy.looksLikeSvg(ByteArray(0), null))
    }

    @Test
    fun sampleSizeKeepsAtLeastTheTargetResolution() {
        assertEquals(1, VercelFaviconPolicy.sampleSizeFor(180, 180))
        assertEquals(1, VercelFaviconPolicy.sampleSizeFor(511, 511))
        assertEquals(2, VercelFaviconPolicy.sampleSizeFor(512, 512))
        assertEquals(4, VercelFaviconPolicy.sampleSizeFor(1_024, 1_100))
        assertEquals(1, VercelFaviconPolicy.sampleSizeFor(4_096, 32))
    }

    @Test
    fun whiteMatteConnectedToTheBorderBecomesTransparent() {
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFE53935.toInt()
        // 5x5: white border and corners, a red ring, and a white pixel enclosed by red.
        val pixels = intArrayOf(
            white, white, white, white, white,
            white, red, red, red, white,
            white, red, white, red, white,
            white, red, red, red, white,
            white, white, white, white, white,
        )

        assertTrue(VercelFaviconPolicy.removeWhiteBackground(pixels, 5, 5))

        (0 until 5).forEach { assertEquals(0, pixels[it]) }
        assertEquals(red, pixels[6])
        assertEquals("Interior white artwork is kept.", white, pixels[12])
    }

    @Test
    fun matteRemovalNeedsAMostlyWhiteBorder() {
        val white = 0xFFFFFFFF.toInt()
        val blue = 0xFF1E88E5.toInt()
        val mostlyBlue = IntArray(16) { if (it == 0) white else blue }
        val original = mostlyBlue.copyOf()

        assertFalse(VercelFaviconPolicy.removeWhiteBackground(mostlyBlue, 4, 4))
        assertArrayEquals(original, mostlyBlue)

        val translucentWhite = IntArray(9) { 0x80FFFFFF.toInt() }
        assertFalse("Already-transparent edges are not near-white mattes.", VercelFaviconPolicy.removeWhiteBackground(translucentWhite, 3, 3))
        assertFalse(VercelFaviconPolicy.removeWhiteBackground(IntArray(0), 0, 0))
    }

    @Test
    fun hrefEncodingKeepsReservedCharactersAndEscapesTheRest() {
        assertEquals("/a%20b/%C3%A9.png?x=1&y=%22", VercelFaviconPolicy.encodeHref("/a b/é.png?x=1&y=\""))
        assertEquals("/already%20encoded.png", VercelFaviconPolicy.encodeHref("/already%20encoded.png"))
    }

    private fun bytes(ipv4: String): ByteArray = ipv4.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun ipv6(literal: String): ByteArray {
        val address = InetAddress.getByName(literal)
        val raw = address.address
        if (raw.size == 16) return raw
        // Java collapses IPv4-mapped literals to IPv4; rebuild the IPv4-compatible form explicitly.
        return ByteArray(12) + raw
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)
}
