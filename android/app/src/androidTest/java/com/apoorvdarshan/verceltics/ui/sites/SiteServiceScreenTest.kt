package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteDetailField
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSection
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeries
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeriesPoint
import com.apoorvdarshan.verceltics.data.sites.SiteDetailTable
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SiteServiceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unconfiguredGoogleShowsTruthfulPausedStateInsteadOfFakeConnect() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(
                    state = state(
                        readiness = SiteGoogleOAuthReadinessUi.ConfigurationNeeded(
                            "Google sign-in is unavailable in this version. Contact support for help connecting.",
                        ),
                    ),
                    providerId = "googleAnalytics",
                    actions = SiteServiceScreenActions(),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.googleConnection")
            .performScrollToNode(hasTestTag("siteService.configurationNeeded"))
        composeRule.onNodeWithTag("siteService.configurationNeeded").assertIsDisplayed()
        composeRule.onNodeWithText("GOOGLE SIGN-IN UNAVAILABLE").assertExists()
        composeRule.onNodeWithTag("siteService.connectGoogle").assertDoesNotExist()
    }

    @Test
    fun readyGoogleConnectionStartsSignIn() {
        var started = false
        composeRule.setContent {
            VercelticsTheme(darkTheme = true) {
                SiteServiceScreen(
                    state = state(),
                    providerId = "googleAnalytics",
                    actions = SiteServiceScreenActions(onConnectGoogle = { started = true }),
                )
            }
        }
        composeRule.onNodeWithText("Connect Google Analytics").assertIsDisplayed()
        composeRule.onNodeWithTag("siteService.googleConnection").performScrollToNode(hasTestTag("siteService.connectGoogle"))
        composeRule.onNodeWithTag("siteService.connectGoogle").performClick()
        assertTrue(started)
    }

    @Test
    fun apiKeyFormRequiresCredentialAndShowsProviderFields() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(state = state(), providerId = "clarity", actions = SiteServiceScreenActions())
            }
        }
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.field.projectName"))
        composeRule.onNodeWithTag("siteService.field.projectName").assertExists()
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.credential"))
        composeRule.onNodeWithTag("siteService.credential").assertExists()
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.connect"))
        composeRule.onNodeWithTag("siteService.connect").assertIsNotEnabled()
        composeRule.onNodeWithText("Credentials stay encrypted on this device", substring = true).assertExists()
    }

    @Test
    fun umamiSelfHostedModeAddsTheBaseUrlField() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(state = state(), providerId = "umami", actions = SiteServiceScreenActions())
            }
        }
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.umamiMode.selfHosted"))
        composeRule.onNodeWithTag("siteService.field.baseURL").assertDoesNotExist()
        composeRule.onNodeWithTag("siteService.umamiMode.selfHosted").performClick()
        composeRule.onNodeWithTag("siteService.connectionForm").performScrollToNode(hasTestTag("siteService.field.baseURL"))
        composeRule.onNodeWithTag("siteService.field.baseURL").assertExists()
        composeRule.onNodeWithText("SELF-HOSTED BEARER TOKEN").assertExists()
    }

    @Test
    fun overviewShowsSummarySearchAndOpensResourcesThroughTheGate() {
        var opened: String? = "unset"
        var refreshed = false
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(
                    state = connectedState("uptimeRobot"),
                    providerId = "uptimeRobot",
                    actions = SiteServiceScreenActions(onOpenDetail = { opened = it }, onRefresh = { refreshed = true }),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.summary").assertIsDisplayed()
        composeRule.onNodeWithTag("siteService.search").assertExists()
        composeRule.onNodeWithTag("siteService.overview").performScrollToNode(hasTestTag("siteService.resource.790002"))
        composeRule.onNodeWithTag("siteService.resource.790002").performClick()
        assertEquals("790002", opened)
        composeRule.onNodeWithTag("siteService.summary").performClick()
        assertNull(opened)
        composeRule.onNodeWithTag("siteService.refreshOrCancel").assertIsEnabled().performClick()
        assertTrue(refreshed)
    }

    @Test
    fun disconnectDialogAppearsOnlyWhenRequested() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                val connected = connectedState("plausible")
                SiteServiceScreen(
                    state = connected.copy(
                        services = connected.services + ("plausible" to connected.service("plausible").copy(showDisconnectConfirmation = true)),
                    ),
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.disconnectDialog").assertExists()
        composeRule.onNodeWithText("Remove studio.example?").assertExists()
    }

    @Test
    fun restoringServicesShowALoadingState() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(
                    state = SiteServicesUiState(SiteGoogleOAuthReadinessUi.Ready),
                    providerId = "betterStack",
                    actions = SiteServiceScreenActions(),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.restoring").assertIsDisplayed()
    }

    @Test
    fun detailRendersSectionsTimelineTablesAndRawExplorer() {
        var state by mutableStateOf(detailState(showRaw = false))
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceScreen(
                    state = state,
                    providerId = "plausible",
                    actions = SiteServiceScreenActions(onShowRawResponses = { state = detailState(showRaw = it) }),
                )
            }
        }
        composeRule.onNodeWithTag("siteService.detail").assertIsDisplayed()
        composeRule.onNodeWithText("Plausible details").assertExists()
        composeRule.onNodeWithTag("siteService.detail").performScrollToNode(hasTestTag("siteService.detail.series.plausible.timeline"))
        composeRule.onNodeWithTag("siteService.detail").performScrollToNode(hasTestTag("siteService.detail.table.plausible.sources"))
        composeRule.onNodeWithTag("siteService.detail").performScrollToNode(hasTestTag("siteService.detail.raw"))
        composeRule.onNodeWithTag("siteService.detail.raw").performClick()
        composeRule.onNodeWithTag("siteService.raw").assertIsDisplayed()
        composeRule.onNodeWithText("$.meta.total_rows").assertExists()
    }

    @Test
    fun connectionCardsListOnlyConnectedServices() {
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteServiceConnectionCards(state = connectedState("umami"), onOpenProvider = {})
            }
        }
        composeRule.onNodeWithTag("workspace.sites.umamiConnection").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.sites.plausibleConnection").assertDoesNotExist()
        composeRule.onNodeWithText("Umami Cloud").assertExists()
    }

    @Test
    fun lockedProAccessShowsThePaywallInsteadOfOpeningDetails() {
        var paywallRequests = 0
        val viewModel = SiteServicesViewModel(SampleSiteServicesUiGateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalProAccess provides ProAccess(isUnlocked = false, isConfirmedLocked = true) { paywallRequests += 1 },
                ) {
                    SiteServiceRoute(viewModel = viewModel, providerId = "plausible", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.service("plausible").dashboard != null }
        composeRule.onNodeWithTag("siteService.summary").performClick()
        assertEquals(1, paywallRequests)
        assertNull(viewModel.uiState.value.detail)
        composeRule.onNodeWithTag("siteService.overview").performScrollToNode(hasTestTag("siteService.openProvider"))
        composeRule.onNodeWithTag("siteService.openProvider").performClick()
        assertEquals(2, paywallRequests)
    }

    private fun state(readiness: SiteGoogleOAuthReadinessUi = SiteGoogleOAuthReadinessUi.Ready) = SiteServicesUiState(
        googleOAuthReadiness = readiness,
        services = SiteServiceProviderIds.associateWith {
            SiteServiceState(it, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null)
        },
    )

    private fun connectedState(providerId: String): SiteServicesUiState {
        val dashboard = SampleSiteServicesUiGateway.dashboard(providerId)
        return state().let { base ->
            base.copy(
                services = base.services + (
                    providerId to SiteServiceState(
                        providerId,
                        status = SiteServiceConnectionStatus.CONNECTED,
                        dashboard = dashboard,
                        savedAccountName = dashboard.accountName,
                        operation = null,
                    )
                ),
            )
        }
    }

    private fun detailState(showRaw: Boolean): SiteServicesUiState {
        val base = connectedState("plausible")
        val payload = SiteServiceDetailUi(
            providerId = "plausible",
            resourceId = "https://studio.example",
            title = "studio.example",
            sections = listOf(
                SiteDetailSection(
                    "plausible.overview",
                    "Overview · 2026-06-16 – 2026-07-15",
                    listOf(SiteDetailField("visitors", "Visitors", ProviderJsonValue.Num.of(1200))),
                ),
            ),
            series = listOf(
                SiteDetailSeries(
                    "plausible.timeline",
                    "Traffic over time",
                    mapOf("visitors" to "Visitors"),
                    listOf(SiteDetailSeriesPoint("2026-07-14", mapOf("visitors" to 10.0)), SiteDetailSeriesPoint("2026-07-15", mapOf("visitors" to 14.0))),
                ),
            ),
            tables = listOf(
                SiteDetailTable(
                    "plausible.sources",
                    "Sources",
                    listOf("visit:source", "visitors"),
                    listOf(mapOf("visit:source" to ProviderJsonValue.Str("Google"), "visitors" to ProviderJsonValue.Num.of(12))),
                ),
            ),
            rawResponses = mapOf("overview" to ProviderJsonParser.parse("""{"meta":{"total_rows":1}}""")),
            warnings = emptyList(),
            fetchedAtMillis = 0,
        )
        return base.copy(
            activeProviderId = "plausible",
            detail = SiteServiceDetailState(
                providerId = "plausible",
                resourceId = "https://studio.example",
                payload = payload,
                showRawResponses = showRaw,
            ),
        )
    }
}
