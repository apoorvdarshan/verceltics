package com.apoorvdarshan.verceltics.ui.vercel

import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelProjectDeploymentUi
import com.apoorvdarshan.verceltics.ui.VercelProjectScopeUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelPresentationTest {
    private val now = 1_712_000_000_000L

    @Test
    fun projectsSortByLatestDeployThenUpdateTime() {
        val projects = listOf(
            project("old-deploy", deployedAt = now - 5 * HOUR, updatedAt = now),
            project("never-deployed", updatedAt = now - HOUR),
            project("fresh", deployedAt = now - MINUTE, updatedAt = now - 10 * HOUR),
            project("unknown"),
        )

        assertEquals(
            listOf("fresh", "never-deployed", "old-deploy", "unknown"),
            sortVercelProjectsByLatestDeploy(projects).map(VercelProjectUi::name),
        )
    }

    @Test
    fun searchMatchesNameDomainAndFrameworkCaseInsensitively() {
        val projects = listOf(
            project("studio-web", framework = "nextjs", domain = "studio.example"),
            project("docs", framework = "astro", domain = "handbook.example"),
            project("api", framework = null, domain = null),
        )

        assertEquals(listOf("docs"), filterVercelProjects(projects, "HANDBOOK").map { it.name })
        assertEquals(listOf("studio-web"), filterVercelProjects(projects, "NextJS").map { it.name })
        assertEquals(listOf("api"), filterVercelProjects(projects, "  api ").map { it.name })
        assertEquals(projects, filterVercelProjects(projects, "   "))
        assertTrue(filterVercelProjects(projects, "missing").isEmpty())
        assertEquals(
            "Visible projects are sorted before filtering.",
            listOf("docs", "studio-web"),
            visibleVercelProjects(
                listOf(project("studio-web", domain = "a.example", updatedAt = 1L), project("docs", domain = "b.example", updatedAt = 2L)),
                ".example",
            ).map { it.name },
        )
    }

    @Test
    fun frameworkNamesAndColorsMatchIos() {
        assertEquals("Next.js", prettyVercelFrameworkName("nextjs"))
        assertEquals("Nuxt", prettyVercelFrameworkName("nuxtjs"))
        assertEquals("SvelteKit", prettyVercelFrameworkName("sveltekit"))
        assertEquals("React", prettyVercelFrameworkName("create-react-app"))
        assertEquals("Blitz.js", prettyVercelFrameworkName("blitzjs"))
        assertEquals("Astro", prettyVercelFrameworkName("astro"))
        assertEquals("Remix", prettyVercelFrameworkName("remix"))

        assertNull("Next.js uses the primary text color.", vercelFrameworkColorArgb("nextjs"))
        assertEquals(0xFFFF734DL, vercelFrameworkColorArgb("Astro"))
        assertEquals(0xFF9980F2L, vercelFrameworkColorArgb("vite"))
        assertEquals(0xFFF24D66L, vercelFrameworkColorArgb("angular"))
        assertEquals(0xFF4DD98CL, vercelFrameworkColorArgb("nuxt"))
        assertEquals("Unknown frameworks use the default green.", 0xFF4DD98CL, vercelFrameworkColorArgb("gridsome"))
    }

    @Test
    fun freshDeployPulseLastsThirtyMinutes() {
        assertTrue(isFreshVercelDeploy(project("a", deployedAt = now - 29 * MINUTE), now))
        assertFalse(isFreshVercelDeploy(project("a", deployedAt = now - 30 * MINUTE), now))
        assertFalse(isFreshVercelDeploy(project("a", updatedAt = now), now))
    }

    @Test
    fun letterTilesUseAStableDjb2Color() {
        // djb2("a") = 5381 * 33 + 97 = 177670; 177670 mod 6 = 4.
        assertEquals(4, vercelLetterTileColorIndex("a"))
        assertEquals(vercelLetterTileColorIndex("studio-web"), vercelLetterTileColorIndex("studio-web"))
        listOf("", "studio-web", "émoji-🚀-site", "x".repeat(500)).forEach {
            assertTrue(vercelLetterTileColorIndex(it) in VERCEL_LETTER_TILE_COLORS.indices)
        }
        assertEquals("S", vercelProjectInitial("studio"))
        assertEquals("É", vercelProjectInitial("  émile"))
        assertEquals("?", vercelProjectInitial(""))
    }

    @Test
    fun websiteDashboardAndInspectorUrlsAreValidated() {
        assertEquals("https://studio.example", vercelWebsiteUrl("Studio.Example"))
        assertEquals("https://studio.example", vercelWebsiteUrl("studio.example."))
        assertNull(vercelWebsiteUrl(null))
        assertNull(vercelWebsiteUrl("localhost"))
        assertNull(vercelWebsiteUrl("studio.example/path"))
        assertNull(vercelWebsiteUrl("evil.example\"onclick"))
        assertNull(vercelWebsiteUrl("a..b"))

        val team = VercelProjectScopeUi("Studio", "studio", isTeam = true)
        val account = VercelAccountUi("Apoorv", null, username = "apoorv")
        assertEquals("https://vercel.com/studio/web", vercelDashboardUrl(project("web", scope = team), account))
        assertEquals("https://vercel.com/apoorv/web", vercelDashboardUrl(project("web"), account))
        assertNull("No username, no personal link.", vercelDashboardUrl(project("web"), VercelAccountUi("Apoorv Darshan", null)))
        assertNull(vercelDashboardUrl(project("web", scope = team.copy(slug = null)), account))
        assertNull(vercelDashboardUrl(project("we b"), account))

        assertEquals("https://vercel.com/acme/web/abc", vercelInspectorUrl("https://vercel.com/acme/web/abc"))
        assertNull(vercelInspectorUrl("https://evil.example/vercel.com"))
        assertNull(vercelInspectorUrl("http://vercel.com/acme"))
        assertNull(vercelInspectorUrl("javascript:alert(1)"))
        assertNull(vercelInspectorUrl("https://evil.example#.vercel.com"))
        assertNull(vercelInspectorUrl("https://evil.example\\.vercel.com/x"))
        assertNull(vercelInspectorUrl("https://user@vercel.com/acme"))
        assertNull(vercelInspectorUrl("https://vercel.com.evil.example/acme"))

        assertTrue(isOpenableVercelUrl("https://studio.example"))
        assertFalse(isOpenableVercelUrl("https://"))
        assertFalse(isOpenableVercelUrl("https://a b"))
        assertFalse(isOpenableVercelUrl("intent://x"))
        assertTrue(isVercelAliasDomain("APP.vercel.app"))
        assertFalse(isVercelAliasDomain("vercel.app.example"))
    }

    @Test
    fun projectMenuOffersWebsiteActionsOnlyWithADomain() {
        val account = VercelAccountUi("Apoorv", null, username = "apoorv")

        val withDomain = vercelProjectActions(project("web", domain = "studio.example"), account)
        assertEquals(
            listOf(
                VercelProjectAction.OpenWebsite("https://studio.example"),
                VercelProjectAction.CopyUrl("https://studio.example"),
                VercelProjectAction.ViewOnVercel("https://vercel.com/apoorv/web"),
                VercelProjectAction.ViewAnalytics,
            ),
            withDomain,
        )
        assertEquals(listOf("Open website", "Copy URL", "View on Vercel", "View analytics"), withDomain.map { it.label })
        assertEquals(listOf(VercelProjectAction.ViewAnalytics), vercelProjectActions(project("web"), VercelAccountUi("A", null)))
    }

    @Test
    fun statusAndEventTonesMatchIosKeywords() {
        assertEquals(VercelStatusTone.SUCCESS, vercelStatusTone("READY"))
        assertEquals(VercelStatusTone.PROGRESS, vercelStatusTone("BUILDING"))
        assertEquals(VercelStatusTone.PROGRESS, vercelStatusTone("INITIALIZING"))
        assertEquals(VercelStatusTone.WARNING, vercelStatusTone("QUEUED"))
        assertEquals(VercelStatusTone.DANGER, vercelStatusTone("ERROR"))
        assertEquals(VercelStatusTone.DANGER, vercelStatusTone("CANCELED"))
        assertEquals(VercelStatusTone.NEUTRAL, vercelStatusTone("UNKNOWN"))

        assertEquals(VercelStatusTone.DANGER, vercelEventTone("stdout", "503"))
        assertEquals(VercelStatusTone.DANGER, vercelEventTone("error", null))
        assertEquals(VercelStatusTone.SUCCESS, vercelEventTone("ready", null))
        assertEquals(VercelStatusTone.PROGRESS, vercelEventTone("command", null))
        assertEquals(VercelStatusTone.NEUTRAL, vercelEventTone("stdout", "200"))
        assertEquals("Canceled", capitalizedVercelState("CANCELED"))
    }

    @Test
    fun bounceRateDeltasUseInvertedColors() {
        // V9: a rising bounce rate is bad, a falling one good; other metrics are the reverse.
        assertEquals(VercelDeltaTone.BAD, vercelDeltaTone(change = 4.0, invert = true))
        assertEquals(VercelDeltaTone.GOOD, vercelDeltaTone(change = -4.0, invert = true))
        assertEquals(VercelDeltaTone.GOOD, vercelDeltaTone(change = 0.0, invert = true))
        assertEquals(VercelDeltaTone.GOOD, vercelDeltaTone(change = 12.5, invert = false))
        assertEquals(VercelDeltaTone.BAD, vercelDeltaTone(change = -0.1, invert = false))
        assertEquals(VercelDeltaTone.GOOD, vercelDeltaTone(change = 0.0, invert = false))
        assertEquals(VercelDeltaTone.NONE, vercelDeltaTone(change = null, invert = true))
        assertEquals(VercelDeltaTone.NONE, vercelDeltaTone(change = Double.NaN, invert = false))
    }

    @Test
    fun changesAndMetricsFormatLikeIos() {
        assertEquals("+12%", formatVercelDelta(12.4))
        assertEquals("+0%", formatVercelDelta(0.0))
        assertEquals("-3%", formatVercelDelta(-3.0))
        assertNull(formatVercelDelta(null))
        assertNull(formatVercelDelta(Double.POSITIVE_INFINITY))

        assertEquals(10.0, vercelPercentChange(110, 100)!!, 1e-9)
        assertNull("No previous traffic, no percentage.", vercelPercentChange(5, 0))
        assertNull(vercelPercentChange(5, null))
        assertEquals("Bounce changes are percentage points.", -3.0, vercelBounceChange(42.0, 45.0)!!, 1e-9)
        assertNull(vercelBounceChange(42.0, 0.0))
        assertNull(vercelBounceChange(null, 40.0))

        assertEquals("999", formatVercelMetric(999))
        assertEquals("12.8K", formatVercelMetric(12_806))
        assertEquals("1.5M", formatVercelMetric(1_500_000))
        assertEquals("42%", formatVercelBounceRate(41.6))
        assertEquals("—", formatVercelBounceRate(null))
    }

    @Test
    fun countriesShowFlagsAndLocalizedNames() {
        assertEquals("🇮🇳", vercelCountryFlag("IN"))
        assertEquals("🇺🇸", vercelCountryFlag("us"))
        assertEquals("", vercelCountryFlag("USA"))
        assertEquals("", vercelCountryFlag("1A"))

        assertEquals("United States", vercelCountryName("US", Locale.US))
        assertEquals("Allemagne", vercelCountryName("DE", Locale.FRANCE))
        assertEquals("Malformed codes fall back to the raw value.", "1A", vercelCountryName("1A", Locale.US))
        assertEquals("EU-West", vercelCountryName("EU-West", Locale.US))

        assertEquals("India", vercelBreakdownLabel("IN", emptyLabel = "", isCountry = true, locale = Locale.US))
        assertEquals("Direct", vercelBreakdownLabel("", emptyLabel = "Direct", isCountry = false))
        assertEquals("Unknown", vercelBreakdownLabel("", emptyLabel = "", isCountry = false))
        assertEquals("/pricing", vercelBreakdownLabel("/pricing", emptyLabel = "", isCountry = false))
    }

    @Test
    fun lockedAndStaleWordingMatchesIos() {
        assertEquals("Requires Pro + Web Analytics Plus", vercelUtmLockedTitle(hasLongAnalyticsHistory = false))
        assertEquals("Upgrade to Web Analytics Plus", vercelUtmLockedTitle(hasLongAnalyticsHistory = true))
        assertEquals(
            "Offline. Showing the last successful 7d · Production result.",
            vercelStaleAnalyticsMessage("Offline.", "7d", "Production"),
        )
        assertEquals("Offline.", vercelStaleAnalyticsMessage("Offline.", null, "Production"))
        assertEquals("0f1e2d3", shortVercelSha("0f1e2d3c4b5a"))
        assertEquals("0f1e2d3c4b5a", shortVercelSha("0f1e2d3c4b5a6978", length = 12))
        assertNull(shortVercelSha(" "))
    }

    private fun project(
        name: String,
        framework: String? = null,
        domain: String? = null,
        deployedAt: Long? = null,
        updatedAt: Long? = null,
        scope: VercelProjectScopeUi? = null,
    ) = VercelProjectUi(
        id = "prj_$name",
        name = name,
        framework = framework,
        updatedAtMillis = updatedAt,
        primaryDomain = domain,
        scope = scope,
        lastDeployment = deployedAt?.let { VercelProjectDeploymentUi("commit", it) },
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
