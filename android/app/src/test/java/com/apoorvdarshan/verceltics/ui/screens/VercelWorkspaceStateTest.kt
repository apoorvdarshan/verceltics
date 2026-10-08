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

    @Test
    fun projectsFirstLoadedNeedsAConnectedNonEmptyErrorFreeList() {
        assertTrue(shouldNotifyProjectsFirstLoaded(VercelConnectionStatus.CONNECTED, projectCount = 3, error = null))
        assertFalse(shouldNotifyProjectsFirstLoaded(VercelConnectionStatus.CONNECTED, projectCount = 0, error = null))
        assertFalse(shouldNotifyProjectsFirstLoaded(VercelConnectionStatus.CONNECTED, projectCount = 3, error = "offline"))
        assertFalse(shouldNotifyProjectsFirstLoaded(VercelConnectionStatus.RESTORING, projectCount = 3, error = null))
        assertFalse(shouldNotifyProjectsFirstLoaded(VercelConnectionStatus.SAVED_UNAVAILABLE, projectCount = 3, error = null))
    }

    @Test
    fun analyticsBreakdownsKeepIosOrderAndLockWording() {
        val data = com.apoorvdarshan.verceltics.ui.VercelAnalyticsDataUi(
            overview = com.apoorvdarshan.verceltics.ui.VercelAnalyticsOverviewUi(1, 1, null),
            previousOverview = null,
            timeseries = emptyList(),
            pages = emptyList(),
            referrers = emptyList(),
            countries = emptyList(),
        )

        val locked = analyticsBreakdownSections(data, hasLongAnalyticsHistory = false)
        assertTrue(
            locked.map { it.title } == listOf(
                "Pages", "Routes", "Hostnames", "Referrers", "UTM Parameters", "Countries",
                "Devices", "Browsers", "Operating Systems", "Events", "Flags", "Query Parameters",
            ),
        )
        assertTrue(locked.single { it.title == "UTM Parameters" }.lockedTitle == "Requires Pro + Web Analytics Plus")
        assertTrue(locked.single { it.title == "Events" }.lockedTitle == "Requires Pro")
        assertTrue(locked.single { it.title == "Countries" }.isCountry)
        assertTrue(locked.single { it.title == "Referrers" }.emptyLabel == "Direct")
        assertTrue(
            analyticsBreakdownSections(data, hasLongAnalyticsHistory = true)
                .single { it.title == "UTM Parameters" }.lockedTitle == "Upgrade to Web Analytics Plus",
        )
    }
}
