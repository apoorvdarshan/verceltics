package com.apoorvdarshan.verceltics.ui.onboarding

import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstConnectionWelcomeLayoutTest {
    @Test
    fun `marquee advertises the same 27 integrations as iOS across three lanes`() {
        assertEquals(27, WelcomeIntegrationCount)
        assertEquals(10, IntegrationCatalog.providers(Workspace.HOSTING).size)
        assertEquals(8, IntegrationCatalog.providers(Workspace.REGISTRARS).size)
        assertEquals(9, IntegrationCatalog.providers(Workspace.SITES).size)
    }

    @Test
    fun `side by side layout needs a wide window and a standard text size`() {
        assertFalse(usesRegularWelcomeLayout(availableWidthDp = 411f, isAccessibilitySize = false))
        assertFalse(usesRegularWelcomeLayout(availableWidthDp = 880f, isAccessibilitySize = false))
        assertTrue(usesRegularWelcomeLayout(availableWidthDp = 904f, isAccessibilitySize = false))
        assertFalse(usesRegularWelcomeLayout(availableWidthDp = 1280f, isAccessibilitySize = true))
    }

    @Test
    fun `lanes only auto scroll without reduced motion, screen readers, or large text`() {
        assertTrue(shouldAutoMoveMarquee(reduceMotion = false, screenReaderEnabled = false, fontScale = 1f))
        assertFalse(shouldAutoMoveMarquee(reduceMotion = true, screenReaderEnabled = false, fontScale = 1f))
        assertFalse(shouldAutoMoveMarquee(reduceMotion = false, screenReaderEnabled = true, fontScale = 1f))
        assertFalse(shouldAutoMoveMarquee(reduceMotion = false, screenReaderEnabled = false, fontScale = 1.15f))
    }
}
