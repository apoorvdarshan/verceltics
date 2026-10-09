package com.apoorvdarshan.verceltics.ui.netlify

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.netlify.NetlifyLinks
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Rule
import org.junit.Test

class NetlifyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disconnectedScreenExposesBrandedEphemeralCredentialFlow() {
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.DISCONNECTED,
                        operation = null,
                        routeVisible = true,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("netlify.connectionForm").assertIsDisplayed()
        composeRule.onNodeWithTag("netlify.token").assertIsDisplayed()
        composeRule.onNodeWithTag("netlify.connect").assertIsDisplayed()
        composeRule.onNodeWithText("Sites, deploys, domains and build controls")
            .assertIsDisplayed()
    }

    @Test
    fun connectFormShowsIosStepsAndAnUngatedCredentialsLink() {
        var credentialLinkOpens = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(status = NetlifyConnectionStatus.DISCONNECTED, operation = null),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    onOpenCredentialsLink = { credentialLinkOpens += 1 },
                )
            }
        }

        val form = composeRule.onNodeWithTag("netlify.connectionForm")
        form.performScrollToNode(hasTestTag("netlify.connectSteps"))
        composeRule.onNodeWithText("Connect securely").assertIsDisplayed()
        composeRule.onNodeWithText("Open Netlify’s token or API key page").assertIsDisplayed()
        composeRule.onNodeWithText("Create a token with the access you want Verceltics to use").assertIsDisplayed()
        composeRule.onNodeWithText("Paste the credentials below and connect").assertIsDisplayed()
        form.performScrollToNode(hasTestTag("netlify.credentialLink"))
        composeRule.onNodeWithTag("netlify.credentialLink")
            .assertTextContains("OPEN NETLIFY CREDENTIALS")
            .performClick()
        composeRule.runOnIdle { assertEquals(1, credentialLinkOpens) }
    }

    @Test
    fun siteRowsShowStatusBadgesAndDetailIsTitledWithTheSite() {
        setConnectedScreen(
            NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = DASHBOARD,
                savedAccount = DASHBOARD.account,
                operation = null,
            ),
        )
        composeRule.onNodeWithTag("netlify.dashboard").performScrollToNode(hasTestTag("netlify.site.${SITE.id}"))
        composeRule.onNodeWithTag("netlify.site.${SITE.id}.status", useUnmergedTree = true).assertIsDisplayed()
        // The pill's label is a child Text of the tagged badge (the row merges both for accessibility).
        composeRule.onNode(
            hasText("CURRENT") and hasAnyAncestor(hasTestTag("netlify.site.${SITE.id}.status")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun siteDetailHeaderCarriesNameStatusAndIosActions() {
        val opened = mutableListOf<String>()
        var redeployRequests = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                        operation = null,
                        selectedSiteId = SITE.id,
                        selectedSiteWorkspace = PARTIAL_SITE_WORKSPACE,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    onOpenExternalLink = { opened += it },
                    onRequestRedeploy = { redeployRequests += 1 },
                )
            }
        }

        // The toolbar and the header both carry the site name (iOS navigationTitle + header).
        assertTrue(composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().size >= 2)
        composeRule.onNodeWithTag("netlify.siteTitle").assertTextContains("Example")
        composeRule.onNode(
            hasText("CURRENT") and hasAnyAncestor(hasTestTag("netlify.siteStatus")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("netlify.openSite").performClick()
        composeRule.onNodeWithTag("netlify.openSiteDashboard").performClick()
        composeRule.onNodeWithTag("netlify.redeploy").assertTextContains("REDEPLOY").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf("https://example.netlify.app", "https://app.netlify.com/sites/Example/overview"),
                opened,
            )
            assertEquals(1, redeployRequests)
        }
    }

    @Test
    fun redeployConfirmationIsAThemedDialogThatOnlyConfirmsOnTap() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                        operation = null,
                        selectedSiteId = SITE.id,
                        selectedSiteWorkspace = PARTIAL_SITE_WORKSPACE,
                        showRedeployConfirmation = true,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    onDismissRedeploy = { dismissed += 1 },
                    onConfirmRedeploy = { confirmed += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("netlify.redeployDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Redeploy Example?").assertIsDisplayed()
        composeRule.onNodeWithText("This sends a real write request to Netlify.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmed) }
        composeRule.onNodeWithText("CANCEL").performClick()
        composeRule.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, confirmed)
        }
    }

    @Test
    fun redeployOutcomeIsShownOnTheSite() {
        setConnectedScreen(
            NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = DASHBOARD,
                savedAccount = DASHBOARD.account,
                operation = null,
                selectedSiteId = SITE.id,
                selectedSiteWorkspace = PARTIAL_SITE_WORKSPACE,
                redeployMessage = "Redeploy request accepted.",
            ),
        )
        composeRule.onNodeWithTag("netlify.siteDetail").performScrollToNode(hasTestTag("netlify.redeploySuccess"))
        composeRule.onNodeWithText("Redeploy request accepted.").assertIsDisplayed()
    }

    @Test
    fun routeGatesDashboardLinkBehindProAndOpensItForPro() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        var paywallRequests = 0
        var unlocked = false
        val access = ProAccess(isUnlocked = false, isConfirmedLocked = false) { onUnlocked ->
            paywallRequests += 1
            if (unlocked) onUnlocked()
        }
        val viewModel = NetlifyViewModel(RouteGateway, SavedStateHandle())
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides access, LocalUriHandler provides uriHandler) {
                    NetlifyRoute(viewModel = viewModel, onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.status == NetlifyConnectionStatus.CONNECTED }
        val dashboard = composeRule.onNodeWithTag("netlify.dashboard")
        dashboard.performScrollToNode(hasTestTag("netlify.openDashboard"))
        composeRule.onNodeWithTag("netlify.openDashboard").performClick()
        composeRule.runOnIdle {
            assertEquals(1, paywallRequests)
            assertTrue(opened.isEmpty())
        }

        // Completing the purchase resumes the tap and opens Netlify's dashboard.
        unlocked = true
        composeRule.onNodeWithTag("netlify.openDashboard").performClick()
        composeRule.runOnIdle {
            assertEquals(2, paywallRequests)
            assertEquals(listOf(NetlifyLinks.DASHBOARD_URL), opened)
        }
    }

    private object RouteGateway : NetlifyUiGateway {
        override suspend fun restore(): Result<NetlifyRestoreUi> = Result.success(NetlifyRestoreUi.Available(DASHBOARD))
        override suspend fun connect(personalToken: com.apoorvdarshan.verceltics.data.account.SecretValue) =
            Result.success(DASHBOARD)
        override suspend fun refresh() = Result.success(DASHBOARD)
        override suspend fun loadSite(siteId: String) = Result.success(PARTIAL_SITE_WORKSPACE)
        override suspend fun disconnect() = Result.success(Unit)
    }

    @Test
    fun partialSiteWorkspaceKeepsIndependentResourceWarningsVisible() {
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                        operation = null,
                        selectedSiteId = SITE.id,
                        selectedSiteWorkspace = PARTIAL_SITE_WORKSPACE,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        val siteDetail = composeRule.onNodeWithTag("netlify.siteDetail").assertIsDisplayed()
        composeRule.onNodeWithText("Domains & build controls").assertIsDisplayed()
        siteDetail.performScrollToNode(hasText("Repository  https://github.com/example/project"))
        composeRule.onNodeWithText("Repository  https://github.com/example/project")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasText("Repository path  apps/site"))
        composeRule.onNodeWithText("Repository path  apps/site")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasText("Allowed branches  main, preview"))
        composeRule.onNodeWithText("Allowed branches  main, preview")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasTestTag("netlify.publishedDeployment.published-1"))
        composeRule.onNodeWithTag("netlify.publishedDeployment.published-1")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasText("Deploy history is incomplete."))
        composeRule.onNodeWithText("Deploy history is incomplete.")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasTestTag("netlify.deploy.deploy-1"))
        composeRule.onNodeWithTag("netlify.deploy.deploy-1")
            .assertIsDisplayed()
        siteDetail.performScrollToNode(hasText("Build history is unavailable."))
        composeRule.onNodeWithText("Build history is unavailable.")
            .assertIsDisplayed()
    }

    @Test
    fun searchRequestFocusesAndFiltersTheSiteList() {
        var searchRequestId by mutableIntStateOf(0)
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                        operation = null,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    searchFocusRequestId = searchRequestId,
                )
            }
        }

        composeRule.runOnIdle { searchRequestId += 1 }
        composeRule.onNodeWithTag("netlify.search").assertIsFocused()
        composeRule.onNodeWithTag("netlify.search").performTextInput("missing")
        composeRule.onNodeWithText("No Netlify sites match “missing”.").assertIsDisplayed()
    }

    @Test
    fun disconnectConfirmationUsesModalThemedDialog() {
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = NetlifyUiState(
                        status = NetlifyConnectionStatus.CONNECTED,
                        dashboard = DASHBOARD,
                        savedAccount = DASHBOARD.account,
                        operation = null,
                        showDisconnectConfirmation = true,
                    ),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("netlify.disconnectDialog").assertIsDisplayed()
        // Like iOS, the confirmation names the saved account it removes from this device.
        composeRule.onNodeWithText("Remove Example Account?").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ACCOUNT").assertIsDisplayed()
    }

    @Test
    fun headerActionIsDisabledDuringSiteLoadWithoutRootOperation() {
        setConnectedScreen(
            NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = DASHBOARD,
                savedAccount = DASHBOARD.account,
                operation = null,
                selectedSiteId = SITE.id,
                isLoadingSite = true,
            ),
        )

        composeRule.onNodeWithTag("netlify.refreshOrCancel").assertIsNotEnabled()
    }

    @Test
    fun headerActionIsDisabledDuringNonCancelableOperationWithoutSiteLoad() {
        setConnectedScreen(
            NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = DASHBOARD,
                savedAccount = DASHBOARD.account,
                operation = NetlifyOperation.DISCONNECTING,
                isLoadingSite = false,
            ),
        )

        composeRule.onNodeWithTag("netlify.refreshOrCancel").assertIsNotEnabled()
    }

    @Test
    fun headerActionRemainsEnabledForCancelableOperation() {
        setConnectedScreen(
            NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = DASHBOARD,
                savedAccount = DASHBOARD.account,
                operation = NetlifyOperation.REFRESHING,
                isLoadingSite = false,
            ),
        )

        composeRule.onNodeWithTag("netlify.refreshOrCancel").assertIsEnabled()
    }

    @Test
    fun connectedDashboardAccountMenuSwitchesAddsAndRemovesAccounts() {
        val switched = mutableListOf<String>()
        var added = 0
        var removeAll = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = connectedWithAccounts(),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    accountActions = NetlifyAccountActions(
                        onSwitchAccount = { switched += it },
                        onAddAccount = { added += 1 },
                        onRequestRemoveAll = { removeAll += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("netlify.accountMenuButton").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("netlify.account.second").performClick()
        composeRule.onNodeWithTag("netlify.accountMenuButton").performClick()
        composeRule.onNodeWithTag("netlify.addAccount").performClick()
        composeRule.onNodeWithTag("netlify.accountMenuButton").performClick()
        composeRule.onNodeWithTag("netlify.removeAllAccounts").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("second"), switched)
            assertEquals(1, added)
            assertEquals(1, removeAll)
        }
    }

    @Test
    fun addingAnAccountShowsTheTokenFormWithABackButton() {
        var cancelled = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = connectedWithAccounts().copy(isAddingAccount = true, routeVisible = true),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    accountActions = NetlifyAccountActions(onCancelAddAccount = { cancelled += 1 }),
                )
            }
        }

        val form = composeRule.onNodeWithTag("netlify.connectionForm").assertIsDisplayed()
        composeRule.onAllNodesWithTag("netlify.accountMenuButton").assertCountEquals(0)
        composeRule.onAllNodesWithTag("netlify.dashboard").assertCountEquals(0)
        form.performScrollToNode(hasTestTag("netlify.cancelAddAccount"))
        composeRule.onNodeWithTag("netlify.cancelAddAccount").performClick()

        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun removeCurrentConfirmationKeepsTheExistingDialogTag() {
        var confirmed = 0
        composeRule.setContent {
            VercelticsTheme {
                NetlifyScreen(
                    state = connectedWithAccounts().copy(showDisconnectConfirmation = true),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onOpenSite = {},
                    onRefreshSite = {},
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = { confirmed += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("netlify.disconnectDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Remove Example Account?").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ACCOUNT").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    private fun connectedWithAccounts() = NetlifyUiState(
        status = NetlifyConnectionStatus.CONNECTED,
        dashboard = DASHBOARD,
        savedAccount = DASHBOARD.account,
        operation = null,
        accounts = listOf(
            ProviderAccountUi("primary", "Example Account", "owner@example.com", isActive = true),
            ProviderAccountUi("second", "Second Account", "second@example.com"),
        ),
    )

    private fun setConnectedScreen(state: NetlifyUiState) {
        composeRule.setContent {
            VercelticsTheme {
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
                )
            }
        }
    }

    private companion object {
        val SITE = NetlifySiteUi(
            id = "site-1",
            name = "Example",
            subtitle = "example.netlify.app",
            url = "https://example.netlify.app",
            status = "current",
            updatedAtMillis = 42L,
        )
        val DASHBOARD = NetlifyDashboardUi(
            account = NetlifyAccountUi("account-1", "Example Account", "owner@example.com"),
            sites = listOf(SITE),
            loadedSiteCount = 1,
            providerInventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = 42L,
            cacheState = NetlifyCacheState.LIVE,
        )
        val PARTIAL_SITE_WORKSPACE = NetlifySiteWorkspaceUi(
            siteId = SITE.id,
            details = NetlifyResourceUi.Available(
                NetlifySiteDetailsUi(
                    site = SITE,
                    domains = listOf(NetlifyDomainUi("example.com", "CUSTOM")),
                    buildControls = NetlifyBuildControlsUi(
                        buildsStopped = false,
                        repositoryUrl = "https://github.com/example/project",
                        repositoryPath = "apps/site",
                        repositoryBranch = "main",
                        baseDirectory = null,
                        publishDirectory = "dist",
                        functionsDirectory = null,
                        buildCommand = "npm run build",
                        allowedBranches = listOf("main", "preview"),
                        provider = "github",
                    ),
                    publishedDeployment = NetlifyDeploymentUi(
                        id = "published-1",
                        title = "Published deploy",
                        status = "ready",
                        createdAtMillis = 42L,
                        url = "https://example.netlify.app",
                        branch = "main",
                        commitMessage = "Publish",
                    ),
                ),
            ),
            deployments = NetlifyCollectionUi(
                items = listOf(
                    NetlifyDeploymentUi(
                        id = "deploy-1",
                        title = "Production deploy",
                        status = "ready",
                        createdAtMillis = 42L,
                        url = null,
                        branch = "main",
                        commitMessage = null,
                    ),
                ),
                loadedItemCount = 1,
                providerCollectionComplete = false,
                warning = "Deploy history is incomplete.",
            ),
            builds = NetlifyCollectionUi(
                items = emptyList(),
                loadedItemCount = 0,
                providerCollectionComplete = false,
                warning = "Build history is unavailable.",
            ),
        )
    }
}
