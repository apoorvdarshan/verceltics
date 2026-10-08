package com.apoorvdarshan.verceltics.ui.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthClientConfiguration
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredential
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthScopes
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthSession
import com.apoorvdarshan.verceltics.data.sites.AesGcmTestCipher
import com.apoorvdarshan.verceltics.data.sites.DirectExecutor
import com.apoorvdarshan.verceltics.data.sites.FakeProviderTransport
import com.apoorvdarshan.verceltics.data.sites.FakeResponse
import com.apoorvdarshan.verceltics.data.sites.MemoryBytesStore
import com.apoorvdarshan.verceltics.data.sites.RecordedRequest
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionRepository
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionStore
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import com.apoorvdarshan.verceltics.data.sites.TEST_NOW_MILLIS
import com.apoorvdarshan.verceltics.data.sites.TEST_ZONE
import com.apoorvdarshan.verceltics.data.sites.json
import com.apoorvdarshan.verceltics.data.sites.ok
import com.apoorvdarshan.verceltics.data.sites.testApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSiteServicesUiGatewayTest {
    private val stores = HashMap<SiteProvider, MemoryBytesStore>()
    private val connectionStore = SiteConnectionStore(
        SiteConnectionRepository({ provider -> stores.getOrPut(provider) { MemoryBytesStore() } }, AesGcmTestCipher()),
    ) { TEST_NOW_MILLIS }
    private val credentialStore = MemoryCredentialStore()
    private val authorizer = FakeAuthorizer()

    @Test
    fun apiKeyConnectValidatesPersistsEncryptedAndRestoresOffline() = runTest {
        val transport = FakeProviderTransport(::plausible)
        val gateway = gateway(transport)

        val dashboard = gateway.connect(
            "plausible",
            SiteServiceConnectionInputUi(
                SecretValue.of("plausible-secret"),
                mapOf("siteID" to " example.com ", "baseURL" to "https://ignored.example", "projectName" to ""),
            ),
        ).getOrThrow()

        assertEquals("example.com", dashboard.accountName)
        assertEquals(SiteServiceCacheState.LIVE, dashboard.cacheState)
        assertEquals("Visitors", dashboard.metrics.first().label)
        assertEquals(mapOf("siteID" to "example.com"), connectionStore.loadForRequest(SiteProvider.PLAUSIBLE)?.connection?.metadata)
        assertFalse(String(stores.getValue(SiteProvider.PLAUSIBLE).bytes!!, Charsets.ISO_8859_1).contains("plausible-secret"))

        val restored = gateway.restore().getOrThrow().services
        val available = restored.getValue("plausible") as SiteServiceRestoreUi.Available
        assertEquals(SiteServiceCacheState.CACHED_FRESH, available.dashboard.cacheState)
        assertEquals(SiteServiceRestoreUi.NotConnected, restored.getValue("umami"))
        assertEquals(SiteServiceProviderIds.toSet(), restored.keys)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun failedValidationPersistsNothingAndNeverEchoesTheCredential() = runTest {
        val transport = FakeProviderTransport { FakeResponse(401, json("error" to "Invalid key leaked-secret-key")) }
        val result = gateway(transport).connect(
            "plausible",
            SiteServiceConnectionInputUi(SecretValue.of("leaked-secret-key"), mapOf("siteID" to "example.com")),
        )

        val error = result.exceptionOrNull() as SiteServicesUiException
        assertEquals("Request failed (HTTP 401).", error.message)
        assertNull(connectionStore.loadForRequest(SiteProvider.PLAUSIBLE))
    }

    @Test
    fun umamiCloudConnectDropsSelfHostedFieldsAndStoresIdentity() = runTest {
        val transport = FakeProviderTransport { request ->
            when (request.path) {
                "/v1/me" -> ok(json("user" to mapOf("id" to "user-1", "username" to "ops")))
                "/v1/websites" -> ok(json("data" to emptyList<Any>()))
                else -> FakeResponse(404)
            }
        }
        val dashboard = gateway(transport).connect(
            "umami",
            SiteServiceConnectionInputUi(
                SecretValue.of("cloud-key"),
                mapOf("authMode" to "cloud", "baseURL" to "https://analytics.example.com"),
            ),
        ).getOrThrow()

        assertEquals("Umami Cloud", dashboard.accountName)
        assertEquals("ops", dashboard.accountDetail)
        val metadata = connectionStore.loadForRequest(SiteProvider.UMAMI)!!.connection.metadata
        assertEquals("cloud", metadata["authMode"])
        assertNull(metadata["baseURL"])
        assertEquals("user-1", metadata["umamiUserID"])
        assertEquals("https://api.umami.is/v1/", metadata["umamiEndpoint"])
    }

    @Test
    fun googleConnectAuthorizesValidatesThenSavesTheCredentialInItsSlot() = runTest {
        val transport = FakeProviderTransport { request ->
            assertEquals("signed-in-token", request.bearer)
            analyticsSummaries(request)
        }
        val gateway = gateway(transport)

        val dashboard = gateway.connectGoogle("googleAnalytics").getOrThrow()

        assertEquals(listOf(GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY), authorizer.authorizedScopes)
        assertEquals("Google Analytics · 0 properties", dashboard.accountName)
        assertEquals("owner@example.com", dashboard.accountDetail)
        assertEquals("No GA4 properties", dashboard.status)
        assertEquals("signed-in-token", credentialStore.credential?.accessToken?.use { it })
        val stored = connectionStore.loadForRequest(SiteProvider.GOOGLE_ANALYTICS)!!.connection
        assertNull(stored.credential)
        assertEquals("subject-1", stored.metadata["googleSubject"])
    }

    @Test
    fun googleValidationFailureLeavesNoCredentialBehind() = runTest {
        val result = gateway(FakeProviderTransport { FakeResponse(403, json("error" to mapOf("message" to "API disabled"))) }).connectGoogle("googleAnalytics")
        assertEquals("Request failed (HTTP 403): API disabled", result.exceptionOrNull()?.message)
        assertNull(credentialStore.credential)
        assertNull(connectionStore.loadForRequest(SiteProvider.GOOGLE_ANALYTICS))
    }

    @Test
    fun googleRefreshRetriesOnceWithAForcedTokenAfter401() = runTest {
        val transport = FakeProviderTransport { request ->
            if (request.bearer == "stale-token") FakeResponse(401, json("error" to mapOf("message" to "expired"))) else analyticsSummaries(request)
        }
        val gateway = gateway(transport)
        credentialStore.credential = credential("stale-token")
        connectionStore.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, "GA", null, emptyMap(),
            com.apoorvdarshan.verceltics.data.sites.SiteSnapshot(SiteProvider.GOOGLE_ANALYTICS, fetchedAtMillis = 1L),
        )
        authorizer.nextRefreshed = credential("fresh-token")

        val dashboard = gateway.refresh("googleAnalytics").getOrThrow()

        assertEquals(1, authorizer.refreshCalls)
        assertEquals("fresh-token", credentialStore.credential?.accessToken?.use { it })
        assertEquals(SiteServiceCacheState.LIVE, dashboard.cacheState)
        assertEquals("Google Analytics · 0 properties", connectionStore.loadForRequest(SiteProvider.GOOGLE_ANALYTICS)!!.connection.name)
    }

    @Test
    fun revokedGoogleAccessAsksTheUserToReconnect() = runTest {
        connectionStore.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, "GA", null, emptyMap(),
            com.apoorvdarshan.verceltics.data.sites.SiteSnapshot(SiteProvider.GOOGLE_ANALYTICS, fetchedAtMillis = 1L),
        )
        val result = gateway(FakeProviderTransport { error("unused") }).refresh("googleAnalytics")
        assertEquals(
            "Google access for Google Analytics expired or was revoked. Reconnect the account.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun detailWorkspacesAreCachedPerQueryAndForceRefreshRefetches() = runTest {
        val transport = FakeProviderTransport(::plausible)
        val gateway = gateway(transport)
        gateway.connect("plausible", SiteServiceConnectionInputUi(SecretValue.of("k"), mapOf("siteID" to "example.com"))).getOrThrow()
        val request = SiteServiceDetailRequestUi("plausible", null, SiteServiceDetailQueryUi())

        val first = gateway.loadDetail(request).getOrThrow()
        val afterFirst = transport.requests.size
        val cached = gateway.loadDetail(request).getOrThrow()
        assertEquals(afterFirst, transport.requests.size)
        assertEquals(first, cached)
        assertEquals("example.com", first.title)
        assertFalse(first.isPartial)

        gateway.loadDetail(request, forceRefresh = true).getOrThrow()
        assertTrue(transport.requests.size > afterFirst)
        val otherRange = request.copy(query = SiteServiceDetailQueryUi(preset = SiteDetailRangePresetUi.DAYS_7))
        val beforeOther = transport.requests.size
        gateway.loadDetail(otherRange).getOrThrow()
        assertTrue(transport.requests.size > beforeOther)
        val sevenDayRequest = transport.requests.last { it.body != null }
        assertEquals("2026-07-09", sevenDayRequest.json["date_range"]?.arrayValue?.first()?.stringValue)
    }

    @Test
    fun disconnectRemovesTheRecordSignsOutGoogleAndClearsCaches() = runTest {
        val transport = FakeProviderTransport(::analyticsSummaries)
        val gateway = gateway(transport)
        gateway.connectGoogle("googleAnalytics").getOrThrow()

        gateway.disconnect("googleAnalytics").getOrThrow()

        assertNull(connectionStore.loadForRequest(SiteProvider.GOOGLE_ANALYTICS))
        assertNull(credentialStore.credential)
        assertEquals(SiteServiceRestoreUi.NotConnected, gateway.restore().getOrThrow().services["googleAnalytics"])
    }

    @Test
    fun missingConnectionsAndUnknownProvidersFailWithSafeMessages() = runTest {
        val gateway = gateway(FakeProviderTransport { error("unused") })
        assertEquals("Connect Better Stack first.", gateway.refresh("betterStack").exceptionOrNull()?.message)
        assertEquals("This site service is not available.", gateway.refresh("vercel").exceptionOrNull()?.message)
        assertEquals(
            "Google Analytics connects with Google sign-in.",
            gateway.connect("googleAnalytics", SiteServiceConnectionInputUi(SecretValue.of("x"))).exceptionOrNull()?.message,
        )
    }

    @Test
    fun unconfiguredGoogleClientReportsConfigurationNeeded() = runTest {
        authorizer.configured = false
        val gateway = gateway(FakeProviderTransport { error("unused") })
        assertTrue(gateway.googleOAuthReadiness is SiteGoogleOAuthReadinessUi.ConfigurationNeeded)
        assertEquals(
            "Google sign-in is unavailable in this version. Contact support for help connecting.",
            gateway.connectGoogle("googleAnalytics").exceptionOrNull()?.message,
        )
        assertTrue(authorizer.authorizedScopes.isEmpty())
    }

    private fun gateway(transport: FakeProviderTransport) = NativeSiteServicesUiGateway(
        store = connectionStore,
        api = testApi(transport),
        googleSessions = mapOf(
            SiteProvider.GOOGLE_ANALYTICS to GoogleOAuthSession(
                "site.google-analytics", credentialStore, authorizer, DirectExecutor, { TEST_NOW_MILLIS },
            ),
        ),
        storageExecutor = DirectExecutor,
        workContext = EmptyCoroutineContext,
        nowMillis = { TEST_NOW_MILLIS },
        zone = { TEST_ZONE },
    )

    private fun plausible(request: RecordedRequest): FakeResponse = ok(
        json(
            "results" to listOf(mapOf("dimensions" to emptyList<Any>(), "metrics" to listOf(10, 20, 30, 1.5, 40, 50, 7))),
            "meta" to mapOf("total_rows" to 1),
        ),
    )

    private fun analyticsSummaries(request: RecordedRequest): FakeResponse =
        if (request.path == "/v1beta/accountSummaries") ok("{}") else FakeResponse(404)

    private class MemoryCredentialStore : GoogleOAuthCredentialStore {
        var credential: GoogleOAuthCredential? = null

        override fun load(): GoogleOAuthCredential? = credential

        override fun save(credential: GoogleOAuthCredential) {
            this.credential = credential
        }

        override fun delete() {
            credential = null
        }
    }

    private class FakeAuthorizer : GoogleOAuthAuthorizer {
        var configured = true
        var nextAuthorized = credential("signed-in-token")
        var nextRefreshed = credential("refreshed-token")
        var refreshCalls = 0
        val authorizedScopes = ArrayList<Set<String>>()

        override val configuration: GoogleOAuthClientConfiguration?
            get() = if (configured) {
                GoogleOAuthClientConfiguration("1-test.apps.googleusercontent.com", "com.googleusercontent.apps.1-test")
            } else {
                null
            }

        override suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential {
            authorizedScopes += scopes
            return nextAuthorized
        }

        override suspend fun refresh(credential: GoogleOAuthCredential): GoogleOAuthCredential {
            refreshCalls += 1
            return nextRefreshed
        }
    }

    private companion object {
        fun credential(token: String) = GoogleOAuthCredential(
            accessToken = SecretValue.of(token),
            refreshToken = SecretValue.of("refresh"),
            tokenType = "Bearer",
            scopes = GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY.toList(),
            expiresAtMillis = TEST_NOW_MILLIS + 3_600_000L,
            subject = "subject-1",
            email = "owner@example.com",
        )
    }
}
