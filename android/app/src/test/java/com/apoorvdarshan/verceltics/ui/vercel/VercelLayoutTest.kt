package com.apoorvdarshan.verceltics.ui.vercel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelLayoutTest {
    @Test
    fun widthClassesUseTheMaterialBreakpoints() {
        assertEquals(VercelWidthClass.COMPACT, vercelWidthClass(411f))
        assertEquals(VercelWidthClass.COMPACT, vercelWidthClass(599.9f))
        assertEquals(VercelWidthClass.MEDIUM, vercelWidthClass(600f))
        assertEquals(VercelWidthClass.MEDIUM, vercelWidthClass(839f))
        assertEquals(VercelWidthClass.EXPANDED, vercelWidthClass(840f))
    }

    @Test
    fun phonesKeepTheirPaddingAndOneColumn() {
        val phone = VercelLayout.page(availableWidthDp = 411f, windowWidthDp = 411f, compactPaddingDp = 18f, maxContentWidthDp = 1_180f)

        assertFalse(phone.isRegular)
        assertEquals(18f, phone.horizontalPaddingDp)
        assertEquals(375f, phone.contentWidthDp)
        assertEquals(1, VercelLayout.projectColumns(phone))
        assertEquals(1, VercelLayout.analyticsPanelColumns(phone))
        assertFalse(VercelLayout.deploymentUsesTwoColumns(phone))
    }

    @Test
    fun theWindowDecidesTheClassEvenWhenARailNarrowsTheScreen() {
        val beside = VercelLayout.page(availableWidthDp = 520f, windowWidthDp = 600f, compactPaddingDp = 18f, maxContentWidthDp = 1_180f)

        assertTrue(beside.isRegular)
        assertEquals("Regular widths use iOS's 24dp page padding.", 24f, beside.horizontalPaddingDp)
    }

    @Test
    fun wideWindowsCenterContentAtItsMaximumWidth() {
        val wide = VercelLayout.page(availableWidthDp = 1_600f, windowWidthDp = 1_680f, compactPaddingDp = 18f, maxContentWidthDp = 1_180f)

        assertEquals(VercelWidthClass.EXPANDED, wide.widthClass)
        assertEquals(210f, wide.horizontalPaddingDp)
        assertEquals(1_180f, wide.contentWidthDp)
    }

    @Test
    fun projectGridMatchesIosAdaptiveCards() {
        fun columns(width: Float) = VercelLayout.projectColumns(
            VercelLayout.page(availableWidthDp = width, windowWidthDp = width, compactPaddingDp = 18f, maxContentWidthDp = 1_180f),
        )

        assertEquals("A narrow medium window still fits one 340dp card.", 1, columns(640f))
        assertEquals(2, columns(760f))
        assertEquals(2, columns(1_000f))
        assertEquals(3, columns(1_280f))
        assertEquals("Never more than three columns.", 3, columns(2_400f))
    }

    @Test
    fun analyticsPanelsSitSideBySideOnRegularWidths() {
        fun columns(width: Float) = VercelLayout.analyticsPanelColumns(
            VercelLayout.page(availableWidthDp = width, windowWidthDp = width, compactPaddingDp = 16f, maxContentWidthDp = 1_100f),
        )

        assertEquals(1, columns(400f))
        assertEquals(2, columns(700f))
        assertEquals(2, columns(900f))
        assertEquals(3, columns(1_280f))
    }

    @Test
    fun deploymentDetailUsesTwoColumnsOnlyWhenBothFit() {
        fun twoColumns(available: Float, window: Float = available) = VercelLayout.deploymentUsesTwoColumns(
            VercelLayout.page(availableWidthDp = available, windowWidthDp = window, compactPaddingDp = 18f, maxContentWidthDp = 920f),
        )

        assertFalse(twoColumns(411f))
        assertFalse("300 + 16 + 420 does not fit in a 600dp window.", twoColumns(600f))
        assertTrue(twoColumns(800f))
        assertTrue(twoColumns(1_280f))
    }
}
