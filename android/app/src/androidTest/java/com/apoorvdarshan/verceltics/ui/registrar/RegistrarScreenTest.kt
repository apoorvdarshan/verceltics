package com.apoorvdarshan.verceltics.ui.registrar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
        state = state.copy(
            removalConfirmation = RegistrarRemovalConfirmation("dynadot", RegistrarRemovalScope.CURRENT_ACCOUNT),
        )
        composeRule.onNodeWithTag("registrar.disconnectDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect Dynadot?").assertIsDisplayed()
    }

    // region Multiple accounts

    @Test
    fun accountMenuListsEverySavedAccountAndSwitches() {
        var switchedTo: String? = null
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = twoAccounts(), providerId = "nameDotCom", onSwitchAccount = { switchedTo = it })
            }
        }

        composeRule.onNodeWithTag("registrar.accountMenu.button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("registrar.accountMenu").assertIsDisplayed()
        composeRule.onNodeWithText("NAME.COM ACCOUNTS").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.accountMenu.account.studio-id").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("registrar.accountMenu.account.personal-id").assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithTag("registrar.accountMenu.account.personal-id").performClick()

        assertEquals("personal-id", switchedTo)
        composeRule.onNodeWithTag("registrar.accountMenu").assertDoesNotExist()
    }

    @Test
    fun accountMenuAddsAccountsAndRequestsConfirmedRemovals() {
        var added = 0
        var removeCurrent = 0
        var removeAll = 0
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    state = twoAccounts(),
                    providerId = "nameDotCom",
                    onAddAccount = { added += 1 },
                    onRequestDisconnect = { removeCurrent += 1 },
                    onRequestRemoveAll = { removeAll += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("registrar.accountMenu.button").performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.add").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.button").performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.removeCurrent").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.button").performClick()
        composeRule.onNodeWithText("Remove all Name.com accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.accountMenu.removeAll").performClick()

        assertEquals(1, added)
        assertEquals(1, removeCurrent)
        assertEquals(1, removeAll)
    }

    @Test
    fun singleAccountMenuOffersNoRemoveAll() {
        composeRule.setContent {
            VercelticsTheme { Screen(state = connected(), providerId = "nameDotCom") }
        }

        composeRule.onNodeWithTag("registrar.accountMenu.button").performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.add").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.accountMenu.removeCurrent").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.accountMenu.removeAll").assertDoesNotExist()
    }

    @Test
    fun removalDialogsUseCurrentAndAllAccountCopy() {
        var state by mutableStateOf(
            twoAccounts().copy(
                removalConfirmation = RegistrarRemovalConfirmation(
                    providerId = "nameDotCom",
                    scope = RegistrarRemovalScope.CURRENT_ACCOUNT,
                    accountId = "studio-id",
                    accountName = "Studio · Name.com",
                    accountCount = 2,
                ),
            ),
        )
        var confirmed = 0
        composeRule.setContent {
            VercelticsTheme {
                Screen(state = state, providerId = "nameDotCom", onConfirmDisconnect = { confirmed += 1 })
            }
        }

        composeRule.onNodeWithText("Remove Studio · Name.com?").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ACCOUNT").assertIsDisplayed()
        composeRule.onNodeWithText("KEEP ACCOUNT").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.dashboard")
            .performScrollToNode(hasTestTag("registrar.disconnect"))
        composeRule.onNodeWithText("REMOVE CURRENT ACCOUNT").assertExists()

        state = state.copy(
            removalConfirmation = RegistrarRemovalConfirmation(
                providerId = "nameDotCom",
                scope = RegistrarRemovalScope.ALL_ACCOUNTS,
                accountId = "studio-id",
                accountName = "Studio · Name.com",
                accountCount = 2,
            ),
        )
        composeRule.onNodeWithText("Remove all Name.com accounts?").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ALL").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun addAccountModeShowsTheConnectionFormWithoutDisconnecting() {
        var cancelled = 0
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    state = twoAccounts().withProvider("nameDotCom") { it.copy(isAddingAccount = true) },
                    providerId = "nameDotCom",
                    onCancelAddAccount = { cancelled += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("registrar.connectionForm").assertIsDisplayed()
        composeRule.onNodeWithText("Add Name.com account").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.addAccount.explanation").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.dashboard").assertDoesNotExist()
        composeRule.onNodeWithTag("registrar.accountMenu.button").assertDoesNotExist()
        composeRule.onNodeWithTag("registrar.connect").performScrollTo()
        composeRule.onNodeWithText("ADD ACCOUNT").assertIsDisplayed()
        composeRule.onNodeWithTag("registrar.addAccount.cancel").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun routeAddsAnAccountBehindTheSecureWindowAndBackReturnsToThePortfolio() {
        val viewModel = RegistrarViewModel(ConnectedGateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides ProAccess.Unlocked) {
                    RegistrarRoute(viewModel = viewModel, providerId = "nameDotCom", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("nameDotCom").status == RegistrarConnectionStatus.CONNECTED
        }
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        composeRule.onNodeWithTag("registrar.accountMenu.button").performClick()
        composeRule.onNodeWithTag("registrar.accountMenu.add").performClick()

        composeRule.onNodeWithTag("registrar.connectionForm").assertIsDisplayed()
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        assertEquals(RegistrarConnectionStatus.CONNECTED, viewModel.uiState.value.provider("nameDotCom").status)
        composeRule.onNodeWithTag("registrar.back").performClick()
        composeRule.onNodeWithTag("registrar.dashboard").assertIsDisplayed()
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
    }

    // endregion

    // region Pull to refresh, tablet layout and copy

    @Test
    fun pullingTheDashboardRefreshes() {
        var refreshes = 0
        composeRule.setContent {
            VercelticsTheme { Screen(state = connected(), providerId = "nameDotCom", onRefresh = { refreshes += 1 }) }
        }

        composeRule.onNodeWithTag("registrar.pullToRefresh").assertExists()
        composeRule.onNodeWithTag("registrar.dashboard").performTouchInput { swipeDown(durationMillis = 600) }
        composeRule.waitForIdle()

        assertEquals(1, refreshes)
    }

    @Test
    fun domainDetailIsPullToRefreshAndExplainsMissingNameservers() {
        var refreshes = 0
        val withoutNameservers = DASHBOARD.copy(
            domains = DASHBOARD.domains.map { if (it.id == "studio.example") it.copy(nameservers = emptyList()) else it },
        )
        composeRule.setContent {
            VercelticsTheme {
                Screen(
                    state = RegistrarUiState(selectedProviderId = "nameDotCom", selectedDomainId = "studio.example")
                        .withProvider("nameDotCom") {
                            it.copy(
                                status = RegistrarConnectionStatus.CONNECTED,
                                dashboard = withoutNameservers,
                                savedAccount = withoutNameservers.account,
                            )
                        },
                    providerId = "nameDotCom",
                    onRefresh = { refreshes += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("registrar.detail.pullToRefresh").assertExists()
        composeRule.onNodeWithTag("registrar.accountMenu.button").assertDoesNotExist()
        composeRule.onNodeWithTag("registrar.domainDetail").performTouchInput { swipeDown(durationMillis = 600) }
        composeRule.waitForIdle()
        assertEquals(1, refreshes)
        composeRule.onNodeWithTag("registrar.domainDetail")
            .performScrollToNode(hasTestTag("registrar.detail.nameservers.empty"))
        composeRule.onNodeWithText(
            "The list endpoint did not include nameservers. Open the Complete API explorer for the domain detail or DNS route.",
        ).assertIsDisplayed()
    }

    @Test
    fun tabletDashboardUsesADomainGridAndThreeStatColumns() {
        composeRule.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 1400.dp))) {
                    Screen(state = connected(), providerId = "nameDotCom")
                }
            }
        }

        val dashboard = composeRule.onNodeWithTag("registrar.dashboard")
        dashboard.performScrollToNode(hasTestTag("registrar.stats"))
        val domains = composeRule.onNodeWithTag("registrar.stat.domains").fetchSemanticsNode().boundsInRoot
        val autoRenew = composeRule.onNodeWithTag("registrar.stat.autoRenew").fetchSemanticsNode().boundsInRoot
        assertEquals(domains.top, autoRenew.top, 1f)
        assertTrue(autoRenew.left > domains.right)

        dashboard.performScrollToNode(hasTestTag("registrar.domain.edge-tools.example"))
        composeRule.onAllNodesWithTag("registrar.domainGridRow").assertCountEquals(2)
        val first = composeRule.onNodeWithTag("registrar.domain.edge-tools.example").fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("registrar.domain.studio-design.example").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.right)
    }

    @Test
    fun phoneDashboardKeepsOneDomainPerRow() {
        composeRule.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 1400.dp))) {
                    Screen(state = connected(), providerId = "nameDotCom")
                }
            }
        }

        composeRule.onNodeWithTag("registrar.dashboard")
            .performScrollToNode(hasTestTag("registrar.domain.studio-design.example"))
        composeRule.onAllNodesWithTag("registrar.domainGridRow").assertCountEquals(0)
        val first = composeRule.onNodeWithTag("registrar.domain.edge-tools.example").fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("registrar.domain.studio-design.example").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top >= first.bottom)
    }

    @Test
    fun tabletDomainDetailPlacesPropertiesBesideNameservers() {
        composeRule.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 1400.dp))) {
                    Screen(
                        state = connected().copy(selectedProviderId = "nameDotCom", selectedDomainId = "studio.example"),
                        providerId = "nameDotCom",
                    )
                }
            }
        }

        composeRule.onNodeWithTag("registrar.domainDetail")
            .performScrollToNode(hasTestTag("registrar.detail.panels"))
        val properties = composeRule.onNodeWithTag("registrar.detail.properties").fetchSemanticsNode().boundsInRoot
        val nameservers = composeRule.onNodeWithTag("registrar.detail.nameservers").fetchSemanticsNode().boundsInRoot
        assertEquals(properties.top, nameservers.top, 1f)
        assertTrue(nameservers.left > properties.right)
    }

    // endregion

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
        onRefresh: () -> Unit = {},
        onOpenUrl: (String) -> Unit = {},
        onOpenDashboard: () -> Unit = {},
        onRequestDisconnect: () -> Unit = {},
        onConfirmDisconnect: () -> Unit = {},
        onSwitchAccount: (String) -> Unit = {},
        onAddAccount: () -> Unit = {},
        onCancelAddAccount: () -> Unit = {},
        onRequestRemoveAll: () -> Unit = {},
        searchFocusRequestId: Int = 0,
    ) {
        RegistrarScreen(
            state = state,
            providerId = providerId,
            onBack = {},
            onConnect = onConnect,
            onCancel = onCancel,
            onRefresh = onRefresh,
            onOpenDomain = {},
            onOpenDashboard = onOpenDashboard,
            onOpenUrl = onOpenUrl,
            onRequestDisconnect = onRequestDisconnect,
            onDismissDisconnect = {},
            onConfirmDisconnect = onConfirmDisconnect,
            onDetectPublicIpv4 = {},
            searchFocusRequestId = searchFocusRequestId,
            nowMillis = NOW,
            onSwitchAccount = onSwitchAccount,
            onAddAccount = onAddAccount,
            onCancelAddAccount = onCancelAddAccount,
            onRequestRemoveAll = onRequestRemoveAll,
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

        val STUDIO = RegistrarAccountUi("nameDotCom", "Studio · Name.com", "studio-id")
        val PERSONAL = RegistrarAccountUi("nameDotCom", "Personal · Name.com", "personal-id")

        /** Name.com with two saved accounts; Studio is active. */
        fun twoAccounts(): RegistrarUiState {
            val dashboard = DASHBOARD.copy(account = STUDIO, accounts = listOf(STUDIO, PERSONAL))
            return RegistrarUiState().withProvider("nameDotCom") {
                it.copy(
                    status = RegistrarConnectionStatus.CONNECTED,
                    dashboard = dashboard,
                    savedAccount = dashboard.account,
                    accounts = dashboard.accounts,
                )
            }
        }

        fun RegistrarUiState.withProvider(
            providerId: String,
            transform: (RegistrarProviderUiState) -> RegistrarProviderUiState,
        ) = copy(providers = providers + (providerId to transform(provider(providerId))))
    }
}
