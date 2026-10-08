package com.apoorvdarshan.verceltics

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.apoorvdarshan.verceltics.ui.DebugVercelScenario
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareScenario
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyScenario
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedScenario
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleScenario
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** End-to-end first-launch gating in the debug shell host (opted in with an intent extra). */
class FirstLaunchShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun newUserSeesTheWelcomeThenAFullScreenConnectFlowWithoutTabs() {
        launch(welcomeCompleted = false).use { scenario ->
            waitForTag("firstLaunch.welcome")
            compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)

            compose.onNodeWithTag("firstLaunch.continue").performClick()

            waitForTag("firstLaunch.connect")
            compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)
            compose.onNodeWithTag("provider.vercel").performClick()
            waitForTag("providerDetail.vercel")
            compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForTag("firstLaunch.connect")
            scenario.onActivity { assertTrue(it.firstLaunchExperience?.hasCompletedWelcome == true) }
        }
    }

    @Test
    fun theFirstConnectionRevealsTheShellAndCompletesTheWelcome() {
        launch(welcomeCompleted = false).use { scenario ->
            waitForTag("firstLaunch.welcome")

            scenario.onActivity { it.configureGateway(DebugVercelScenario.CONNECTED) }

            waitForTag("mainNavigation.dock")
            compose.onNodeWithTag("mainNavigation.hosting").performClick()
            waitForTag("workspace.hosting.connected")
            compose.onAllNodesWithTag("firstLaunch.welcome").assertCountEquals(0)
            scenario.onActivity { assertTrue(it.firstLaunchExperience?.hasCompletedWelcome == true) }
        }
    }

    @Test
    fun returningUserWithoutConnectionsGoesStraightToTheConnectFlow() {
        launch(welcomeCompleted = true).use {
            waitForTag("firstLaunch.connect")
            compose.onAllNodesWithTag("firstLaunch.welcome").assertCountEquals(0)
            compose.onAllNodesWithTag("mainNavigation.dock").assertCountEquals(0)
        }
    }

    private fun launch(welcomeCompleted: Boolean): ActivityScenario<VercelticsTestActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), VercelticsTestActivity::class.java)
            .putExtra(VercelticsTestActivity.EXTRA_FIRST_LAUNCH, true)
            .putExtra(VercelticsTestActivity.EXTRA_FIRST_LAUNCH_COMPLETED, welcomeCompleted)
            .putExtra(VercelticsTestActivity.EXTRA_VERCEL_SCENARIO, DebugVercelScenario.DISCONNECTED.name)
            .putExtra(VercelticsTestActivity.EXTRA_NETLIFY_SCENARIO, DebugNetlifyScenario.DISCONNECTED.name)
            .putExtra(VercelticsTestActivity.EXTRA_PAGE_SPEED_SCENARIO, DebugPageSpeedScenario.DISCONNECTED.name)
            .putExtra(VercelticsTestActivity.EXTRA_CLOUDFLARE_SCENARIO, DebugCloudflareScenario.DISCONNECTED.name)
            .putExtra(
                VercelticsTestActivity.EXTRA_SEARCH_CONSOLE_SCENARIO,
                DebugSearchConsoleScenario.DISCONNECTED.name,
            )
        return ActivityScenario.launch(intent)
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
