package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.apoorvdarshan.verceltics.ui.VercelDeploymentDetailUiState
import com.apoorvdarshan.verceltics.ui.VercelDeploymentEventUi
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VercelDeploymentDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var refreshes = 0
    private val openedUrls = mutableListOf<String>()

    @Test
    fun headerOffersOpenAndInspectAndShowsStatus() {
        setScreen(loaded())

        composeRule.onNodeWithText("studio-web-abc.vercel.app", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ready", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.deployment.open").performClick()
        composeRule.onNodeWithTag("workspace.hosting.deployment.inspect").performClick()

        assertEquals(
            listOf("https://studio-web-abc.vercel.app", "https://vercel.com/studio/studio-web/abc"),
            openedUrls,
        )
    }

    @Test
    fun detailsListTargetBranchCommitAndCreator() {
        setScreen(loaded())

        scrollTo("workspace.hosting.deployment.details")
        listOf("Production", "main", "0f1e2d3c4b5a", "apoorv", "acme/web").forEach {
            composeRule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun buildEventsRenderNewestFirstAsReturned() {
        setScreen(loaded())

        scrollTo("workspace.hosting.deployment.events")
        composeRule.onNodeWithText("Compiled successfully", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("COMMAND", useUnmergedTree = true).assertExists()
        assertEquals(3, composeRule.onAllNodesWithTag("workspace.hosting.deployment.event", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun atMostEightyEventsAreRendered() {
        val many = (0 until 120).map { VercelDeploymentEventUi("$it", "stdout", it.toLong(), "line $it", null) }
        setScreen(VercelDeploymentDetailUiState(deploymentId = VercelTestFixtures.DEPLOYMENT.id, events = many, hasLoaded = true))

        scrollTo("workspace.hosting.deployment.events")
        assertEquals(
            VERCEL_MAX_RENDERED_EVENTS,
            composeRule.onAllNodesWithTag("workspace.hosting.deployment.event", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun failedRefreshKeepsEventsAndOffersRetry() {
        setScreen(loaded().copy(error = "Vercel timed out."))

        scrollTo("workspace.hosting.deployment.events")
        composeRule.onNodeWithText("Event refresh failed", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Vercel timed out. Showing the last successful result.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("workspace.hosting.deployment.events.retry.action").performClick()
        assertEquals(1, refreshes)
        composeRule.onNodeWithText("Compiled successfully", useUnmergedTree = true).assertExists()
    }

    @Test
    fun firstLoadFailureExplainsTheError() {
        setScreen(VercelDeploymentDetailUiState(deploymentId = VercelTestFixtures.DEPLOYMENT.id, error = "Vercel could not find the requested resource."))

        scrollTo("workspace.hosting.deployment.events")
        composeRule.onNodeWithTag("workspace.hosting.deployment.events.error", useUnmergedTree = true).assertExists()
    }

    @Test
    fun loadingAndEmptyStatesAreDistinct() {
        setScreen(VercelDeploymentDetailUiState(deploymentId = VercelTestFixtures.DEPLOYMENT.id, isLoading = true))
        scrollTo("workspace.hosting.deployment.events")
        composeRule.onNodeWithText("Loading events", useUnmergedTree = true).assertExists()
    }

    @Test
    fun emptyEventsSayNoneWereReturned() {
        setScreen(VercelDeploymentDetailUiState(deploymentId = VercelTestFixtures.DEPLOYMENT.id, hasLoaded = true))
        scrollTo("workspace.hosting.deployment.events")
        composeRule.onNodeWithText("No build events returned", useUnmergedTree = true).assertExists()
    }

    @Test
    fun refreshActionReloadsEvents() {
        setScreen(loaded())

        composeRule.onNodeWithTag("workspace.hosting.deployment.refresh").performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun pullingDownReloadsBuildEvents() {
        setScreen(loaded())

        composeRule.onNodeWithTag("workspace.hosting.deployment.list").performTouchInput { swipeDown() }

        composeRule.waitUntil(5_000) { refreshes == 1 }
    }

    @Test
    fun phonesStackDetailsAboveEvents() {
        setScreen(loaded())

        composeRule.onNodeWithTag("workspace.hosting.deployment.list")
            .assert(hasStateDescription("One column"))
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.deployment.columns").fetchSemanticsNodes().size)
    }

    private fun loaded() = VercelDeploymentDetailUiState(
        deploymentId = VercelTestFixtures.DEPLOYMENT.id,
        events = VercelTestFixtures.EVENTS,
        hasLoaded = true,
    )

    private fun setScreen(state: VercelDeploymentDetailUiState) {
        composeRule.setContent {
            VercelticsTheme {
                VercelDeploymentDetailScreen(
                    project = VercelTestFixtures.FRESH,
                    deployment = VercelTestFixtures.DEPLOYMENT,
                    state = state,
                    onBack = {},
                    onRefresh = { refreshes += 1 },
                    onOpenUrl = { openedUrls += it },
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }
}
