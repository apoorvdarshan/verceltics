package com.apoorvdarshan.verceltics.ui.hosting

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostingProviderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disconnectedRailwayShowsEphemeralTokenFormWithTokenTypeChoice() {
        setScreen("railway", disconnected("railway"))

        composeRule.onNodeWithTag("hosting.railway.connectionForm").assertIsDisplayed()
        composeRule.onNodeWithText("Connect Railway").assertIsDisplayed()
        val form = composeRule.onNodeWithTag("hosting.railway.connectionForm")
        form.performScrollToNode(hasTestTag("hosting.railway.token"))
        composeRule.onNodeWithTag("hosting.railway.token").assertIsDisplayed()
        form.performScrollToNode(hasTestTag("hosting.railway.tokenType.project"))
        composeRule.onNodeWithTag("hosting.railway.tokenType.account").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.railway.tokenType.project").assertIsDisplayed()
        form.performScrollToNode(hasTestTag("hosting.railway.connect"))
        composeRule.onNodeWithTag("hosting.railway.connect").assertIsNotEnabled()
        form.performScrollToNode(hasTestTag("hosting.railway.credentialLink"))
        composeRule.onNodeWithTag("hosting.railway.credentialLink").assertIsDisplayed()
    }

    @Test
    fun firebaseFormTakesAProjectIdAndContinuesWithGoogle() {
        var submitted: HostingCredentials? = null
        setScreen("firebase", disconnected("firebase"), HostingProviderScreenCallbacks(onConnect = { submitted = it }))

        val form = composeRule.onNodeWithTag("hosting.firebase.connectionForm")
        form.performScrollToNode(hasTestTag("hosting.firebase.projectId"))
        composeRule.onNodeWithTag("hosting.firebase.projectId").performTextInput("studio-prod")
        form.performScrollToNode(hasTestTag("hosting.firebase.connect"))
        composeRule.onNodeWithText("CONTINUE WITH GOOGLE").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.firebase.connect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("studio-prod", (submitted as HostingCredentials.Firebase).projectId)
        }
    }

    @Test
    fun awsFormShowsEveryCredentialField() {
        setScreen("awsAmplify", disconnected("awsAmplify"))

        val form = composeRule.onNodeWithTag("hosting.awsAmplify.connectionForm")
        listOf(
            "hosting.awsAmplify.accessKeyId",
            "hosting.awsAmplify.secretAccessKey",
            "hosting.awsAmplify.region",
            "hosting.awsAmplify.sessionToken",
        ).forEach { tag ->
            form.performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        form.performScrollToNode(hasTestTag("hosting.awsAmplify.connect"))
        composeRule.onNodeWithTag("hosting.awsAmplify.connect").assertIsNotEnabled()
    }

    @Test
    fun dashboardListsResourcesSearchesAndRoutesProActions() {
        val opened = mutableListOf<String>()
        val dashboards = mutableListOf<String>()
        var disconnectRequests = 0
        setScreen(
            "render",
            connected("render"),
            HostingProviderScreenCallbacks(
                onOpenResource = { opened += it },
                onOpenDashboard = { dashboards += it },
                onRequestDisconnect = { disconnectRequests += 1 },
            ),
        )

        val dashboard = composeRule.onNodeWithTag("hosting.render.dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.render.summary").assertIsDisplayed()
        dashboard.performScrollToNode(hasTestTag("hosting.render.openDashboard"))
        composeRule.onNodeWithTag("hosting.render.openDashboard").performClick()
        dashboard.performScrollToNode(hasTestTag("hosting.render.resource.render-1"))
        composeRule.onNodeWithTag("hosting.render.resource.render-1").performClick()
        dashboard.performScrollToNode(hasTestTag("hosting.render.disconnect"))
        composeRule.onNodeWithTag("hosting.render.disconnect").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("render-1"), opened)
            assertEquals(listOf("https://console.example/render"), dashboards)
            assertEquals(1, disconnectRequests)
        }

        dashboard.performScrollToNode(hasTestTag("hosting.render.search"))
        composeRule.onNodeWithTag("hosting.render.search").performTextInput("missing")
        dashboard.performScrollToNode(hasTestTag("hosting.render.empty"))
        composeRule.onNodeWithText("No matching resources").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing matches “missing”.").assertIsDisplayed()
    }

    @Test
    fun searchRequestFocusesTheResourceSearch() {
        var searchRequestId by mutableIntStateOf(0)
        composeRule.setContent {
            VercelticsTheme {
                HostingProviderScreen(
                    providerId = "fly",
                    state = connected("fly"),
                    callbacks = HostingProviderScreenCallbacks(),
                    searchFocusRequestId = searchRequestId,
                )
            }
        }
        composeRule.runOnIdle { searchRequestId += 1 }
        composeRule.onNodeWithTag("hosting.fly.search").assertIsFocused()
    }

    @Test
    fun resourceDetailShowsHistoryAndConfirmsTheWriteAction() {
        var actionRequests = 0
        val state = connected("railway").copy(
            selectedResourceId = "railway-1",
            resourceWorkspace = HostingResourceWorkspaceUi(
                providerId = "railway",
                resourceId = "railway-1",
                deployments = listOf(
                    HostingDeploymentUi("dep-1", "web · production", "SUCCESS", 1_759_312_800_000L, null, "main", "Ship it"),
                    HostingDeploymentUi("dep-0", "web · production", "FAILED", null, null, null, null),
                ),
                loadedDeploymentCount = 2,
            ),
        )
        setScreen(
            "railway",
            state,
            HostingProviderScreenCallbacks(onRequestPrimaryAction = { actionRequests += 1 }),
        )

        val detail = composeRule.onNodeWithTag("hosting.railway.resourceDetail").assertIsDisplayed()
        composeRule.onNodeWithText("Deployments").assertIsDisplayed()
        detail.performScrollToNode(hasTestTag("hosting.railway.deployment.dep-1"))
        composeRule.onNodeWithTag("hosting.railway.deployment.dep-1").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.railway.primaryAction").performClick()
        composeRule.runOnIdle { assertEquals(1, actionRequests) }
    }

    @Test
    fun primaryActionConfirmationNamesTheResourceAndProvider() {
        var confirmations = 0
        setScreen(
            "heroku",
            connected("heroku").copy(selectedResourceId = "heroku-1", showActionConfirmation = true),
            HostingProviderScreenCallbacks(onConfirmPrimaryAction = { confirmations += 1 }),
        )

        composeRule.onNodeWithTag("hosting.heroku.actionDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Restart heroku app 1?").assertIsDisplayed()
        composeRule.onNodeWithText("This sends a real write request to Heroku.").assertIsDisplayed()
        // The detail behind the dialog has its own RESTART button; target the dialog's.
        composeRule.onNode(hasText("RESTART") and hasAnyAncestor(hasTestTag("hosting.heroku.actionDialog"))).performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun actionOutcomeAndEmptyHistoryAreVisible() {
        setScreen(
            "fly",
            connected("fly").copy(
                selectedResourceId = "fly-1",
                actionMessage = "Restart request accepted.",
                resourceWorkspace = HostingResourceWorkspaceUi("fly", "fly-1", emptyList(), 0),
            ),
        )
        composeRule.onNodeWithText("Request accepted").assertIsDisplayed()
        composeRule.onNodeWithText("Restart request accepted.").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.fly.resourceDetail")
            .performScrollToNode(hasTestTag("hosting.fly.historyEmpty"))
        composeRule.onNodeWithText("No machines").assertIsDisplayed()
    }

    @Test
    fun savedButUnavailableConnectionOffersRefreshAndDisconnect() {
        setScreen(
            "digitalOcean",
            HostingProviderUiState(
                providerId = "digitalOcean",
                status = HostingConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = "The saved DigitalOcean connection could not be opened. It was not deleted or replaced.",
            ),
        )
        composeRule.onNodeWithTag("hosting.digitalOcean.savedUnavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.digitalOcean.recovery.refresh").assertIsEnabled()
        composeRule.onNodeWithTag("hosting.digitalOcean.disconnect").assertIsEnabled()
    }

    @Test
    fun disconnectConfirmationUsesTheThemedDialog() {
        setScreen("render", connected("render").copy(showDisconnectConfirmation = true))
        composeRule.onNodeWithTag("hosting.render.disconnectDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect Render?").assertIsDisplayed()
    }

    @Test
    fun headerRefreshIsCancelableOnlyForConnectAndRefresh() {
        setScreen("render", connected("render").copy(operation = HostingOperation.DISCONNECTING))
        composeRule.onNodeWithTag("hosting.refreshOrCancel").assertIsNotEnabled()
    }

    @Test
    fun connectionCardsListOnlyConnectedProviders() {
        val opened = mutableListOf<String>()
        val state = HostingProvidersUiState().let { base ->
            base.copy(
                providers = base.providers + mapOf(
                    "render" to connected("render"),
                    "fly" to connected("fly").copy(error = "Fly.io is temporarily unavailable."),
                    "heroku" to disconnected("heroku"),
                ),
            )
        }
        composeRule.setContent {
            VercelticsTheme { HostingProviderConnectionCards(state = state, onOpenProvider = { opened += it }) }
        }

        composeRule.onNodeWithTag("workspace.hosting.renderConnection").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.flyConnection").assertIsDisplayed()
        composeRule.onNodeWithText("ATTENTION").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.flyConnection").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("fly"), opened)
            assertTrue(state.connectedProviderIds == setOf("render", "fly"))
        }
    }

    @Test
    fun routeClosesARestoredDetailOnceProIsConfirmedLockedAndGatesNewOnes() {
        val gateway = InstrumentedHostingGateway(mapOf("render" to HostingRestoreUi.Available(dashboardUi("render"))))
        val viewModel = HostingProvidersViewModel(
            gateway,
            SavedStateHandle(mapOf(HostingProvidersViewModel.selectedResourceKey("render") to "render-1")),
        )
        var paywallRequests = 0
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) { paywallRequests += 1 }
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    HostingProviderRoute(viewModel = viewModel, providerId = "render", onBack = {})
                }
            }
        }

        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("render").status == HostingConnectionStatus.CONNECTED &&
                viewModel.uiState.value.provider("render").selectedResourceId == null
        }
        val dashboard = composeRule.onNodeWithTag("hosting.render.dashboard").assertIsDisplayed()
        dashboard.performScrollToNode(hasTestTag("hosting.render.resource.render-1"))
        composeRule.onNodeWithTag("hosting.render.resource.render-1").performClick()
        dashboard.performScrollToNode(hasTestTag("hosting.render.openDashboard"))
        composeRule.onNodeWithTag("hosting.render.openDashboard").performClick()
        composeRule.runOnIdle {
            assertEquals(2, paywallRequests)
            assertEquals(null, viewModel.uiState.value.provider("render").selectedResourceId)
        }
    }

    @Test
    fun routeForwardsFirebaseGoogleSignInRequestExactlyOnce() {
        val gateway = InstrumentedHostingGateway(emptyMap()).apply {
            connectFailure = HostingUiException("Continue with Google to connect Firebase Hosting.", requiresGoogleSignIn = true)
        }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        val requests = mutableListOf<Set<String>>()
        composeRule.setContent {
            VercelticsTheme {
                HostingProviderRoute(
                    viewModel = viewModel,
                    providerId = "firebase",
                    onBack = {},
                    onRequestGoogleSignIn = { requests += it },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("firebase").status == HostingConnectionStatus.DISCONNECTED
        }
        composeRule.runOnIdle { viewModel.connect(HostingCredentials.Firebase("studio-prod")) }
        composeRule.waitUntil(5_000) { requests.isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals(listOf(setOf("openid", "email", "https://www.googleapis.com/auth/firebase.hosting")), requests)
            assertEquals(null, viewModel.uiState.value.provider("firebase").googleSignInRequest)
        }
    }

    @Test
    fun connectedDashboardAccountMenuSwitchesAndAddsAccounts() {
        val switched = mutableListOf<String>()
        var addRequests = 0
        setScreen(
            "render",
            connected("render").copy(accounts = TWO_ACCOUNTS),
            HostingProviderScreenCallbacks(onSwitchAccount = { switched += it }, onAddAccount = { addRequests += 1 }),
        )

        composeRule.onNodeWithTag("hosting.render.accountMenuButton").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("hosting.render.account.second").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("hosting.render.accountMenuButton").performClick()
        composeRule.onNodeWithTag("hosting.render.addAccount").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("second"), switched)
            assertEquals(1, addRequests)
        }
    }

    @Test
    fun accountMenuRemovalsOpenTheirConfirmationsThroughCallbacks() {
        var removeCurrent = 0
        var removeAll = 0
        setScreen(
            "render",
            connected("render").copy(accounts = TWO_ACCOUNTS),
            HostingProviderScreenCallbacks(
                onRequestDisconnect = { removeCurrent += 1 },
                onRequestRemoveAll = { removeAll += 1 },
            ),
        )

        composeRule.onNodeWithTag("hosting.render.accountMenuButton").performClick()
        composeRule.onNodeWithTag("hosting.render.removeCurrentAccount").performClick()
        composeRule.onNodeWithTag("hosting.render.accountMenuButton").performClick()
        composeRule.onNodeWithTag("hosting.render.removeAllAccounts").performClick()

        composeRule.runOnIdle {
            assertEquals(1, removeCurrent)
            assertEquals(1, removeAll)
        }
    }

    @Test
    fun removeAllConfirmationIsShownFromStateAndConfirms() {
        var confirmed = 0
        setScreen(
            "render",
            connected("render").copy(accounts = TWO_ACCOUNTS, showRemoveAllConfirmation = true),
            HostingProviderScreenCallbacks(onConfirmRemoveAll = { confirmed += 1 }),
        )

        composeRule.onNodeWithTag("hosting.render.removeAllDialog").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ALL ACCOUNTS").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun addingAnAccountShowsTheConnectFormWithABackButtonInsteadOfTheMenu() {
        var cancelled = 0
        setScreen(
            "render",
            connected("render").copy(accounts = TWO_ACCOUNTS, isAddingAccount = true),
            HostingProviderScreenCallbacks(onCancelAddAccount = { cancelled += 1 }),
        )

        val form = composeRule.onNodeWithTag("hosting.render.connectionForm").assertIsDisplayed()
        composeRule.onNodeWithText("Add Render account").assertIsDisplayed()
        composeRule.onAllNodesWithTag("hosting.render.accountMenuButton").assertCountEquals(0)
        composeRule.onAllNodesWithTag("hosting.render.dashboard").assertCountEquals(0)
        form.performScrollToNode(hasTestTag("hosting.render.cancelAddAccount"))
        composeRule.onNodeWithTag("hosting.render.cancelAddAccount").performClick()

        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun disconnectedProviderHasNoAccountMenu() {
        setScreen("render", disconnected("render"))

        composeRule.onAllNodesWithTag("hosting.render.accountMenuButton").assertCountEquals(0)
        composeRule.onAllNodesWithTag("hosting.render.cancelAddAccount").assertCountEquals(0)
    }

    private fun setScreen(
        providerId: String,
        state: HostingProviderUiState,
        callbacks: HostingProviderScreenCallbacks = HostingProviderScreenCallbacks(),
    ) {
        composeRule.setContent {
            VercelticsTheme {
                HostingProviderScreen(providerId = providerId, state = state, callbacks = callbacks)
            }
        }
    }

    private fun disconnected(providerId: String) =
        HostingProviderUiState(providerId, status = HostingConnectionStatus.DISCONNECTED, operation = null)

    private fun connected(providerId: String) = HostingProviderUiState(
        providerId = providerId,
        status = HostingConnectionStatus.CONNECTED,
        dashboard = dashboardUi(providerId),
        savedAccount = dashboardUi(providerId).account,
        operation = null,
    )

    private class InstrumentedHostingGateway(
        private val restored: Map<String, HostingRestoreUi>,
    ) : HostingProviderUiGateway {
        var connectFailure: HostingUiException? = null

        override suspend fun restore() = Result.success(restored)

        override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> =
            connectFailure?.let { Result.failure(it) } ?: Result.success(dashboardUi(credentials.provider.id))

        override suspend fun refresh(providerId: String) = Result.success(dashboardUi(providerId))

        override suspend fun loadResource(providerId: String, resource: HostingResourceUi) =
            Result.success(HostingResourceWorkspaceUi(providerId, resource.id, emptyList(), 0))

        override suspend fun performPrimaryAction(providerId: String, resource: HostingResourceUi, latestDeploymentId: String?) =
            Result.success("Request accepted.")

        override suspend fun disconnect(providerId: String) = Result.success(Unit)
    }
}

private val TWO_ACCOUNTS = listOf(
    ProviderAccountUi("primary", "Primary account", "owner@example.com", isActive = true),
    ProviderAccountUi("second", "Second account", "second@example.com"),
)

private fun dashboardUi(providerId: String) = HostingDashboardUi(
    providerId = providerId,
    account = HostingAccountUi("$providerId-account", "$providerId account", "owner@example.com"),
    resources = (1..2).map { index ->
        HostingResourceUi(
            id = "$providerId-$index",
            name = "$providerId app $index",
            subtitle = "Subtitle $index",
            url = "https://$providerId-$index.example",
            status = if (index == 1) "Running" else "Failed",
            region = "iad",
            kind = "App",
            updatedAtMillis = 1_000L,
            dashboardUrl = "https://console.example/$providerId/$index",
        )
    },
    loadedResourceCount = 2,
    warnings = emptyList(),
    fetchedAtMillis = System.currentTimeMillis(),
    cacheState = HostingCacheState.LIVE,
    dashboardUrl = "https://console.example/$providerId",
)
