package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedCacheState
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedConnectionStatus
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedDashboardUi
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedMetricUi
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedScreen
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedSourceUiState
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedSourcesUi
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedUiState
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleAccountUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleCacheState
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleConnectionStatus
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleDashboardUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleOAuthReadinessUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsolePropertyUi
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleScreen
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleUiState
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Account menus (switch, add, Remove Current, Remove All) and tablet layouts for the site workspaces. */
class SiteAccountMenuScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun siteServiceMenuSwitchesAddsAndRequestsBothRemovals() {
        val events = ArrayList<String>()
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(
                    state = twoPlausibleSites(),
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(
                        onSwitchAccount = { events += "switch:$it" },
                        onAddAccount = { events += "add" },
                        onRequestDisconnect = { events += "removeCurrent" },
                        onRequestRemoveAll = { events += "removeAll" },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("siteService.accountMenu").performClick()
        composeRule.onNodeWithTag("siteService.accountMenu.list").assertIsDisplayed()
        composeRule.onNodeWithText("docs.studio.example").assertIsDisplayed()
        composeRule.onNodeWithTag("siteService.account.docs").performClick()
        composeRule.onNodeWithTag("siteService.accountMenu").performClick()
        composeRule.onNodeWithTag("siteService.addAccount").performClick()
        composeRule.onNodeWithTag("siteService.accountMenu").performClick()
        composeRule.onNodeWithTag("siteService.removeCurrentAccount").performClick()
        composeRule.onNodeWithTag("siteService.accountMenu").performClick()
        composeRule.onNodeWithTag("siteService.removeAllAccounts").performClick()

        assertEquals(listOf("switch:docs", "add", "removeCurrent", "removeAll"), events)
    }

    @Test
    fun aSingleAccountMenuOffersNoRemoveAll() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = true) {
                val state = twoPlausibleSites()
                val service = state.service("plausible")
                SiteServiceScreen(
                    state = state.copy(
                        services = state.services + (
                            "plausible" to service.copy(
                                accounts = SiteAccountsUi(service.accounts.accounts.take(1), "studio"),
                            )
                        ),
                    ),
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.accountMenu").performClick()
        composeRule.onNodeWithTag("siteService.removeCurrentAccount").assertExists()
        composeRule.onNodeWithTag("siteService.removeAllAccounts").assertDoesNotExist()
    }

    @Test
    fun removalsAreConfirmedWithDestructiveDialogs() {
        var confirmed = ""
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                val state = twoPlausibleSites()
                SiteServiceScreen(
                    state = state.copy(
                        services = state.services + ("plausible" to state.service("plausible").copy(showRemoveAllConfirmation = true)),
                    ),
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(onConfirmRemoveAll = { confirmed = "all" }),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.removeAllDialog").assertExists()
        composeRule.onNodeWithText("Remove all Plausible accounts?").assertExists()
        composeRule.onNodeWithText("REMOVE ALL").performClick()
        assertEquals("all", confirmed)
    }

    @Test
    fun addingAnAccountShowsTheConnectFormWithAWayBack() {
        var cancelled = false
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                val state = twoPlausibleSites()
                SiteServiceScreen(
                    state = state.copy(
                        services = state.services + ("plausible" to state.service("plausible").copy(isAddingAccount = true)),
                    ),
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(onCancelAddAccount = { cancelled = true }),
                )
            }
        }
        composeRule.onNodeWithText("Add a Plausible account").assertIsDisplayed()
        composeRule.onNodeWithTag("siteService.accountMenu").assertDoesNotExist()
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.cancelAddAccount"))
        composeRule.onNodeWithTag("siteService.cancelAddAccount").performClick()
        assertEquals(true, cancelled)
    }

    @Test
    fun tabletWidthsSplitTheOverviewWhilePhonesKeepOneColumn() {
        var tablet by mutableStateOf(true)
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                Box(Modifier.requiredSize(width = if (tablet) 1_000.dp else 400.dp, height = 800.dp)) {
                    SiteServiceScreen(
                        state = connected("uptimeRobot"),
                        providerId = "uptimeRobot",
                        actions = SiteServiceScreenActions(),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("siteService.overview.summaryPane").assertExists()
        composeRule.onNodeWithTag("siteService.overview").assertExists()
        composeRule.onNodeWithTag("siteService.resource.790001").assertExists()

        composeRule.runOnIdle { tablet = false }
        composeRule.onNodeWithTag("siteService.overview.summaryPane").assertDoesNotExist()
        composeRule.onNodeWithTag("siteService.summary").assertExists()
    }

    @Test
    fun searchConsoleMenuAndTabletDashboard() {
        val events = ArrayList<String>()
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                Box(Modifier.requiredSize(width = 1_000.dp, height = 800.dp)) {
                    SearchConsoleScreen(
                        state = searchConsoleState(),
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
                        onRequestDisconnect = { events += "removeCurrent" },
                        onDismissDisconnect = {},
                        onConfirmDisconnect = {},
                        onSwitchAccount = { events += "switch:$it" },
                        onAddAccount = { events += "add" },
                        onRequestRemoveAll = { events += "removeAll" },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("searchConsole.dashboard.summaryPane").assertExists()
        composeRule.onNodeWithTag("searchConsole.accountMenu").performClick()
        composeRule.onNodeWithTag("searchConsole.account.google-b").performClick()
        composeRule.onNodeWithTag("searchConsole.accountMenu").performClick()
        composeRule.onNodeWithText("Add Google account").performClick()
        composeRule.onNodeWithTag("searchConsole.accountMenu").performClick()
        composeRule.onNodeWithTag("searchConsole.removeAllAccounts").performClick()
        assertEquals(listOf("switch:google-b", "add", "removeAll"), events)
    }

    @Test
    fun searchConsoleAddPanelOffersAWayBack() {
        var cancelled = false
        composeRule.setContent {
            VercelticsTheme(darkTheme = true) {
                SearchConsoleScreen(
                    state = searchConsoleState().copy(isAddingAccount = true),
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
                    onCancelAddAccount = { cancelled = true },
                )
            }
        }
        composeRule.onNodeWithText("Add a Google account").assertIsDisplayed()
        composeRule.onNodeWithTag("searchConsole.connectionPanel").performScrollToNode(hasTestTag("searchConsole.cancelAddAccount"))
        composeRule.onNodeWithTag("searchConsole.cancelAddAccount").performClick()
        assertEquals(true, cancelled)
    }

    @Test
    fun pageSpeedMenuAddsSitesAndShowsTheAddForm() {
        val events = ArrayList<String>()
        var adding by mutableStateOf(false)
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                PageSpeedScreen(
                    state = pageSpeedState().copy(isAddingAccount = adding),
                    onBack = {},
                    onConnect = { _, _ -> },
                    onRefresh = {},
                    onCancel = {},
                    onRequestDisconnect = { events += "removeCurrent" },
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    onSwitchAccount = { events += "switch:$it" },
                    onAddAccount = {
                        events += "add"
                        adding = true
                    },
                    onCancelAddAccount = { adding = false },
                )
            }
        }
        composeRule.onNodeWithTag("pagespeed.accountMenu").performClick()
        composeRule.onNodeWithTag("pagespeed.account.site-b").performClick()
        composeRule.onNodeWithTag("pagespeed.accountMenu").performClick()
        composeRule.onNodeWithTag("pagespeed.removeCurrentAccount").performClick()
        composeRule.onNodeWithTag("pagespeed.accountMenu").performClick()
        composeRule.onNodeWithTag("pagespeed.addAccount").performClick()

        composeRule.onNodeWithText("ADD ANOTHER SITE").assertIsDisplayed()
        composeRule.onNodeWithTag("pagespeed.accountMenu").assertDoesNotExist()
        composeRule.onNodeWithTag("pagespeed.connectionForm").performScrollToNode(hasTestTag("pagespeed.cancelAddAccount"))
        composeRule.onNodeWithTag("pagespeed.cancelAddAccount").performClick()
        composeRule.onNodeWithTag("pagespeed.dashboard").assertExists()
        assertEquals(listOf("switch:site-b", "removeCurrent", "add"), events)
    }

    private fun connected(providerId: String): SiteServicesUiState {
        val dashboard = SampleSiteServicesUiGateway.dashboard(providerId)
        return SiteServicesUiState(
            googleOAuthReadiness = SiteGoogleOAuthReadinessUi.Ready,
            services = SiteServiceProviderIds.associateWith {
                SiteServiceState(it, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null)
            } + (
                providerId to SiteServiceState(
                    providerId,
                    status = SiteServiceConnectionStatus.CONNECTED,
                    dashboard = dashboard,
                    savedAccountName = dashboard.accountName,
                    operation = null,
                    accounts = SiteAccountsUi(listOf(SiteAccountOptionUi("main", dashboard.accountName)), "main"),
                )
            ),
        )
    }

    private fun twoPlausibleSites(): SiteServicesUiState {
        val base = connected("plausible")
        val service = base.service("plausible")
        return base.copy(
            services = base.services + (
                "plausible" to service.copy(
                    accounts = SiteAccountsUi(
                        listOf(SiteAccountOptionUi("studio", "studio.example"), SiteAccountOptionUi("docs", "docs.studio.example")),
                        "studio",
                    ),
                )
            ),
        )
    }

    private fun searchConsoleState(): SearchConsoleUiState {
        val properties = (1..4).map { SearchConsolePropertyUi("sc-domain:site$it.example", "site$it.example", "Owner") }
        val dashboard = SearchConsoleDashboardUi(
            account = SearchConsoleAccountUi("google-a", "a@example.com"),
            properties = properties,
            loadedPropertyCount = properties.size,
            providerInventoryComplete = true,
            inventoryTruncatedForDisplay = false,
            warnings = emptyList(),
            fetchedAtMillis = 1L,
            cacheState = SearchConsoleCacheState.CACHED_FRESH,
        )
        return SearchConsoleUiState(
            oauthReadiness = SearchConsoleOAuthReadinessUi.Ready,
            status = SearchConsoleConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedAccount = dashboard.account,
            operation = null,
            accounts = SiteAccountsUi(
                listOf(SiteAccountOptionUi("google-a", "a@example.com"), SiteAccountOptionUi("google-b", "b@example.com")),
                "google-a",
            ),
        )
    }

    private fun pageSpeedState() = PageSpeedUiState(
        status = PageSpeedConnectionStatus.CONNECTED,
        dashboard = PageSpeedDashboardUi(
            siteUrl = "https://one.example",
            siteName = "one.example",
            status = "Good",
            metrics = listOf(PageSpeedMetricUi("pagespeed.mobile.performance", "Mobile Performance", 96.0, PageSpeedMetricUnit.SCORE, "96")),
            fetchedAtMillis = 1L,
            sources = PageSpeedSourcesUi(PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE),
            warnings = emptyList(),
            cacheState = PageSpeedCacheState.CACHED_FRESH,
            accountId = "site-a",
        ),
        savedSiteUrl = "https://one.example",
        operation = null,
        canDisconnect = true,
        accounts = SiteAccountsUi(
            listOf(SiteAccountOptionUi("site-a", "one.example"), SiteAccountOptionUi("site-b", "two.example")),
            "site-a",
        ),
    )
}
