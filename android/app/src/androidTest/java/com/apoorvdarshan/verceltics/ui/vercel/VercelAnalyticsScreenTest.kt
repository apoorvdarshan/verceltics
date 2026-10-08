package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsEnvironment
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsRange
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsUiState
import com.apoorvdarshan.verceltics.ui.VercelDeploymentUi
import com.apoorvdarshan.verceltics.ui.screens.VercelAnalyticsScreen
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VercelAnalyticsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var refreshes = 0
    private var selectedRange: VercelAnalyticsRange? = null
    private var openedUrl: String? = null
    private var openedDeployment: VercelDeploymentUi? = null

    @Test
    fun risingBounceRateReadsAsWorseWhileRisingTrafficReadsAsBetter() {
        setScreen(loadedState())

        composeRule.onNode(
            hasStateDescription("Worsened") and hasAnyAncestor(hasTestTag("workspace.hosting.analytics.stat.bounce-rate")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            hasStateDescription("Improved") and hasAnyAncestor(hasTestTag("workspace.hosting.analytics.stat.visitors")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("+5%", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("45%", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun fallingBounceRateReadsAsBetter() {
        setScreen(loadedState(VercelTestFixtures.analytics(bounce = 38.0, previousBounce = 44.0)))

        composeRule.onNode(
            hasStateDescription("Improved") and hasAnyAncestor(hasTestTag("workspace.hosting.analytics.stat.bounce-rate")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun longRangesShowLocksUntilLongHistoryIsProven() {
        var state by mutableStateOf(loadedState())
        composeRule.setContent { VercelticsTheme { Screen(state) } }

        composeRule.onNodeWithTag("workspace.hosting.analytics.range").performClick()
        composeRule.onNodeWithTag("workspace.hosting.analytics.range.3mo.lock", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.analytics.range.12mo.lock", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.analytics.range.30d.lock", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Last 3 Months").performClick()
        assertEquals(VercelAnalyticsRange.QUARTER, selectedRange)

        state = state.copy(hasLongAnalyticsHistory = true)
        composeRule.onNodeWithTag("workspace.hosting.analytics.range").performClick()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.analytics.range.3mo.lock", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun utmWordingDependsOnLongHistory() {
        var state by mutableStateOf(loadedState())
        composeRule.setContent { VercelticsTheme { Screen(state) } }

        scrollTo("workspace.hosting.analytics.breakdown.utm-parameters")
        composeRule.onNodeWithText("Requires Pro + Web Analytics Plus").assertIsDisplayed()

        state = state.copy(hasLongAnalyticsHistory = true)
        scrollTo("workspace.hosting.analytics.breakdown.utm-parameters")
        composeRule.onNodeWithText("Upgrade to Web Analytics Plus").assertIsDisplayed()
    }

    @Test
    fun countriesShowFlagsAndNames() {
        setScreen(loadedState())

        scrollTo("workspace.hosting.analytics.breakdown.countries")
        composeRule.onNodeWithText("🇮🇳", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(vercelCountryName("IN"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun staleDataBannerRetries() {
        setScreen(loadedState().copy(error = "Vercel is rate limiting requests."))

        composeRule.onNodeWithTag("workspace.hosting.analytics.error").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Vercel is rate limiting requests. Showing the last successful 7d · Production result.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.analytics.error.action").performClick()
        assertEquals(1, refreshes)
        composeRule.onNodeWithTag("workspace.hosting.analytics.chart").assertIsDisplayed()
    }

    @Test
    fun failureWithoutDataOffersFullScreenTryAgain() {
        setScreen(VercelAnalyticsUiState(projectId = VercelTestFixtures.FRESH.id, error = "Session expired."))

        composeRule.onNodeWithTag("workspace.hosting.analytics.errorState").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t load data").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.analytics.errorState.action").performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun firstLoadShowsSkeletonAndKeepsTheRangeUsable() {
        setScreen(VercelAnalyticsUiState(projectId = VercelTestFixtures.FRESH.id, isLoading = true))

        composeRule.onNodeWithTag("workspace.hosting.analytics.loading").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.analytics.range").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Last 24 Hours").performClick()
        assertEquals(VercelAnalyticsRange.DAY, selectedRange)
    }

    @Test
    fun headerDomainOpensTheSite() {
        setScreen(loadedState())

        composeRule.onNodeWithTag("workspace.hosting.analytics.domainLink").performClick()

        assertEquals("https://studio.example", openedUrl)
    }

    @Test
    fun chartOffersBounceRateAndAPeak() {
        setScreen(loadedState())

        composeRule.onNodeWithTag("workspace.hosting.analytics.chart.peak", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Peak 480", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("workspace.hosting.analytics.chart.metric.bounce_rate").performClick()
        composeRule.onNodeWithText("Peak 47%", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun deploymentsAndDomainsAreListedAndTappable() {
        setScreen(
            loadedState().copy(
                domains = listOf("studio.example", "studio-web.vercel.app"),
                recentDeployments = listOf(VercelTestFixtures.DEPLOYMENT),
                hasLoadedContext = true,
            ),
        )

        scrollTo("workspace.hosting.analytics.deployments")
        composeRule.onNodeWithTag("workspace.hosting.analytics.deployment.dpl_ready").performClick()
        assertEquals(VercelTestFixtures.DEPLOYMENT, openedDeployment)

        scrollTo("workspace.hosting.analytics.domains")
        composeRule.onNodeWithText("Custom Domain".uppercase(), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Vercel Alias".uppercase(), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("workspace.hosting.analytics.domain.studio-web.vercel.app").performClick()
        assertEquals("https://studio-web.vercel.app", openedUrl)
    }

    @Test
    fun unavailableAnalyticsHidesStatsButKeepsProjectContext() {
        setScreen(
            loadedState().copy(
                data = null,
                unavailableMessage = "Vercel Web Analytics is not available through token access right now.",
            ),
        )

        composeRule.onNodeWithTag("workspace.hosting.analytics.unavailable").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.analytics.stats").fetchSemanticsNodes().size)
        scrollTo("workspace.hosting.analytics.overview")
        composeRule.onNodeWithText("acme/web", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pullingDownReloadsTheReport() {
        setScreen(loadedState())

        composeRule.onNodeWithTag("workspace.hosting.analytics.grid").performTouchInput { swipeDown() }

        composeRule.waitUntil(5_000) { refreshes == 1 }
    }

    private fun loadedState(data: com.apoorvdarshan.verceltics.ui.VercelAnalyticsDataUi = VercelTestFixtures.analytics()) =
        VercelAnalyticsUiState(
            projectId = VercelTestFixtures.FRESH.id,
            selectedRange = VercelAnalyticsRange.WEEK,
            selectedEnvironment = VercelAnalyticsEnvironment.PRODUCTION,
            displayedRange = VercelAnalyticsRange.WEEK,
            displayedEnvironment = VercelAnalyticsEnvironment.PRODUCTION,
            data = data,
            lastUpdatedMillis = System.currentTimeMillis(),
            projectDetails = VercelTestFixtures.FRESH,
            domains = listOf("studio.example"),
        )

    private fun setScreen(state: VercelAnalyticsUiState) {
        composeRule.setContent { VercelticsTheme { Screen(state) } }
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: VercelAnalyticsUiState) {
        VercelAnalyticsScreen(
            project = VercelTestFixtures.FRESH,
            state = state,
            onBack = {},
            onRefresh = { refreshes += 1 },
            onRangeSelected = { selectedRange = it },
            onEnvironmentSelected = {},
            onOpenDeployment = { openedDeployment = it },
            onOpenUrl = { openedUrl = it },
        )
    }

    private fun scrollTo(tag: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }
}
