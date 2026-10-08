package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdaptiveNavigationScaffoldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactWindowsKeepTheBottomDock() {
        setShell(width = 400)

        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.onAllNodesWithTag("mainNavigation.rail").assertCountEquals(0)
        compose.onNodeWithTag("mainNavigation.content").assertIsDisplayed()
        compose.onNodeWithText("Workspace content").assertIsDisplayed()
    }

    @Test
    fun wideWindowsUseARailWithTabsAndAContextualSearchButton() {
        var selected by mutableStateOf(AppNavigationDestination.HOSTING)
        var searches = 0
        setShell(
            width = 840,
            selected = { selected },
            onSelected = { selected = it },
            onSearch = { searches += 1 },
        )

        compose.onNodeWithTag("mainNavigation.rail").assertIsDisplayed()
        compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)
        compose.onNodeWithTag("mainNavigation.content.rail").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.hosting")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()

        compose.onNodeWithTag("mainNavigation.registrars").performClick()
        compose.onNodeWithTag("mainNavigation.registrars").assertIsSelected()
        compose.onNodeWithTag("mainNavigation.hosting").assertIsNotSelected()

        compose.onNodeWithTag("mainNavigation.search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .performClick()
        assertEquals(1, searches)
        assertEquals(AppNavigationDestination.REGISTRARS, selected)
    }

    @Test
    fun aboutUpdateBadgeShowsInBothDockAndRail() {
        var width by mutableStateOf(400)
        compose.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp))) {
                    AdaptiveNavigationScaffold(
                        selectedDestination = AppNavigationDestination.HOSTING,
                        onDestinationSelected = {},
                        onSearch = {},
                        showsAboutBadge = true,
                    ) { Text("Workspace content") }
                }
            }
        }

        compose.onNodeWithTag("mainNavigation.about.badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("mainNavigation.about")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("About, update available")))

        width = 840
        compose.onNodeWithTag("mainNavigation.rail").assertExists()
        compose.onNodeWithTag("mainNavigation.about.badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noBadgeWithoutAnUpdate() {
        setShell(width = 400)

        compose.onAllNodesWithTag("mainNavigation.about.badge", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun hiddenNavigationShowsNeitherDockNorRail() {
        compose.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(840.dp, 900.dp))) {
                    AdaptiveNavigationScaffold(
                        selectedDestination = AppNavigationDestination.HOSTING,
                        onDestinationSelected = {},
                        onSearch = {},
                        showsNavigation = false,
                    ) { Text("Workspace content") }
                }
            }
        }

        compose.onAllNodesWithTag("mainNavigation.rail").assertCountEquals(0)
        compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)
        compose.onAllNodesWithTag("mainNavigation.search").assertCountEquals(0)
        compose.onNodeWithText("Workspace content").assertIsDisplayed()
    }

    private fun setShell(
        width: Int,
        selected: () -> AppNavigationDestination = { AppNavigationDestination.HOSTING },
        onSelected: (AppNavigationDestination) -> Unit = {},
        onSearch: () -> Unit = {},
    ) {
        compose.setContent {
            VercelticsTheme {
                ForcedWidth(width) {
                    AdaptiveNavigationScaffold(
                        selectedDestination = selected(),
                        onDestinationSelected = onSelected,
                        onSearch = onSearch,
                    ) { Text("Workspace content") }
                }
            }
        }
    }

    @Composable
    private fun ForcedWidth(width: Int, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp)),
            content = content,
        )
    }
}
