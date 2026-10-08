package com.apoorvdarshan.verceltics.ui.hosting

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderAccountMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val provider = checkNotNull(IntegrationCatalog.provider("netlify"))

    @Test
    fun menuListsEverySavedAccountWithTheActiveOneSelected() {
        setMenu(TWO_ACCOUNTS)

        composeRule.onNodeWithTag("test.accountMenuButton").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("test.accountMenu").assertIsDisplayed()
        composeRule.onNodeWithTag("test.account.primary").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("test.account.second").assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithText("Primary account").assertIsDisplayed()
        composeRule.onNodeWithText("second@example.com").assertIsDisplayed()
    }

    @Test
    fun tappingAnotherAccountSwitchesToItsId() {
        val switched = mutableListOf<String>()
        setMenu(TWO_ACCOUNTS, ProviderAccountMenuActions(onSwitchAccount = { switched += it }))

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()
        composeRule.onNodeWithTag("test.account.second").performClick()

        composeRule.runOnIdle { assertEquals(listOf("second"), switched) }
        composeRule.onAllNodesWithTag("test.accountMenu").assertCountEquals(0)
    }

    @Test
    fun tappingTheActiveAccountOnlyClosesTheMenu() {
        val switched = mutableListOf<String>()
        setMenu(TWO_ACCOUNTS, ProviderAccountMenuActions(onSwitchAccount = { switched += it }))

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()
        composeRule.onNodeWithTag("test.account.primary").performClick()

        composeRule.runOnIdle { assertEquals(emptyList<String>(), switched) }
        composeRule.onAllNodesWithTag("test.accountMenu").assertCountEquals(0)
    }

    @Test
    fun addAccountAndRemoveCurrentForwardTheirActions() {
        var added = 0
        var removeCurrent = 0
        setMenu(
            TWO_ACCOUNTS,
            ProviderAccountMenuActions(onAddAccount = { added += 1 }, onRemoveCurrent = { removeCurrent += 1 }),
        )

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()
        composeRule.onNodeWithTag("test.addAccount").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("test.accountMenuButton").performClick()
        composeRule.onNodeWithTag("test.removeCurrentAccount").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(1, added)
            assertEquals(1, removeCurrent)
        }
    }

    @Test
    fun removeAllOnlyAppearsWithSeveralAccounts() {
        var removeAll = 0
        setMenu(TWO_ACCOUNTS, ProviderAccountMenuActions(onRemoveAll = { removeAll += 1 }))

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()
        composeRule.onNodeWithTag("test.removeAllAccounts").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, removeAll) }
    }

    @Test
    fun singleAccountMenuHasNoRemoveAll() {
        setMenu(listOf(TWO_ACCOUNTS.first()))

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()

        composeRule.onNodeWithTag("test.removeCurrentAccount").assertIsDisplayed()
        composeRule.onNodeWithTag("test.addAccount").assertIsDisplayed()
        composeRule.onAllNodesWithTag("test.removeAllAccounts").assertCountEquals(0)
    }

    @Test
    fun disabledMenuDoesNotOpen() {
        setMenu(TWO_ACCOUNTS, enabled = false)

        composeRule.onNodeWithTag("test.accountMenuButton").performClick()

        composeRule.onAllNodesWithTag("test.accountMenu").assertCountEquals(0)
    }

    @Test
    fun removeCurrentConfirmationNamesTheAccountAndForwardsConfirmAndDismiss() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            VercelticsTheme {
                ProviderAccountRemovalDialogs(
                    providerName = "Netlify",
                    currentAccountName = "Primary account",
                    showRemoveCurrent = true,
                    showRemoveAll = false,
                    accountCount = 2,
                    enabled = true,
                    onConfirmRemoveCurrent = { confirmed += 1 },
                    onDismissRemoveCurrent = { dismissed += 1 },
                    onConfirmRemoveAll = {},
                    onDismissRemoveAll = {},
                    testTagPrefix = "test",
                )
            }
        }

        composeRule.onNodeWithTag("test.disconnectDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Remove Primary account?").assertIsDisplayed()
        composeRule.onAllNodesWithTag("test.removeAllDialog").assertCountEquals(0)
        composeRule.onNodeWithText("REMOVE ACCOUNT").performClick()
        composeRule.onNodeWithText("KEEP ACCOUNT").performClick()

        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun removeAllConfirmationIsDestructiveAndForwardsConfirmAndDismiss() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            VercelticsTheme {
                ProviderAccountRemovalDialogs(
                    providerName = "Netlify",
                    currentAccountName = "Primary account",
                    showRemoveCurrent = false,
                    showRemoveAll = true,
                    accountCount = 2,
                    enabled = true,
                    onConfirmRemoveCurrent = {},
                    onDismissRemoveCurrent = {},
                    onConfirmRemoveAll = { confirmed += 1 },
                    onDismissRemoveAll = { dismissed += 1 },
                    testTagPrefix = "test",
                )
            }
        }

        composeRule.onNodeWithTag("test.removeAllDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Remove all Netlify accounts?").assertIsDisplayed()
        composeRule.onAllNodesWithTag("test.disconnectDialog").assertCountEquals(0)
        composeRule.onNodeWithText("REMOVE ALL ACCOUNTS").performClick()
        composeRule.onNodeWithText("CANCEL").performClick()

        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    private fun setMenu(
        accounts: List<ProviderAccountUi>,
        actions: ProviderAccountMenuActions = ProviderAccountMenuActions(),
        enabled: Boolean = true,
    ) {
        composeRule.setContent {
            VercelticsTheme {
                ProviderAccountMenu(
                    provider = provider,
                    accounts = accounts,
                    actions = actions,
                    testTagPrefix = "test",
                    enabled = enabled,
                )
            }
        }
    }

    private companion object {
        val TWO_ACCOUNTS = listOf(
            ProviderAccountUi("primary", "Primary account", "primary@example.com", isActive = true),
            ProviderAccountUi("second", "Second account", "second@example.com"),
        )
    }
}
