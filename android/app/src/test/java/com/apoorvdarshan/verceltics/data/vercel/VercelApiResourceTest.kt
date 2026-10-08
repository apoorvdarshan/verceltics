package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelApiResourceTest {
    private val token = SecretValue.of("vercel-secret")

    @Test
    fun projectDetailUsesV9PathAndTeamScope() {
        val client = JsonClient("""{"id":"prj_web","name":"studio-web","framework":"nextjs"}""")
        val project = VercelApi(client, AndroidVercelJsonParser()).newProjectCall(token, "prj_web", "team_1").execute()

        assertEquals("/v9/projects/prj_web", client.relativePath)
        assertEquals(listOf("teamId" to "team_1"), client.query)
        assertEquals(token, client.bearerToken)
        assertEquals("studio-web", project.name)
    }

    @Test
    fun projectDomainsKeepOnlyVerifiedNonRedirectingNames() {
        val client = JsonClient(VercelResourceDecoderTest.DOMAINS_FIXTURE)
        val domains = VercelApi(client, AndroidVercelJsonParser()).newProjectDomainsCall(token, "prj_web", null).execute()

        assertEquals("/v9/projects/prj_web/domains", client.relativePath)
        assertTrue("Personal projects send no teamId.", client.query.isEmpty())
        assertEquals(listOf("studio.example", "studio-web.vercel.app"), domains)
    }

    @Test
    fun deploymentsUseV6WithProjectAndLimitAfterTeam() {
        val client = JsonClient(VercelResourceDecoderTest.DEPLOYMENTS_FIXTURE)
        val api = VercelApi(client, AndroidVercelJsonParser())

        val deployments = api.newDeploymentsCall(token, "prj_web", "team_1").execute()

        assertEquals("/v6/deployments", client.relativePath)
        assertEquals(listOf("teamId" to "team_1", "projectId" to "prj_web", "limit" to "6"), client.query)
        assertEquals(3, deployments.size)
        assertThrows(IllegalArgumentException::class.java) { api.newDeploymentsCall(token, "prj_web", null, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { api.newDeploymentsCall(token, " ", null) }
    }

    @Test
    fun deploymentEventsUseV3BackwardBuildEventsWithDefaultLimit() {
        val client = JsonClient(VercelResourceDecoderTest.EVENTS_FIXTURE)
        val api = VercelApi(client, AndroidVercelJsonParser())

        val events = api.newDeploymentEventsCall(token, "dpl_ready", "team_1").execute()

        assertEquals("/v3/deployments/dpl_ready/events", client.relativePath)
        assertEquals(
            listOf("teamId" to "team_1", "direction" to "backward", "limit" to "80", "builds" to "1"),
            client.query,
        )
        assertEquals(6, events.size)

        api.newDeploymentEventsCall(token, "studio-web-git-feature.vercel.app", null).execute()
        assertEquals("/v3/deployments/studio-web-git-feature.vercel.app/events", client.relativePath)
    }

    @Test
    fun identifiersThatCouldEscapeTheirPathSegmentAreRejectedBeforeAnyRequest() {
        val client = JsonClient("{}")
        val api = VercelApi(client, AndroidVercelJsonParser())
        val unsafe = listOf("", ".", "..", "../v2/user", "prj/../../x", "prj%2F..", "prj?teamId=x", "prj#x", "prj web", "ünïcode")

        unsafe.forEach { value ->
            assertThrows("Rejects '$value' as a project id.", IllegalArgumentException::class.java) {
                api.newProjectCall(token, value, null)
            }
            assertThrows("Rejects '$value' as a deployment id.", IllegalArgumentException::class.java) {
                api.newDeploymentEventsCall(token, value, null)
            }
        }
        assertEquals("No request was created for an unsafe identifier.", 0, client.requests)
        assertEquals("prj_AbC-1.2", VercelApi.requireSafePathSegment("prj_AbC-1.2", "id"))
    }

    @Test
    fun resourceFailuresMapToVercelErrorsWithoutLeakingTheBody() {
        val secretBody = """{"error":{"code":"forbidden","message":"never-leak-this"}}"""
        val error = assertThrows(VercelApiException::class.java) {
            VercelApi(JsonClient(secretBody, status = 403), AndroidVercelJsonParser())
                .newDeploymentsCall(token, "prj_web", null)
                .execute()
        }

        assertEquals(403, error.statusCode)
        assertEquals("Vercel rejected this personal token.", error.message)
        assertFalse(error.toString().contains("never-leak-this"))

        val notFound = assertThrows(VercelApiException::class.java) {
            VercelApi(JsonClient("{}", status = 404), AndroidVercelJsonParser()).newProjectCall(token, "prj", null).execute()
        }
        assertEquals(404, notFound.statusCode)
    }

    @Test
    fun validatedUserAccountStoresTheUsernameForProjectLinks() {
        val account = VercelApi(JsonClient("{}"), AndroidVercelJsonParser()).accountForValidatedUser(
            user = VercelUser("user_1", "apoorv", "apoorv@example.com", null, null),
            token = token,
            nowMillis = 5L,
        )

        assertEquals("apoorv", account.username)
        assertEquals("apoorv", account.displayName)
        assertFalse(account.hasLongAnalyticsHistory)
    }

    private class JsonClient(
        private val body: String,
        private val status: Int = 200,
    ) : ProviderHttpClient {
        var relativePath: String? = null
        var query: List<Pair<String, String>> = emptyList()
        var bearerToken: SecretValue? = null
        var requests = 0

        override fun newGetCall(
            relativePath: String,
            queryParameters: List<Pair<String, String>>,
            bearerToken: SecretValue?,
            headers: Map<String, String>,
        ): CancelableCall<HttpResponse> {
            requests += 1
            this.relativePath = relativePath
            this.query = queryParameters
            this.bearerToken = bearerToken
            return object : CancelableCall<HttpResponse> {
                override fun execute(): HttpResponse = HttpResponse(status, body.encodeToByteArray(), emptyMap())
                override fun cancel() = Unit
            }
        }
    }
}
