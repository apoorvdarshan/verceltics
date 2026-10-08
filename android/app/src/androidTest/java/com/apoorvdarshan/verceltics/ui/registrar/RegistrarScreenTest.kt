package com.apoorvdarshan.verceltics.ui.registrar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegistrarScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun namecheapFormShowsIosFieldsAndPublicAddressHelper() {
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    state = disconnected("namecheap").copy(
                        publicIpv4 = RegistrarPublicIpv4Ui(providerId = "namecheap", address = "8.8.4.4"),
                    ),
                    providerId = "namecheap",
                )
            }
        }

        composeRule.onNodeWithTag("registrar.connectionForm").assertIsDisplayed()
        composeRule.onNodeWithText("Connect Namecheap").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.security").performScrollTo()
        composeRule.onNodeWithTag("registrar.security").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.credentialLink").performScrollTo()
        composeRule.onNodeWithTag("registrar.credentialLink").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.publicIpv4").performScrollTo()
        composeRule.onNodeWithText("Required by Namecheap").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.publicIpv4.address").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.publicIpv4.use").performClick()
        composeRule.onNodeWithText("Using this IP").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.field.username").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.field.apiKey").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.field.clientIp").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.connect").performScrollTo()
        composeRule.onNodeWithTag("registrar.connect").assertIsNotEnabled()
    }

    @Test
    fun porkbunConnectsOnlyWithKeyAndSecretAndHandsThemOverOnce() {
        var submitted: RegistrarConnectRequest? = null
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = disconnected("porkbun"), providerId = "porkbun", onConnect = { submitted = it })
            }
        }
        composeRule.onNodeWithTag("registrar.connect").performScrollTo()
        composeRule.onNodeWithTag("registrar.connect").assertIsNotEnabled()

        composeRule.onNodeWithTag("registrar.field.apiKey").performScrollTo()
        composeRule.onNodeWithTag("registrar.field.apiKey").performTextInput("pk1_temporary")
        composeRule.onNodeWithTag("registrar.connect").assertIsNotEnabled()
        composeRule.onNodeWithTag("registrar.field.apiSecret").performScrollTo().performTextInput("sk1_temporary")
        composeRule.onNodeWithTag("registrar.connect").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitForIdle()

        val request = checkNotNull(submitted)
        assertEquals("porkbun", request.providerId)
        assertEquals("pk1_temporary", request.apiKey.use { it })
        assertEquals("sk1_temporary", request.apiSecret?.use { it })
        assertFalse(request.toString().contains("temporary"))
        composeRule.onNodeWithTag("registrar.connect").assertIsNotEnabled()
    }

    @Test
    fun connectionFailureAndCancellationStatesAreVisible() {
        var cancelled = false
        var state by mutableStateOf(
            disconnected("gandi").withProvider("gandi") {
                it.copy(error = "Request failed (HTTP 401): Unauthorized")
            },
        )
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = state, providerId = "gandi", onCancel = { cancelled = true })
            }
        }

        composeRule.onNodeWithTag("registrar.connectError").performScrollTo()
        composeRule.onNodeWithText("Connection failed").assertIsDisplayed()
        composeRule.onNodeWithText("Request failed (HTTP 401): Unauthorized").assertIsDisplayed()
        state = state.withProvider("gandi") { it.copy(error = null, operation = RegistrarOperation.CONNECTING) }
        composeRule.onNodeWithTag("registrar.cancel").performScrollTo()
        composeRule.onNodeWithTag("registrar.cancel").performClick()
        assertTrue(cancelled)
        composeRule.onNodeWithContentDescription("Cancel request").assertIsDisplayed()
    }

    @Test
    fun dashboardShowsPortfolioHealthStatsAndDomainRows() {
        composeRule.setContent {
            VercelticsTheme { Screen(state = connected(), providerId = "nameDotCom") }
        }

        composeRule.onNodeWithTag("registrar.dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.portfolio").assertIsDisplayed()
        composeRule.onNodeWithText("Studio · Name.com").assertIsDisplayed()
        composeRule.onNodeWithText("EXPIRY HEALTH").assertIsDisplayed()
        composeRule.onNodeWithText("1 need attention").assertIsDisplayed()
        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.stats"))
        composeRule.onNodeWithContentDescription("Domains: 3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Attention: 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Auto renew: 2").assertIsDisplayed()
        dashboard.performScrollToNode(hasTestTag("registrar.domain.studio.example"))
        composeRule.onNodeWithTag("registrar.domain.studio.example").assertIsDisplayed()
        dashboard.performScrollToNode(hasTestTag("registrar.disconnect"))
        composeRule.onNodeWithTag("registrar.disconnect").assertIsDisplayed()
    }

    @Test
    fun searchRequestFocusesAndFiltersTheDomainList() {
        var searchRequestId by mutableStateOf(0)
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = connected(), providerId = "nameDotCom", searchFocusRequestId = searchRequestId)
            }
        }

        searchRequestId = 1
        composeRule.waitForIdle()
        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.search"))
        composeRule.onNodeWithTag("registrar.search").performTextInput("design")
        dashboard.performScrollToNode(hasTestTag("registrar.domain.studio-design.example"))
        composeRule.onNodeWithTag("registrar.domain.studio-design.example").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.domain.studio.example").assertDoesNotExist()

        composeRule.onNodeWithTag("registrar.search").performTextInput("zzz")
        dashboard.performScrollToNode(hasTestTag("registrar.empty"))
        composeRule.onNodeWithText("No matching domains").assertIsDisplayed()
    }

    @Test
    fun domainDetailShowsExpiryPropertiesAndNameservers() {
        var openedUrl: String? = null
        var dashboardOpened = false
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    state = connected().copy(selectedProviderId = "nameDotCom", selectedDomainId = "studio.example"),
                    providerId = "nameDotCom",
                    onOpenUrl = { openedUrl = it },
                    onOpenDashboard = { dashboardOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("registrar.domainDetail").assertIsDisplayed()
        composeRule.onNodeWithText("days left").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.detail.openDomain").performClick()
        assertEquals("https://studio.example", openedUrl)
        composeRule.onNodeWithTag("registrar.detail.openRegistrar").performClick()
        assertTrue(dashboardOpened)
        val detail = composeRule.onNodeWithTag("registrar.domainDetail")
        detail.performScrollToNode(hasText("Auto renewal"))
        composeRule.onNodeWithText("Transfer lock").assertIsDisplayed()
        composeRule.onNodeWithText("WHOIS privacy").assertIsDisplayed()
        detail.performScrollToNode(hasTestTag("registrar.detail.nameservers"))
        composeRule.onNodeWithText("maya.ns.cloudflare.com").assertIsDisplayed()
    }

    @Test
    fun savedUnavailableShowsRecoveryAndDisconnectConfirmation() {
        var state by mutableStateOf(
            RegistrarUiState().withProvider("dynadot") {
                it.copy(
                    status = RegistrarConnectionStatus.SAVED_UNAVAILABLE,
                    error = "The saved Dynadot connection could not be opened. It was not deleted or replaced.",
                )
            },
        )
        var requestedDisconnect = false
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = state, providerId = "dynadot", onRequestDisconnect = { requestedDisconnect = true })
            }
        }

        composeRule.onNodeWithTag("registrar.savedUnavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Saved Dynadot account").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.recovery.refresh").assertIsEnabled()
        composeRule.onNodeWithTag("registrar.disconnect").performClick()
        assertTrue(requestedDisconnect)
        state = state.copy(disconnectConfirmationProviderId = "dynadot")
        composeRule.onNodeWithTag("registrar.disconnectDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect Dynadot?").assertIsDisplayed()
    }

    @Test
    fun connectionCardsListOnlyConnectedRegistrars() {
        var opened: String? = null
        composeRule.setContent {
            VercelticsTheme {
                RegistrarConnectionCards(
                    state = connected().withProvider("gandi") {
                        it.copy(status = RegistrarConnectionStatus.SAVED_UNAVAILABLE, error = "Needs attention")
                    }.withProvider("porkbun") { it.copy(status = RegistrarConnectionStatus.DISCONNECTED) },
                    onOpenProvider = { opened = it },
                )
            }
        }

        composeRule.onNodeWithTag("workspace.registrars.connection.nameDotCom").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.registrars.connection.gandi").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.registrars.connection.porkbun").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace.registrars.connection.gandi").performClick()
        assertEquals("gandi", opened)
    }

    @Test
    fun unknownProviderShowsConnectionUnavailable() {
        composeRule.setContent { VercelticsTheme { Screen(state = RegistrarUiState(), providerId = "vercel") } }

        composeRule.onNodeWithTag("registrar.unsupported").assertIsDisplayed()
        composeRule.onNodeWithText("Connection unavailable").assertIsDisplayed()
    }

    @Test
    fun routeRequiresProForDomainDetailsAndTheRegistrarDashboard() {
        var paywallRequests = 0
        val viewModel = RegistrarViewModel(ConnectedGateway, SavedStateHandle())
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) { paywallRequests += 1 }
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    RegistrarRoute(viewModel = viewModel, providerId = "nameDotCom", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("nameDotCom").status == RegistrarConnectionStatus.CONNECTED
        }

        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.openDashboard"))
        composeRule.onNodeWithTag("registrar.openDashboard").performClick()
        dashboard.performScrollToNode(hasTestTag("registrar.domain.studio.example"))
        composeRule.onNodeWithTag("registrar.domain.studio.example").performClick()
        composeRule.waitForIdle()

        assertEquals(2, paywallRequests)
        assertNull(viewModel.uiState.value.selectedDomainId)
        composeRule.onNodeWithTag("registrar.domainDetail").assertDoesNotExist()
    }

    @Test
    fun routeClosesARestoredDetailOnceProIsConfirmedLocked() {
        val handle = SavedStateHandle(
            mapOf(
                RegistrarViewModel.SELECTED_PROVIDER_ID to "nameDotCom",
                RegistrarViewModel.SELECTED_DOMAIN_ID to "studio.example",
            ),
        )
        val viewModel = RegistrarViewModel(ConnectedGateway, handle)
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) {}
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    RegistrarRoute(viewModel = viewModel, providerId = "nameDotCom", onBack = {})
                }
            }
        }

        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("nameDotCom").status == RegistrarConnectionStatus.CONNECTED &&
                viewModel.uiState.value.selectedDomainId == null
        }
        composeRule.onNodeWithTag("registrar.dashboard").assertIsDisplayed()
        assertNull(handle.get<String>(RegistrarViewModel.SELECTED_DOMAIN_ID))
    }

    @Test
    fun routeOpensDomainDetailsForProUsersAndBackReturnsToThePortfolio() {
        var leftRoute = false
        val viewModel = RegistrarViewModel(ConnectedGateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides ProAccess.Unlocked) {
                    RegistrarRoute(viewModel = viewModel, providerId = "nameDotCom", onBack = { leftRoute = true })
                }
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("nameDotCom").status == RegistrarConnectionStatus.CONNECTED
        }

        composeRule.onNodeWithTag("registrar.dashboard")
            .performScrollToNode(hasTestTag("registrar.domain.studio.example"))
        composeRule.onNodeWithTag("registrar.domain.studio.example").performClick()
        composeRule.onNodeWithTag("registrar.domainDetail").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.back").performClick()
        composeRule.onNodeWithTag("registrar.dashboard").assertIsDisplayed()
        assertFalse(leftRoute)
        composeRule.onNodeWithTag("registrar.back").performClick()
        assertTrue(leftRoute)
    }

    @androidx.compose.runtime.Composable
    private fun Screen(
        state: RegistrarUiState,
        providerId: String,
        onConnect: (RegistrarConnectRequest) -> Unit = {},
        onCancel: () -> Unit = {},
        onOpenUrl: (String) -> Unit = {},
        onOpenDashboard: () -> Unit = {},
        onRequestDisconnect: () -> Unit = {},
        searchFocusRequestId: Int = 0,
    ) {
        RegistrarScreen(
            state = state,
            providerId = providerId,
            onBack = {},
            onConnect = onConnect,
            onCancel = onCancel,
            onRefresh = {},
            onOpenDomain = {},
            onOpenDashboard = onOpenDashboard,
            onOpenUrl = onOpenUrl,
            onRequestDisconnect = onRequestDisconnect,
            onDismissDisconnect = {},
            onConfirmDisconnect = {},
            onDetectPublicIpv4 = {},
            searchFocusRequestId = searchFocusRequestId,
            nowMillis = NOW,
        )
    }

    private object ConnectedGateway : RegistrarUiGateway {
        override suspend fun restore() = Result.success(
            RegistrarRestoreUi(mapOf("nameDotCom" to RegistrarProviderRestoreUi.Available(DASHBOARD))),
        )

        override suspend fun connect(request: RegistrarConnectRequest) = Result.success(DASHBOARD)
        override suspend fun refresh(providerId: String) = Result.success(DASHBOARD)
        override suspend fun disconnect(providerId: String) = Result.success(Unit)
        override suspend fun detectPublicIpv4() = Result.success("8.8.4.4")
    }

    private companion object {
        val NOW = System.currentTimeMillis()
        const val DAY = 86_400_000L

        val DASHBOARD = RegistrarDashboardUi(
            account = RegistrarAccountUi("nameDotCom", "Studio · Name.com"),
            domains = listOf(
                domain("edge-tools.example", 92, autoRenew = false),
                domain("studio-design.example", 18, autoRenew = true),
                domain("studio.example", 284, autoRenew = true),
            ),
            inventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = NOW,
            cacheState = RegistrarCacheState.LIVE,
        )

        fun domain(name: String, days: Int, autoRenew: Boolean) = RegistrarDomainUi(
            id = name,
            name = name,
            status = "Active",
            createdAtMillis = NOW - 700 * DAY,
            expiresAtMillis = NOW + days * DAY + DAY / 2,
            autoRenew = autoRenew,
            locked = true,
            privacyEnabled = true,
            nameservers = listOf("maya.ns.cloudflare.com", "rick.ns.cloudflare.com"),
        )

        fun disconnected(providerId: String) = RegistrarUiState().withProvider(providerId) {
            it.copy(status = RegistrarConnectionStatus.DISCONNECTED)
        }

        fun connected() = RegistrarUiState().withProvider("nameDotCom") {
            it.copy(status = RegistrarConnectionStatus.CONNECTED, dashboard = DASHBOARD, savedAccount = DASHBOARD.account)
        }

        fun RegistrarUiState.withProvider(
            providerId: String,
            transform: (RegistrarProviderUiState) -> RegistrarProviderUiState,
        ) = copy(providers = providers + (providerId to transform(provider(providerId))))
    }
}
