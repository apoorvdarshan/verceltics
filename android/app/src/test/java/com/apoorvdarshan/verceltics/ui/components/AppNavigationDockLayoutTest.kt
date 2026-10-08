package com.apoorvdarshan.verceltics.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationDockLayoutTest {
    @Test
    fun `wide screens use full labels at standard font scale`() {
        assertEquals(
            NavigationLabelLayout.FULL,
            navigationLabelLayout(availableWidthDp = 400f, fontScale = 1f),
        )
        assertEquals(
            NavigationLabelLayout.FULL,
            navigationLabelLayout(availableWidthDp = 366f, fontScale = 1f),
        )
    }

    @Test
    fun `compact labels are selected for constrained width or moderate font scale`() {
        assertEquals(
            NavigationLabelLayout.COMPACT,
            navigationLabelLayout(availableWidthDp = 359f, fontScale = 1f),
        )
        assertEquals(
            NavigationLabelLayout.COMPACT,
            navigationLabelLayout(availableWidthDp = 400f, fontScale = 1.15f),
        )
    }

    @Test
    fun `icon only labels preserve touch targets at accessibility scale or very narrow width`() {
        assertEquals(
            NavigationLabelLayout.ICON_ONLY,
            navigationLabelLayout(availableWidthDp = 303f, fontScale = 1f),
        )
        assertEquals(
            NavigationLabelLayout.ICON_ONLY,
            navigationLabelLayout(availableWidthDp = 400f, fontScale = 1.30f),
        )
        assertEquals(
            NavigationLabelLayout.ICON_ONLY,
            navigationLabelLayout(availableWidthDp = 400f, fontScale = 2f),
        )
    }

    @Test
    fun `narrow split windows stack search instead of shrinking primary targets`() {
        assertEquals(
            NavigationDockArrangement.STACKED_SEARCH,
            navigationDockArrangement(availableWidthDp = 276f),
        )
        assertTrue(stackedPrimaryTargetWidthDp(availableWidthDp = 276f) >= 48f)
    }

    @Test
    fun `inline dock begins only when four primary targets can remain 48dp`() {
        assertEquals(
            NavigationDockArrangement.STACKED_SEARCH,
            navigationDockArrangement(availableWidthDp = 285.99f),
        )
        assertEquals(
            NavigationDockArrangement.INLINE,
            navigationDockArrangement(availableWidthDp = 286f),
        )
    }

    @Test
    fun `windows at least 600dp wide use a navigation rail instead of the dock`() {
        assertFalse(usesNavigationRail(windowWidthDp = 411f))
        assertFalse(usesNavigationRail(windowWidthDp = 599.9f))
        assertTrue(usesNavigationRail(windowWidthDp = 600f))
        assertTrue(usesNavigationRail(windowWidthDp = 1280f))
    }
}
