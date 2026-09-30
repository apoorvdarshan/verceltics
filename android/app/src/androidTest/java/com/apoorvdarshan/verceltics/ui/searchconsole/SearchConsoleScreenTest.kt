package com.apoorvdarshan.verceltics.ui.searchconsole

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Rule
import org.junit.Test

class SearchConsoleScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unconfiguredOAuthShowsTruthfulPausedStateInsteadOfFakeConnect() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        oauthReadiness = SearchConsoleOAuthReadinessUi.ConfigurationNeeded(
                            "Google sign-in is unavailable in this version. Contact support for help connecting.",
                        ),
                        status = SearchConsoleConnectionStatus.DISCONNECTED,
                        operation = null,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSearchChange = {},
                    onOpenProperty = {},
                    onRefreshProperty = {},
                    onSelectSection = {},
                    onInspectionUrlChange = {},
                    onInspect = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("searchConsole.configurationNeeded").assertIsDisplayed()
        composeRule.onNodeWithText("GOOGLE SIGN-IN UNAVAILABLE").assertExists()
        composeRule.onNodeWithTag("searchConsole.connect").assertDoesNotExist()
    }

    @Test
    fun dashboardRendersCachedAccountSearchAndVerifiedProperties() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = true) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        savedAccount = dashboard.account,
                        operation = null,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSearchChange = {},
                    onOpenProperty = {},
                    onRefreshProperty = {},
                    onSelectSection = {},
                    onInspectionUrlChange = {},
                    onInspect = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("owner@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("searchConsole.propertySearch").assertExists()
        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        composeRule.onNodeWithText("SAVED · RECENT").assertExists()
    }

    @Test
    fun propertyDetailSwitchesFromPerformanceToReadOnlyInspection() {
        var state by mutableStateOf(
            baseState.copy(
                status = SearchConsoleConnectionStatus.CONNECTED,
                dashboard = dashboard,
                savedAccount = dashboard.account,
                operation = null,
                selectedPropertyUrl = property.siteUrl,
                propertyWorkspace = workspace,
            ),
        )
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = state,
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSearchChange = {},
                    onOpenProperty = {},
                    onRefreshProperty = {},
                    onSelectSection = { state = state.copy(selectedSection = it) },
                    onInspectionUrlChange = {},
                    onInspect = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("10").assertExists()
        composeRule.onNodeWithTag("searchConsole.section.inspect").performClick()
        composeRule.onNodeWithTag("searchConsole.inspectionUrl").assertIsDisplayed()
        composeRule.onNodeWithTag("searchConsole.inspect").assertIsNotEnabled()
    }

    @Test
    fun connectionCardExposesStableTagAndCacheFreshness() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleConnectionCard(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        operation = null,
                    ),
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("workspace.sites.searchConsoleConnection")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Connected, recent saved data",
                ),
            )
    }

    @Test
    fun connectionCardReportsAttentionForPartialInventory() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleConnectionCard(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard.copy(
                            providerInventoryComplete = false,
                            warnings = listOf("Inventory is partial."),
                        ),
                        operation = null,
                    ),
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("workspace.sites.searchConsoleConnection")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Attention, recent saved data",
                ),
            )
        composeRule.onNodeWithText("ATTENTION").assertIsDisplayed()
    }

    @Test
    fun disconnectedSearchFeedbackInvitesConnectionWithoutClaimingSavedState() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.DISCONNECTED,
                        operation = null,
                        notice = "Connect Google Search Console to search verified properties.",
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSearchChange = {},
                    onOpenProperty = {},
                    onRefreshProperty = {},
                    onSelectSection = {},
                    onInspectionUrlChange = {},
                    onInspect = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("Connect Google Search Console to search verified properties.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("SAVED GOOGLE CONNECTION").assertDoesNotExist()
    }

    @Test
    fun detailPropertySwitcherKeepsSearchablePropertyListAccessible() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        selectedPropertyUrl = property.siteUrl,
                        propertyWorkspace = workspace,
                        showPropertySwitcher = true,
                        operation = null,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSearchChange = {},
                    onOpenProperty = {},
                    onRefreshProperty = {},
                    onSelectSection = {},
                    onInspectionUrlChange = {},
                    onInspect = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("searchConsole.propertySwitcher").assertIsDisplayed()
        composeRule.onNodeWithTag("searchConsole.propertySwitcher.search").assertIsDisplayed()
    }

    private companion object {
        val property = SearchConsolePropertyUi("sc-domain:example.com", "example.com", "Owner")
        val dashboard = SearchConsoleDashboardUi(
            account = SearchConsoleAccountUi("subject", "owner@example.com"),
            properties = listOf(property),
            loadedPropertyCount = 1,
            providerInventoryComplete = true,
            inventoryTruncatedForDisplay = false,
            warnings = emptyList(),
            fetchedAtMillis = 1_700_000_000_000,
            cacheState = SearchConsoleCacheState.CACHED_FRESH,
        )
        val workspace = SearchConsolePropertyWorkspaceUi(
            property = property,
            performance = SearchConsoleResourceUi.Available(
                SearchConsolePerformanceUi(
                    clicks = 10.0,
                    impressions = 200.0,
                    ctr = 0.05,
                    position = 4.2,
                    timeline = emptyList(),
                    breakdownRows = emptyList(),
                    loadedBreakdownRowCount = 0,
                    hasPreviousPage = false,
                    hasNextPage = false,
                    firstIncompleteDate = null,
                    firstIncompleteHour = null,
                ),
            ),
            sitemaps = SearchConsoleResourceUi.Available(emptyList()),
        )
        val baseState = SearchConsoleUiState(
            oauthReadiness = SearchConsoleOAuthReadinessUi.Ready,
        )
    }
}
