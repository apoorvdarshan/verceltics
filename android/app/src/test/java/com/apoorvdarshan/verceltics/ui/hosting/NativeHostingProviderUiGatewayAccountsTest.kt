package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultLayout
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.FirebaseGoogleCredentialSlots
import com.apoorvdarshan.verceltics.data.hosting.FirebaseGoogleSlotUndo
import com.apoorvdarshan.verceltics.data.hosting.FirebaseGoogleSlots
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpRequest
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingProviderApi
import com.apoorvdarshan.verceltics.data.hosting.HostingStoreFixture
import com.apoorvdarshan.verceltics.data.hosting.bearerToken
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHostingProviderUiGatewayAccountsTest {
    @Test
    fun addingSwitchingRotatingAndRemovingRenderAccounts() = runBlocking {
        Fixture().use { fixture ->
            val gateway = fixture.gateway
            gateway.connect(HostingCredentials.Render(SecretValue.of("token-alpha"))).getOrThrow()
            val beta = gateway.connect(HostingCredentials.Render(SecretValue.of("token-beta"))).getOrThrow()
            assertEquals("Beta team", beta.account.displayName)

            val accounts = gateway.accounts("render").getOrThrow()
            assertEquals(listOf("Alpha team", "Beta team"), accounts.map { it.displayName })
            assertEquals(listOf(false, true), accounts.map { it.isActive })
            assertEquals(listOf("alpha@studio.example", "beta@studio.example"), accounts.map { it.detail })
            assertEquals(beta.account.savedAccountId, accounts.last().id)

            // Requests always use the active account's credential.
            val switched = gateway.switchAccount("render", accounts.first().id).getOrThrow() as HostingRestoreUi.Available
            assertEquals("Alpha team", switched.dashboard.account.displayName)
            assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, switched.dashboard.account.savedAccountId)
            fixture.transport.requests.clear()
            gateway.refresh("render").getOrThrow()
            assertTrue(fixture.transport.requests.all { it.bearerToken() == "token-alpha" })

            // Reconnecting Alpha with a new token rotates it in place.
            gateway.connect(HostingCredentials.Render(SecretValue.of("token-alpha-rotated"))).getOrThrow()
            assertEquals(2, gateway.accounts("render").getOrThrow().size)
            fixture.transport.requests.clear()
            gateway.refresh("render").getOrThrow()
            assertTrue(fixture.transport.requests.all { it.bearerToken() == "token-alpha-rotated" })

            // Removing the active account restores the remaining one; removing it empties the provider.
            val afterRemoval = gateway.removeAccount("render", AccountVaultLayout.PRIMARY_ACCOUNT_ID).getOrThrow() as HostingRestoreUi.Available
            assertEquals("Beta team", afterRemoval.dashboard.account.displayName)
            assertEquals(listOf("Beta team"), gateway.accounts("render").getOrThrow().map { it.displayName })
            assertEquals(
                HostingRestoreUi.NotConnected,
                gateway.removeAccount("render", checkNotNull(afterRemoval.dashboard.account.savedAccountId)).getOrThrow(),
            )
            assertTrue(gateway.accounts("render").getOrThrow().isEmpty())
            assertTrue(gateway.switchAccount("render", "missing").isFailure)
        }
    }

    @Test
    fun removeAllClearsEveryAccountOfOnlyThatProvider() = runBlocking {
        Fixture().use { fixture ->
            fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("token-alpha"))).getOrThrow()
            fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("token-beta"))).getOrThrow()
            fixture.tokens[FirebaseGoogleSlots.SIGN_IN] = "ya29.alice"
            fixture.gateway.connect(HostingCredentials.Firebase("studio-prod")).getOrThrow()

            fixture.gateway.disconnect("render").getOrThrow()

            assertTrue(fixture.gateway.accounts("render").getOrThrow().isEmpty())
            assertEquals(1, fixture.gateway.accounts("firebase").getOrThrow().size)
            assertEquals("ya29.alice", fixture.tokens[FirebaseGoogleSlots.LEGACY])
        }
    }

    @Test
    fun firebaseAdoptsTheFreshSignInIntoEachAccountsOwnGoogleSlot() = runBlocking {
        Fixture().use { fixture ->
            val gateway = fixture.gateway
            fixture.tokens[FirebaseGoogleSlots.SIGN_IN] = "ya29.alice"
            val alice = gateway.connect(HostingCredentials.Firebase("studio-prod")).getOrThrow()
            assertEquals("studio-prod", alice.account.firebaseProjectId)
            assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, alice.account.savedAccountId)
            // The first account uses the original slot; the staging slot is cleared after adoption.
            assertEquals("ya29.alice", fixture.tokens[FirebaseGoogleSlots.LEGACY])
            assertNull(fixture.tokens[FirebaseGoogleSlots.SIGN_IN])

            // Adding a second Google identity never overwrites the first account's Google credential.
            fixture.tokens[FirebaseGoogleSlots.SIGN_IN] = "ya29.bob"
            val bob = gateway.connect(HostingCredentials.Firebase("studio-prod")).getOrThrow()
            val bobId = checkNotNull(bob.account.savedAccountId)
            assertEquals("ya29.bob", fixture.tokens["hosting.firebase.$bobId"])
            assertEquals("ya29.alice", fixture.tokens[FirebaseGoogleSlots.LEGACY])
            assertEquals(2, gateway.accounts("firebase").getOrThrow().size)

            // Refreshing reads the active account's slot.
            fixture.transport.requests.clear()
            gateway.refresh("firebase").getOrThrow()
            assertTrue(fixture.transport.requests.isNotEmpty())
            assertTrue(fixture.transport.requests.all { it.bearerToken() == "ya29.bob" })
            gateway.switchAccount("firebase", AccountVaultLayout.PRIMARY_ACCOUNT_ID).getOrThrow()
            fixture.transport.requests.clear()
            gateway.refresh("firebase").getOrThrow()
            assertTrue(fixture.transport.requests.all { it.bearerToken() == "ya29.alice" })

            // Renewing Alice's sign-in rotates her account in place.
            fixture.tokens[FirebaseGoogleSlots.SIGN_IN] = "ya29.alice-renewed"
            val renewed = gateway.connect(HostingCredentials.Firebase("studio-prod")).getOrThrow()
            assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, renewed.account.savedAccountId)
            assertEquals("ya29.alice-renewed", fixture.tokens[FirebaseGoogleSlots.LEGACY])
            assertEquals(2, gateway.accounts("firebase").getOrThrow().size)

            // Removing an account clears its Google slot; removing all clears every slot.
            gateway.removeAccount("firebase", bobId).getOrThrow()
            assertNull(fixture.tokens["hosting.firebase.$bobId"])
            assertEquals("ya29.alice-renewed", fixture.tokens[FirebaseGoogleSlots.LEGACY])
            gateway.disconnect("firebase").getOrThrow()
            assertTrue(fixture.tokens.keys.none { it.startsWith("hosting.firebase") })
        }
    }

    @Test
    fun failedFirebaseValidationAdoptsNothing() = runBlocking {
        Fixture(firebaseProjectExists = false).use { fixture ->
            fixture.tokens[FirebaseGoogleSlots.SIGN_IN] = "ya29.alice"
            assertTrue(fixture.gateway.connect(HostingCredentials.Firebase("studio-prod")).isFailure)
            assertNull(fixture.tokens[FirebaseGoogleSlots.LEGACY])
            assertEquals("ya29.alice", fixture.tokens[FirebaseGoogleSlots.SIGN_IN])
            assertTrue(fixture.gateway.accounts("firebase").getOrThrow().isEmpty())
        }
    }

    @Test
    fun launchProfileRefreshStoresNewNamesForEverySavedAccount() = runBlocking {
        Fixture().use { fixture ->
            fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("token-alpha"))).getOrThrow()
            fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("token-beta"))).getOrThrow()
            fixture.renamed = true

            val refreshed = fixture.gateway.refreshAccountProfiles("render").getOrThrow()

            assertEquals(listOf("Alpha team (renamed)", "Beta team (renamed)"), refreshed.map { it.displayName })
            assertEquals(refreshed, fixture.gateway.accounts("render").getOrThrow())
            val restored = fixture.gateway.restore().getOrThrow().getValue("render") as HostingRestoreUi.Available
            assertEquals("Beta team (renamed)", restored.dashboard.account.displayName)
            assertFalse(refreshed.toString().contains("token-"))
        }
    }

    /** Google slots held in memory: slot name to access token. */
    private class FakeSlots(private val tokens: MutableMap<String, String>) : FirebaseGoogleCredentialSlots {
        private val previous = mutableMapOf<String, String?>()

        override suspend fun copy(from: String, to: String): FirebaseGoogleSlotUndo? {
            val token = tokens[from] ?: return null
            previous[to] = tokens[to]
            tokens[to] = token
            return FirebaseGoogleSlotUndo(to, null)
        }

        override suspend fun undo(undo: FirebaseGoogleSlotUndo) {
            val prior = previous.remove(undo.slot)
            if (prior == null) tokens.remove(undo.slot) else tokens[undo.slot] = prior
        }

        override suspend fun clear(slot: String) {
            tokens.remove(slot)
        }
    }

    private class Fixture(private val firebaseProjectExists: Boolean = true) : AutoCloseable {
        val tokens: MutableMap<String, String> = ConcurrentHashMap()

        @Volatile
        var renamed = false
        val transport = FakeHostingTransport(::respond)
        val storage = HostingStoreFixture()
        private val storageExecutor = Executors.newSingleThreadExecutor()
        private val tokenSource = object : GoogleAccessTokenSource {
            override suspend fun accessToken(scopes: Set<String>): String? = tokens[FirebaseGoogleSlots.SIGN_IN]

            override suspend fun accessToken(slot: String, scopes: Set<String>): String? = tokens[slot]
        }
        val gateway = NativeHostingProviderUiGateway(
            connectionStore = HostingConnectionStore(storage.repository) { 1_000L },
            api = HostingProviderApi(transport, tokenSource),
            storageExecutor = storageExecutor,
            workDispatcher = Dispatchers.Default,
            nowMillis = { 1_000L },
            firebaseSlots = FakeSlots(tokens),
        )

        private fun respond(request: HostingHttpRequest): HttpResponse {
            val suffix = if (renamed) " (renamed)" else ""
            val token = request.bearerToken()
            return when (request.encodedPath) {
                "/v1/owners" -> when (token?.removeSuffix("-rotated")) {
                    "token-alpha" -> jsonResponse("""[{"owner":{"id":"tea_alpha","name":"Alpha team$suffix","email":"alpha@studio.example"}}]""")
                    "token-beta" -> jsonResponse("""[{"owner":{"id":"tea_beta","name":"Beta team$suffix","email":"beta@studio.example"}}]""")
                    else -> jsonResponse("{}", status = 401)
                }
                "/v1/services" -> jsonResponse("[]")
                "/v1beta1/projects/studio-prod/sites" -> if (firebaseProjectExists) {
                    jsonResponse("""{"sites":[{"name":"projects/studio-prod/sites/studio-prod"}]}""")
                } else {
                    jsonResponse("""{"error":{"message":"not found"}}""", status = 404)
                }
                "/v1/userinfo" -> when (token) {
                    "ya29.alice", "ya29.alice-renewed" -> jsonResponse("""{"sub":"alice","email":"alice@example.com"}""")
                    "ya29.bob" -> jsonResponse("""{"sub":"bob","email":"bob@example.com"}""")
                    else -> jsonResponse("{}", status = 401)
                }
                else -> jsonResponse("{}", status = 404)
            }
        }

        override fun close() {
            storageExecutor.shutdownNow()
        }
    }
}
