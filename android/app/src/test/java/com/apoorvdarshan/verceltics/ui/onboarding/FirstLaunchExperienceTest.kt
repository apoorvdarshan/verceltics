package com.apoorvdarshan.verceltics.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchExperienceTest {
    @Test
    fun `new user without connections sees the welcome until it is completed`() {
        val preferences = MemoryPreferences()
        val store = FirstLaunchExperienceStore(preferences)

        assertTrue(store.shouldPresentWelcome(hasAnyConnection = false, hasActiveSubscription = false))

        store.completeWelcome()

        assertFalse(store.shouldPresentWelcome(hasAnyConnection = false, hasActiveSubscription = false))
        assertTrue(preferences.completed)
        assertEquals(1, preferences.writes)
    }

    @Test
    fun `completion is persisted and restored by a new store`() {
        val preferences = MemoryPreferences()
        FirstLaunchExperienceStore(preferences).completeWelcome()

        val restored = FirstLaunchExperienceStore(preferences)

        assertTrue(restored.hasCompletedWelcome)
        assertFalse(restored.shouldPresentWelcome(hasAnyConnection = false, hasActiveSubscription = false))
    }

    @Test
    fun `completing twice writes the flag once`() {
        val preferences = MemoryPreferences()
        val store = FirstLaunchExperienceStore(preferences)

        store.completeWelcome()
        store.completeWelcome()

        assertEquals(1, preferences.writes)
    }

    @Test
    fun `existing connected users are migrated as completed`() {
        val preferences = MemoryPreferences()
        val store = FirstLaunchExperienceStore(preferences)

        store.migrateIfNeeded(hasAnyConnection = true, hasActiveSubscription = false)

        assertTrue(store.hasCompletedWelcome)
        assertTrue(preferences.completed)
    }

    @Test
    fun `subscribers and sample data users are migrated as completed`() {
        val subscriber = FirstLaunchExperienceStore(MemoryPreferences())
        subscriber.migrateIfNeeded(hasAnyConnection = false, hasActiveSubscription = true)
        assertTrue(subscriber.hasCompletedWelcome)

        val sampleDataUser = FirstLaunchExperienceStore(MemoryPreferences())
        sampleDataUser.migrateIfNeeded(
            hasAnyConnection = false,
            hasActiveSubscription = false,
            isSampleData = true,
        )
        assertTrue(sampleDataUser.hasCompletedWelcome)
    }

    @Test
    fun `migration without any signal leaves a new user on the welcome`() {
        val preferences = MemoryPreferences()
        val store = FirstLaunchExperienceStore(preferences)

        store.migrateIfNeeded(hasAnyConnection = false, hasActiveSubscription = false)

        assertFalse(store.hasCompletedWelcome)
        assertEquals(0, preferences.writes)
    }

    @Test
    fun `unreadable or unwritable preferences never crash the gate`() {
        val store = FirstLaunchExperienceStore(FailingPreferences)

        assertFalse(store.hasCompletedWelcome)
        store.completeWelcome()
        assertTrue(store.hasCompletedWelcome)
    }

    @Test
    fun `welcome rule matches iOS for every combination`() {
        for (completed in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                for (subscribed in listOf(false, true)) {
                    assertEquals(
                        !completed && !connected && !subscribed,
                        FirstLaunchExperienceStore.shouldPresentWelcome(completed, connected, subscribed),
                    )
                }
            }
        }
    }

    @Test
    fun `shell is shown when the gate is disabled, in sample data, or with any connection`() {
        assertEquals(FirstLaunchPresentation.SHELL, presentation(isEnabled = false))
        assertEquals(FirstLaunchPresentation.SHELL, presentation(isSampleData = true))
        assertEquals(FirstLaunchPresentation.SHELL, presentation(hasAnyConnection = true))
        assertEquals(
            FirstLaunchPresentation.SHELL,
            presentation(hasAnyConnection = true, isRestoringConnections = true),
        )
    }

    @Test
    fun `restoring connections shows loading instead of flashing the welcome`() {
        assertEquals(FirstLaunchPresentation.LOADING, presentation(isRestoringConnections = true))
        assertEquals(
            FirstLaunchPresentation.LOADING,
            presentation(isRestoringConnections = true, hasCompletedWelcome = true),
        )
    }

    @Test
    fun `unconnected users see the welcome once, then the connect flow`() {
        assertEquals(FirstLaunchPresentation.WELCOME, presentation())
        assertEquals(FirstLaunchPresentation.CONNECT, presentation(hasCompletedWelcome = true))
    }

    @Test
    fun `subscribers without connections skip the welcome but still get the connect flow`() {
        assertEquals(FirstLaunchPresentation.CONNECT, presentation(hasActiveSubscription = true))
    }

    private fun presentation(
        isEnabled: Boolean = true,
        isSampleData: Boolean = false,
        hasAnyConnection: Boolean = false,
        isRestoringConnections: Boolean = false,
        hasCompletedWelcome: Boolean = false,
        hasActiveSubscription: Boolean = false,
    ) = firstLaunchPresentation(
        isEnabled = isEnabled,
        isSampleData = isSampleData,
        hasAnyConnection = hasAnyConnection,
        isRestoringConnections = isRestoringConnections,
        hasCompletedWelcome = hasCompletedWelcome,
        hasActiveSubscription = hasActiveSubscription,
    )

    private class MemoryPreferences : FirstLaunchPreferences {
        var completed = false
        var writes = 0

        override fun isWelcomeCompleted(): Boolean = completed

        override fun markWelcomeCompleted() {
            completed = true
            writes += 1
        }
    }

    private object FailingPreferences : FirstLaunchPreferences {
        override fun isWelcomeCompleted(): Boolean = error("disk unavailable")

        override fun markWelcomeCompleted() {
            error("disk unavailable")
        }
    }
}
