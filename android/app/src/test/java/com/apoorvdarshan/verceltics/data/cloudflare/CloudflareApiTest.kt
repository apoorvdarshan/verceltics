package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareApiTest {
    @Test
    fun verificationAndInventoriesUseExactVersionedReadOnlyPathsAndBearerToken() {
        val client = RecordingHttpClient()
        val token = SecretValue.of("cloudflare-secret")
        val api = CloudflareApi(client, FakeParser())

        api.newVerifyTokenCall(token).execute()
        assertEquals("/client/v4/user/tokens/verify", client.lastPath)
        assertEquals(token, client.lastToken)

        val credential = CloudflareCredential.ApiToken(token)
        api.newAccountsPageCall(credential, page = 2, perPage = 50).execute()
        assertEquals("/client/v4/accounts", client.lastPath)
        assertEquals(listOf("page" to "2", "per_page" to "50"), client.lastQuery)

        api.newZonesPageCall(credential, "account-123", page = 3, perPage = 25).execute()
        assertEquals("/client/v4/zones", client.lastPath)
        assertEquals(
            listOf("account.id" to "account-123", "page" to "3", "per_page" to "25"),
            client.lastQuery,
        )

        api.newPagesProjectsPageCall(credential, "account-123", 1, 20).execute()
        assertEquals("/client/v4/accounts/account-123/pages/projects", client.lastPath)

        api.newWorkerScriptsCall(credential, "account-123").execute()
        assertEquals("/client/v4/accounts/account-123/workers/scripts", client.lastPath)
        assertTrue(client.lastQuery.isEmpty())
        assertEquals(5, client.requestCount)
        assertTrue(client.allHeaders.all { it.isEmpty() })
    }

    @Test
    fun globalApiKeySendsEmailAndKeyHeadersInsteadOfBearerOnEveryDashboardRequest() {
        val client = RecordingHttpClient()
        val api = CloudflareApi(client, FakeParser())
        val credential = CloudflareCredential.globalApiKey("  Owner@Example.com ", "global-key-123")

        val user = api.newUserCall(credential).execute()
        assertEquals("/client/v4/user", client.lastPath)
        assertEquals("user-1", user.id)
        api.newAccountsPageCall(credential, 1, 50).execute()
        api.newZonesPageCall(credential, "account-123", 1, 50).execute()
        api.newPagesProjectsPageCall(credential, "account-123", 1, 20).execute()
        api.newWorkerScriptsCall(credential, "account-123").execute()

        assertEquals(5, client.requestCount)
        assertTrue(client.allTokens.all { it == null })
        client.allHeaders.forEach { headers ->
            assertEquals(mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key-123"), headers)
        }
    }

    @Test
    fun globalApiKeyRejectionUsesCredentialNeutralCopy() {
        val error = assertThrows(CloudflareApiException::class.java) {
            CloudflareApi(RecordingHttpClient(401), FakeParser())
                .newUserCall(CloudflareCredential.globalApiKey("owner@example.com", "bad-key"))
                .execute()
        }
        assertEquals(CloudflareFailureKind.AUTHENTICATION, error.failure.kind)
        assertEquals("Cloudflare rejected this email and Global API Key.", error.failure.message)
    }

    @Test
    fun pathAndPaginationInputsAreBoundedBeforeTransport() {
        val client = RecordingHttpClient()
        val api = CloudflareApi(client, FakeParser())
        val token = CloudflareCredential.apiToken("token")

        assertThrows(IllegalArgumentException::class.java) {
            api.newPagesProjectsPageCall(token, "../../outside", 1, 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.newAccountsPageCall(token, 0, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.newAccountsPageCall(token, 1, 101)
        }
        assertEquals(0, client.requestCount)
    }

    @Test
    fun malformedErrorBodyCannotMaskAuthenticationClassificationOrLeakBody() {
        val secret = "never-log-cloudflare-token"
        val client = RecordingHttpClient(401, secret.encodeToByteArray())
        val error = assertThrows(CloudflareApiException::class.java) {
            CloudflareApi(client, FakeParser(throwWhileParsingError = true))
                .newVerifyTokenCall(SecretValue.of(secret))
                .execute()
        }

        assertEquals(CloudflareFailureKind.AUTHENTICATION, error.failure.kind)
        assertEquals(401, error.failure.statusCode)
        assertEquals(null, error.errorCode)
        assertFalse(error.toString().contains(secret))
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun authorizationRateLimitAndTemporaryFailuresStayDistinct() {
        val parser = FakeParser(errorCode = "provider-code")
        val token = SecretValue.of("token")

        fun failure(status: Int): CloudflareFailure = assertThrows(CloudflareApiException::class.java) {
            CloudflareApi(RecordingHttpClient(status), parser).newVerifyTokenCall(token).execute()
        }.failure

        assertEquals(CloudflareFailureKind.AUTHORIZATION, failure(403).kind)
        assertEquals(CloudflareFailureKind.RATE_LIMITED, failure(429).kind)
        assertEquals(CloudflareFailureKind.TEMPORARY, failure(503).kind)
    }

    @Test
    fun successfulHttpEnvelopeRejectionIsSafeAndProviderCodeIsRedacted() {
        val reflectedSecret = "reflected-provider-value"
        val error = assertThrows(CloudflareApiException::class.java) {
            CloudflareApi(
                RecordingHttpClient(),
                FakeParser(envelopeErrorCode = reflectedSecret),
            ).newVerifyTokenCall(SecretValue.of("token")).execute()
        }

        assertEquals(CloudflareFailureKind.PROVIDER_REJECTED, error.failure.kind)
        assertEquals(reflectedSecret, error.errorCode)
        assertFalse(error.toString().contains(reflectedSecret))
        assertTrue(error.toString().contains("<redacted>"))
    }

    @Test
    fun publicSurfaceHasNoMutationOrRawRequestEscapeHatch() {
        val methodNames = CloudflareReadApi::class.java.methods.map { it.name.lowercase() }
        assertTrue(methodNames.all { name ->
            listOf("create", "update", "delete", "post", "put", "patch", "purge", "raw")
                .none(name::contains)
        })
        assertEquals("https://api.cloudflare.com/", CloudflareApi.ORIGIN)
    }

    private class RecordingHttpClient(
        private val statusCode: Int = 200,
        private val responseBody: ByteArray = byteArrayOf(1),
    ) : ProviderHttpClient {
        var lastPath: String? = null
        var lastQuery: List<Pair<String, String>> = emptyList()
        var lastToken: SecretValue? = null
        var requestCount = 0
        val allHeaders = mutableListOf<Map<String, String>>()
        val allTokens = mutableListOf<SecretValue?>()

        override fun newGetCall(
            relativePath: String,
            queryParameters: List<Pair<String, String>>,
            bearerToken: SecretValue?,
            headers: Map<String, String>,
        ): CancelableCall<HttpResponse> {
            lastPath = relativePath
            lastQuery = queryParameters
            lastToken = bearerToken
            requestCount += 1
            allHeaders += headers
            allTokens += bearerToken
            return valueCall(HttpResponse(statusCode, responseBody, emptyMap()))
        }
    }

    private class FakeParser(
        private val errorCode: String? = null,
        private val throwWhileParsingError: Boolean = false,
        private val envelopeErrorCode: String? = null,
    ) : CloudflareJsonParser {
        private fun rejectIfConfigured() {
            if (envelopeErrorCode != null) throw CloudflareEnvelopeRejectedException(envelopeErrorCode)
        }

        override fun parseTokenVerification(bytes: ByteArray): CloudflareTokenVerification {
            rejectIfConfigured()
            return CloudflareTokenVerification("token-id", "active", null, null)
        }

        override fun parseUser(bytes: ByteArray): CloudflareUserIdentity {
            rejectIfConfigured()
            return CloudflareUserIdentity("user-1", "owner@example.com", "Ada", null, false)
        }

        override fun parseAccountsPage(bytes: ByteArray): CloudflarePage<CloudflareAccountSummary> {
            rejectIfConfigured()
            return CloudflarePage(listOf(ACCOUNT), 1, 1)
        }

        override fun parseZonesPage(bytes: ByteArray): CloudflarePage<CloudflareZone> {
            rejectIfConfigured()
            return CloudflarePage(listOf(ZONE), 1, 1)
        }

        override fun parsePagesProjectsPage(bytes: ByteArray): CloudflarePage<CloudflarePagesProject> {
            rejectIfConfigured()
            return CloudflarePage(listOf(PAGES_PROJECT), 1, 1)
        }

        override fun parseWorkerScripts(bytes: ByteArray): List<CloudflareWorkerScript> {
            rejectIfConfigured()
            return listOf(WORKER)
        }

        override fun parseErrorCode(bytes: ByteArray): String? {
            if (throwWhileParsingError) throw CloudflareResponseFormatException("malformed")
            return errorCode
        }
    }

    companion object {
        private val ACCOUNT = CloudflareAccountSummary("account-123", "Account", null, null)
        private val ZONE = CloudflareZone(
            "zone-1", "example.com", "active", "full", false, "account-123", "Account", "Free",
        )
        private val PAGES_PROJECT = CloudflarePagesProject(
            "pages-1", "Website", null, emptyList(), "main", null, "success",
        )
        private val WORKER = CloudflareWorkerScript(
            "worker-1", null, null, null, emptyList(), null, null,
        )

        private fun <T> valueCall(value: T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = value
            override fun cancel() = Unit
        }
    }
}
