package com.apoorvdarshan.verceltics.ui.netlify

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class NetlifyCompleteApiScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardAndSiteDetailOfferCompleteApi() {
        val requests = mutableListOf<String?>()
        composeRule.setContent { VercelticsTheme { Screen(connected(), onOpenCompleteApi = { requests += it }) } }
        val dashboard = composeRule.onNodeWithTag("netlify.dashboard")
        dashboard.performScrollToNode(hasTestTag("netlify.completeApi"))
        composeRule.onNodeWithTag("netlify.completeApi").assertTextContains("COMPLETE API").performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), requests) }
    }

    @Test
    fun siteDetailCompleteApiCarriesTheSite() {
        val requests = mutableListOf<String?>()
        composeRule.setContent {
            VercelticsTheme { Screen(connected().copy(selectedSiteId = "site-1"), onOpenCompleteApi = { requests += it }) }
        }
        composeRule.onNodeWithTag("netlify.siteDetail").performScrollToNode(hasTestTag("netlify.siteCompleteApi"))
        composeRule.onNodeWithText("Complete API").assertIsDisplayed()
        composeRule.onNodeWithTag("netlify.siteCompleteApi").performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>("site-1"), requests) }
    }

    @Test
    fun routeGatesCompleteApiAndOpensNetlifysCatalogForPro() {
        var paywallRequests = 0
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = false) { paywallRequests += 1 }
        val lockedModel = NetlifyViewModel(Gateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    NetlifyRoute(viewModel = lockedModel, onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { lockedModel.uiState.value.status == NetlifyConnectionStatus.CONNECTED }
        composeRule.onNodeWithTag("netlify.dashboard").performScrollToNode(hasTestTag("netlify.completeApi"))
        composeRule.onNodeWithTag("netlify.completeApi").performClick()
        composeRule.runOnIdle {
            assertEquals(1, paywallRequests)
            assertFalse(lockedModel.apiWorkspace.state.value.isOpen)
        }
    }

    @Test
    fun routeOpensNetlifysBundledCatalogForProUsers() {
        val viewModel = NetlifyViewModel(Gateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides ProAccess.Unlocked) {
                    NetlifyRoute(viewModel = viewModel, onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.status == NetlifyConnectionStatus.CONNECTED }
        composeRule.onNodeWithTag("netlify.dashboard").performScrollToNode(hasTestTag("netlify.completeApi"))
        composeRule.onNodeWithTag("netlify.completeApi").performClick()
        composeRule.onNodeWithTag("providerApi.workspace").assertIsDisplayed()
        composeRule.waitUntil(10_000) { viewModel.apiWorkspace.state.value.loadedCatalog != null }
        composeRule.onNodeWithTag("providerApi.totalCount").assertTextContains("180")
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.path").performScrollTo().assertTextContains("/sites?per_page=100")
        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("netlify.dashboard").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: NetlifyUiState, onOpenCompleteApi: (String?) -> Unit) {
        NetlifyScreen(
            state = state,
            onBack = {},
            onConnect = {},
            onRefresh = {},
            onCancel = {},
            onOpenSite = {},
            onRefreshSite = {},
            onRequestDisconnect = {},
            onDismissDisconnect = {},
            onConfirmDisconnect = {},
            onOpenCompleteApi = onOpenCompleteApi,
        )
    }

    private object Gateway : NetlifyUiGateway {
        override suspend fun restore(): Result<NetlifyRestoreUi> = Result.success(NetlifyRestoreUi.Available(DASHBOARD))

        override suspend fun connect(personalToken: SecretValue) = Result.success(DASHBOARD)

        override suspend fun refresh() = Result.success(DASHBOARD)

        override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> =
            Result.failure(NetlifyUiException("Site unavailable."))

        override suspend fun disconnect() = Result.success(Unit)
    }

    private companion object {
        val SITE = NetlifySiteUi("site-1", "Example", "example.netlify.app", "https://example.netlify.app", "current", 42L)
        val DASHBOARD = NetlifyDashboardUi(
            account = NetlifyAccountUi("account-1", "Example Account", "owner@example.com"),
            sites = listOf(SITE),
            loadedSiteCount = 1,
            providerInventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = System.currentTimeMillis(),
            cacheState = NetlifyCacheState.LIVE,
        )

        fun connected() = NetlifyUiState(
            status = NetlifyConnectionStatus.CONNECTED,
            dashboard = DASHBOARD,
            savedAccount = DASHBOARD.account,
            operation = null,
        )
    }
}
