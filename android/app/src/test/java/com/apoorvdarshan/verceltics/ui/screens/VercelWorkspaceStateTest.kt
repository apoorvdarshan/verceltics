package com.apoorvdarshan.verceltics.ui.screens

import com.apoorvdarshan.verceltics.ui.VercelConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelWorkspaceStateTest {
    @Test
    fun standardPhoneWidthKeepsDenseVercelRowsSideBySide() {
        assertFalse(shouldUseStackedVercelLayout(availableWidthDp = 390f, fontScale = 1f))
        assertFalse(shouldUseStackedVercelLayout(availableWidthDp = 280f, fontScale = 1.29f))
    }

    @Test
    fun narrowWidthOrLargeTextStacksVercelRows() {
        assertTrue(shouldUseStackedVercelLayout(availableWidthDp = 279f, fontScale = 1f))
        assertTrue(shouldUseStackedVercelLayout(availableWidthDp = 390f, fontScale = 1.30f))
    }

    @Test
    fun coldRestoreKeepsSavedProjectRouteUntilDashboardIsKnown() {
        assertFalse(
            shouldClearSavedProjectSelection(
                status = VercelConnectionStatus.RESTORING,
                projectStillExists = null,
            ),
        )
        assertFalse(
            shouldClearSavedProjectSelection(
                status = VercelConnectionStatus.CONNECTED,
                projectStillExists = true,
            ),
        )
        assertTrue(
            shouldClearSavedProjectSelection(
                status = VercelConnectionStatus.CONNECTED,
                projectStillExists = false,
            ),
        )
    }
}
