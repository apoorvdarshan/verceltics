package com.apoorvdarshan.verceltics.ui.screens.about

import android.app.Activity
import android.content.Context
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Persists whether the one-time automatic rating prompt has already been attempted. */
interface ReviewPromptStore {
    val hasPrompted: Boolean
    fun markPrompted()
}

class SharedPreferencesReviewPromptStore(context: Context) : ReviewPromptStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override val hasPrompted: Boolean
        get() = preferences.getBoolean(KEY_HAS_PROMPTED, false)

    override fun markPrompted() {
        preferences.edit().putBoolean(KEY_HAS_PROMPTED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "verceltics.preferences"

        // Same key name as the iOS @AppStorage flag.
        const val KEY_HAS_PROMPTED = "hasShownOnboardingRatePrompt"
    }
}

/** Launches the store's review sheet. Returns false when the flow could not be started. */
fun interface InAppReviewLauncher {
    suspend fun launch(activity: Activity): Boolean
}

/**
 * Google Play In-App Review. Play decides whether the sheet actually appears (it enforces its own
 * quota); a request or launch failure, such as a sideloaded build without Play, returns false.
 */
class PlayInAppReviewLauncher(context: Context) : InAppReviewLauncher {
    private val applicationContext = context.applicationContext
    private val manager: ReviewManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReviewManagerFactory.create(applicationContext)
    }

    override suspend fun launch(activity: Activity): Boolean = try {
        val reviewInfo = manager.requestReview()
        manager.launchReview(activity, reviewInfo)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
}

/**
 * Owns rating requests.
 *
 * [onProjectsFirstLoaded] mirrors iOS `ProjectsView.maybeRequestReview()`: once per install, about
 * three seconds after the user's Vercel projects first load, the native review sheet is requested.
 * [requestReviewNow] serves the About "Rate" row and falls back to the store listing when the
 * in-app flow is unavailable.
 */
class ReviewPrompter(
    private val store: ReviewPromptStore,
    private val launcher: InAppReviewLauncher,
    private val scope: CoroutineScope,
    private val promptDelayMillis: Long = AUTOMATIC_PROMPT_DELAY_MILLIS,
) {
    private var pendingPrompt: Job? = null

    /** Schedules the one-time automatic prompt. Safe to call on every successful projects load. */
    fun onProjectsFirstLoaded(activity: Activity) {
        val host = WeakReference(activity)
        scheduleAutomaticPrompt(
            resolveHost = { host.get()?.takeUnless { it.isFinishing || it.isDestroyed } },
            showPrompt = { launcher.launch(it) },
        )
    }

    /** Explicit user request from About. [onUnavailable] opens the store listing instead. */
    fun requestReviewNow(activity: Activity, onUnavailable: () -> Unit) {
        scope.launch {
            if (!launcher.launch(activity)) onUnavailable()
        }
    }

    /**
     * Testable core of the automatic prompt. Returns true when a prompt was scheduled. The flag is
     * persisted only once the prompt is actually attempted, so leaving the screen during the delay
     * lets a later launch try again, exactly like the cancelled iOS task.
     */
    internal fun <Host : Any> scheduleAutomaticPrompt(
        resolveHost: () -> Host?,
        showPrompt: suspend (Host) -> Unit,
    ): Boolean {
        if (store.hasPrompted || pendingPrompt?.isActive == true) return false
        pendingPrompt = scope.launch {
            delay(promptDelayMillis)
            if (store.hasPrompted) return@launch
            val host = resolveHost() ?: return@launch
            store.markPrompted()
            showPrompt(host)
        }
        return true
    }

    companion object {
        const val AUTOMATIC_PROMPT_DELAY_MILLIS: Long = 3_000L
    }
}
