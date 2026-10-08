package com.apoorvdarshan.verceltics.ui.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FirstConnectionFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcomeShowsPromisePrivacyAndTheIntegrationMarquee() {
        compose.setContent {
            VercelticsTheme { FirstConnectionWelcomeScreen(onContinue = {}) }
        }

        compose.onNodeWithText("Check your whole stack. Close the laptop.").assertIsDisplayed()
        compose.onNodeWithText("OPEN SOURCE").assertIsDisplayed()
        compose.onNodeWithText(
            "Credentials are encrypted on this device with Android Keystore. Requests go directly to provider APIs.",
        ).assertExists()
        compose.onNodeWithText("No Verceltics account required.").assertIsDisplayed()
        compose.onNodeWithText("Choose what to connect").assertIsDisplayed()
        compose.onNodeWithTag("firstLaunch.integrationCount").performScrollTo()
        compose.onNodeWithText("27 INTEGRATIONS").assertIsDisplayed()
        compose.onNodeWithTag("firstLaunch.lane.hosting")
            .assert(laneDescriptionContains("Hosting, 10 integrations: Vercel, Cloudflare"))
        compose.onNodeWithTag("firstLaunch.lane.domains")
            .assert(laneDescriptionContains("Domains, 8 integrations:"))
        compose.onNodeWithTag("firstLaunch.lane.sites")
            .assert(laneDescriptionContains("Site services, 9 integrations:"))
    }

    @Test
    fun accessibilityTextSizeUsesCompactCopyAndKeepsTheAccountNote() {
        compose.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    FirstConnectionWelcomeScreen(onContinue = {})
                }
            }
        }

        compose.onNodeWithText("Connect services").assertIsDisplayed()
        compose.onNodeWithText("No Verceltics account required.").assertExists()
    }

    @Test
    fun continueCompletesTheWelcomeAndOpensTheFullScreenConnectFlow() {
        val store = FirstLaunchExperienceStore(MemoryPreferences())
        val selected = mutableListOf<IntegrationProvider>()
        var previewedSampleData = false
        compose.setContent {
            VercelticsTheme {
                FirstConnectionFlow(
                    presentation = if (store.hasCompletedWelcome) {
                        FirstLaunchPresentation.CONNECT
                    } else {
                        FirstLaunchPresentation.WELCOME
                    },
                    onContinue = store::completeWelcome,
                    onConnectProvider = { selected += it },
                    onPreviewSampleData = { previewedSampleData = true },
                )
            }
        }

        compose.onNodeWithTag("firstLaunch.welcome").assertIsDisplayed()
        compose.onNodeWithTag("firstLaunch.continue").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "firstLaunch.connect"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertTrue(store.hasCompletedWelcome)
        compose.onNodeWithText("Connect your first service").assertIsDisplayed()
        compose.onNodeWithTag("connection.category.hosting").assertIsSelected()

        compose.onNodeWithTag("connection.category.registrars").performClick()
        compose.onNodeWithTag("connection.catalog.registrars").assertIsDisplayed()
        compose.onNodeWithTag("connection.category.sites").performClick()
        compose.onNodeWithTag("connection.catalog.sites").assertIsDisplayed()
        compose.onNodeWithTag("connection.category.hosting").performClick()

        compose.onNodeWithTag("provider.vercel").performClick()
        assertEquals(listOf("vercel"), selected.map(IntegrationProvider::id))

        assertFalse(previewedSampleData)
        compose.onNodeWithTag("firstLaunch.sampleData").performClick()
        assertTrue(previewedSampleData)
    }

    @Test
    fun loadingScreenAnnouncesTheWorkspaceIsLoading() {
        compose.setContent {
            VercelticsTheme {
                FirstConnectionFlow(
                    presentation = FirstLaunchPresentation.LOADING,
                    onContinue = {},
                    onConnectProvider = {},
                )
            }
        }

        compose.onNodeWithTag("firstLaunch.loading")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Loading workspace")))
    }

    private fun laneDescriptionContains(text: String) = SemanticsMatcher("content description contains '$text'") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it.contains(text) }
    }

    private class MemoryPreferences : FirstLaunchPreferences {
        private var completed = false

        override fun isWelcomeCompleted(): Boolean = completed

        override fun markWelcomeCompleted() {
            completed = true
        }
    }
}
