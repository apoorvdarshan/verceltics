package com.apoorvdarshan.verceltics.ui.screens.about

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException

@Immutable
data class AboutAppVersion(
    val name: String,
    val code: Long,
) {
    init {
        require(name.isNotBlank()) { "The app version name cannot be blank." }
        require(code >= 0) { "The app version code cannot be negative." }
    }
}

enum class AboutAppearance(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStoredValue(value: String?): AboutAppearance =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

/** Local persistence seam shared by the About screen and the app-level theme owner. */
interface AppearancePreferenceStore {
    fun load(): AboutAppearance
    fun save(appearance: AboutAppearance)
}

/**
 * Platform update seam. Release builds use Google Play In-App Updates; builds that Google Play did
 * not install report themselves as unconfigured so the About row stays truthful.
 */
interface AboutUpdateChecker {
    val isConfigured: Boolean

    suspend fun check(currentVersion: AboutAppVersion): AboutUpdateResult
}

sealed interface AboutUpdateResult {
    data object Current : AboutUpdateResult

    data class Available(
        val latestVersion: String,
        val destinationUri: String,
    ) : AboutUpdateResult

    data class Failed(val message: String) : AboutUpdateResult

    /** The update service cannot serve this install, for example a sideloaded build. */
    data object Unavailable : AboutUpdateResult
}

sealed interface AboutUpdateState {
    data object NotConfigured : AboutUpdateState
    data object Idle : AboutUpdateState
    data object Checking : AboutUpdateState
    data class Current(val checkedVersion: String) : AboutUpdateState

    /**
     * A newer build is ready. [latestVersion] is blank when the store only reports a build number,
     * in which case the row uses generic copy instead of inventing a version name.
     */
    data class Available(
        val latestVersion: String,
        val destinationUri: String,
    ) : AboutUpdateState

    data class Failed(val message: String) : AboutUpdateState
}

/** The navigation badge mirrors iOS: it only appears while an update is ready to install. */
val AboutUpdateState.showsUpdateBadge: Boolean
    get() = this is AboutUpdateState.Available

enum class AboutDestination(val uri: String) {
    WEBSITE("https://verceltics.com"),
    SOURCE_CODE("https://github.com/apoorvdarshan/verceltics"),
    LINKED_IN("https://www.linkedin.com/showcase/verceltics"),
    X_PROFILE("https://x.com/apoorvdarshan"),
    INSTAGRAM_PROFILE("https://www.instagram.com/apoorvcodes/"),
    DISCORD("https://discord.gg/qv5nsTmkxA"),
    CONTACT("mailto:ad13dtu@gmail.com"),
    REPORT_ISSUE("https://github.com/apoorvdarshan/verceltics/issues/new?template=bug_report.yml"),
    REQUEST_FEATURE("https://github.com/apoorvdarshan/verceltics/issues/new?template=feature_request.yml"),
    DISCORD_BUG_REPORT("https://discord.gg/Skw8emkFev"),
    DISCORD_FEATURE_REQUEST("https://discord.gg/R798cm6n3h"),
    PRODUCT_HUNT("https://www.producthunt.com/products/verceltics-2"),
    RATE_APP("market://details?id=com.apoorvdarshan.verceltics"),
    PLAY_STORE_LISTING("https://play.google.com/store/apps/details?id=com.apoorvdarshan.verceltics"),
    MANAGE_SUBSCRIPTION("https://play.google.com/store/account/subscriptions?package=com.apoorvdarshan.verceltics"),
    PRIVACY_POLICY("https://verceltics.com/privacy"),
    TERMS_OF_SERVICE("https://verceltics.com/terms"),
    LICENSE("https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE"),
}

@Immutable
data class AboutScreenState(
    val version: AboutAppVersion,
    val appearance: AboutAppearance,
    val update: AboutUpdateState,
)

sealed interface AboutScreenAction {
    data class SelectAppearance(val appearance: AboutAppearance) : AboutScreenAction
    data object CheckForUpdates : AboutScreenAction
    data class OpenDestination(val destination: AboutDestination) : AboutScreenAction
    data class OpenExternalUri(val uri: String) : AboutScreenAction
    data object ShareApp : AboutScreenAction

    /** Start the store's in-app update flow, opening [fallbackUri] when that flow is unavailable. */
    data class InstallUpdate(val fallbackUri: String) : AboutScreenAction

    /** Ask for an in-app rating, opening the store listing when the review flow is unavailable. */
    data object RateApp : AboutScreenAction
}

@Stable
class AboutScreenController(
    private val appearanceStore: AppearancePreferenceStore,
    private val updateChecker: AboutUpdateChecker,
    version: AboutAppVersion,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastCheckedAtMillis: Long? = null

    var state by mutableStateOf(
        AboutScreenState(
            version = version,
            appearance = runCatching(appearanceStore::load).getOrDefault(AboutAppearance.SYSTEM),
            update = if (updateChecker.isConfigured) {
                AboutUpdateState.Idle
            } else {
                AboutUpdateState.NotConfigured
            },
        ),
    )
        private set

    fun selectAppearance(appearance: AboutAppearance) {
        if (appearance == state.appearance) return
        if (runCatching { appearanceStore.save(appearance) }.isSuccess) {
            state = state.copy(appearance = appearance)
        }
    }

    /**
     * Checks the update service. Automatic launch and foreground checks pass `force = false` and
     * are throttled to once an hour, like the iOS App Store lookup; the About row always forces.
     */
    suspend fun checkForUpdates(force: Boolean = true) {
        if (!updateChecker.isConfigured || state.update == AboutUpdateState.Checking) return
        if (state.update == AboutUpdateState.NotConfigured) return
        val lastCheckedAt = lastCheckedAtMillis
        if (!force && lastCheckedAt != null &&
            nowMillis() - lastCheckedAt < AUTOMATIC_CHECK_INTERVAL_MILLIS
        ) {
            return
        }

        val previous = state.update
        state = state.copy(update = AboutUpdateState.Checking)
        val result = try {
            updateChecker.check(state.version)
        } catch (cancellation: CancellationException) {
            state = state.copy(update = previous)
            throw cancellation
        } catch (_: Exception) {
            AboutUpdateResult.Failed(UPDATE_CHECK_FAILED_MESSAGE)
        }
        lastCheckedAtMillis = nowMillis()
        state = state.copy(update = aboutUpdateState(result, state.version))
    }

    companion object {
        const val AUTOMATIC_CHECK_INTERVAL_MILLIS: Long = 60L * 60L * 1_000L
        const val UPDATE_CHECK_FAILED_MESSAGE = "Unable to check right now"
    }
}

/** Pure result-to-row mapping shared by every update checker. */
fun aboutUpdateState(result: AboutUpdateResult, version: AboutAppVersion): AboutUpdateState =
    when (result) {
        AboutUpdateResult.Current -> AboutUpdateState.Current(version.name)
        is AboutUpdateResult.Available -> AboutUpdateState.Available(
            latestVersion = result.latestVersion,
            destinationUri = result.destinationUri,
        )
        is AboutUpdateResult.Failed -> AboutUpdateState.Failed(
            result.message.ifBlank { AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE },
        )
        // A build Google Play cannot update stays "unavailable" rather than a scary failure.
        AboutUpdateResult.Unavailable -> AboutUpdateState.NotConfigured
    }

object UnconfiguredAboutUpdateChecker : AboutUpdateChecker {
    override val isConfigured: Boolean = false

    override suspend fun check(currentVersion: AboutAppVersion): AboutUpdateResult =
        AboutUpdateResult.Unavailable
}
