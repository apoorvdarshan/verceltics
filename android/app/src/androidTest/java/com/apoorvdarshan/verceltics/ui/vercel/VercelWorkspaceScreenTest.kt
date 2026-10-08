package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelRestoreUi
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.screens.ProviderDetailScreen
import com.apoorvdarshan.verceltics.ui.screens.VercelWorkspaceScreen
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VercelWorkspaceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun reArmFirstLoadLatch() {
        VercelProjectsFirstLoad.resetForTesting()
    }

    @Test
    fun cardsShowDomainRepositoryFrameworkAndTeamNewestDeployFirst() {
        setWorkspace(FakeVercelGateway())

        composeRule.onNodeWithTag("workspace.hosting.project.prj_fresh").assertIsDisplayed()
        composeRule.onNodeWithText("studio.example", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("acme/web", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Next.js", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Studio", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ship the pricing refresh", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("vercel.project.freshDeploy", useUnmergedTree = true).assertIsDisplayed()

        assertTrue(
            "Newest deployment first.",
            top("workspace.hosting.project.prj_fresh") < top("workspace.hosting.project.prj_docs"),
        )
        val letterTiles = composeRule.onAllNodesWithTag("vercel.projectIcon.letter", useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertTrue("Without a favicon source every visible tile is a letter.", letterTiles >= 2)
        assertEquals(0, composeRule.onAllNodesWithTag("vercel.projectIcon.favicon", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun searchMatchesDomainsAndNoMatchesOffersClearSearch() {
        setWorkspace(FakeVercelGateway())

        composeRule.onNodeWithTag("workspace.hosting.searchField").performTextInput("handbook")
        composeRule.onNodeWithTag("workspace.hosting.project.prj_docs").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.project.prj_fresh").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("workspace.hosting.searchField").performTextReplacement("zzz")
        composeRule.onNodeWithTag("workspace.hosting.noMatches").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing in your projects matches “zzz”.").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.noMatches.action").performClick()

        composeRule.onNodeWithTag("workspace.hosting.project.prj_fresh").assertIsDisplayed()
    }

    @Test
    fun longPressMenuActionsAreProGated() {
        var paywallRequests = 0
        val locked = ProAccess(isUnlocked = false, isConfirmedLocked = true) { paywallRequests += 1 }
        setWorkspace(FakeVercelGateway(), proAccess = locked)

        composeRule.onNodeWithTag("workspace.hosting.project.prj_fresh").performTouchInput { longClick() }

        composeRule.onNodeWithTag("workspace.hosting.projectMenu").assertIsDisplayed()
        listOf("openWebsite", "copyUrl", "viewOnVercel", "viewAnalytics").forEach { action ->
            composeRule.onNodeWithTag("workspace.hosting.projectMenu.$action").assertIsDisplayed()
        }
        composeRule.onNodeWithText("View on Vercel").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.projectMenu.viewAnalytics").performClick()

        assertEquals(1, paywallRequests)
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.analytics").fetchSemanticsNodes().size)
    }

    @Test
    fun unlockedViewAnalyticsMenuOpensTheProject() {
        setWorkspace(FakeVercelGateway())

        composeRule.onNodeWithTag("workspace.hosting.project.prj_bare").performTouchInput { longClick() }
        assertEquals(
            "A project without a domain offers no website actions.",
            0,
            composeRule.onAllNodesWithTag("workspace.hosting.projectMenu.openWebsite").fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("workspace.hosting.projectMenu.viewAnalytics").performClick()

        waitForTag("workspace.hosting.analytics")
    }

    @Test
    fun emptyAccountOffersOpenVercel() {
        val empty = VercelTestFixtures.DASHBOARD.copy(projects = emptyList())
        setWorkspace(FakeVercelGateway(VercelRestoreUi.Available(empty)).apply { refreshResult = Result.success(empty) })

        composeRule.onNodeWithTag("workspace.hosting.noProjects").assertIsDisplayed()
        composeRule.onNodeWithText("Create a Vercel project, then refresh this screen.").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.noProjects.action").assertIsDisplayed()
    }

    @Test
    fun failedLoadWithoutProjectsOffersTryAgain() {
        val empty = VercelTestFixtures.DASHBOARD.copy(projects = emptyList())
        val gateway = FakeVercelGateway(VercelRestoreUi.Available(empty)).apply {
            refreshResult = Result.failure(IllegalStateException("Vercel is temporarily unavailable."))
        }
        val viewModel = setWorkspace(gateway)
        composeRule.runOnIdle { viewModel.refresh() }

        waitForTag("workspace.hosting.loadError")
        composeRule.onNodeWithText("Vercel is temporarily unavailable.").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.loadError.action").performClick()
        composeRule.waitUntil(5_000) { gateway.refreshCalls.get() == 2 }
    }

    @Test
    fun failedRefreshKeepsProjectsAndOffersTryAgain() {
        val gateway = FakeVercelGateway().apply { refreshResult = Result.failure(IllegalStateException("Rate limited.")) }
        val viewModel = setWorkspace(gateway)
        composeRule.runOnIdle { viewModel.refresh() }

        waitForTag("workspace.hosting.refreshError")
        composeRule.onNodeWithText("Couldn’t refresh projects").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.project.prj_fresh").assertIsDisplayed()
    }

    @Test
    fun projectsFirstLoadedIsReportedOnce() {
        var reports = 0
        var searchRequestId by mutableIntStateOf(0)
        val viewModel = VercelConnectionViewModel(FakeVercelGateway())
        composeRule.setContent {
            VercelticsTheme {
                VercelWorkspaceScreen(
                    vercelConnectionViewModel = viewModel,
                    searchRequestId = searchRequestId,
                    refreshRequestId = 0,
                    onConnectProvider = {},
                    onProjectsFirstLoaded = { reports += 1 },
                )
            }
        }
        waitForTag("workspace.hosting.connected")
        composeRule.runOnIdle {
            viewModel.refresh()
            searchRequestId += 1
        }
        composeRule.waitForIdle()

        assertEquals(1, reports)
    }

    @Test
    fun foregroundRefreshInsideTheWindowDoesNotRefetch() {
        val gateway = FakeVercelGateway()
        var refreshRequestId by mutableIntStateOf(0)
        val viewModel = VercelConnectionViewModel(gateway)
        composeRule.setContent {
            VercelticsTheme {
                VercelWorkspaceScreen(
                    vercelConnectionViewModel = viewModel,
                    searchRequestId = 0,
                    refreshRequestId = refreshRequestId,
                    onConnectProvider = {},
                )
            }
        }
        waitForTag("workspace.hosting.connected")

        composeRule.runOnIdle { refreshRequestId += 1 }
        composeRule.waitForIdle()
        assertEquals(0, gateway.refreshCalls.get())

        composeRule.onNodeWithTag("workspace.hosting.refresh").performClick()
        composeRule.waitUntil(5_000) { gateway.refreshCalls.get() == 1 }
    }

    @Test
    fun deploymentRowOpensDetailWithBuildEventsAndBackReturns() {
        setWorkspace(FakeVercelGateway())
        composeRule.onNodeWithTag("workspace.hosting.project.prj_fresh").performClick()
        waitForTag("workspace.hosting.analytics.stats")

        composeRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("workspace.hosting.analytics.deployments"))
        waitForTag("workspace.hosting.analytics.deployment.dpl_ready")
        composeRule.onNodeWithTag("workspace.hosting.analytics.deployment.dpl_ready").performClick()

        waitForTag("workspace.hosting.deployment")
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("workspace.hosting.deployment.events"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("workspace.hosting.deployment.event", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 3
        }
        composeRule.onNodeWithTag("workspace.hosting.deployment.back").performClick()
        waitForTag("workspace.hosting.analytics")
    }

    @Test
    fun connectInstructionsListFiveStepsAndOpenTheTokensPage() {
        val viewModel = VercelConnectionViewModel(FakeVercelGateway(VercelRestoreUi.NoSavedAccount))
        composeRule.setContent {
            VercelticsTheme {
                ProviderDetailScreen(
                    provider = requireNotNull(IntegrationCatalog.provider("vercel")),
                    vercelConnectionViewModel = viewModel,
                    onBack = {},
                )
            }
        }

        waitForTag("vercel.tokenSteps")
        composeRule.onNodeWithText("How to get your token").assertIsDisplayed()
        listOf(
            "Go to vercel.com/account/tokens",
            "Tap \"Create Token\"",
            "Name it anything (e.g. Verceltics)",
            "Set scope to your account",
            "Copy and paste below",
        ).forEach { composeRule.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
        composeRule.onNodeWithTag("vercel.openTokensPage").assertIsDisplayed()
        composeRule.onNodeWithText("Open Vercel Tokens Page").assertIsDisplayed()
    }

    private fun setWorkspace(
        gateway: FakeVercelGateway,
        proAccess: ProAccess = ProAccess.Unlocked,
    ): VercelConnectionViewModel {
        val viewModel = VercelConnectionViewModel(gateway)
        composeRule.setContent {
            VercelticsTheme {
                CompositionLocalProvider(LocalProAccess provides proAccess) {
                    VercelWorkspaceScreen(
                        vercelConnectionViewModel = viewModel,
                        searchRequestId = 0,
                        refreshRequestId = 0,
                        onConnectProvider = {},
                    )
                }
            }
        }
        waitForTag("workspace.hosting.connected")
        return viewModel
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(tag: String): Float = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
}
