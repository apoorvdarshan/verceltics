package com.apoorvdarshan.verceltics.ui.screens.about

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlin.coroutines.cancellation.CancellationException

/**
 * Google Play In-App Updates behind the About update seam.
 *
 * Only installs that Google Play delivered can be updated by Play, so sideloaded and debug builds
 * report [isConfigured] = false and never call the Play service. Any Play error that means "this
 * install cannot be served" maps to [AboutUpdateResult.Unavailable]; transient failures stay
 * retryable.
 */
class PlayInAppUpdateChecker internal constructor(
    private val appUpdateManagerProvider: () -> AppUpdateManager,
    override val isConfigured: Boolean,
) : AboutUpdateChecker {
    constructor(context: Context) : this(
        appUpdateManagerProvider = lazyAppUpdateManager(context.applicationContext),
        isConfigured = isInstalledByGooglePlay(context),
    )

    override suspend fun check(currentVersion: AboutAppVersion): AboutUpdateResult {
        if (!isConfigured) return AboutUpdateResult.Unavailable
        val info = try {
            appUpdateManagerProvider().requestAppUpdateInfo()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: InstallException) {
            return playUpdateErrorResult(error.errorCode)
        } catch (_: Exception) {
            return AboutUpdateResult.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE)
        }
        return playUpdateResult(
            availability = info.updateAvailability(),
            availableVersionCode = info.availableVersionCode().toLong(),
            currentVersionCode = currentVersion.code,
        )
    }

    /**
     * Launches Play's full-screen (immediate) update over [activity]. Returns false when Play
     * cannot run it, so the caller can open the store listing instead. Play accepts each
     * [AppUpdateInfo] only once, so a fresh one is requested for every attempt.
     */
    suspend fun startUpdate(activity: Activity): Boolean {
        val info = freshUpdateInfo() ?: return false
        val isUpdatable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        if (!isUpdatable || !info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) return false
        return launchImmediateUpdate(info, activity)
    }

    /**
     * Play requires apps to resume an immediate update the user already started when they return
     * to the app before it finished installing.
     */
    suspend fun resumeInterruptedUpdate(activity: Activity) {
        val info = freshUpdateInfo() ?: return
        if (info.updateAvailability() != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) return
        launchImmediateUpdate(info, activity)
    }

    private suspend fun freshUpdateInfo(): AppUpdateInfo? {
        if (!isConfigured) return null
        return try {
            appUpdateManagerProvider().requestAppUpdateInfo()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Play hosts the result itself, so no activity-result plumbing is needed in the caller. */
    private fun launchImmediateUpdate(info: AppUpdateInfo, activity: Activity): Boolean = runCatching {
        appUpdateManagerProvider().startUpdateFlow(
            info,
            activity,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
        )
    }.isSuccess
}

/** Pure mapping from Play's update availability to the About row. */
internal fun playUpdateResult(
    availability: Int,
    availableVersionCode: Long,
    currentVersionCode: Long,
): AboutUpdateResult = when (availability) {
    UpdateAvailability.UPDATE_AVAILABLE,
    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
    -> if (availableVersionCode > 0 && availableVersionCode <= currentVersionCode) {
        // Play can briefly report a build this device already runs; never badge for it.
        AboutUpdateResult.Current
    } else {
        // Play exposes only a build number, so the row uses generic "newer version" copy.
        AboutUpdateResult.Available(
            latestVersion = "",
            destinationUri = AboutDestination.PLAY_STORE_LISTING.uri,
        )
    }
    UpdateAvailability.UPDATE_NOT_AVAILABLE -> AboutUpdateResult.Current
    else -> AboutUpdateResult.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE)
}

/** Install errors that mean Play will never serve this install degrade to "unavailable". */
internal fun playUpdateErrorResult(errorCode: Int): AboutUpdateResult = when (errorCode) {
    InstallErrorCode.ERROR_APP_NOT_OWNED,
    InstallErrorCode.ERROR_API_NOT_AVAILABLE,
    InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND,
    InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED,
    InstallErrorCode.ERROR_INSTALL_UNAVAILABLE,
    InstallErrorCode.ERROR_INVALID_REQUEST,
    -> AboutUpdateResult.Unavailable
    else -> AboutUpdateResult.Failed(AboutScreenController.UPDATE_CHECK_FAILED_MESSAGE)
}

internal val GooglePlayInstallerPackages = setOf(
    "com.android.vending",
    "com.google.android.feedback",
)

/** True only when Google Play installed this package; any lookup failure counts as sideloaded. */
internal fun isInstalledByGooglePlay(context: Context): Boolean = runCatching {
    val packageManager: PackageManager = context.packageManager
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(context.packageName)
    }
    installer in GooglePlayInstallerPackages
}.getOrDefault(false)

/** Defers Play service binding until the first check actually runs. */
private fun lazyAppUpdateManager(context: Context): () -> AppUpdateManager {
    val manager = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppUpdateManagerFactory.create(context) }
    return { manager.value }
}
