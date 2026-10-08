package com.apoorvdarshan.verceltics.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switching, adding, removing and in-place token rotation of the saved Vercel account list. */
class VercelAccountsTest {
    @Test
    fun connectingANewIdentityAddsItAndMakesItActive() {
        val accounts = VercelAccounts.EMPTY
            .connect(account("user_a", "token-a"), nowMillis = 100L)
            .connect(account("user_b", "token-b"), nowMillis = 200L)

        assertEquals(listOf("user_a", "user_b"), accounts.accounts.map(VercelAccount::id))
        assertEquals("user_b", accounts.activeAccountId)
        assertEquals(SecretValue.of("token-a"), accounts.find("user_a")?.token)
    }

    @Test
    fun reconnectingTheSameIdentityRotatesItsTokenInPlace() {
        val original = account("user_a", "old-token", createdAt = 10L)
            .withUsername("apoorv")
            .withLongAnalyticsHistory()
        val saved = VercelAccounts.EMPTY
            .connect(original, nowMillis = 10L)
            .connect(account("user_b", "token-b"), nowMillis = 20L)

        val reconnected = account("user_a", "new-token", name = "Apoorv Darshan", avatar = "abc123", createdAt = 500L)
        val rotated = saved.connect(reconnected, nowMillis = 500L)

        assertEquals("No duplicate is added.", listOf("user_a", "user_b"), rotated.accounts.map(VercelAccount::id))
        assertEquals("The rotated account becomes active.", "user_a", rotated.activeAccountId)
        val account = checkNotNull(rotated.find("user_a"))
        assertEquals(SecretValue.of("new-token"), account.token)
        assertEquals("Apoorv Darshan", account.displayName)
        assertEquals("abc123", account.avatar)
        assertEquals("First connection time is kept.", 10L, account.createdAtMillis)
        assertEquals(500L, account.updatedAtMillis)
        assertEquals("apoorv", account.username)
        assertTrue("Long analytics history survives rotation.", account.hasLongAnalyticsHistory)
        assertEquals(SecretValue.of("token-b"), rotated.find("user_b")?.token)
    }

    @Test
    fun switchingRequiresASavedAccount() {
        val accounts = twoAccounts()

        val switched = accounts.switchTo("user_a")

        assertEquals("user_a", switched.activeAccountId)
        assertSame("Switching to the active account changes nothing.", switched, switched.switchTo("user_a"))
        assertThrows(IllegalArgumentException::class.java) { accounts.switchTo("user_unknown") }
    }

    @Test
    fun removingTheActiveAccountActivatesTheFirstRemainingOne() {
        val accounts = VercelAccounts.EMPTY
            .connect(account("user_a", "a"), 1L)
            .connect(account("user_b", "b"), 2L)
            .connect(account("user_c", "c"), 3L)

        val remaining = accounts.remove("user_c")

        assertEquals(listOf("user_a", "user_b"), remaining.accounts.map(VercelAccount::id))
        assertEquals("user_a", remaining.activeAccountId)
    }

    @Test
    fun removingAnInactiveAccountKeepsTheActiveOne() {
        val remaining = twoAccounts().remove("user_a")

        assertEquals(listOf("user_b"), remaining.accounts.map(VercelAccount::id))
        assertEquals("user_b", remaining.activeAccountId)
    }

    @Test
    fun removingTheLastAccountLeavesNothingActive() {
        val accounts = VercelAccounts.EMPTY.connect(account("user_a", "a"), 1L)
        val unknown = accounts.remove("user_unknown")

        val empty = accounts.remove("user_a")

        assertSame(accounts, unknown)
        assertTrue(empty.isEmpty)
        assertNull(empty.activeAccountId)
        assertNull(empty.active)
    }

    @Test
    fun aMissingActiveIdFallsBackToTheFirstAccount() {
        val accounts = VercelAccounts.of(listOf(account("user_a", "a"), account("user_b", "b")), "user_gone")

        assertEquals("user_a", accounts.activeAccountId)
        assertNull(VercelAccounts.of(emptyList(), "user_gone").activeAccountId)
    }

    @Test
    fun duplicateIdentitiesAndTooManyAccountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VercelAccounts.of(listOf(account("user_a", "a"), account("user_a", "b")), "user_a")
        }
        var accounts = VercelAccounts.EMPTY
        repeat(VercelAccounts.MAX_ACCOUNTS) { index -> accounts = accounts.connect(account("user_$index", "t$index"), 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            accounts.connect(account("user_extra", "extra"), 1L)
        }
        assertEquals("Rotating a saved identity still works at the cap.", VercelAccounts.MAX_ACCOUNTS, accounts.connect(account("user_0", "new"), 2L).accounts.size)
    }

    @Test
    fun aLegacyAccountBecomesTheActiveAccountOfAnEmptyList() {
        val legacy = account("user_a", "legacy-token").withUsername("apoorv").withLongAnalyticsHistory()

        val migrated = VercelAccounts.EMPTY.adoptingLegacy(legacy)

        assertEquals("user_a", migrated.activeAccountId)
        val account = checkNotNull(migrated.active)
        assertEquals(SecretValue.of("legacy-token"), account.token)
        assertEquals("apoorv", account.username)
        assertTrue(account.hasLongAnalyticsHistory)
    }

    @Test
    fun aLegacyAccountIsAddedBesideOtherAccountsWithoutTakingOver() {
        val migrated = twoAccounts().adoptingLegacy(account("user_legacy", "legacy-token"))

        assertEquals(listOf("user_a", "user_b", "user_legacy"), migrated.accounts.map(VercelAccount::id))
        assertEquals("user_b", migrated.activeAccountId)
    }

    @Test
    fun anAlreadyMigratedLegacyAccountIsNotDuplicated() {
        val saved = VercelAccounts.EMPTY.connect(account("user_a", "token", updatedAt = 50L), 50L)

        val repeated = saved.adoptingLegacy(account("user_a", "token", updatedAt = 50L))

        assertSame(saved, repeated)
    }

    @Test
    fun aNewerLegacyCopyOfTheSameIdentityWinsButKeepsItsAnalyticsFlag() {
        val saved = VercelAccounts.EMPTY.connect(account("user_a", "old", updatedAt = 50L).withLongAnalyticsHistory(), 50L)

        val merged = saved.adoptingLegacy(account("user_a", "newer", updatedAt = 90L))

        val account = checkNotNull(merged.find("user_a"))
        assertEquals(SecretValue.of("newer"), account.token)
        assertTrue(account.hasLongAnalyticsHistory)
        assertEquals(1, merged.accounts.size)
    }

    @Test
    fun replacingKeepsOrderAndTheActiveAccount() {
        val accounts = twoAccounts()

        val replaced = accounts.replace(checkNotNull(accounts.find("user_a")).withUsername("renamed"))

        assertEquals(listOf("user_a", "user_b"), replaced.accounts.map(VercelAccount::id))
        assertEquals("renamed", replaced.find("user_a")?.username)
        assertEquals("user_b", replaced.activeAccountId)
        assertThrows(IllegalArgumentException::class.java) { accounts.replace(account("user_unknown", "x")) }
    }

    @Test
    fun printableFormNeverContainsTokens() {
        val printed = twoAccounts().toString()

        assertFalse(printed.contains("token-a"))
        assertTrue(printed.contains("user_a"))
    }

    private fun twoAccounts(): VercelAccounts = VercelAccounts.EMPTY
        .connect(account("user_a", "token-a"), 1L)
        .connect(account("user_b", "token-b"), 2L)

    private fun account(
        id: String,
        token: String,
        name: String = "Account $id",
        avatar: String? = null,
        createdAt: Long = 1L,
        updatedAt: Long = createdAt,
    ) = VercelAccount(
        id = id,
        displayName = name,
        email = "$id@example.com",
        token = SecretValue.of(token),
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
        avatar = avatar,
    )
}
