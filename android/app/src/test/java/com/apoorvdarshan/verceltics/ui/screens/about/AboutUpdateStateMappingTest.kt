package com.apoorvdarshan.verceltics.ui.screens.about

import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutUpdateStateMappingTest {
    private val version = AboutAppVersion(name = "1.0", code = 43)

    @Test
    fun `play availability maps to current, available, or retryable failure`() {
        assertEquals(
            AboutUpdateResult.Current,
            playUpdateResult(UpdateAvailability.UPDATE_NOT_AVAILABLE, availableVersionCode = 43, currentVersionCode = 43),
        )
        assertEquals(
            AboutUpdateResult.Available(latestVersion = "", destinationUri = AboutDestination.PLAY_STORE_LISTING.uri),
            playUpdateResult(UpdateAvailability.UPDATE_AVAILABLE, availableVersionCode = 44, currentVersionCode = 43),
        )
        assertEquals(
            AboutUpdateResult.Available(latestVersion = "", destinationUri = AboutDestination.PLAY_STORE_LISTING.uri),
            playUpdateResult(
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                availableVersionCode = 44,
                currentVersionCode = 43,
            ),
        )
        assertEquals(
            AboutUpdateResult.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE),
            playUpdateResult(UpdateAvailability.UNKNOWN, availableVersionCode = 0, currentVersionCode = 43),
        )
    }

    @Test
    fun `play never badges a build this device already runs`() {
        assertEquals(
            AboutUpdateResult.Current,
            playUpdateResult(UpdateAvailability.UPDATE_AVAILABLE, availableVersionCode = 43, currentVersionCode = 43),
        )
    }

    @Test
    fun `sideloaded and unsupported installs degrade to unavailable instead of an error`() {
        listOf(
            InstallErrorCode.ERROR_APP_NOT_OWNED,
            InstallErrorCode.ERROR_API_NOT_AVAILABLE,
            InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND,
            InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED,
            InstallErrorCode.ERROR_INSTALL_UNAVAILABLE,
            InstallErrorCode.ERROR_INVALID_REQUEST,
        ).forEach { code ->
            assertEquals("error $code", AboutUpdateResult.Unavailable, playUpdateErrorResult(code))
        }
        assertEquals(
            AboutUpdateResult.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE),
            playUpdateErrorResult(InstallErrorCode.ERROR_INTERNAL_ERROR),
        )
    }

    @Test
    fun `results map to about row states`() {
        assertEquals(AboutUpdateState.Current("1.0"), aboutUpdateState(AboutUpdateResult.Current, version))
        assertEquals(
            AboutUpdateState.Available("", "https://play.example"),
            aboutUpdateState(AboutUpdateResult.Available("", "https://play.example"), version),
        )
        assertEquals(
            AboutUpdateState.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE),
            aboutUpdateState(AboutUpdateResult.Failed(""), version),
        )
        assertEquals(AboutUpdateState.NotConfigured, aboutUpdateState(AboutUpdateResult.Unavailable, version))
    }

    @Test
    fun `about badge only shows while an update is available`() {
        assertTrue(AboutUpdateState.Available("", "https://play.example").showsUpdateBadge)
        listOf(
            AboutUpdateState.NotConfigured,
            AboutUpdateState.Idle,
            AboutUpdateState.Checking,
            AboutUpdateState.Current("1.0"),
            AboutUpdateState.Failed("offline"),
        ).forEach { assertFalse(it.toString(), it.showsUpdateBadge) }
    }

    @Test
    fun `sideloaded play checker is unconfigured and never binds the play service`() = runTest {
        val checker = PlayInAppUpdateChecker(
            appUpdateManagerProvider = { error("Play must not be contacted for sideloaded builds") },
            isConfigured = false,
        )

        assertFalse(checker.isConfigured)
        assertEquals(AboutUpdateResult.Unavailable, checker.check(version))

        val controller = AboutScreenController(MemoryAppearanceStore(), checker, version)
        controller.checkForUpdates()
        assertEquals(AboutUpdateState.NotConfigured, controller.state.update)
    }

    @Test
    fun `automatic checks are throttled to once an hour while manual checks always run`() = runTest {
        var now = 0L
        val checker = CountingChecker(AboutUpdateResult.Current)
        val controller = AboutScreenController(MemoryAppearanceStore(), checker, version, nowMillis = { now })

        controller.checkForUpdates(force = false)
        now += 10 * 60 * 1_000L
        controller.checkForUpdates(force = false)
        assertEquals(1, checker.calls)

        controller.checkForUpdates(force = true)
        assertEquals(2, checker.calls)

        now += AboutScreenController.AUTOMATIC_CHECK_INTERVAL_MILLIS
        controller.checkForUpdates(force = false)
        assertEquals(3, checker.calls)
        assertEquals(AboutUpdateState.Current("1.0"), controller.state.update)
    }

    @Test
    fun `an unavailable result stops further checks and shows the unavailable row`() = runTest {
        val checker = CountingChecker(AboutUpdateResult.Unavailable)
        val controller = AboutScreenController(MemoryAppearanceStore(), checker, version)

        controller.checkForUpdates()
        controller.checkForUpdates()

        assertEquals(AboutUpdateState.NotConfigured, controller.state.update)
        assertEquals(1, checker.calls)
    }

    @Test
    fun `failed checks stay retryable`() = runTest {
        val checker = CountingChecker(AboutUpdateResult.Failed("offline"))
        val controller = AboutScreenController(MemoryAppearanceStore(), checker, version)

        controller.checkForUpdates()
        assertEquals(AboutUpdateState.Failed("offline"), controller.state.update)

        checker.result = AboutUpdateResult.Available("", AboutDestination.PLAY_STORE_LISTING.uri)
        controller.checkForUpdates()
        assertEquals(
            AboutUpdateState.Available("", AboutDestination.PLAY_STORE_LISTING.uri),
            controller.state.update,
        )
        assertEquals(2, checker.calls)
    }

    private class CountingChecker(var result: AboutUpdateResult) : AboutUpdateChecker {
        override val isConfigured: Boolean = true
        var calls = 0

        override suspend fun check(currentVersion: AboutAppVersion): AboutUpdateResult {
            calls += 1
            return result
        }
    }

    private class MemoryAppearanceStore : AppearancePreferenceStore {
        private var appearance = AboutAppearance.SYSTEM

        override fun load(): AboutAppearance = appearance

        override fun save(appearance: AboutAppearance) {
            this.appearance = appearance
        }
    }
}
