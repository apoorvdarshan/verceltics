package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.screens.VercelWorkspaceScreen
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Vercel section of the Hosting account menu (iOS `ProviderAccountMenu`). */
class VercelAccountMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun reArmFirstLoadLatch() {
        VercelProjectsFirstLoad.resetForTesting()
    }

    @Test
    fun menuListsEverySavedAccountAboveTheOtherHostingProviders() {
        setWorkspace(MultiAccountFakeVercelGateway())

        openMenu()

        composeRule.onNodeWithTag("workspace.hosting.accountMenu.vercelSection").assertIsDisplayed()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.PERSONAL.id)).assertIsSelected()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.TEAM.id)).assertIsNotSelected()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.addVercel").assertExists()
        composeRule.onNodeWithTag("workspace.hosting.connectNetlify").assertExists()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeCurrent").assertExists()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeAll").assertExists()
        assertTrue(
            "Vercel accounts come before the other hosting providers.",
            top(accountTag(MultiAccountFakeVercelGateway.TEAM.id)) < top("workspace.hosting.connectNetlify"),
        )
    }

    @Test
    fun avatarsLoadForAccountsThatHaveOneAndOthersShowTheirInitial() {
        setWorkspace(MultiAccountFakeVercelGateway())

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(
                hasTestTag("vercel.accountAvatar.image") and hasAnyAncestor(hasTestTag("workspace.hosting.account.badge")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        openMenu()
        composeRule.onNode(
            hasTestTag("vercel.accountAvatar.initial") and
                hasAnyAncestor(hasTestTag(accountTag(MultiAccountFakeVercelGateway.TEAM.id))),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun switchingFromTheMenuShowsTheOtherAccountsProjects() {
        val gateway = MultiAccountFakeVercelGateway()
        setWorkspace(gateway)

        openMenu()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.TEAM.id)).performClick()

        waitForTag("workspace.hosting.project.prj_team")
        assertEquals(1, gateway.switchCalls.get())
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.project.prj_personal").fetchSemanticsNodes().size)
        assertTrue(composeRule.onAllNodesWithText("Studio token").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun addingAnAccountOpensTheConnectFormWithoutDisconnecting() {
        val gateway = MultiAccountFakeVercelGateway()
        setWorkspace(gateway)

        openMenu()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.addVercel").performClick()

        waitForTag("workspace.hosting.addAccount")
        composeRule.onNodeWithTag("vercel.token").assertIsDisplayed()
        composeRule.onNodeWithTag("vercel.tokenSteps").assertExists()
        composeRule.onNodeWithTag("workspace.hosting.addAccount.back").performClick()

        waitForTag("workspace.hosting.connected")
        composeRule.onNodeWithTag("workspace.hosting.project.prj_personal").assertExists()
        assertEquals(0, gateway.removals.get())
    }

    @Test
    fun connectingFromTheAddFormShowsTheNewAccountAndKeepsTheOthers() {
        setWorkspace(MultiAccountFakeVercelGateway())

        openMenu()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.addVercel").performClick()
        waitForTag("vercel.token")
        composeRule.onNodeWithTag("vercel.token").performTextInput("another-token")
        composeRule.onNodeWithTag("vercel.connect").performClick()

        waitForTag("workspace.hosting.project.prj_bare")
        openMenu()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.NEW.id)).assertIsSelected()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.PERSONAL.id)).assertExists()
        composeRule.onNodeWithTag(accountTag(MultiAccountFakeVercelGateway.TEAM.id)).assertExists()
    }

    @Test
    fun removingTheCurrentAccountAsksFirstThenShowsTheNextAccount() {
        val gateway = MultiAccountFakeVercelGateway()
        setWorkspace(gateway)

        openMenu()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeCurrent").performClick()
        composeRule.onNodeWithTag("workspace.hosting.removeAccountDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Remove Apoorv?").assertIsDisplayed()
        composeRule.onNodeWithText("CANCEL").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.removeAccountDialog").fetchSemanticsNodes().size)
        assertEquals("Cancelling removes nothing.", 0, gateway.removals.get())

        openMenu()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeCurrent").performClick()
        composeRule.onNodeWithText("REMOVE ACCOUNT").performClick()

        composeRule.waitUntil(5_000) { gateway.removals.get() == 1 }
        waitForTag("workspace.hosting.project.prj_team")
    }

    @Test
    fun removingAllAccountsAsksFirstThenDisconnects() {
        val gateway = MultiAccountFakeVercelGateway()
        setWorkspace(gateway)

        openMenu()
        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeAll").performClick()
        composeRule.onNodeWithTag("workspace.hosting.removeAllAccountsDialog").assertIsDisplayed()
        composeRule.onNodeWithText("REMOVE ALL ACCOUNTS").performClick()

        composeRule.waitUntil(5_000) { gateway.removeAllCalls.get() == 1 }
        waitForTag("workspace.hosting.empty")
    }

    @Test
    fun aSingleAccountOffersNoRemoveAll() {
        setWorkspace(MultiAccountFakeVercelGateway(savedIds = listOf(MultiAccountFakeVercelGateway.PERSONAL.id)))

        openMenu()

        composeRule.onNodeWithTag("workspace.hosting.accountMenu.removeCurrent").assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("workspace.hosting.accountMenu.removeAll").fetchSemanticsNodes().size)
    }

    @Test
    fun pullingDownTheProjectListRefreshesIt() {
        val gateway = MultiAccountFakeVercelGateway()
        setWorkspace(gateway)

        composeRule.onNodeWithTag("workspace.hosting.projectGrid").performTouchInput { swipeDown() }

        composeRule.waitUntil(5_000) { gateway.refreshCalls.get() == 1 }
    }

    private fun setWorkspace(gateway: MultiAccountFakeVercelGateway): VercelConnectionViewModel {
        val viewModel = VercelConnectionViewModel(gateway)
        composeRule.setContent {
            VercelticsTheme {
                VercelWorkspaceScreen(
                    vercelConnectionViewModel = viewModel,
                    searchRequestId = 0,
                    refreshRequestId = 0,
                    onConnectProvider = {},
                )
            }
        }
        waitForTag("workspace.hosting.connected")
        return viewModel
    }

    private fun openMenu() {
        composeRule.onNodeWithTag("workspace.hosting.account").performClick()
        waitForTag("workspace.hosting.accountMenu")
    }

    private fun accountTag(id: String) = "workspace.hosting.accountMenu.account.$id"

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(tag: String): Float = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
}
