package com.apoorvdarshan.verceltics.ui.registrar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RegistrarCompleteApiScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardAndDomainDetailOfferCompleteApi() {
        val requests = mutableListOf<String?>()
        composeRule.setContent {
            VercelticsTheme { Screen(connected(), onOpenCompleteApi = { requests += it }) }
        }
        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.completeApi"))
        composeRule.onNodeWithTag("registrar.completeApi").assertTextContains("COMPLETE API").performClick()
        dashboard.performScrollToNode(hasTestTag("registrar.openDashboard"))
        composeRule.onNodeWithTag("registrar.openDashboard").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), requests) }
    }

    @Test
    fun domainDetailCompleteApiCarriesTheDomain() {
        val requests = mutableListOf<String?>()
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    connected().copy(selectedProviderId = "gandi", selectedDomainId = "studio.example"),
                    onOpenCompleteApi = { requests += it },
                )
            }
        }
        composeRule.onNodeWithTag("registrar.domainDetail")
            .performScrollToNode(hasTestTag("registrar.detail.completeApi"))
        composeRule.onNodeWithText("Complete registrar API").assertIsDisplayed()
        composeRule.onNodeWithText("Search every indexed read and write operation, then inspect the full raw response").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.detail.completeApi").performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>("studio.example"), requests) }
    }

    @Test
    fun routeGatesCompleteApiAndOpensTheRegistrarCatalogForPro() {
        var paywallRequests = 0
        val unlocked = mutableStateOf(false)
        val viewModel = RegistrarViewModel(Gateway, SavedStateHandle())
        composeRule.setContent {
            val access = if (unlocked.value) ProAccess.Unlocked else ProAccess(isUnlocked = false, isConfirmedLocked = false) { paywallRequests += 1 }
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides access) {
                    RegistrarRoute(viewModel = viewModel, providerId = "gandi", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.provider("gandi").status == RegistrarConnectionStatus.CONNECTED }
        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.completeApi"))
        composeRule.onNodeWithTag("registrar.completeApi").performClick()
        composeRule.runOnIdle {
            assertEquals(1, paywallRequests)
            assertFalse(viewModel.apiWorkspace("gandi")!!.state.value.isOpen)
            unlocked.value = true
        }

        composeRule.onNodeWithTag("registrar.dashboard").performScrollToNode(hasTestTag("registrar.completeApi"))
        composeRule.onNodeWithTag("registrar.completeApi").performClick()
        composeRule.onNodeWithTag("providerApi.workspace").assertIsDisplayed()
        composeRule.waitUntil(10_000) { viewModel.apiWorkspace("gandi")!!.state.value.loadedCatalog != null }
        composeRule.onNodeWithTag("providerApi.totalCount").assertTextContains("206")
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithText("Full official registrar API").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.path").performScrollTo().assertTextContains("/v5/domain/domains?per_page=100&page=1")
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: RegistrarUiState, onOpenCompleteApi: (String?) -> Unit) {
        RegistrarScreen(
            state = state,
            providerId = "gandi",
            onBack = {},
            onConnect = {},
            onCancel = {},
            onRefresh = {},
            onOpenDomain = {},
            onOpenDashboard = {},
            onOpenUrl = {},
            onRequestDisconnect = {},
            onDismissDisconnect = {},
            onConfirmDisconnect = {},
            onDetectPublicIpv4 = {},
            nowMillis = NOW,
            onOpenCompleteApi = onOpenCompleteApi,
        )
    }

    private object Gateway : RegistrarUiGateway {
        override suspend fun restore() = Result.success(
            RegistrarRestoreUi(mapOf("gandi" to RegistrarProviderRestoreUi.Available(DASHBOARD))),
        )

        override suspend fun connect(request: RegistrarConnectRequest) = Result.success(DASHBOARD)

        override suspend fun refresh(providerId: String) = Result.success(DASHBOARD)

        override suspend fun disconnect(providerId: String) = Result.success(Unit)

        override suspend fun detectPublicIpv4() = Result.success("8.8.4.4")
    }

    private companion object {
        val NOW = System.currentTimeMillis()

        val DASHBOARD = RegistrarDashboardUi(
            account = RegistrarAccountUi("gandi", "Gandi Account"),
            domains = listOf(
                RegistrarDomainUi(
                    id = "studio.example",
                    name = "studio.example",
                    status = "Active",
                    createdAtMillis = NOW - 86_400_000L * 400,
                    expiresAtMillis = NOW + 86_400_000L * 120,
                    autoRenew = true,
                    locked = true,
                    privacyEnabled = true,
                    nameservers = listOf("ns1.gandi.net"),
                ),
            ),
            inventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = NOW,
            cacheState = RegistrarCacheState.LIVE,
        )

        fun connected() = RegistrarUiState().let { state ->
            state.copy(
                providers = state.providers + (
                    "gandi" to state.provider("gandi").copy(
                        status = RegistrarConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                    )
                    ),
            )
        }
    }
}
