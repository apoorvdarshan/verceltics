package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsEnvironment
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsRange
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsUiState
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelDeploymentDetailUiState
import com.apoorvdarshan.verceltics.ui.screens.VercelAnalyticsScreen
import com.apoorvdarshan.verceltics.ui.screens.VercelWorkspaceScreen
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** iOS regular-width layouts on 600dp+ windows: project grid and two-column details. */
class VercelTabletLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun reArmFirstLoadLatch() {
        VercelProjectsFirstLoad.resetForTesting()
    }

    @Test
    fun wideWindowsShowProjectsInAGrid() {
        val viewModel = VercelConnectionViewModel(FakeVercelGateway())
        setWide {
            VercelWorkspaceScreen(
                vercelConnectionViewModel = viewModel,
                searchRequestId = 0,
                refreshRequestId = 0,
                onConnectProvider = {},
            )
        }
        waitForTag("workspace.hosting.project.prj_fresh")

        composeRule.onNodeWithTag("workspace.hosting.projectGrid").assert(hasStateDescription("2 columns"))
        val fresh = bounds("workspace.hosting.project.prj_fresh")
        val docs = bounds("workspace.hosting.project.prj_docs")
        assertEquals("The two newest projects share a row.", fresh.top, docs.top, 1f)
        assertTrue(fresh.right <= docs.left)
        assertTrue(
            "Grid cards keep the iOS regular minimum height.",
            fresh.height >= 184f * pxPerDp("workspace.hosting.connected") - 1f,
        )
    }

    @Test
    fun wideWindowsLayAnalyticsPanelsSideBySide() {
        setWide {
            VercelAnalyticsScreen(
                project = VercelTestFixtures.FRESH,
                state = VercelAnalyticsUiState(
                    projectId = VercelTestFixtures.FRESH.id,
                    displayedRange = VercelAnalyticsRange.WEEK,
                    displayedEnvironment = VercelAnalyticsEnvironment.PRODUCTION,
                    data = VercelTestFixtures.analytics(),
                    projectDetails = VercelTestFixtures.FRESH,
                    domains = listOf("studio.example"),
                    recentDeployments = listOf(VercelTestFixtures.DEPLOYMENT),
                    hasLoadedContext = true,
                ),
                onBack = {},
                onRefresh = {},
                onRangeSelected = {},
                onEnvironmentSelected = {},
            )
        }

        composeRule.onNodeWithTag("workspace.hosting.analytics.grid").assert(hasStateDescription("2 columns"))
        composeRule.onNodeWithTag("workspace.hosting.analytics.grid")
            .performScrollToNode(hasTestTag("workspace.hosting.analytics.deployments"))
        val overview = bounds("workspace.hosting.analytics.overview")
        val deployments = bounds("workspace.hosting.analytics.deployments")
        assertEquals("Project and deployments panels share a row.", overview.top, deployments.top, 1f)
        assertTrue(overview.right <= deployments.left)
    }

    @Test
    fun wideWindowsPutDeploymentDetailsBesideBuildEvents() {
        setWide {
            VercelDeploymentDetailScreen(
                project = VercelTestFixtures.FRESH,
                deployment = VercelTestFixtures.DEPLOYMENT,
                state = VercelDeploymentDetailUiState(
                    deploymentId = VercelTestFixtures.DEPLOYMENT.id,
                    events = VercelTestFixtures.EVENTS,
                    hasLoaded = true,
                ),
                onBack = {},
                onRefresh = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithTag("workspace.hosting.deployment.list").assert(hasStateDescription("Two columns"))
        waitForTag("workspace.hosting.deployment.columns")
        val details = bounds("workspace.hosting.deployment.details")
        val events = bounds("workspace.hosting.deployment.events")
        assertEquals(details.top, events.top, 1f)
        assertTrue("Details sit to the left of the events.", details.right <= events.left)
        assertEquals("The details column is 300dp wide.", 300f * pxPerDp("workspace.hosting.deployment"), details.width, 2f)
    }

    private fun setWide(content: @Composable () -> Unit) {
        composeRule.setContent {
            VercelticsTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1_000.dp, 900.dp))) {
                    content()
                }
            }
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Pixels per dp inside the forced 1000dp window, whose density may be scaled to fit. */
    private fun pxPerDp(rootTag: String): Float = bounds(rootTag).width / WIDE_WIDTH_DP

    private companion object {
        const val WIDE_WIDTH_DP = 1_000f
    }
}
