package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareAccountUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareCacheState
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareDashboardUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareInventoryUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareProfileUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareResourceKind
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareResourceSelection
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.cloudflare.storage.CloudflareStorageEntryRow
import com.apoorvdarshan.verceltics.ui.cloudflare.storage.CloudflareStorageRoutes
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflareOperationsHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sampleDataRendersPushedScreensReadOnly() {
        val navigator = CloudflareOperationsNavigator()
        navigator.push(CloudflareStorageRoutes.dashboard("account", "Production"))
        compose.setContent {
            VercelticsTheme {
                CloudflareOperationsHost(navigator, client = null, dashboard = dashboard(), selectedResource = null, onCloseResource = {}, onInventoryChanged = {})
            }
        }
        compose.onNodeWithTag("cloudflare.resourceDetail").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.operationsUnavailable").assertIsDisplayed()
    }

    @Test
    fun selectedZoneWithoutLiveClientShowsInventoryDetail() {
        compose.setContent {
            VercelticsTheme {
                CloudflareOperationsHost(
                    CloudflareOperationsNavigator(),
                    client = null,
                    dashboard = dashboard(),
                    selectedResource = CloudflareResourceSelection(CloudflareResourceKind.ZONE, "zone"),
                    onCloseResource = {},
                    onInventoryChanged = {},
                )
            }
        }
        compose.onNodeWithText("example.com").assertIsDisplayed()
    }

    @Test
    fun lockedProAccessClosesRestoredOperationsScreens() {
        val navigator = CloudflareOperationsNavigator()
        navigator.push(CloudflareStorageRoutes.dashboard("account", "Production"))
        compose.setContent {
            CompositionLocalProvider(LocalProAccess provides ProAccess(isUnlocked = false, isConfirmedLocked = true) {}) {
                VercelticsTheme {
                    CloudflareOperationsHost(navigator, client = null, dashboard = dashboard(), selectedResource = null, onCloseResource = {}, onInventoryChanged = {})
                }
            }
        }
        compose.waitUntil(5_000) { navigator.stack.value.isEmpty() }
    }

    @Test
    fun storageEntryRowInvokesItsGatedCallback() {
        var paywallRequests = 0
        var opened = 0
        compose.setContent {
            val proAccess = ProAccess(isUnlocked = false, isConfirmedLocked = false) { paywallRequests += 1 }
            VercelticsTheme {
                CloudflareStorageEntryRow(onOpen = { proAccess.requestPro { opened += 1 } })
            }
        }
        compose.onNodeWithTag("cloudflare.storage.open").performClick()
        compose.runOnIdle {
            assertEquals(1, paywallRequests)
            assertEquals(0, opened)
        }
    }

    @Test
    fun confirmationDialogUsesExactCopy() {
        var confirmed = false
        compose.setContent {
            VercelticsTheme {
                CloudflareConfirmationDialog(
                    prompt = CloudflareConfirmationPrompt(
                        title = "Delete this DNS record?",
                        message = "www.example.com → 192.0.2.1 will be permanently removed.",
                        confirmLabel = "Delete A Record",
                        resourceId = "record",
                        destructive = true,
                    ),
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("www.example.com → 192.0.2.1 will be permanently removed.").assertIsDisplayed()
        compose.onNodeWithText("DELETE A RECORD").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    private fun dashboard() = CloudflareDashboardUi(
        profile = CloudflareProfileUi("profile", "Apoorv", "active"),
        accounts = listOf(CloudflareAccountUi("account", "Production", "standard")),
        loadedAccountCount = 1,
        accountsComplete = true,
        accountsTruncatedForDisplay = false,
        selectedAccountId = "account",
        inventory = CloudflareInventoryUi(
            accountId = "account",
            zones = listOf(CloudflareZoneUi("zone", "example.com", "active", "full", false, "Production", "Free")),
            pagesProjects = emptyList(),
            workers = emptyList(),
            loadedZoneCount = 1,
            loadedPagesProjectCount = 0,
            loadedWorkerCount = 0,
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
    )
}
