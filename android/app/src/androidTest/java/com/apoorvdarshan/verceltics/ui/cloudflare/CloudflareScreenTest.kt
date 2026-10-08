package com.apoorvdarshan.verceltics.ui.cloudflare

import androidx.compose.ui.test.assertCountEquals
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CloudflareScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectedDashboardAccountMenuSwitchesLoginsAndAddsOne() {
        val switched = mutableListOf<String>()
        var added = 0
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState().copy(savedLogins = TWO_LOGINS),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    accountActions = CloudflareAccountActions(
                        onSwitchLogin = { switched += it },
                        onAddAccount = { added += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("cloudflare.accountMenuButton").assertIsDisplayed().performClick()
        compose.onNodeWithTag("cloudflare.account.second").performClick()
        compose.onNodeWithTag("cloudflare.accountMenuButton").performClick()
        compose.onNodeWithTag("cloudflare.addAccount").performClick()

        compose.runOnIdle {
            assertEquals(listOf("second"), switched)
            assertEquals(1, added)
        }
    }

    @Test
    fun addingALoginShowsTheCredentialFormWithABackButton() {
        var cancelled = 0
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState().copy(savedLogins = TWO_LOGINS, isAddingAccount = true),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    accountActions = CloudflareAccountActions(onCancelAddAccount = { cancelled += 1 }),
                )
            }
        }

        val form = compose.onNodeWithTag("cloudflare.connectionForm").assertIsDisplayed()
        compose.onAllNodesWithTag("cloudflare.accountMenuButton").assertCountEquals(0)
        compose.onAllNodesWithTag("cloudflare.dashboard").assertCountEquals(0)
        form.performScrollToNode(hasTestTag("cloudflare.cancelAddAccount"))
        compose.onNodeWithTag("cloudflare.cancelAddAccount").performClick()

        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun removeAllConfirmationForwardsTheDestructiveAction() {
        var confirmed = 0
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState().copy(savedLogins = TWO_LOGINS, showRemoveAllConfirmation = true),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    accountActions = CloudflareAccountActions(onConfirmRemoveAll = { confirmed += 1 }),
                )
            }
        }

        compose.onNodeWithTag("cloudflare.removeAllDialog").assertIsDisplayed()
        compose.onNodeWithText("Remove all Cloudflare accounts?").assertIsDisplayed()
        compose.onNodeWithText("REMOVE ALL ACCOUNTS").performClick()

        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun dashboardSearchSectionsAndAccountPickerExposeRealInventory() {
        var selectedAccount: String? = null
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState(),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = { selectedAccount = it },
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        compose.onNodeWithTag("cloudflare.dashboard").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.search"))
        compose.onNodeWithTag("cloudflare.search").performTextInput("missing")
        compose.onAllNodesWithTag("cloudflare.zone.zone-one").assertCountEquals(0)
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasText("No zones match “missing”."))
        compose.onNodeWithText("No zones match “missing”.").assertIsDisplayed()

        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.search.clear"))
        compose.onNodeWithTag("cloudflare.search.clear").performClick()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.zone.zone-one"))
        compose.onNodeWithTag("cloudflare.zone.zone-one").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.pages.pages-one"))
        compose.onNodeWithTag("cloudflare.pages.pages-one").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.section.workers"))
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.worker.worker-one"))
        compose.onNodeWithTag("cloudflare.worker.worker-one").assertIsDisplayed()

        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.accountPicker"))
        compose.onNodeWithTag("cloudflare.accountPicker").performClick()
        compose.onNodeWithTag("cloudflare.accountSheet").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.account.account-one").assertIsSelected()
        compose.onNodeWithTag("cloudflare.account.account-two").performClick()
        compose.runOnIdle { assertEquals("account-two", selectedAccount) }
    }

    @Test
    fun contextualSearchRequestFocusesCloudflareSearch() {
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState(),
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                    searchRequestId = 1,
                )
            }
        }

        compose.onNodeWithTag("cloudflare.search").assertIsFocused()
    }

    @Test
    fun detailShowsReadOnlyZoneFieldsAndBackCallback() {
        var backed = false
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = connectedState().copy(
                        selectedResource = CloudflareResourceSelection(
                            CloudflareResourceKind.ZONE,
                            "zone-one",
                        ),
                    ),
                    onBack = { backed = true },
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        compose.onNodeWithTag("cloudflare.resourceDetail").assertIsDisplayed()
        compose.onNodeWithText("verceltics.app").assertIsDisplayed()
        compose.onNodeWithText("Pro").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.resourceDetail").performScrollToNode(
            hasText("Read-only Cloudflare data fetched for Production. No mutation controls are available."),
        )
        compose.onNodeWithText("Read-only Cloudflare data fetched for Production. No mutation controls are available.")
            .assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.back").performClick()
        compose.runOnIdle { assertEquals(true, backed) }
    }

    @Test
    fun connectionFormKeepsTokenOutOfStateAndPassesSecretOnlyOnSubmit() {
        var connected: CloudflareCredential? = null
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = CloudflareUiState(
                        status = CloudflareConnectionStatus.DISCONNECTED,
                        operation = null,
                    ),
                    onBack = {},
                    onConnect = { connected = it },
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        compose.onNodeWithTag("cloudflare.connectionForm").assertIsDisplayed()
        compose.runOnIdle { assertNull(connected) }
        compose.onNodeWithTag("cloudflare.authMode.apiToken").performClick()
        compose.onNodeWithText("Connect with scoped API token").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.token").performTextInput("scoped-token")
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.connect"))
        compose.onNodeWithTag("cloudflare.connect").performClick()
        compose.runOnIdle {
            val token = connected as CloudflareCredential.ApiToken
            assertEquals("scoped-token", token.token.use { it })
        }
    }

    @Test
    fun globalApiKeyIsTheDefaultModeAndSubmitsEmailAndKeyLikeIos() {
        var connected: CloudflareCredential? = null
        var opened: String? = null
        compose.setContent {
            VercelticsTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                        override fun openUri(uri: String) {
                            opened = uri
                        }
                    },
                ) {
                    CloudflareScreen(
                        state = CloudflareUiState(status = CloudflareConnectionStatus.DISCONNECTED, operation = null),
                        onBack = {},
                        onConnect = { connected = it },
                        onRefresh = {},
                        onCancel = {},
                        onSelectAccount = {},
                        onOpenResource = { _, _ -> },
                        onRequestDisconnect = {},
                        onDismissDisconnect = {},
                        onConfirmDisconnect = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("cloudflare.authMode.globalKey").assertIsSelected()
        compose.onNodeWithText("Connect with Global API Key").assertIsDisplayed()
        compose.onNodeWithText("In API Keys, tap View beside Global API Key").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.openApiTokens"))
        compose.onNodeWithTag("cloudflare.openApiTokens").performClick()
        compose.runOnIdle { assertEquals("https://dash.cloudflare.com/profile/api-tokens", opened) }

        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.globalKey"))
        compose.onNodeWithTag("cloudflare.globalKey").performTextInput("global-key-123")
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.connect"))
        // The key alone is not enough: iOS requires the login email too.
        compose.onNodeWithTag("cloudflare.connect").assertIsNotEnabled()
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.email"))
        compose.onNodeWithTag("cloudflare.email").performTextInput("Owner@Example.com")
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.connect"))
        compose.onNodeWithTag("cloudflare.connect").assertIsEnabled().performClick()

        compose.runOnIdle {
            val credential = connected as CloudflareCredential.GlobalApiKey
            assertEquals("owner@example.com", credential.email)
            assertEquals("global-key-123", credential.key.use { it })
        }
    }

    @Test
    fun summaryIsWritableAndLabelsTheGlobalKeyEmailAndWorkerSearchMatchesRoutes() {
        val base = connectedState()
        val dashboard = checkNotNull(base.dashboard)
        val inventory = checkNotNull(dashboard.inventory)
        val state = base.copy(
            dashboard = dashboard.copy(
                profile = CloudflareProfileUi("user", "Ada", "active", CloudflareAuthMode.GLOBAL_API_KEY, "owner@example.com"),
                inventory = inventory.copy(
                    workers = inventory.workers.map { it.copy(routes = listOf("shop.verceltics.app/api/*"), tags = listOf("billing")) },
                ),
            ),
        )
        compose.setContent {
            VercelticsTheme {
                CloudflareScreen(
                    state = state,
                    onBack = {},
                    onConnect = {},
                    onRefresh = {},
                    onCancel = {},
                    onSelectAccount = {},
                    onOpenResource = { _, _ -> },
                    onRequestDisconnect = {},
                    onDismissDisconnect = {},
                    onConfirmDisconnect = {},
                )
            }
        }

        compose.onNodeWithText("owner@example.com · Global API Key · writes confirmed").assertIsDisplayed()
        compose.onAllNodesWithText("read-only", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.writeNotice"))
        compose.onNodeWithText("Write access is guarded").assertIsDisplayed()

        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.search"))
        compose.onNodeWithTag("cloudflare.search").performTextInput("shop.verceltics.app")
        compose.onNodeWithTag("cloudflare.dashboard").performScrollToNode(hasTestTag("cloudflare.worker.worker-one"))
        compose.onNodeWithTag("cloudflare.worker.worker-one").assertIsDisplayed()
        compose.onAllNodesWithTag("cloudflare.zone.zone-one").assertCountEquals(0)
    }
}

private val TWO_LOGINS = listOf(
    ProviderAccountUi("primary", "Primary login", "Scoped API token", isActive = true),
    ProviderAccountUi("second", "Second login", "owner@example.com"),
)

private fun connectedState(): CloudflareUiState = CloudflareUiState(
    status = CloudflareConnectionStatus.CONNECTED,
    dashboard = CloudflareDashboardUi(
        profile = CloudflareProfileUi("profile", "Apoorv Cloudflare", "active"),
        accounts = listOf(
            CloudflareAccountUi("account-one", "Production", "standard"),
            CloudflareAccountUi("account-two", "Labs", "standard"),
        ),
        loadedAccountCount = 2,
        accountsComplete = true,
        accountsTruncatedForDisplay = false,
        selectedAccountId = "account-one",
        inventory = CloudflareInventoryUi(
            accountId = "account-one",
            zones = listOf(
                CloudflareZoneUi(
                    id = "zone-one",
                    name = "verceltics.app",
                    status = "active",
                    type = "full",
                    paused = false,
                    accountName = "Production",
                    planName = "Pro",
                ),
            ),
            pagesProjects = listOf(
                CloudflarePagesProjectUi(
                    id = "pages-one",
                    name = "docs",
                    subdomain = "docs.pages.dev",
                    domains = listOf("docs.verceltics.app"),
                    productionBranch = "main",
                    latestDeploymentStatus = "success",
                ),
            ),
            workers = listOf(
                CloudflareWorkerUi(
                    id = "worker-one",
                    modifiedOn = "2026-08-26T12:00:00Z",
                    compatibilityDate = "2026-08-01",
                    handlers = listOf("fetch"),
                    hasAssets = false,
                    hasModules = true,
                ),
            ),
            loadedZoneCount = 1,
            loadedPagesProjectCount = 1,
            loadedWorkerCount = 1,
            zonesComplete = true,
            pagesComplete = true,
            workersComplete = true,
            zonesTruncatedForDisplay = false,
            pagesTruncatedForDisplay = false,
            workersTruncatedForDisplay = false,
            warnings = emptyList(),
        ),
        warnings = emptyList(),
        fetchedAtMillis = 1_700_000_000_000,
        cacheState = CloudflareCacheState.LIVE,
    ),
    savedProfile = CloudflareProfileUi("profile", "Apoorv Cloudflare", "active"),
    operation = null,
)
