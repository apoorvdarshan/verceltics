package com.apoorvdarshan.verceltics

import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.apoorvdarshan.verceltics.ui.DebugVercelGatewayController
import com.apoorvdarshan.verceltics.ui.DebugVercelScenario
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareGatewayController
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareScenario
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyGatewayController
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyScenario
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedGatewayController
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedScenario
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleGatewayController
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleScenario
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VercelticsNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<VercelticsTestActivity>()

    @Before
    fun returnToDisconnectedHostingRoot() {
        closeSoftKeyboard()
        compose.activityRule.scenario.onActivity { activity ->
            activity.configureGateway(DebugVercelScenario.DISCONNECTED)
            activity.configurePageSpeedGateway(DebugPageSpeedScenario.DISCONNECTED)
            activity.configureNetlifyGateway(DebugNetlifyScenario.DISCONNECTED)
            activity.configureCloudflareGateway(DebugCloudflareScenario.DISCONNECTED)
            activity.configureSearchConsoleGateway(DebugSearchConsoleScenario.DISCONNECTED)
        }
        waitForTag("mainNavigation.hosting")
        compose.onNodeWithTag("mainNavigation.hosting").performClick()
        waitForTag("workspace.hosting.empty")
    }

    @Test
    fun connectedHostingSearchFocusesExactlyOnce() {
        configureGateway(DebugVercelScenario.CONNECTED)
        waitForTag("workspace.hosting.connected")

        compose.onNodeWithTag("mainNavigation.search").performClick()

        waitForTag("workspace.hosting.searchField")
        compose.onNodeWithTag("mainNavigation.hosting").assertIsSelected()
        compose.onNodeWithTag("workspace.hosting.searchField").assertIsFocused()

        compose.activityRule.scenario.recreate()

        waitForTag("workspace.hosting.connected")
        compose.onNodeWithTag("workspace.hosting.searchField")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun connectedProjectDetailAndSystemBackPreserveWorkspace() {
        configureGateway(DebugVercelScenario.CONNECTED)
        waitForTag("workspace.hosting.connected")

        compose.onNodeWithTag("workspace.hosting.project.test-project").performClick()
        compose.onNodeWithTag("workspace.hosting.analytics").assertIsDisplayed()
        compose.onNodeWithTag("workspace.hosting.analytics.chart").assertIsDisplayed()

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForTag("workspace.hosting.connected")
        compose.onNodeWithTag("workspace.hosting.project.test-project").assertIsDisplayed()
    }

    @Test
    fun offlineSavedAccountNeverShowsDisconnectedConnectState() {
        configureGateway(DebugVercelScenario.OFFLINE_SAVED)

        waitForTag("workspace.hosting.savedUnavailable")
        compose.onAllNodesWithTag("workspace.hosting.empty").assertCountEquals(0)
        compose.onAllNodesWithTag("workspace.hosting.connect").assertCountEquals(0)
    }

    @Test
    fun offlineSavedAccountExplainsWhyProjectSearchIsUnavailable() {
        configureGateway(DebugVercelScenario.OFFLINE_SAVED)
        waitForTag("workspace.hosting.savedUnavailable")

        compose.onNodeWithTag("mainNavigation.search").performClick()

        compose.onNodeWithText(
            "Project search is unavailable while the saved Vercel dashboard is offline. Retry the connection first.",
        ).assertIsDisplayed()
    }

    @Test
    fun disconnectedCatalogSearchDoesNotReplayAfterVercelConnects() {
        compose.onNodeWithTag("mainNavigation.search").performClick()
        waitForTag("connection.catalog.hosting")
        compose.onNodeWithTag("connection.catalog.search").assertIsFocused()

        configureGateway(DebugVercelScenario.CONNECTED)

        waitForTag("workspace.hosting.connected")
        compose.onNodeWithTag("workspace.hosting.searchField")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun connectedNetlifyRemainsReachableWhenVercelSavedAccountIsUnavailable() {
        configureNetlifyGateway(DebugNetlifyScenario.CONNECTED)
        configureGateway(DebugVercelScenario.OFFLINE_SAVED)

        waitForTag("workspace.hosting.savedUnavailable")
        compose.onNodeWithTag("workspace.hosting.netlifyConnection").assertIsDisplayed().performClick()
        waitForTag("netlify.dashboard")
    }

    @Test
    fun restoredCloudflareCardOpensDashboardAndResourceDetailSurvivesRecreation() {
        configureCloudflareGateway(DebugCloudflareScenario.CONNECTED)

        waitForTag("workspace.hosting.cloudflareConnection")
        compose.onNodeWithTag("workspace.hosting.cloudflareConnection").performClick()
        waitForTag("cloudflare.dashboard")
        compose.onNodeWithTag("cloudflare.dashboard")
            .performScrollToNode(hasTestTag("cloudflare.zone.zone-apoorv"))
        compose.onNodeWithTag("cloudflare.zone.zone-apoorv").performClick()
        waitForTag("cloudflare.resourceDetail")
        val restoreCallsBeforeRecreation = DebugCloudflareGatewayController.restoreCalls

        compose.activityRule.scenario.recreate()

        waitForTag("cloudflare.resourceDetail")
        assertEquals(restoreCallsBeforeRecreation, DebugCloudflareGatewayController.restoreCalls)
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag("cloudflare.dashboard")
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag("workspace.hosting.cloudflareConnection")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
    }

    @Test
    fun connectedCloudflareKeepsDockAndBottomSearchFocusesInventoryExactlyOnce() {
        configureCloudflareGateway(DebugCloudflareScenario.CONNECTED)

        waitForTag("workspace.hosting.cloudflareConnection")
        compose.onNodeWithTag("workspace.hosting.cloudflareConnection").performClick()
        waitForTag("cloudflare.dashboard")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.dashboard")
            .performScrollToNode(hasTestTag("cloudflare.zone.zone-apoorv"))
        compose.onNodeWithTag("cloudflare.zone.zone-apoorv").performClick()
        waitForTag("cloudflare.resourceDetail")
        compose.onNodeWithTag("mainNavigation.search").performClick()

        waitForTag("cloudflare.dashboard")
        compose.onNodeWithTag("cloudflare.search").assertIsFocused()

        compose.activityRule.scenario.recreate()

        waitForTag("cloudflare.dashboard")
        compose.onNodeWithTag("cloudflare.search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))

        compose.onNodeWithTag("cloudflare.dashboard")
            .performScrollToNode(hasTestTag("cloudflare.zone.zone-apoorv"))
        compose.onNodeWithTag("cloudflare.zone.zone-apoorv").performClick()
        waitForTag("cloudflare.resourceDetail")
        compose.onNodeWithTag("mainNavigation.hosting").performClick()
        waitForTag("workspace.hosting.cloudflareConnection")
        compose.onNodeWithTag("workspace.hosting.cloudflareConnection").performClick()

        waitForTag("cloudflare.resourceDetail")
    }

    @Test
    fun hostingCatalogOpensCloudflareTokenRouteAndBackClearsCredentialProtection() {
        openCloudflareDetail()

        compose.onNodeWithTag("cloudflare.connectionForm").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForTag("workspace.hosting.empty")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        }
    }

    @Test
    fun persistentDockSwitchesDirectlyFromProviderRouteToAnotherWorkspace() {
        openCloudflareDetail()

        compose.onNodeWithTag("mainNavigation.sites").performClick()

        waitForTag("workspace.sites.empty")
        compose.onNodeWithTag("mainNavigation.sites").assertIsSelected()
        compose.onAllNodesWithTag("cloudflare.screen").assertCountEquals(0)
    }

    @Test
    fun cloudflareConnectMutationSurvivesRecreationWithoutSavingTokenInUi() {
        configureCloudflareGateway(DebugCloudflareScenario.DISCONNECTED, blockConnect = true)
        openCloudflareDetail()
        // Like iOS, the form opens on the Global API Key mode; switch to the scoped token field.
        compose.onNodeWithTag("cloudflare.authMode.apiToken").performClick()
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.token"))
        compose.onNodeWithTag("cloudflare.token").performTextInput("temporary-cloudflare-token")
        compose.onNodeWithTag("cloudflare.connectionForm").performScrollToNode(hasTestTag("cloudflare.connect"))
        compose.onNodeWithTag("cloudflare.connect").performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            DebugCloudflareGatewayController.isConnectStarted()
        }

        compose.activityRule.scenario.recreate()
        waitForTag("cloudflare.screen")
        compose.activityRule.scenario.onActivity(VercelticsTestActivity::releaseCloudflareConnect)

        waitForTag("cloudflare.dashboard")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        }
    }

    @Test
    fun connectMutationSurvivesActivityRecreation() {
        configureGateway(
            scenario = DebugVercelScenario.DISCONNECTED,
            blockConnect = true,
        )
        openVercelDetail()
        compose.onNodeWithTag("vercel.token").performTextInput("temporary-token")
        compose.onNodeWithTag("vercel.connect").performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            DebugVercelGatewayController.isConnectStarted()
        }

        compose.activityRule.scenario.recreate()
        waitForTag("providerDetail.vercel")
        compose.activityRule.scenario.onActivity(VercelticsTestActivity::releaseConnect)

        waitForTag("vercel.disconnect")
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag("workspace.hosting.connected")
    }

    @Test
    fun workspaceTabsShowTheirDisconnectedNativeRoots() {
        compose.onNodeWithTag("workspace.hosting.empty").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.hosting").assertIsSelected()

        compose.onNodeWithTag("mainNavigation.registrars").performClick().assertIsSelected()
        compose.onNodeWithTag("workspace.registrars.empty").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace.hosting.empty").assertCountEquals(0)

        compose.onNodeWithTag("mainNavigation.sites").performClick().assertIsSelected()
        compose.onNodeWithTag("workspace.sites.empty").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace.registrars.empty").assertCountEquals(0)
    }

    @Test
    fun searchIsAContextualButtonAndNeverASelectedDestination() {
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val noSelectedState = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

        primaryNavigationTags.forEach { tag ->
            compose.onNodeWithTag(tag).assert(tabRole)
        }
        compose.onNodeWithTag("mainNavigation.search")
            .assert(buttonRole)
            .assert(noSelectedState)

        compose.onNodeWithTag("mainNavigation.sites").performClick()
        compose.onNodeWithTag("workspace.sites.empty").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.about").performClick().assertIsSelected()
        compose.onNodeWithTag("about").assertIsDisplayed()

        compose.onNodeWithTag("mainNavigation.search").performClick()

        compose.onNodeWithTag("workspace.sites.empty").assertIsDisplayed()
        waitForTag("connection.catalog.sites")
        compose.onNodeWithTag("connection.catalog.search").assertIsFocused()
        compose.onNodeWithTag("mainNavigation.sites").assertIsSelected()
        compose.onNodeWithTag("mainNavigation.about").assertIsNotSelected()
        compose.onNodeWithTag("mainNavigation.search").assert(noSelectedState)
        compose.onAllNodesWithTag("globalSearch").assertCountEquals(0)

        compose.activityRule.scenario.recreate()
        waitForTag("connection.catalog.sites")
        compose.onNodeWithTag("connection.catalog.search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun connectionCatalogOpensVercelDetailAndBackRestoresDock() {
        openVercelDetail()

        compose.onNodeWithTag("providerDetail.vercel").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForTag("workspace.hosting.empty")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.hosting").assertIsSelected()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }

    @Test
    fun sitesCatalogOpensNativePageSpeedRouteAndBackClearsCredentialProtection() {
        openPageSpeedDetail()

        compose.onNodeWithTag("pagespeed.connectionForm").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForTag("workspace.sites.empty")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }

    @Test
    fun restoredPageSpeedConnectionIsVisibleAndKeepsActivityScopedDashboardOnRecreation() {
        configurePageSpeedGateway(DebugPageSpeedScenario.CONNECTED)
        compose.onNodeWithTag("mainNavigation.sites").performClick()

        waitForTag("workspace.sites.pageSpeedConnection")
        compose.onNodeWithTag("workspace.sites.connected").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace.sites.empty").assertCountEquals(0)
        compose.onNodeWithTag("workspace.sites.pageSpeedConnection").assertIsDisplayed()
        assertEquals(1, DebugPageSpeedGatewayController.restoreCalls)
        assertEquals(0, DebugPageSpeedGatewayController.refreshCalls)

        compose.onNodeWithTag("workspace.sites.pageSpeedConnection").performClick()
        waitForTag("pagespeed.dashboard")
        val restoreCallsBeforeRecreation = DebugPageSpeedGatewayController.restoreCalls

        compose.activityRule.scenario.recreate()

        waitForTag("pagespeed.dashboard")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        assertEquals(restoreCallsBeforeRecreation, DebugPageSpeedGatewayController.restoreCalls)
    }

    @Test
    fun sitesCatalogDistinguishesConnectedAndConnectableProviders() {
        configureSearchConsoleGateway(DebugSearchConsoleScenario.CONNECTED)
        compose.onNodeWithTag("mainNavigation.sites").performClick()

        waitForTag("workspace.sites.searchConsoleConnection")
        compose.onNodeWithTag("workspace.sites.connect").performClick()
        waitForTag("connection.catalog.sites")

        compose.onNodeWithTag("provider.googleSearchConsole")
            .assertIsEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Connected",
                ),
            )
        compose.onNodeWithText("Open Google Search Console").assertIsDisplayed()
        // The provider row is clickable and therefore merges its child semantics. Inspect the
        // badge in the unmerged tree so this verifies the rendered badge rather than the row's
        // combined accessibility node.
        compose.onNodeWithTag(
            "provider.googleSearchConsole.connected",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        // Every catalog provider is connectable now; a provider without a saved connection offers
        // to connect instead of carrying the connected state or badge.
        compose.onNodeWithTag("provider.googleAnalytics")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Open Google Analytics connection"),
                ),
            )
        compose.onNodeWithText("Connect Google Analytics").assertIsDisplayed()
        compose.onAllNodesWithTag("provider.googleAnalytics.connected", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithTag("provider.googleSearchConsole").performClick()
        waitForTag("searchConsole.propertySearch")
    }

    @Test
    fun disconnectedPageSpeedSearchStaysInProviderAndFocusesSiteUrl() {
        openPageSpeedDetail()

        compose.onNodeWithTag("mainNavigation.search").performClick()

        compose.onNodeWithTag("pagespeed.screen").assertIsDisplayed()
        compose.onNodeWithTag("pagespeed.siteUrl").assertIsFocused()
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
    }

    @Test
    fun connectedPageSpeedSearchStaysInProviderAndExplainsSingleSiteScope() {
        configurePageSpeedGateway(DebugPageSpeedScenario.CONNECTED)
        compose.onNodeWithTag("mainNavigation.sites").performClick()
        waitForTag("workspace.sites.pageSpeedConnection")
        compose.onNodeWithTag("workspace.sites.pageSpeedConnection").performClick()
        waitForTag("pagespeed.dashboard")

        compose.onNodeWithTag("mainNavigation.search").performClick()

        compose.onNodeWithTag("pagespeed.screen").assertIsDisplayed()
        compose.onNodeWithTag("pagespeed.searchNotice").assertIsDisplayed()
    }

    @Test
    fun connectedSearchConsoleKeepsDockAndContextualSearchReturnsToPropertiesOnce() {
        configureSearchConsoleGateway(DebugSearchConsoleScenario.CONNECTED)
        compose.onNodeWithTag("mainNavigation.sites").performClick()

        waitForTag("workspace.sites.searchConsoleConnection")
        compose.onNodeWithTag("workspace.sites.searchConsoleConnection").performClick()
        waitForTag("searchConsole.propertySearch")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.onNodeWithTag("searchConsole.property.sc-domain:apoorvdarshan.com").performClick()
        waitForTag("searchConsole.switchProperty")

        compose.onNodeWithTag("mainNavigation.search").performClick()

        waitForTag("searchConsole.propertySearch")
        compose.onNodeWithTag("searchConsole.propertySearch").assertIsFocused()

        compose.activityRule.scenario.recreate()

        waitForTag("searchConsole.propertySearch")
        compose.onNodeWithTag("searchConsole.propertySearch")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun searchConsoleAuthorizationSurvivesRecreationWithoutExposingCredentialUiState() {
        configureSearchConsoleGateway(
            scenario = DebugSearchConsoleScenario.DISCONNECTED,
            blockConnect = true,
        )
        openSearchConsoleDetail()
        compose.onNodeWithTag("searchConsole.connect").performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            DebugSearchConsoleGatewayController.isConnectStarted()
        }
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }

        compose.activityRule.scenario.recreate()
        waitForTag("searchConsole.screen")
        compose.activityRule.scenario.onActivity(VercelticsTestActivity::releaseSearchConsoleConnect)

        waitForTag("searchConsole.propertySearch")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        }
    }

    @Test
    fun hostingCatalogOpensNativeNetlifyRouteAndBackClearsCredentialProtection() {
        openNetlifyDetail()

        compose.onNodeWithTag("netlify.connectionForm").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForTag("workspace.hosting.empty")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }

    @Test
    fun connectedVercelWorkspaceStillExposesNetlifyProviderRoute() {
        configureGateway(DebugVercelScenario.CONNECTED)
        waitForTag("workspace.hosting.connected")

        compose.onNodeWithTag("workspace.hosting.account").performClick()
        compose.onNodeWithTag("workspace.hosting.connectNetlify").performClick()

        waitForTag("netlify.connectionForm")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
    }

    @Test
    fun connectedVercelWorkspaceStillExposesCloudflareProviderRoute() {
        configureGateway(DebugVercelScenario.CONNECTED)
        waitForTag("workspace.hosting.connected")

        compose.onNodeWithTag("workspace.hosting.account").performClick()
        compose.onNodeWithTag("workspace.hosting.connectCloudflare").performClick()

        waitForTag("cloudflare.connectionForm")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
    }

    @Test
    fun restoredNetlifyCardAndSavedSiteDetailSurviveActivityRecreation() {
        configureNetlifyGateway(DebugNetlifyScenario.CONNECTED)

        waitForTag("workspace.hosting.netlifyConnection")
        compose.onNodeWithTag("workspace.hosting.connected").assertIsDisplayed()
        compose.onNodeWithTag("workspace.hosting.netlifyConnection").performClick()
        waitForTag("netlify.dashboard")
        compose.onNodeWithTag("netlify.site.netlify-test-site").performClick()
        waitForTag("netlify.siteDetail")
        compose.onNodeWithTag("netlify.siteDetail")
            .performScrollToNode(hasTestTag("netlify.deploy.debug-deploy"))
        compose.onNodeWithTag("netlify.deploy.debug-deploy").assertIsDisplayed()
        val restoreCallsBeforeRecreation = DebugNetlifyGatewayController.restoreCalls

        compose.activityRule.scenario.recreate()

        waitForTag("netlify.siteDetail")
        assertEquals(restoreCallsBeforeRecreation, DebugNetlifyGatewayController.restoreCalls)
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag("netlify.dashboard")
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag("workspace.hosting.netlifyConnection")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
    }

    @Test
    fun connectedNetlifySearchClosesDetailAndFocusesSiteInventoryOnce() {
        configureNetlifyGateway(DebugNetlifyScenario.CONNECTED)
        waitForTag("workspace.hosting.netlifyConnection")
        compose.onNodeWithTag("workspace.hosting.netlifyConnection").performClick()
        waitForTag("netlify.dashboard")
        compose.onNodeWithTag("netlify.site.netlify-test-site").performClick()
        waitForTag("netlify.siteDetail")

        compose.onNodeWithTag("mainNavigation.search").performClick()

        waitForTag("netlify.search")
        compose.onNodeWithTag("netlify.search").assertIsFocused()
        compose.onAllNodesWithTag("netlify.siteDetail").assertCountEquals(0)

        compose.activityRule.scenario.recreate()

        waitForTag("netlify.search")
        compose.onNodeWithTag("netlify.search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test
    fun netlifyConnectMutationSurvivesActivityRecreationWithoutSavingTokenInUi() {
        configureNetlifyGateway(DebugNetlifyScenario.DISCONNECTED, blockConnect = true)
        openNetlifyDetail()
        compose.onNodeWithTag("netlify.token").performTextInput("temporary-netlify-token")
        compose.onNodeWithTag("netlify.connect").performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            DebugNetlifyGatewayController.isConnectStarted()
        }

        compose.activityRule.scenario.recreate()
        waitForTag("netlify.screen")
        compose.activityRule.scenario.onActivity(VercelticsTestActivity::releaseNetlifyConnect)

        waitForTag("netlify.dashboard")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }

    @Test
    fun topLevelSystemBackDoesNotReplayTabSelections() {
        compose.onNodeWithTag("mainNavigation.sites").performClick()
        compose.onNodeWithTag("workspace.sites.empty").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.registrars").performClick()
        compose.onNodeWithTag("workspace.registrars.empty").assertIsDisplayed()

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            compose.activityRule.scenario.state == Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun tokenIsNotRestoredWithActivityState() {
        openVercelDetail()
        compose.onNodeWithTag("vercel.token").performTextInput("temporary-token")

        compose.activityRule.scenario.recreate()

        waitForTag("providerDetail.vercel")
        compose.onNodeWithTag("mainNavigation.dock").assertIsDisplayed()
        val editableText = compose.onNodeWithTag("vercel.token")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertEquals("", editableText.text)
    }

    @Test
    fun dockTargetsDoNotOverlapAndDeadSpaceDoesNotClickThrough() {
        val primaryBounds = primaryNavigationTags.map { tag ->
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        val searchBounds = compose.onNodeWithTag("mainNavigation.search")
            .fetchSemanticsNode()
            .boundsInRoot

        primaryBounds.zipWithNext().forEach { (left, right) ->
            check(left.right <= right.left) {
                "Primary dock touch targets overlap: $left and $right"
            }
        }
        check(primaryBounds.last().right <= searchBounds.left) {
            "Search overlaps the primary dock: ${primaryBounds.last()} and $searchBounds"
        }

        val dockNode = compose.onNodeWithTag("mainNavigation.dock")
        val dockBounds = dockNode.fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag("workspace.hosting.empty")
            .fetchSemanticsNode()
            .boundsInRoot
        check(contentBounds.bottom <= dockBounds.top + 1f) {
            "Workspace content is laid out under the dock: $contentBounds and $dockBounds"
        }

        val primarySurfaceBounds = compose.onNodeWithTag("mainNavigation.primary")
            .fetchSemanticsNode()
            .boundsInRoot
        val gapCenterInRoot = Offset(
            x = (primarySurfaceBounds.right + searchBounds.left) / 2f,
            y = (primarySurfaceBounds.top + primarySurfaceBounds.bottom) / 2f,
        )
        dockNode.performTouchInput {
            click(
                Offset(
                    x = gapCenterInRoot.x - dockBounds.left,
                    y = gapCenterInRoot.y - dockBounds.top,
                ),
            )
        }

        compose.onNodeWithTag("workspace.hosting.empty").assertIsDisplayed()
        compose.onNodeWithTag("mainNavigation.hosting").assertIsSelected()
    }

    private fun openVercelDetail() {
        compose.onNodeWithTag("workspace.hosting.connect").performClick()
        waitForTag("connection.catalog.hosting")
        compose.onNodeWithTag("connection.category.hosting").assertIsSelected()
        compose.onNodeWithTag("provider.vercel").performClick()
        waitForTag("providerDetail.vercel")
    }

    private fun openPageSpeedDetail() {
        compose.onNodeWithTag("mainNavigation.sites").performClick()
        waitForTag("workspace.sites.empty")
        compose.onNodeWithTag("workspace.sites.connect").performClick()
        waitForTag("connection.catalog.sites")
        compose.onNodeWithTag("provider.pageSpeed").performClick()
        waitForTag("pagespeed.screen")
    }

    private fun openNetlifyDetail() {
        compose.onNodeWithTag("workspace.hosting.connect").performClick()
        waitForTag("connection.catalog.hosting")
        compose.onNodeWithTag("connection.category.hosting").assertIsSelected()
        compose.onNodeWithTag("provider.netlify").performClick()
        waitForTag("netlify.screen")
    }

    private fun openCloudflareDetail() {
        compose.onNodeWithTag("workspace.hosting.connect").performClick()
        waitForTag("connection.catalog.hosting")
        compose.onNodeWithTag("connection.category.hosting").assertIsSelected()
        compose.onNodeWithTag("provider.cloudflare").performClick()
        waitForTag("cloudflare.screen")
    }

    private fun openSearchConsoleDetail() {
        compose.onNodeWithTag("mainNavigation.sites").performClick()
        waitForTag("workspace.sites.empty")
        compose.onNodeWithTag("workspace.sites.connect").performClick()
        waitForTag("connection.catalog.sites")
        compose.onNodeWithTag("provider.googleSearchConsole").performClick()
        waitForTag("searchConsole.screen")
    }

    private fun configureGateway(
        scenario: DebugVercelScenario,
        blockConnect: Boolean = false,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.configureGateway(
                scenario = scenario,
                blockConnect = blockConnect,
            )
        }
    }

    private fun configurePageSpeedGateway(scenario: DebugPageSpeedScenario) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.configurePageSpeedGateway(scenario)
        }
    }

    private fun configureNetlifyGateway(
        scenario: DebugNetlifyScenario,
        blockConnect: Boolean = false,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.configureNetlifyGateway(scenario, blockConnect)
        }
    }

    private fun configureCloudflareGateway(
        scenario: DebugCloudflareScenario,
        blockConnect: Boolean = false,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.configureCloudflareGateway(scenario, blockConnect)
        }
    }

    private fun configureSearchConsoleGateway(
        scenario: DebugSearchConsoleScenario,
        blockConnect: Boolean = false,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.configureSearchConsoleGateway(scenario, blockConnect)
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        // A cold, headless emulator can spend several seconds compiling the first Compose frame.
        const val TIMEOUT_MILLIS = 20_000L
        val primaryNavigationTags = listOf(
            "mainNavigation.hosting",
            "mainNavigation.registrars",
            "mainNavigation.sites",
            "mainNavigation.about",
        )
    }
}
