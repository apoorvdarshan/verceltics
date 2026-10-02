package com.apoorvdarshan.verceltics.ui.screens.about

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * Platform update seam. Android intentionally ships with an unconfigured implementation until a
 * trusted Play or release endpoint is selected by the app shell.
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
}

sealed interface AboutUpdateState {
    data object NotConfigured : AboutUpdateState
    data object Idle : AboutUpdateState
    data object Checking : AboutUpdateState
    data class Current(val checkedVersion: String) : AboutUpdateState

    data class Available(
        val latestVersion: String,
        val destinationUri: String,
    ) : AboutUpdateState

    data class Failed(val message: String) : AboutUpdateState
}

enum class AboutDestination(val uri: String) {
    WEBSITE("https://verceltics.com"),
    SOURCE_CODE("https://github.com/apoorvdarshan/verceltics"),
    LINKED_IN("https://www.linkedin.com/showcase/verceltics"),
    X_PROFILE("https://x.com/apoorvdarshan"),
    CONTACT("mailto:ad13dtu@gmail.com"),
    REPORT_ISSUE("https://github.com/apoorvdarshan/verceltics/issues"),
    PRODUCT_HUNT("https://www.producthunt.com/products/verceltics"),
    RATE_APP("market://details?id=com.apoorvdarshan.verceltics"),
    SUPPORT_DEVELOPMENT("https://ko-fi.com/apoorvdarshan"),
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
}

@Stable
class AboutScreenController(
    private val appearanceStore: AppearancePreferenceStore,
    private val updateChecker: AboutUpdateChecker,
    version: AboutAppVersion,
) {
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

    suspend fun checkForUpdates() {
        if (!updateChecker.isConfigured || state.update == AboutUpdateState.Checking) return

        state = state.copy(update = AboutUpdateState.Checking)
        val result = runCatching { updateChecker.check(state.version) }
            .getOrElse { AboutUpdateResult.Failed("Unable to check right now") }
        state = state.copy(
            update = when (result) {
                AboutUpdateResult.Current -> AboutUpdateState.Current(state.version.name)
                is AboutUpdateResult.Available -> AboutUpdateState.Available(
                    latestVersion = result.latestVersion,
                    destinationUri = result.destinationUri,
                )

                is AboutUpdateResult.Failed -> AboutUpdateState.Failed(
                    result.message.ifBlank { "Unable to check right now" },
                )
            },
        )
    }
}

object UnconfiguredAboutUpdateChecker : AboutUpdateChecker {
    override val isConfigured: Boolean = false

    override suspend fun check(currentVersion: AboutAppVersion): AboutUpdateResult =
        AboutUpdateResult.Failed("Android update checks are not configured.")
}
