package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.screens.about.AboutDestination
import com.apoorvdarshan.verceltics.ui.screens.about.AboutAppearance
import com.apoorvdarshan.verceltics.ui.screens.about.AboutAppVersion
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenAction
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenController
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenState
import com.apoorvdarshan.verceltics.ui.screens.about.AboutUpdateState
import com.apoorvdarshan.verceltics.ui.screens.about.AppearancePreferenceStore
import com.apoorvdarshan.verceltics.ui.screens.about.UnconfiguredAboutUpdateChecker
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun versionAppearanceAndUpdateStateAreTruthfulAndActionable() {
        val actions = mutableListOf<AboutScreenAction>()
        compose.setContent {
            VercelticsTheme {
                AboutScreen(
                    state = defaultState(),
                    onAction = actions::add,
                )
            }
        }

        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("Version 3.0 · Update checks unavailable").assertIsDisplayed()
        compose.onNodeWithText("Built for operators").assertDoesNotExist()
        compose.onNodeWithText("Android native").assertDoesNotExist()
        compose.onNodeWithTag("about.appearance.system").assertIsSelected()
        compose.onNodeWithTag("about.appearance.dark").performClick()
        assertEquals(
            AboutScreenAction.SelectAppearance(AboutAppearance.DARK),
            actions.single(),
        )
        compose.onNodeWithTag("about.update.notConfigured")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun availableUpdateAsksTheHostToInstallWithItsTrustedFallbackUri() {
        val actions = mutableListOf<AboutScreenAction>()
        val destination = "https://play.google.com/store/apps/details?id=com.apoorvdarshan.verceltics"
        compose.setContent {
            VercelticsTheme {
                AboutScreen(
                    state = defaultState(
                        update = AboutUpdateState.Available("3.1", destination),
                    ),
                    onAction = actions::add,
                )
            }
        }

        compose.onNodeWithText("Version 3.1 is ready").assertIsDisplayed()
        compose.onNodeWithTag("about.update.available")
            .performScrollTo()
            .performClick()

        assertEquals(AboutScreenAction.InstallUpdate(destination), actions.single())
    }

    @Test
    fun playUpdateWithoutAVersionNameUsesGenericCopy() {
        compose.setContent {
            VercelticsTheme {
                AboutScreen(
                    state = defaultState(
                        update = AboutUpdateState.Available("", AboutDestination.PLAY_STORE_LISTING.uri),
                    ),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText("A newer version is ready · Tap to update").assertIsDisplayed()
        compose.onNodeWithText("Version  is ready").assertDoesNotExist()
    }

    @Test
    fun currentAndFailedUpdateRowsStayActionable() {
        val actions = mutableListOf<AboutScreenAction>()
        var update by mutableStateOf<AboutUpdateState>(AboutUpdateState.Current("3.0"))
        compose.setContent {
            VercelticsTheme {
                AboutScreen(state = defaultState(update = update), onAction = actions::add)
            }
        }

        compose.onNodeWithText("Version 3.0 is current").assertIsDisplayed()
        compose.onNodeWithTag("about.update.current").performClick()

        update = AboutUpdateState.Failed("Unable to check right now")
        compose.onNodeWithText("Unable to check right now · Tap to retry").assertIsDisplayed()
        compose.onNodeWithTag("about.update.failed").performClick()

        update = AboutUpdateState.Checking
        compose.onNodeWithTag("about.update.checking").assertIsDisplayed()
        assertEquals(
            listOf(AboutScreenAction.CheckForUpdates, AboutScreenAction.CheckForUpdates),
            actions,
        )
    }

    @Test
    fun rateRowRequestsAnInAppReview() {
        val actions = mutableListOf<AboutScreenAction>()
        compose.setContent {
            VercelticsTheme {
                AboutScreen(state = defaultState(), onAction = actions::add)
            }
        }

        compose.onNodeWithTag("about.action.rate")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(AboutScreenAction.RateApp, actions.single())
    }

    @Test
    fun aboutContentIsCappedOnWideWindows() {
        compose.setContent {
            VercelticsTheme {
                Box(Modifier.requiredWidth(1200.dp)) {
                    AboutScreen(state = defaultState(), onAction = {})
                }
            }
        }

        val sectionWidth = compose.onNodeWithTag("about.section.app")
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        val maxWidthPx = with(compose.density) { AboutContentMaxWidth.toPx() }
        assertTrue("About section is $sectionWidth px wide", sectionWidth <= maxWidthPx + 1f)
    }

    @Test
    fun accessibilityFontScaleUsesTheVerticalAppearancePicker() {
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 2f)) {
                VercelticsTheme {
                    AboutScreen(
                        state = defaultState(),
                        onAction = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("about.appearance.system")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("about.appearance.light").assertIsDisplayed()
        compose.onNodeWithTag("about.appearance.dark").assertIsDisplayed()
    }

    @Test
    fun rootControllerSelectionRecomposesTheAppThemeImmediately() {
        var observedBackground = 0
        compose.setContent {
            val controller = remember {
                AboutScreenController(
                    appearanceStore = MemoryAppearanceStore(AboutAppearance.LIGHT),
                    updateChecker = UnconfiguredAboutUpdateChecker,
                    version = AboutAppVersion(name = "3.0", code = 42),
                )
            }
            val state = controller.state
            VercelticsTheme(appearance = state.appearance) {
                val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    observedBackground = backgroundColor
                }
                AboutScreen(
                    state = state,
                    onAction = { action ->
                        if (action is AboutScreenAction.SelectAppearance) {
                            controller.selectAppearance(action.appearance)
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        val lightBackground = observedBackground

        compose.onNodeWithTag("about.appearance.dark").performClick()
        compose.waitForIdle()

        assertNotEquals(lightBackground, observedBackground)
        compose.onNodeWithTag("about.appearance.dark").assertIsSelected()
    }

    private fun defaultState(
        update: AboutUpdateState = AboutUpdateState.NotConfigured,
    ) = AboutScreenState(
        version = AboutAppVersion(name = "3.0", code = 42),
        appearance = AboutAppearance.SYSTEM,
        update = update,
    )

    private class MemoryAppearanceStore(initial: AboutAppearance) : AppearancePreferenceStore {
        private var appearance = initial

        override fun load(): AboutAppearance = appearance

        override fun save(appearance: AboutAppearance) {
            this.appearance = appearance
        }
    }
}
