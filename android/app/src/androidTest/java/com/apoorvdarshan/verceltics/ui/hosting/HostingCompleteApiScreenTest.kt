package com.apoorvdarshan.verceltics.ui.hosting

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class HostingCompleteApiScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardAndResourceDetailOfferCompleteApi() {
        val requests = mutableListOf<String?>()
        composeRule.setContent {
            VercelticsTheme {
                HostingProviderScreen(
                    providerId = "render",
                    state = connected(),
                    callbacks = HostingProviderScreenCallbacks(onOpenCompleteApi = { requests += it }),
                )
            }
        }
        val dashboard = composeRule.onNodeWithTag("hosting.render.dashboard")
        dashboard.performScrollToNode(hasTestTag("hosting.render.completeApi"))
        composeRule.onNodeWithTag("hosting.render.completeApi").assertTextContains("COMPLETE API").performClick()
        dashboard.performScrollToNode(hasTestTag("hosting.render.openDashboard"))
        composeRule.onNodeWithTag("hosting.render.openDashboard").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), requests) }
    }

    @Test
    fun resourceDetailCompleteApiCarriesTheResource() {
        val requests = mutableListOf<String?>()
        composeRule.setContent {
            VercelticsTheme {
                HostingProviderScreen(
                    providerId = "render",
                    state = connected().copy(selectedResourceId = "srv-1"),
                    callbacks = HostingProviderScreenCallbacks(onOpenCompleteApi = { requests += it }),
                )
            }
        }
        val detail = composeRule.onNodeWithTag("hosting.render.resourceDetail")
        detail.performScrollToNode(hasTestTag("hosting.render.resourceCompleteApi"))
        composeRule.onNodeWithText("Search official operations or send a manual raw request").assertIsDisplayed()
        composeRule.onNodeWithTag("hosting.render.resourceCompleteApi").performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>("srv-1"), requests) }
    }

    @Test
    fun routeGatesCompleteApiBehindPro() {
        val viewModel = HostingProvidersViewModel(Gateway(), SavedStateHandle())
        var paywallRequests = 0
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = false) { paywallRequests += 1 }
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    HostingProviderRoute(viewModel = viewModel, providerId = "render", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.provider("render").status == HostingConnectionStatus.CONNECTED }
        val dashboard = composeRule.onNodeWithTag("hosting.render.dashboard")
        dashboard.performScrollToNode(hasTestTag("hosting.render.completeApi"))
        composeRule.onNodeWithTag("hosting.render.completeApi").performClick()
        composeRule.runOnIdle {
            assertEquals(1, paywallRequests)
            assertFalse(viewModel.apiWorkspace("render")!!.state.value.isOpen)
        }
        composeRule.onNodeWithTag("providerApi.workspace").assertDoesNotExist()
    }

    @Test
    fun routeOpensTheBundledCatalogForProAndBackReturnsToTheDashboard() {
        val viewModel = HostingProvidersViewModel(Gateway(), SavedStateHandle())
        var leftRoute = false
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides ProAccess.Unlocked) {
                    HostingProviderRoute(viewModel = viewModel, providerId = "render", onBack = { leftRoute = true })
                }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.provider("render").status == HostingConnectionStatus.CONNECTED }
        val dashboard = composeRule.onNodeWithTag("hosting.render.dashboard")
        dashboard.performScrollToNode(hasTestTag("hosting.render.completeApi"))
        composeRule.onNodeWithTag("hosting.render.completeApi").performClick()

        composeRule.onNodeWithTag("providerApi.workspace").assertIsDisplayed()
        // The real bundled asset loads off the main thread.
        composeRule.waitUntil(10_000) { viewModel.apiWorkspace("render")!!.state.value.loadedCatalog != null }
        composeRule.onNodeWithTag("providerApi.totalCount").assertTextContains("207")
        composeRule.onNodeWithText("Official Render Public API definition", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("hosting.render.dashboard").assertIsDisplayed()
        assertFalse(leftRoute)
    }

    @Test
    fun routeClosesARestoredWorkspaceOnceProIsConfirmedLocked() {
        val handle = SavedStateHandle(
            mapOf(
                "${HostingProvidersViewModel.apiWorkspaceKey("render")}.open" to true,
                "${HostingProvidersViewModel.apiWorkspaceKey("render")}.stack" to arrayListOf("catalog"),
            ),
        )
        val viewModel = HostingProvidersViewModel(Gateway(), handle)
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) {}
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides locked) {
                    HostingProviderRoute(viewModel = viewModel, providerId = "render", onBack = {})
                }
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.provider("render").status == HostingConnectionStatus.CONNECTED &&
                viewModel.apiWorkspace("render")?.state?.value?.isOpen == false
        }
        composeRule.onNodeWithTag("hosting.render.dashboard").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(false, handle.get<Boolean>("${HostingProvidersViewModel.apiWorkspaceKey("render")}.open")) }
    }

    private fun connected() = HostingProviderUiState(
        providerId = "render",
        status = HostingConnectionStatus.CONNECTED,
        dashboard = DASHBOARD,
        savedAccount = DASHBOARD.account,
        operation = null,
    )

    private class Gateway : HostingProviderUiGateway {
        override suspend fun restore() = Result.success(mapOf<String, HostingRestoreUi>("render" to HostingRestoreUi.Available(DASHBOARD)))

        override suspend fun connect(credentials: HostingCredentials) = Result.success(DASHBOARD)

        override suspend fun refresh(providerId: String) = Result.success(DASHBOARD)

        override suspend fun loadResource(providerId: String, resource: HostingResourceUi) =
            Result.success(HostingResourceWorkspaceUi(providerId, resource.id, emptyList(), 0, false))

        override suspend fun performPrimaryAction(providerId: String, resource: HostingResourceUi, latestDeploymentId: String?) =
            Result.success("Redeploy request accepted.")

        override suspend fun disconnect(providerId: String) = Result.success(Unit)

        override suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> =
            Result.success(ProviderRawResponse(200, emptyList(), "[]"))
    }

    private companion object {
        val DASHBOARD = HostingDashboardUi(
            providerId = "render",
            account = HostingAccountUi("owner", "Studio", "team@example.com"),
            resources = listOf(
                HostingResourceUi(
                    id = "srv-1",
                    name = "studio-web",
                    subtitle = null,
                    url = "https://studio-web.example",
                    status = "Live",
                    region = "oregon",
                    kind = "Web Service",
                    updatedAtMillis = 1_000L,
                    dashboardUrl = "https://dashboard.render.com/srv-1",
                    apiExplorerPath = "/services/srv-1",
                ),
            ),
            loadedResourceCount = 1,
            truncatedForDisplay = false,
            warnings = emptyList(),
            fetchedAtMillis = System.currentTimeMillis(),
            cacheState = HostingCacheState.LIVE,
            dashboardUrl = "https://dashboard.render.com/",
            apiExplorerPath = "/services?limit=100",
        )
    }
}
