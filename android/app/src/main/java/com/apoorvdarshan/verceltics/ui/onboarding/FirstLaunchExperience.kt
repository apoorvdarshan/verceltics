package com.apoorvdarshan.verceltics.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persistence seam for the one-time welcome, so gating and migration are unit testable. */
interface FirstLaunchPreferences {
    fun isWelcomeCompleted(): Boolean
    fun markWelcomeCompleted()
}

class SharedPreferencesFirstLaunchPreferences(context: Context) : FirstLaunchPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isWelcomeCompleted(): Boolean =
        preferences.getBoolean(FirstLaunchExperienceStore.COMPLETION_KEY, false)

    override fun markWelcomeCompleted() {
        preferences.edit().putBoolean(FirstLaunchExperienceStore.COMPLETION_KEY, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "verceltics.onboarding"
    }
}

/**
 * Owns only the one-time welcome presentation, like iOS `FirstLaunchExperienceStore`. Pro access is
 * still decided exclusively by the billing layer.
 */
@Stable
class FirstLaunchExperienceStore(
    private val preferences: FirstLaunchPreferences,
) {
    var hasCompletedWelcome: Boolean by mutableStateOf(
        runCatching(preferences::isWelcomeCompleted).getOrDefault(false),
    )
        private set

    fun shouldPresentWelcome(
        hasAnyConnection: Boolean,
        hasActiveSubscription: Boolean,
    ): Boolean = shouldPresentWelcome(
        hasCompletedWelcome = hasCompletedWelcome,
        hasAnyConnection = hasAnyConnection,
        hasActiveSubscription = hasActiveSubscription,
    )

    /**
     * Existing connected, subscribed, or sample-data users must never be routed through a newly
     * introduced first-launch screen after updating the app.
     */
    fun migrateIfNeeded(
        hasAnyConnection: Boolean,
        hasActiveSubscription: Boolean,
        isSampleData: Boolean = false,
    ) {
        if (hasAnyConnection || hasActiveSubscription || isSampleData) completeWelcome()
    }

    fun completeWelcome() {
        if (hasCompletedWelcome) return
        runCatching(preferences::markWelcomeCompleted)
        hasCompletedWelcome = true
    }

    companion object {
        /** Same key as iOS so the meaning of the flag is shared across platforms. */
        const val COMPLETION_KEY = "onboarding.firstLaunchWelcome.completed.v1"

        fun shouldPresentWelcome(
            hasCompletedWelcome: Boolean,
            hasAnyConnection: Boolean,
            hasActiveSubscription: Boolean,
        ): Boolean = !hasCompletedWelcome && !hasAnyConnection && !hasActiveSubscription
    }
}

/** What the app root shows instead of, or as, the tab shell. */
enum class FirstLaunchPresentation {
    /** The normal tab shell. */
    SHELL,

    /** Saved connections are still being read; avoid flashing the welcome to connected users. */
    LOADING,

    /** The one-time welcome with the integration marquee. */
    WELCOME,

    /** The full-screen connect flow shown until the first connection exists. */
    CONNECT,
}

/**
 * Pure root gate matching iOS `VercelticsApp.appContent`: with no connection the tabs are hidden
 * and the welcome (once) or the connect flow is shown. Sample data is a full preview of the shell.
 */
fun firstLaunchPresentation(
    isEnabled: Boolean,
    isSampleData: Boolean,
    hasAnyConnection: Boolean,
    isRestoringConnections: Boolean,
    hasCompletedWelcome: Boolean,
    hasActiveSubscription: Boolean,
): FirstLaunchPresentation = when {
    !isEnabled || isSampleData || hasAnyConnection -> FirstLaunchPresentation.SHELL
    isRestoringConnections -> FirstLaunchPresentation.LOADING
    FirstLaunchExperienceStore.shouldPresentWelcome(
        hasCompletedWelcome = hasCompletedWelcome,
        hasAnyConnection = false,
        hasActiveSubscription = hasActiveSubscription,
    ) -> FirstLaunchPresentation.WELCOME
    else -> FirstLaunchPresentation.CONNECT
}
