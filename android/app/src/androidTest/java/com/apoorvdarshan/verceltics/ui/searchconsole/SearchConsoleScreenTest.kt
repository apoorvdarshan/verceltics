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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun overviewShowsTwentyEightDayTotalsAndPerPropertyMetricsForFree() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard.copy(cacheState = SearchConsoleCacheState.LIVE),
                        operation = null,
                        propertySummaries = mapOf(property.siteUrl to summary),
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

        composeRule.onNodeWithTag("searchConsole.overview.totals").assertIsDisplayed()
        composeRule.onNodeWithText("LAST 28 DAYS").assertIsDisplayed()
        // The property row is one clickable (merged) node, so its 28-day summary block keeps its own
        // tag only in the unmerged tree.
        composeRule.onNodeWithTag("searchConsole.dashboard", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("searchConsole.property.summary.${property.siteUrl}"))
        composeRule.onNodeWithTag("searchConsole.property.summary.${property.siteUrl}", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Indexed").assertExists()
        composeRule.onAllNodesWithText("1.2K").assertCountEquals(2)
    }

    @Test
    fun performanceControlsChartAndSortableBreakdownWorkOnPage() {
        var preset: SearchConsoleDatePresetUi? = null
        var dimension: SearchConsoleDimensionUi? = null
        var sort: SearchConsoleSortFieldUi? = null
        var dataState: SearchConsoleDataStateUi? = null
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        operation = null,
                        selectedPropertyUrl = property.siteUrl,
                        propertyWorkspace = workspace.copy(performance = SearchConsoleResourceUi.Available(performance)),
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
                    performanceActions = SearchConsolePerformanceActions(
                        onSelectDatePreset = { preset = it },
                        onSelectDimension = { dimension = it },
                        onToggleSort = { sort = it },
                        onSelectDataState = { dataState = it },
                    ),
                )
            }
        }
        val detail = composeRule.onNodeWithTag("searchConsole.detail")

        detail.performScrollToNode(hasTestTag("searchConsole.performance.preset.DAYS_7"))
        composeRule.onNodeWithTag("searchConsole.performance.preset.DAYS_7").performClick()
        composeRule.onNodeWithTag("searchConsole.performance.advanced").performClick()
        detail.performScrollToNode(hasTestTag("searchConsole.performance.dataState.HOURLY_ALL"))
        composeRule.onNodeWithTag("searchConsole.performance.dataState.HOURLY_ALL").performClick()
        composeRule.onNodeWithTag("searchConsole.performance.aggregation.BY_NEWS_SHOWCASE_PANEL").assertIsNotEnabled()
        composeRule.onNodeWithTag("searchConsole.performance.returnedAggregation").assertExists()

        detail.performScrollToNode(hasTestTag("searchConsole.performance.chart"))
        composeRule.onNodeWithTag("searchConsole.performance.chart.yAxis").assertExists()
        composeRule.onNodeWithText("Drag across the chart").assertExists()

        detail.performScrollToNode(hasTestTag("searchConsole.breakdown.dimension.HOUR"))
        composeRule.onNodeWithTag("searchConsole.breakdown.dimension.HOUR").performClick()
        detail.performScrollToNode(hasTestTag("searchConsole.breakdown.sort.POSITION"))
        composeRule.onNodeWithTag("searchConsole.breakdown.sort.POSITION").performClick()
        detail.performScrollToNode(hasTestTag("searchConsole.breakdown.row.0"))
        composeRule.onNodeWithText("swift charts").assertExists()

        composeRule.runOnIdle {
            assertEquals(SearchConsoleDatePresetUi.DAYS_7, preset)
            assertEquals(SearchConsoleDataStateUi.HOURLY_ALL, dataState)
            assertEquals(SearchConsoleDimensionUi.HOUR, dimension)
            assertEquals(SearchConsoleSortFieldUi.POSITION, sort)
        }
    }

    @Test
    fun inspectionShowsAmpCardAndRichResultTypesWithoutIssues() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = true) {
                SearchConsoleScreen(
                    state = baseState.copy(
                        status = SearchConsoleConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        operation = null,
                        selectedPropertyUrl = property.siteUrl,
                        propertyWorkspace = workspace,
                        selectedSection = SearchConsoleDetailSection.INSPECT,
                        inspectionUrl = "https://example.com/",
                        inspection = inspection,
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
        val detail = composeRule.onNodeWithTag("searchConsole.detail")

        detail.performScrollToNode(hasTestTag("searchConsole.inspection.amp"))
        composeRule.onNodeWithTag("searchConsole.inspection.amp").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.com/amp").assertExists()
        detail.performScrollToNode(hasTestTag("searchConsole.inspection.richType.Breadcrumbs"))
        composeRule.onNodeWithTag("searchConsole.inspection.richType.Breadcrumbs").assertIsDisplayed()
        composeRule.onNodeWithText("1 item · 0 issues").assertExists()
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
                    firstIncompleteDate = null,
                    firstIncompleteHour = null,
                ),
            ),
            sitemaps = SearchConsoleResourceUi.Available(emptyList()),
        )
        val summary = SearchConsolePropertySummaryUi(
            siteUrl = property.siteUrl,
            clicks = 1_234.0,
            impressions = 56_000.0,
            ctr = 0.022,
            position = 7.4,
            sitemapCount = 3,
            indexStatus = "Indexed",
            indexVerdict = "PASS",
            lastCrawlTime = "2026-10-01T10:00:00Z",
            isPartial = false,
        )
        val performance = SearchConsolePerformanceUi(
            clicks = 60.0,
            impressions = 600.0,
            ctr = 0.1,
            position = 4.0,
            timeline = listOf(
                SearchConsoleTimelinePointUi("2026-08-01", 10.0, 100.0, 0.1, 5.0),
                SearchConsoleTimelinePointUi("2026-08-02", 20.0, 200.0, 0.1, 4.0),
                SearchConsoleTimelinePointUi("2026-08-03", 30.0, 300.0, 0.1, 3.5),
            ),
            breakdownRows = listOf(
                SearchConsoleBreakdownRowUi(listOf("swift charts"), 40.0, 400.0, 0.1, 3.0),
                SearchConsoleBreakdownRowUi(listOf("compose charts"), 20.0, 200.0, 0.1, 6.0),
            ),
            firstIncompleteDate = null,
            firstIncompleteHour = null,
            timelineAggregationType = "byProperty",
        )
        val inspection = SearchConsoleInspectionUi(
            inspectionResultLink = null,
            verdict = "PASS",
            coverageState = "Submitted and indexed",
            indexingState = "INDEXING_ALLOWED",
            robotsTxtState = "ALLOWED",
            pageFetchState = "SUCCESSFUL",
            lastCrawlTime = "2026-08-26T09:12:00Z",
            googleCanonical = "https://example.com/",
            userCanonical = "https://example.com/",
            crawledAs = "MOBILE",
            sitemaps = listOf("https://example.com/sitemap.xml"),
            referringUrls = emptyList(),
            ampVerdict = "PASS",
            mobileVerdict = null,
            richResultsVerdict = "PASS",
            issues = emptyList(),
            inspectedUrl = "https://example.com/",
            amp = SearchConsoleAmpInspectionUi(
                ampUrl = "https://example.com/amp",
                verdict = "PASS",
                indexStatusVerdict = "PASS",
                indexingState = "INDEXING_ALLOWED",
                robotsTxtState = "ALLOWED",
                pageFetchState = "SUCCESSFUL",
                lastCrawlTime = "2026-08-25T08:00:00Z",
                issues = emptyList(),
            ),
            richResultTypes = listOf(
                SearchConsoleRichResultTypeUi(
                    "Breadcrumbs",
                    listOf(SearchConsoleRichResultItemUi("Unnamed item", emptyList())),
                ),
            ),
        )
        val baseState = SearchConsoleUiState(
            oauthReadiness = SearchConsoleOAuthReadinessUi.Ready,
        )
    }
}
