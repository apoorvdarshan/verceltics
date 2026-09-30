package com.apoorvdarshan.verceltics.ui.pagespeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSpeedLayoutTest {
    @Test
    fun `normal phone content width keeps compact rows`() {
        assertFalse(shouldStackPageSpeedLayout(availableWidthDp = 357f, fontScale = 1f))
    }

    @Test
    fun `narrow content or accessibility text stacks metrics`() {
        assertTrue(shouldStackPageSpeedLayout(availableWidthDp = 279f, fontScale = 1f))
        assertTrue(shouldStackPageSpeedLayout(availableWidthDp = 357f, fontScale = 1.3f))
    }
}
