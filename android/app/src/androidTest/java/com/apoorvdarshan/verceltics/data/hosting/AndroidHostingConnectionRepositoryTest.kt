package com.apoorvdarshan.verceltics.data.hosting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHostingConnectionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableMapOf<String, NoBackupAtomicFileStore>()
    private val stores by lazy {
        HostingProvider.entries.associateWith { testStore(HostingConnectionRepository.accountPath(it)) }
    }

    /** Every repository path, redirected under `accounts/test-…` so real accounts are never touched. */
    private fun testStore(path: String): NoBackupAtomicFileStore = synchronized(opened) {
        opened.getOrPut(path) { NoBackupAtomicFileStore(context, path.replaceFirst("accounts/", "accounts/test-")) }
    }

    private fun repository() = HostingConnectionRepository(
        storeFactory = ::testStore,
        cipher = AndroidKeystoreAccountCipher(TEST_KEY_ALIAS),
    )

    @Before
    fun prepare() = clean()

    @After
    fun cleanUp() = clean()

    @Test
    fun keystoreEncryptedSlotsRoundTripWithoutPlaintextAtRest() {
        val secret = "instrumented-aws-secret-that-must-never-appear-on-disk"
        val credentials = HostingCredentials.AwsAmplify(
            "AKIAIOSFODNN7EXAMPLE",
            SecretValue.of(secret),
            "eu-west-1",
            SecretValue.of("instrumented-session-token"),
        )
        val profile = HostingProfile("MPLE-eu-west-1", "AWS eu-west-1", null, null)
        repository().save(
            HostingStoredConnection(
                HostingAccount(profile, credentials, 1_000L, 2_000L),
                HostingSnapshot(HostingProvider.AWS_AMPLIFY, profile, emptyList(), 2_000L),
            ),
        )

        val encrypted = checkNotNull(stores.getValue(HostingProvider.AWS_AMPLIFY).read())
        assertFalse(encrypted.containsSubsequence(secret.encodeToByteArray()))
        assertFalse(encrypted.containsSubsequence("instrumented-session-token".encodeToByteArray()))

        val restored = checkNotNull(repository().load(HostingProvider.AWS_AMPLIFY))
        val restoredCredentials = restored.account.credentials as HostingCredentials.AwsAmplify
        assertEquals(SecretValue.of(secret), restoredCredentials.secretAccessKey)
        assertEquals("eu-west-1", restoredCredentials.region)
        assertEquals(profile, restored.account.profile)
    }

    @Test
    fun envelopeMovedToAnotherProviderSlotFailsAuthentication() {
        val profile = HostingProfile("acct", "Studio", null, null)
        repository().save(
            HostingStoredConnection(
                HostingAccount(profile, HostingCredentials.Render(SecretValue.of("render-key")), 1L, 1L),
                null,
            ),
        )
        stores.getValue(HostingProvider.HEROKU).write(checkNotNull(stores.getValue(HostingProvider.RENDER).read()))

        assertThrows(Exception::class.java) { repository().load(HostingProvider.HEROKU) }
        assertTrue(repository().load(HostingProvider.RENDER) != null)
    }

    @Test
    fun secondAccountGetsItsOwnKeystoreEncryptedSlotAndRemovingItKeepsTheFirst() {
        val first = HostingProfile("render-first", "First", null, null)
        val second = HostingProfile("render-second", "Second", null, null)
        val repository = repository()
        val store = HostingConnectionStore(repository)
        store.acceptValidatedConnection(
            store.saveValidatedConnection(
                HostingCredentials.Render(SecretValue.of("first-key")),
                HostingSnapshot(HostingProvider.RENDER, first, emptyList(), 1L),
            ),
        )
        val added = store.saveValidatedConnection(
            HostingCredentials.Render(SecretValue.of("second-key")),
            HostingSnapshot(HostingProvider.RENDER, second, emptyList(), 2L),
        )
        store.acceptValidatedConnection(added)

        assertEquals(listOf("render-first", "render-second"), store.accounts(HostingProvider.RENDER).map { it.profile?.id })
        assertEquals("render-second", repository().load(HostingProvider.RENDER)?.account?.profile?.id)
        assertTrue(store.switchAccount(HostingProvider.RENDER, AccountVaultLayout.PRIMARY_ACCOUNT_ID))
        assertEquals("render-first", repository().load(HostingProvider.RENDER)?.account?.profile?.id)

        assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, store.removeAccount(HostingProvider.RENDER, added.accountId))
        assertEquals(listOf("render-first"), store.accounts(HostingProvider.RENDER).map { it.profile?.id })
    }

    private fun clean() {
        stores.values.forEach(NoBackupAtomicFileStore::delete)
        HostingProvider.entries.forEach { provider ->
            runCatching { repository().delete(provider) }
        }
        synchronized(opened) { opened.values.forEach(NoBackupAtomicFileStore::delete) }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_KEY_ALIAS)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private companion object {
        const val TEST_KEY_ALIAS = "verceltics.account-storage.hosting.instrumented-test"
    }
}
