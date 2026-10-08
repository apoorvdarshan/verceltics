package com.apoorvdarshan.verceltics.ui.vercel

import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Newest deployment first, falling back to the project's update time (iOS `filteredProjects`). */
fun sortVercelProjectsByLatestDeploy(projects: List<VercelProjectUi>): List<VercelProjectUi> =
    projects.sortedByDescending(VercelProjectUi::latestActivityMillis)

/** Case-insensitive match on the project name, primary domain or framework. */
fun filterVercelProjects(projects: List<VercelProjectUi>, query: String): List<VercelProjectUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return projects
    return projects.filter { project ->
        project.name.contains(normalized, ignoreCase = true) ||
            project.primaryDomain?.contains(normalized, ignoreCase = true) == true ||
            project.framework?.contains(normalized, ignoreCase = true) == true
    }
}

fun visibleVercelProjects(projects: List<VercelProjectUi>, query: String): List<VercelProjectUi> =
    filterVercelProjects(sortVercelProjectsByLatestDeploy(projects), query)

/** Human framework names used on project cards, e.g. `nextjs` → `Next.js`. */
fun prettyVercelFrameworkName(framework: String): String = when (framework.lowercase(Locale.ROOT)) {
    "nextjs", "next.js" -> "Next.js"
    "nuxtjs", "nuxt" -> "Nuxt"
    "sveltekit" -> "SvelteKit"
    "create-react-app" -> "React"
    "blitzjs" -> "Blitz.js"
    else -> framework.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * The framework dot color as ARGB, or null when the dot should use the primary text color
 * (Next.js, like iOS). Unknown frameworks use the default green.
 */
fun vercelFrameworkColorArgb(framework: String): Long? = when (framework.lowercase(Locale.ROOT)) {
    "nextjs", "next.js" -> null
    "astro", "sveltekit", "svelte" -> 0xFFFF734DL
    "vite" -> 0xFF9980F2L
    "gatsby" -> 0xFFD966F2L
    "remix", "create-react-app" -> 0xFF4DBFF2L
    "angular" -> 0xFFF24D66L
    "hugo" -> 0xFFF273F2L
    "blitzjs" -> 0xFF8C80F2L
    "eleventy" -> 0xFFFFD94DL
    else -> DEFAULT_FRAMEWORK_ARGB
}

private const val DEFAULT_FRAMEWORK_ARGB = 0xFF4DD98CL
private const val FRESH_DEPLOY_WINDOW_MILLIS = 30 * 60 * 1_000L

/** A deployment younger than 30 minutes gets the pulsing dot on its card. */
fun isFreshVercelDeploy(project: VercelProjectUi, nowMillis: Long): Boolean {
    val deployedAt = project.lastDeployment?.createdAtMillis ?: return false
    return nowMillis - deployedAt < FRESH_DEPLOY_WINDOW_MILLIS
}

/** Letter-tile palette (iOS `ProjectIcon.colorForName`). */
val VERCEL_LETTER_TILE_COLORS: List<Long> = listOf(
    0xFF5480C2L,
    0xFF4A9C8FL,
    0xFF9E70BAL,
    0xFFBA7357L,
    0xFF7A87A6L,
    0xFFA66E8CL,
)

/** djb2 over Unicode scalars with 64-bit wrapping, so a name keeps its tile color everywhere. */
fun vercelLetterTileColorIndex(name: String): Int {
    var hash = 5381L
    var index = 0
    while (index < name.length) {
        val codePoint = name.codePointAt(index)
        hash = (hash shl 5) + hash + codePoint.toLong()
        index += Character.charCount(codePoint)
    }
    return (hash.toULong() % VERCEL_LETTER_TILE_COLORS.size.toULong()).toInt()
}

fun vercelProjectInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase(Locale.ROOT)
}

private val SAFE_HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
private val SAFE_SLUG = Regex("[A-Za-z0-9._-]{1,128}")

/** `vercel.app` and its subdomains are Vercel aliases; everything else is a custom domain. */
fun isVercelAliasDomain(domain: String): Boolean {
    val normalized = domain.trim().lowercase(Locale.ROOT)
    return normalized == "vercel.app" || normalized.endsWith(".vercel.app")
}

/** `https://{domain}` for a plain host name, or null for anything that is not one. */
fun vercelWebsiteUrl(domain: String?): String? {
    val host = domain?.trim()?.removeSuffix(".") ?: return null
    if (!SAFE_HOST.matches(host) || !host.contains('.') || host.contains("..")) return null
    return "https://${host.lowercase(Locale.ROOT)}"
}

/**
 * `https://vercel.com/{scope}/{project}`: the team slug for team projects, the username for
 * personal ones. Null when either segment is unknown or not URL-safe.
 */
fun vercelDashboardUrl(project: VercelProjectUi, account: VercelAccountUi?): String? {
    val scope = if (project.scope?.isTeam == true) project.scope.slug else account?.username
    if (scope == null || !SAFE_SLUG.matches(scope) || !SAFE_SLUG.matches(project.name)) return null
    return "https://vercel.com/$scope/${project.name}"
}

/** Only absolute HTTPS URLs without whitespace or control characters are opened. */
fun isOpenableVercelUrl(url: String?): Boolean =
    url != null && url.startsWith("https://") && url.length > "https://".length &&
        url.none { it.isWhitespace() || it.isISOControl() }

/** The Inspect link from Vercel, accepted only when it points at vercel.com. */
fun vercelInspectorUrl(raw: String?): String? {
    val url = raw?.trim() ?: return null
    if (!isOpenableVercelUrl(url)) return null
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    return url.takeIf { host == "vercel.com" || host.endsWith(".vercel.com") }
}

enum class VercelStatusTone {
    SUCCESS,
    WARNING,
    DANGER,
    PROGRESS,
    NEUTRAL,
}

/** Keyword mapping from iOS `AppStatusTone.status`. */
fun vercelStatusTone(value: String): VercelStatusTone {
    val text = value.lowercase(Locale.ROOT)
    fun any(vararg words: String) = words.any(text::contains)
    return when {
        any(
            "inactive", "deactiv", "expired", "disabled", "deleted", "blocked", "fail", "error",
            "cancel", "suspend", "fatal", "stopped", "offline",
        ) -> VercelStatusTone.DANGER
        any("build", "progress", "initial") -> VercelStatusTone.PROGRESS
        any("pending", "queued", "starting", "warning", "paused", "not ready", "incomplete") ->
            VercelStatusTone.WARNING
        any("active", "ready", "success", "live", "running", "published", "succeed", "complete") ->
            VercelStatusTone.SUCCESS
        else -> VercelStatusTone.NEUTRAL
    }
}

/** Build-event dot color (iOS `DeploymentDetailView.eventColor`). */
fun vercelEventTone(type: String, statusCode: String?): VercelStatusTone {
    if (statusCode?.startsWith("5") == true) return VercelStatusTone.DANGER
    return when (type.uppercase(Locale.ROOT)) {
        "ERROR", "FATAL", "WARNING" -> VercelStatusTone.DANGER
        "READY", "DONE", "COMPLETE" -> VercelStatusTone.SUCCESS
        "BUILDING", "INITIALIZING", "COMMAND" -> VercelStatusTone.PROGRESS
        else -> VercelStatusTone.NEUTRAL
    }
}

fun capitalizedVercelState(state: String): String =
    state.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

enum class VercelDeltaTone {
    /** A good change: green. */
    GOOD,

    /** A bad change: red. */
    BAD,

    /** No comparison is available. */
    NONE,
}

/**
 * Color meaning of a period-over-period change. With [invert] (bounce rate) a rising value is
 * bad and a falling one good, as in iOS `StatCard(invertChange:)`. No change counts as good.
 */
fun vercelDeltaTone(change: Double?, invert: Boolean): VercelDeltaTone = when {
    change == null || change.isNaN() -> VercelDeltaTone.NONE
    invert -> if (change <= 0.0) VercelDeltaTone.GOOD else VercelDeltaTone.BAD
    else -> if (change >= 0.0) VercelDeltaTone.GOOD else VercelDeltaTone.BAD
}

/** `+12%` / `-3%`, rounded like iOS `%.0f`. */
fun formatVercelDelta(change: Double?): String? {
    if (change == null || change.isNaN() || change.isInfinite()) return null
    val rounded = change.roundToLong()
    return if (change >= 0) "+$rounded%" else "$rounded%"
}

fun vercelPercentChange(current: Long, previous: Long?): Double? =
    previous?.takeIf { it != 0L }?.let { (current - it).toDouble() / it.toDouble() * 100.0 }

/** Bounce rate change in percentage points, as on iOS. */
fun vercelBounceChange(current: Double?, previous: Double?): Double? =
    if (current == null || previous == null || previous == 0.0) null else current - previous

fun formatVercelMetric(value: Long): String = when {
    abs(value) >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    abs(value) >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatVercelBounceRate(value: Double?): String = value?.let { "${it.roundToLong()}%" } ?: "—"

/** Regional-indicator flag for a two-letter ISO country code, or an empty string. */
fun vercelCountryFlag(code: String): String {
    val normalized = code.trim().uppercase(Locale.ROOT)
    if (normalized.length != 2 || !normalized.all { it in 'A'..'Z' }) return ""
    return buildString {
        normalized.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) }
    }
}

/** Localized country name, falling back to the raw code. */
fun vercelCountryName(code: String, locale: Locale = Locale.getDefault()): String {
    val normalized = code.trim()
    if (normalized.length != 2) return normalized
    val region = runCatching { Locale.Builder().setRegion(normalized.uppercase(Locale.ROOT)).build() }
        .getOrNull() ?: return normalized
    val name = region.getDisplayCountry(locale)
    return name.takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) } ?: normalized
}

fun vercelBreakdownLabel(
    key: String,
    emptyLabel: String,
    isCountry: Boolean,
    locale: Locale = Locale.getDefault(),
): String = when {
    key.isEmpty() -> emptyLabel.ifEmpty { "Unknown" }
    isCountry -> vercelCountryName(key, locale)
    else -> key
}

/** Locked-card wording for the UTM breakdown: Plus upsell once long history is proven. */
fun vercelUtmLockedTitle(hasLongAnalyticsHistory: Boolean): String =
    if (hasLongAnalyticsHistory) "Upgrade to Web Analytics Plus" else "Requires Pro + Web Analytics Plus"

/** `{error} Showing the last successful 7d · Production result.` */
fun vercelStaleAnalyticsMessage(error: String, rangeLabel: String?, environmentLabel: String?): String =
    if (rangeLabel == null || environmentLabel == null) {
        error
    } else {
        "$error Showing the last successful $rangeLabel · $environmentLabel result."
    }

fun shortVercelSha(sha: String?, length: Int = 7): String? = sha?.trim()?.takeIf(String::isNotEmpty)?.take(length)

/** Long-press menu actions on a project card (iOS `projectContextMenu`). All are Pro actions. */
sealed interface VercelProjectAction {
    val label: String
    val testTagSuffix: String

    data class OpenWebsite(val url: String) : VercelProjectAction {
        override val label = "Open website"
        override val testTagSuffix = "openWebsite"
    }

    data class CopyUrl(val url: String) : VercelProjectAction {
        override val label = "Copy URL"
        override val testTagSuffix = "copyUrl"
    }

    data class ViewOnVercel(val url: String) : VercelProjectAction {
        override val label = "View on Vercel"
        override val testTagSuffix = "viewOnVercel"
    }

    data object ViewAnalytics : VercelProjectAction {
        override val label = "View analytics"
        override val testTagSuffix = "viewAnalytics"
    }
}

/** Website actions need a public domain and Vercel needs a known scope; analytics is always offered. */
fun vercelProjectActions(project: VercelProjectUi, account: VercelAccountUi?): List<VercelProjectAction> =
    buildList {
        vercelWebsiteUrl(project.primaryDomain)?.let { url ->
            add(VercelProjectAction.OpenWebsite(url))
            add(VercelProjectAction.CopyUrl(url))
        }
        vercelDashboardUrl(project, account)?.let { add(VercelProjectAction.ViewOnVercel(it)) }
        add(VercelProjectAction.ViewAnalytics)
    }
