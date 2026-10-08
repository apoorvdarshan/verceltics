package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceScreenHubTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun registrarRestoreFailureShowsTheSavedAccountsBanner() {
        compose.setContent {
            VercelticsTheme {
                WorkspaceScreen(
                    workspace = Workspace.REGISTRARS,
                    onConnectProvider = {},
                    onAccountAction = {},
                    persistenceError = "Saved registrar credentials could not be read.",
                )
            }
        }

        compose.onNodeWithTag("workspace.registrars.persistenceError").assertIsDisplayed()
        compose.onNodeWithText("Saved registrar accounts need attention").assertIsDisplayed()
        compose.onNodeWithText("Saved registrar credentials could not be read.").assertIsDisplayed()
        compose.onNodeWithTag("workspace.registrars.empty").assertIsDisplayed()
    }

    @Test
    fun siteServicesRestoreFailureShowsTheSavedServicesBannerAboveConnectedCards() {
        compose.setContent {
            VercelticsTheme {
                WorkspaceScreen(
                    workspace = Workspace.SITES,
                    onConnectProvider = {},
                    onAccountAction = {},
                    persistenceError = "Saved site services could not be read.",
                    connectedContent = { Text("PageSpeed card") },
                )
            }
        }

        compose.onNodeWithTag("workspace.sites.persistenceError").assertIsDisplayed()
        compose.onNodeWithText("Saved site services need attention").assertIsDisplayed()
        compose.onNodeWithText("PageSpeed card").assertIsDisplayed()
    }

    @Test
    fun noBannerWithoutARestoreFailure() {
        compose.setContent {
            VercelticsTheme {
                WorkspaceScreen(workspace = Workspace.SITES, onConnectProvider = {}, onAccountAction = {})
            }
        }

        compose.onAllNodesWithTag("workspace.sites.persistenceError").assertCountEquals(0)
    }

    @Test
    fun searchFocusesTheHubSearchInsteadOfOpeningTheCatalog() {
        var searchRequestId by mutableIntStateOf(0)
        var query by mutableStateOf("")
        compose.setContent {
            VercelticsTheme {
                WorkspaceScreen(
                    workspace = Workspace.SITES,
                    onConnectProvider = {},
                    onAccountAction = {},
                    connectedContent = { if (query.isBlank()) Text("Two cards") },
                    searchRequestId = searchRequestId,
                    hubSearchQuery = query,
                    onHubSearchQueryChange = { query = it },
                    hubSearchMatchCount = if (query.isBlank()) 2 else 0,
                )
            }
        }

        compose.onNodeWithTag("workspace.sites.hubSearch").assertIsDisplayed()
        searchRequestId = 1
        compose.waitForIdle()

        compose.onNodeWithTag("workspace.sites.hubSearch").assertIsFocused()
        compose.onAllNodesWithTag("connection.catalog").assertCountEquals(0)

        compose.onNodeWithTag("workspace.sites.hubSearch").performTextInput("zzz")
        assertEquals("zzz", query)
        compose.onNodeWithTag("workspace.sites.hubSearchEmpty").assertIsDisplayed()
        compose.onNodeWithText("No connected site services match “zzz”.").assertIsDisplayed()
    }

    @Test
    fun withoutConnectionsSearchStillOpensTheConnectionCatalog() {
        var searchRequestId by mutableIntStateOf(0)
        compose.setContent {
            VercelticsTheme {
                WorkspaceScreen(
                    workspace = Workspace.REGISTRARS,
                    onConnectProvider = {},
                    onAccountAction = {},
                    searchRequestId = searchRequestId,
                    hubSearchQuery = "",
                )
            }
        }

        compose.onAllNodesWithTag("workspace.registrars.hubSearch").assertCountEquals(0)
        searchRequestId = 1

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("connection.catalog.registrars").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun connectedHubContentIsCappedOnWideWindows() {
        compose.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1200.dp, 900.dp))) {
                    WorkspaceScreen(
                        workspace = Workspace.REGISTRARS,
                        onConnectProvider = {},
                        onAccountAction = {},
                        connectedContent = { Text("Registrar cards") },
                        hubSearchQuery = "",
                        hubSearchMatchCount = 2,
                    )
                }
            }
        }

        val fieldWidth = compose.onNodeWithTag("workspace.registrars.hubSearch.container")
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        val rootWidth = compose.onNodeWithTag("workspace.registrars")
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        assertTrue("Hub search is $fieldWidth px wide in a $rootWidth px window", fieldWidth < rootWidth * 0.75f)
    }
}
