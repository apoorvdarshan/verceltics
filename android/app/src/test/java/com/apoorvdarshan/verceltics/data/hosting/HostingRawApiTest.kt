package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import java.io.IOException
import java.net.ProtocolException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostingRawApiTest {
    private val render = HostingCredentials.Render(SecretValue.of("rnd_secret_key_123"))

    @Test
    fun renderRequestsStayOnTheApiHostWithBearerAuth() = runTest {
        val transport = FakeHostingTransport { jsonResponse("[]") }
        val response = HostingRawApi(transport).send(render, ProviderRawRequest("GET", "/services?limit=100&cursor=a b"))
        val request = transport.requests.single()
        assertEquals(HostingHttpMethod.GET, request.method)
        assertEquals("/v1/services", request.encodedPath)
        assertEquals("limit=100&cursor=a%20b", request.encodedQuery)
        assertEquals("https://api.render.com/v1/services?limit=100&cursor=a%20b", request.uri().toString())
        assertEquals("rnd_secret_key_123", request.bearerToken())
        assertEquals("application/json", request.contentType)
        assertNull(request.bodyCopy())
        assertEquals(200, response.statusCode)
        assertEquals("[]", response.body)
    }

    @Test
    fun everyProviderTargetIsPinnedToItsOfficialHostAndBasePath() = runTest {
        val tokenSource = GoogleAccessTokenSource { "ya29.google-token" }
        val cases = listOf(
            HostingCredentials.Railway(SecretValue.of("railway-token"), RailwayTokenType.ACCOUNT) to "https://backboard.railway.com/graphql/v2",
            render to "https://api.render.com/v1/graphql/v2",
            HostingCredentials.DigitalOcean(SecretValue.of("do-token-123")) to "https://api.digitalocean.com/v2/graphql/v2",
            HostingCredentials.Heroku(SecretValue.of("heroku-token")) to "https://api.heroku.com/graphql/v2",
            HostingCredentials.Fly(SecretValue.of("fly-token-123"), "personal") to "https://api.machines.dev/v1/graphql/v2",
            HostingCredentials.Firebase("studio-prod") to "https://firebasehosting.googleapis.com/v1beta1/graphql/v2",
            HostingCredentials.AwsAmplify("AKIDEXAMPLE12345", SecretValue.of("aws-secret-key"), "eu-west-1", null) to
                "https://amplify.eu-west-1.amazonaws.com/graphql/v2",
        )
        cases.forEach { (credentials, expected) ->
            val transport = FakeHostingTransport { jsonResponse("{}") }
            HostingRawApi(transport, tokenSource).send(credentials, ProviderRawRequest("POST", "/graphql/v2", body = "{}"))
            assertEquals(credentials.provider.id, expected, transport.requests.single().uri().toString())
        }
        val netlify = FakeHostingTransport { jsonResponse("{}") }
        HostingRawApi(netlify).sendNetlify(SecretValue.of("nfp_token_1234"), ProviderRawRequest("GET", "/sites?per_page=100"))
        assertEquals("https://api.netlify.com/api/v1/sites?per_page=100", netlify.requests.single().uri().toString())
        assertEquals("nfp_token_1234", netlify.requests.single().bearerToken())
    }

    @Test
    fun pathsThatTryToChangeTheHostAreRejectedOrStayOnTheHost() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        val api = HostingRawApi(transport)
        listOf("https://evil.example/x", "//evil.example/x", "evil.example/x", "/x/../../admin").forEach { path ->
            try {
                api.send(render, ProviderRawRequest("GET", path))
                fail("Expected $path to be rejected")
            } catch (_: ProviderApiRequestException) {
            }
        }
        api.send(render, ProviderRawRequest("GET", "/@evil.example/x\\y"))
        val uri = transport.requests.single().uri()
        assertEquals("api.render.com", uri.host)
        assertNull(uri.userInfo)
        assertEquals("/v1/@evil.example/x%5Cy", uri.rawPath)
    }

    @Test
    fun digitalOceanPathsGetExactlyOneVersionPrefix() = runTest {
        assertEquals("/v2/account", HostingRawApi.normalizedRequestPath("digitalOcean", "/account"))
        assertEquals("/v2/apps?per_page=200", HostingRawApi.normalizedRequestPath("digitalOcean", "/v2/apps?per_page=200"))
        assertEquals("/v2?example=true", HostingRawApi.normalizedRequestPath("digitalOcean", "/v2?example=true"))
        assertEquals("/v2", HostingRawApi.normalizedRequestPath("digitalOcean", "/v2"))
        assertEquals("/v2/v2x", HostingRawApi.normalizedRequestPath("digitalOcean", "/v2x"))
        assertEquals("/sites", HostingRawApi.normalizedRequestPath("netlify", "/sites"))
        val transport = FakeHostingTransport { jsonResponse("{}") }
        val api = HostingRawApi(transport)
        val token = HostingCredentials.DigitalOcean(SecretValue.of("do-token-123"))
        api.send(token, ProviderRawRequest("GET", "/apps?per_page=200"))
        api.send(token, ProviderRawRequest("GET", "/v2/apps"))
        assertEquals(listOf("/v2/apps", "/v2/apps"), transport.paths())
    }

    @Test
    fun protectedHeadersAreSilentlyDroppedAndOthersForwarded() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        HostingRawApi(transport).send(
            render,
            ProviderRawRequest(
                "GET",
                "/services",
                headers = mapOf(
                    "Authorization" to "Bearer attacker",
                    "Host" to "evil.example",
                    "Content-Length" to "1",
                    "content-type" to "text/plain",
                    "X-Amz-Date" to "x",
                    "x-amz-content-sha256" to "x",
                    "Project-Access-Token" to "x",
                    "Cookie" to "a=b",
                    "Accept-Encoding" to "gzip",
                    "X-Request-Id" to "trace-1",
                    "Accept" to "application/xml",
                ),
            ),
        )
        val request = transport.requests.single()
        assertEquals(mapOf("X-Request-Id" to "trace-1", "Accept" to "application/xml"), request.headers)
        val prepared = request.prepare(Instant.EPOCH)
        assertEquals("application/xml", prepared.header("Accept"))
        assertEquals("Bearer rnd_secret_key_123", prepared.header("Authorization"))
        assertEquals("identity", prepared.header("Accept-Encoding"))
        assertTrue(HostingRawApi.isProtectedHeader("X-AMZ-Anything"))
        assertFalse(HostingRawApi.isProtectedHeader("X-Request-Id"))
    }

    @Test
    fun herokuSendsItsVersionedAcceptUnlessOverridden() = runTest {
        val transport = FakeHostingTransport { jsonResponse("[]") }
        val heroku = HostingCredentials.Heroku(SecretValue.of("heroku-token"))
        val api = HostingRawApi(transport)
        api.send(heroku, ProviderRawRequest("GET", "/apps"))
        api.send(heroku, ProviderRawRequest("GET", "/apps", headers = mapOf("accept" to "application/json")))
        assertEquals("application/vnd.heroku+json; version=3", transport.requests[0].headers["Accept"])
        assertEquals(mapOf("accept" to "application/json"), transport.requests[1].headers)
    }

    @Test
    fun railwayAlwaysPostsAndUsesTheTokenTypeHeader() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{\"data\":{}}") }
        val project = HostingCredentials.Railway(SecretValue.of("railway-project-token"), RailwayTokenType.PROJECT)
        HostingRawApi(transport).send(project, ProviderRawRequest("GET", "/graphql/v2", body = "{\"query\":\"{ me { id } }\"}"))
        val request = transport.requests.single()
        assertEquals(HostingHttpMethod.POST, request.method)
        assertEquals("{\"query\":\"{ me { id } }\"}", request.bodyText())
        assertEquals("railway-project-token", request.prepare(Instant.EPOCH).header("Project-Access-Token"))
        assertNull(request.prepare(Instant.EPOCH).header("Authorization"))
    }

    @Test
    fun bodiesFollowAndroidHttpRules() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        val api = HostingRawApi(transport)
        try {
            api.send(render, ProviderRawRequest("GET", "/services", body = "{}"))
            fail("Expected GET with a body to be rejected")
        } catch (error: ProviderApiRequestException) {
            assertEquals("GET requests can't include a body on Android. Clear the request body or choose another method.", error.message)
        }
        api.send(render, ProviderRawRequest("POST", "/services/srv-1/deploys"))
        assertArrayEquals(ByteArray(0), transport.requests.last().bodyCopy())
        api.send(render, ProviderRawRequest("DELETE", "/services/srv-1"))
        assertNull(transport.requests.last().bodyCopy())
        api.send(render, ProviderRawRequest("PATCH", "/services/srv-1", body = "{\"name\":\"x\"}", contentType = " application/merge-patch+json "))
        assertEquals("application/merge-patch+json", transport.requests.last().contentType)
        assertEquals(HostingHttpMethod.PATCH, transport.requests.last().method)
        api.send(render, ProviderRawRequest("PUT", "/blob", binaryBody = byteArrayOf(1, 2, 3), contentType = "application/octet-stream"))
        assertArrayEquals(byteArrayOf(1, 2, 3), transport.requests.last().bodyCopy())
        try {
            api.send(render, ProviderRawRequest("TRACE", "/services"))
            fail("Expected an unsupported method")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.", error.message)
        }
    }

    @Test
    fun httpErrorsComeBackAsResponsesWithSecretsRedacted() = runTest {
        val transport = FakeHostingTransport {
            jsonResponse(
                "{\"message\":\"bad token rnd_secret_key_123\"}",
                status = 401,
                headers = mapOf("Content-Type" to listOf("application/json"), "X-Echo" to listOf("rnd_secret_key_123")),
            )
        }
        val response = HostingRawApi(transport).send(render, ProviderRawRequest("GET", "/owners"))
        assertEquals(401, response.statusCode)
        assertFalse(response.isSuccess)
        assertEquals("{\"message\":\"bad token <redacted>\"}", response.body)
        assertEquals(listOf("Content-Type" to "application/json", "X-Echo" to "<redacted>"), response.headers)
    }

    @Test
    fun binaryResponsesAreBase64() = runTest {
        val transport = FakeHostingTransport { com.apoorvdarshan.verceltics.data.network.HttpResponse(200, byteArrayOf(0xff.toByte(), 0), emptyMap()) }
        val response = HostingRawApi(transport).send(render, ProviderRawRequest("GET", "/logo"))
        assertTrue(response.isBinary)
        assertEquals("/wA=", response.body)
    }

    @Test
    fun transportFailuresBecomeSafeMessages() = runTest {
        val failures = listOf(
            IOException("socket reset") to "Render could not be reached. Check your connection and try again.",
            ResponseTooLargeException(8) to "The Render response exceeded the safe 8 MB limit.",
            ProtocolException("PATCH") to "This device can't send GET requests to Render.",
        )
        failures.forEach { (failure, message) ->
            val api = HostingRawApi(FakeHostingTransport { throw failure })
            try {
                api.send(render, ProviderRawRequest("GET", "/services"))
                fail("Expected $failure")
            } catch (error: HostingApiException) {
                assertEquals(message, error.failure.message)
            }
        }
    }

    @Test
    fun firebaseUsesAFreshGoogleTokenOrAsksForSignIn() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        HostingRawApi(transport, GoogleAccessTokenSource { " ya29.token " })
            .send(HostingCredentials.Firebase("studio-prod"), ProviderRawRequest("GET", "/projects/studio-prod/sites"))
        assertEquals("ya29.token", transport.requests.single().bearerToken())
        assertEquals("/v1beta1/projects/studio-prod/sites", transport.requests.single().encodedPath)
        try {
            HostingRawApi(transport, GoogleAccessTokenSource.Unavailable)
                .send(HostingCredentials.Firebase("studio-prod"), ProviderRawRequest("GET", "/projects/studio-prod/sites"))
            fail("Expected Google sign-in")
        } catch (error: HostingApiException) {
            assertEquals(HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED, error.failure.kind)
        }
    }

    @Test
    fun amplifyRawRequestsAreStrictlyEncodedAndSigned() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        val credentials = HostingCredentials.AwsAmplify("AKIDEXAMPLE12345", SecretValue.of("aws-secret-key"), "us-east-1", SecretValue.of("session-token-1"))
        HostingRawApi(transport).send(credentials, ProviderRawRequest("GET", "/apps/d1/branches/feature%2Flogin/jobs?maxResults=5&next=a+b:c"))
        val request = transport.requests.single()
        assertEquals("/apps/d1/branches/feature%2Flogin/jobs", request.encodedPath)
        assertEquals("maxResults=5&next=a%2Bb%3Ac", request.encodedQuery)
        assertEquals("amplify.us-east-1.amazonaws.com", request.uri().host)

        val instant = Instant.parse("2026-10-09T12:00:00Z")
        val prepared = request.prepare(instant)
        val expected = AwsSigV4Signer.sign(
            method = "GET",
            canonicalUri = "/apps/d1/branches/feature%252Flogin/jobs",
            canonicalQuery = "maxResults=5&next=a%2Bb%3Ac",
            headers = listOf(
                "content-type" to "application/json",
                "host" to "amplify.us-east-1.amazonaws.com",
                "x-amz-date" to "20261009T120000Z",
                "x-amz-security-token" to "session-token-1",
            ),
            payloadSha256Hex = sha256Hex(ByteArray(0)),
            accessKeyId = "AKIDEXAMPLE12345",
            secretAccessKey = SecretValue.of("aws-secret-key"),
            region = "us-east-1",
            service = "amplify",
            instant = instant,
        )
        assertEquals(expected.authorization, prepared.header("Authorization"))
        assertTrue(prepared.header("Authorization")!!.contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"))
        assertEquals("session-token-1", prepared.header("X-Amz-Security-Token"))
        assertEquals("20261009T120000Z", prepared.header("X-Amz-Date"))
    }

    @Test
    fun oversizedBodiesAreRejectedBeforeSending() = runTest {
        val transport = FakeHostingTransport { jsonResponse("{}") }
        try {
            HostingRawApi(transport).send(
                render,
                ProviderRawRequest("POST", "/x", binaryBody = ByteArray(HostingHttpRequest.MAX_RAW_REQUEST_BODY_BYTES + 1)),
            )
            fail("Expected an oversized body")
        } catch (error: ProviderApiRequestException) {
            assertEquals("The request body must be 25 MB or smaller.", error.message)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun railwayIntrospectionBuildsTheLiveCatalog() = runTest {
        val transport = FakeHostingTransport {
            jsonResponse(
                """
                {"data":{"__schema":{"queryType":{"name":"Query"},"mutationType":null,"types":[
                  {"kind":"OBJECT","name":"Query","fields":[
                    {"name":"me","description":"The signed-in user","isDeprecated":false,"deprecationReason":null,"args":[],
                     "type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"OBJECT","name":"User","ofType":null}}}
                  ]},
                  {"kind":"OBJECT","name":"User","fields":[
                    {"name":"email","isDeprecated":false,"args":[],"type":{"kind":"SCALAR","name":"String","ofType":null}},
                    {"name":"id","isDeprecated":false,"args":[],"type":{"kind":"SCALAR","name":"ID","ofType":null}}
                  ]},
                  {"kind":"SCALAR","name":"String"},{"kind":"SCALAR","name":"ID"}
                ]}}}
                """.trimIndent(),
            )
        }
        val credentials = HostingCredentials.Railway(SecretValue.of("railway-token"), RailwayTokenType.ACCOUNT)
        val catalog = HostingRawApi(transport).railwayLiveCatalog(credentials)
        assertEquals("Live GraphQL v2", catalog.apiVersion)
        val me = catalog.operations.single()
        assertEquals("railway.Query.me", me.id)
        assertTrue(me.bodyTemplate.contains("query Verceltics_me { me { id email } }"))
        val request = transport.requests.single()
        assertEquals(HostingHttpMethod.POST, request.method)
        assertEquals("/graphql/v2", request.encodedPath)
        assertTrue(request.graphqlQuery().startsWith("query VercelticsIntrospection"))
    }

    @Test
    fun railwayIntrospectionErrorsAreReported() = runTest {
        val graphQlError = HostingRawApi(FakeHostingTransport { jsonResponse("{\"errors\":[{\"message\":\"Not Authorized\"}]}") })
        val credentials = HostingCredentials.Railway(SecretValue.of("railway-token"), RailwayTokenType.ACCOUNT)
        try {
            graphQlError.railwayLiveCatalog(credentials)
            fail("Expected an error")
        } catch (error: HostingApiException) {
            assertEquals("Not Authorized", error.failure.message)
        }
        val notJson = HostingRawApi(FakeHostingTransport { jsonResponse("<html>", status = 502) })
        try {
            notJson.railwayLiveCatalog(credentials)
            fail("Expected an error")
        } catch (error: HostingApiException) {
            assertEquals("Railway did not return its GraphQL schema.", error.failure.message)
        }
    }

    @Test
    fun explorerDefaultsMatchIosDefaultPaths() {
        fun link(provider: HostingProvider) = HostingLinkContext(provider)
        val app = resource("app 1", name = "edge", metadata = mapOf("appName" to "edge-app"))
        assertEquals("/graphql/v2", HostingApiDefaults.explorerPath(link(HostingProvider.RAILWAY), app))
        assertEquals("/services?limit=100", HostingApiDefaults.explorerPath(link(HostingProvider.RENDER)))
        assertEquals("/services/app%201", HostingApiDefaults.explorerPath(link(HostingProvider.RENDER), app))
        assertEquals("/apps?per_page=200", HostingApiDefaults.explorerPath(link(HostingProvider.DIGITAL_OCEAN)))
        assertEquals("/apps/app%201", HostingApiDefaults.explorerPath(link(HostingProvider.DIGITAL_OCEAN), app))
        assertEquals("/apps", HostingApiDefaults.explorerPath(link(HostingProvider.HEROKU)))
        assertEquals("/apps/app%201", HostingApiDefaults.explorerPath(link(HostingProvider.HEROKU), app))
        assertEquals("/apps?org_slug=personal", HostingApiDefaults.explorerPath(link(HostingProvider.FLY)))
        assertEquals("/apps?org_slug=acme", HostingApiDefaults.explorerPath(HostingLinkContext(HostingProvider.FLY, flyOrganization = "acme")))
        assertEquals("/apps/edge-app/machines", HostingApiDefaults.explorerPath(link(HostingProvider.FLY), app))
        assertEquals("/projects/PROJECT_ID/sites", HostingApiDefaults.explorerPath(link(HostingProvider.FIREBASE)))
        assertEquals(
            "/projects/studio-prod/sites",
            HostingApiDefaults.explorerPath(HostingLinkContext(HostingProvider.FIREBASE, firebaseProjectId = "studio-prod")),
        )
        assertEquals("/sites/app%201/releases?pageSize=50", HostingApiDefaults.explorerPath(link(HostingProvider.FIREBASE), app))
        assertEquals("/apps?maxResults=100", HostingApiDefaults.explorerPath(link(HostingProvider.AWS_AMPLIFY)))
        assertEquals("/apps/app%201", HostingApiDefaults.explorerPath(link(HostingProvider.AWS_AMPLIFY), app))
        assertEquals("/sites?per_page=100", HostingApiDefaults.netlifyExplorerPath())
        assertEquals("/sites/site-1", HostingApiDefaults.netlifyExplorerPath("site-1"))
        assertEquals("acme", HostingLinkContext.of(HostingCredentials.Fly(SecretValue.of("fly-token-123"), "acme")).flyOrganization)
    }

    @Test
    fun rawRequestValidationGuardsTheWireForm() {
        listOf("/a b", "//x", "x", "/a/../b", "/a/.%2e/b", "/a?b", "/a#b").forEach { path ->
            try {
                HostingHttpRequest.raw(HostingEndpoint.RENDER, HostingHttpMethod.GET, path, null, emptyList(), emptyMap(), null, null, HostingAuth.None)
                fail("Expected $path to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            HostingHttpRequest.raw(HostingEndpoint.RENDER, HostingHttpMethod.HEAD, "/x", null, emptyList(), emptyMap(), byteArrayOf(1), null, HostingAuth.None)
            fail("Expected HEAD with a body to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val request = HostingHttpRequest.raw(
            HostingEndpoint.RENDER,
            HostingHttpMethod.OPTIONS,
            "/",
            "a=1",
            listOf("a" to "1"),
            emptyMap(),
            null,
            null,
            HostingAuth.None,
        )
        assertTrue(request.isRaw)
        assertEquals("https://api.render.com/?a=1", request.uri().toString())
        assertFalse(HostingHttpRequest(HostingEndpoint.RENDER, HostingHttpMethod.GET, listOf("x"), auth = HostingAuth.None).isRaw)
    }
}
