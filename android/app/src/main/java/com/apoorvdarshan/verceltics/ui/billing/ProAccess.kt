package com.apoorvdarshan.verceltics.ui.billing

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf

/**
 * Soft-paywall access for screens. Lists, search, refresh and account management stay free;
 * opening an item's details or a provider tool goes through [requestPro].
 */
@Stable
class ProAccess(
    /** True when Pro screens may be shown now (active entitlement or sample-data preview). */
    val isUnlocked: Boolean,
    /**
     * True only once the entitlement check has confirmed the user is not Pro. Screens use it to
     * close details restored from saved state, so a lapsed subscription can't reopen them.
     */
    val isConfirmedLocked: Boolean,
    private val presentPaywall: (onUnlocked: () -> Unit) -> Unit,
) {
    /** Runs [onUnlocked] now for Pro users; otherwise shows the paywall and runs it after purchase. */
    fun requestPro(onUnlocked: () -> Unit) {
        if (isUnlocked) onUnlocked() else presentPaywall(onUnlocked)
    }

    companion object {
        /** Used by previews and tests that render screens without the app shell. */
        val Unlocked = ProAccess(isUnlocked = true, isConfirmedLocked = false) { it() }
    }
}

val LocalProAccess = compositionLocalOf { ProAccess.Unlocked }
