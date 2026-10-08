package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareToolsApiTest {
    private val token = SecretValue.of("cf-secret-token")

    @Test
    fun rawRequestSendsPinnedRequestWithTokenAndMeasuresElapsedTime() {
        val transport = RecordingToolsTransport {
            jsonResponse(403, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""", mapOf("CF-Ray" to listOf("a", "b")))
        }
        var clock = 1_000_000_000L
        val api = CloudflareToolsApi(transport) { clock.also { clock += 37_000_000L } }

        val response = api.newRawRequestCall(
            token,
            CloudflareExplorerDraft(path = "/accounts", queryText = "page=1"),
            confirmation = null,
        ).execute()

        assertEquals(403, response.statusCode)
        assertEquals(37L, response.elapsedMillis)
        assertEquals("a, b", response.headers["cf-ray"])
        assertEquals("https://api.cloudflare.com/client/v4/accounts?page=1", transport.requests.single().uri.toString())
        assertEquals(listOf("cf-secret-token"), transport.tokens)
        assertFalse(response.toString().contains("Authentication error"))
    }

    @Test
    fun unconfirmedWritesAndInvalidDraftsNeverReachTheTransport() {
        val transport = RecordingToolsTransport()
        val api = CloudflareToolsApi(transport)
        val write = assertThrows(CloudflareToolsException::class.java) {
            api.newRawRequestCall(token, CloudflareExplorerDraft(method = CloudflareHttpMethod.POST, path = "/zones"), null)
        }
        assertEquals(CloudflareToolsFailureKind.CONFIRMATION_REQUIRED, write.kind)
        assertThrows(CloudflareToolsException::class.java) {
            api.newRawRequestCall(token, CloudflareExplorerDraft(path = "https://evil.example/"), null)
        }
        assertTrue(transport.requests.isEmpty())

        api.newRawRequestCall(
            token,
            CloudflareExplorerDraft(method = CloudflareHttpMethod.PATCH, path = "/zones/z", bodyText = "{\"paused\":true}"),
            CloudflareMutationConfirmation("/zones/z"),
        ).execute()
        val sent = transport.requests.single()
        assertEquals(CloudflareHttpMethod.PATCH, sent.method)
        assertEquals("application/json", sent.contentType)
        assertEquals("{\"paused\":true}", transport.lastBody)
    }

    @Test
    fun graphQLPostsQueryAndVariablesAndMapsTokenScopeFailures() {
        val transport = RecordingToolsTransport { jsonResponse(200, """{"data":{"viewer":{}}}""") }
        val api = CloudflareToolsApi(transport)
        api.newGraphQLCall(token, "query { viewer { zones { zoneTag } } }", mapOf("tag" to ProviderJsonValue.Str("abc"))).execute()
        val request = transport.requests.single()
        assertEquals(CloudflareHttpMethod.POST, request.method)
        assertEquals("/client/v4/graphql", request.uri.rawPath)
        assertEquals("application/json", request.contentType)
        val body = ProviderJsonParser.parse(transport.lastBody!!)
        assertEquals("query { viewer { zones { zoneTag } } }", body["query"]?.stringValue)
        assertEquals("abc", body["variables"]?.get("tag")?.stringValue)

        assertThrows(CloudflareToolsException::class.java) { api.newGraphQLCall(token, "  ") }

        val forbidden = CloudflareToolsApi(
            RecordingToolsTransport { jsonResponse(403, """{"errors":[{"code":10000,"message":"not entitled"}]}""") },
        )
        val error = assertThrows(CloudflareToolsException::class.java) {
            forbidden.newGraphQLCall(token, "query { a }", requiredPermission = "Account Analytics Read").execute()
        }
        assertEquals(CloudflareToolsFailureKind.AUTHORIZATION, error.kind)
        assertEquals(
            "This API token can’t access Cloudflare GraphQL Analytics. Add the “Account Analytics Read” permission to the token, then try again." +
                "\nCloudflare: not entitled [code 10000]",
            error.message,
        )

        val rejected = CloudflareToolsApi(RecordingToolsTransport { jsonResponse(401, "{}") })
        assertEquals(
            CloudflareToolsFailureKind.AUTHENTICATION,
            assertThrows(CloudflareToolsException::class.java) { rejected.newGraphQLCall(token, "query { a }").execute() }.kind,
        )
    }

    @Test
    fun accountDetailParsesSettingsAndParentOrganization() {
        val transport = RecordingToolsTransport {
            jsonResponse(
                200,
                """{"success":true,"result":{"id":"acc-1","name":"Studio","type":"enterprise","created_on":"2024-01-02T03:04:05Z",
                   "settings":{"enforce_twofactor":true,"abuse_contact_email":"abuse@example.com"},
                   "managed_by":{"parent_org_id":"org-1","parent_org_name":"Parent"}}}""",
            )
        }
        val detail = CloudflareToolsApi(transport).newAccountDetailCall(token, "acc/1").execute()
        assertEquals("/client/v4/accounts/acc%2F1", transport.requests.single().uri.rawPath)
        assertEquals(
            CloudflareAccountDetail("acc-1", "Studio", "enterprise", "2024-01-02T03:04:05Z", true, "abuse@example.com", "org-1", "Parent"),
            detail,
        )
        assertTrue(detail.isManaged)
    }

    @Test
    fun membersFollowTotalPagesAndStopWithoutIt() {
        val pages = mapOf(
            "1" to """{"success":true,"result":[{"id":"m1","email":"a@example.com","status":"accepted"}],"result_info":{"page":1,"total_pages":2}}""",
            "2" to """{"success":true,"result":[{"id":"m2","user":{"first_name":"Ada","last_name":"L","email":"ada@example.com","two_factor_authentication_enabled":true},"roles":[{"id":"r","name":"Admin"}]}],"result_info":{"page":2,"total_pages":2}}""",
        )
        val transport = RecordingToolsTransport { request ->
            val page = request.uri.rawQuery.split('&').first { it.startsWith("page=") }.removePrefix("page=")
            jsonResponse(200, pages.getValue(page))
        }
        val members = CloudflareToolsApi(transport).newMembersCall(token, "acc").execute()
        assertEquals(listOf("m1", "m2"), members.map { it.id })
        assertEquals("Ada L", members[1].displayName)
        assertTrue(members[1].twoFactorEnabled)
        assertEquals(listOf("Admin"), members[1].roles.map { it.name })
        assertEquals(listOf("page=1&per_page=50", "page=2&per_page=50"), transport.requests.map { it.uri.rawQuery })

        val single = RecordingToolsTransport { jsonResponse(200, """{"success":true,"result":[{"id":"r1","name":"Admin"}]}""") }
        assertEquals(1, CloudflareToolsApi(single).newRolesCall(token, "acc").execute().size)
        assertEquals(1, single.requests.size)
    }

    @Test
    fun repeatedPagesStopSafely() {
        val transport = RecordingToolsTransport {
            jsonResponse(200, """{"success":true,"result":[{"id":"m1"}],"result_info":{"total_pages":50}}""")
        }
        val error = assertThrows(CloudflareToolsException::class.java) {
            CloudflareToolsApi(transport).newMembersCall(token, "acc").execute()
        }
        assertEquals("Cloudflare repeated a results page, so loading stopped safely.", error.message)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun rolesParsePermissionGrants() {
        val transport = RecordingToolsTransport {
            jsonResponse(
                200,
                """{"success":true,"result":[{"id":"r1","name":"DNS","description":"DNS only","permissions":{"zones":{"read":true,"write":false},"dns_records":{"read":true,"write":true},"bogus":3}}]}""",
            )
        }
        val role = CloudflareToolsApi(transport).newRolesCall(token, "acc").execute().single()
        assertEquals(setOf("zones", "dns_records"), role.permissions.keys)
        assertEquals("dns records: read/write  ·  zones: read", role.permissionSummary)
    }

    @Test
    fun auditEventsUseBoundedNewestFirstWindow() {
        val transport = RecordingToolsTransport {
            jsonResponse(
                200,
                """{"success":true,"result":[{"id":"e1","action":{"type":"update","result":"failure","time":"2026-10-01T10:00:00Z"},
                   "actor":{"email":"me@example.com","ip_address":"192.0.2.1"},"raw":{"method":"PATCH","status_code":403,"uri":"/client/v4/x"},
                   "resource":{"product":"dns","scope":{"zone":"z"}},"zone":{"id":"z","name":"example.com"}}]}""",
            )
        }
        val since = Instant.parse("2026-10-02T00:00:00.123Z")
        val before = Instant.parse("2026-10-09T00:00:00.999Z")
        val event = CloudflareToolsApi(transport).newAuditEventsCall(token, "acc", since, before, limit = 5_000).execute().single()
        assertEquals(
            "before=2026-10-09T00%3A00%3A00Z&direction=desc&limit=1000&since=2026-10-02T00%3A00%3A00Z",
            transport.requests.single().uri.rawQuery,
        )
        assertEquals("/client/v4/accounts/acc/logs/audit", transport.requests.single().uri.rawPath)
        assertEquals("e1", event.id)
        assertTrue(event.isFailure)
        assertEquals("Update", event.title)
        assertEquals(403, event.rawStatusCode)
        assertEquals("zone: z", CloudflareToolsFormat.displayText(event.resourceScope!!))
        assertThrows(CloudflareToolsException::class.java) {
            CloudflareToolsApi(transport).newAuditEventsCall(token, "acc", before, since)
        }
    }

    @Test
    fun unsuccessfulEnvelopesAndHttpFailuresUseScopeAwareMessages() {
        val failed = RecordingToolsTransport {
            jsonResponse(200, """{"success":false,"errors":[{"code":7003,"message":"Could not route"}]}""")
        }
        val envelopeError = assertThrows(CloudflareToolsException::class.java) {
            CloudflareToolsApi(failed).newAccountDetailCall(token, "acc").execute()
        }
        assertEquals(CloudflareToolsFailureKind.PROVIDER, envelopeError.kind)
        assertEquals("Cloudflare request failed (200): Could not route [code 7003]", envelopeError.message)

        val forbidden = RecordingToolsTransport { jsonResponse(403, "not json") }
        val scopeError = assertThrows(CloudflareToolsException::class.java) {
            CloudflareToolsApi(forbidden).newMembersCall(token, "acc").execute()
        }
        assertEquals(
            "This API token can’t access account members. Add the “Account Settings Read” permission to the token, then try again.",
            scopeError.message,
        )

        val missing = RecordingToolsTransport { jsonResponse(404, "{}") }
        assertEquals(
            CloudflareToolsFailureKind.NOT_FOUND,
            assertThrows(CloudflareToolsException::class.java) { CloudflareToolsApi(missing).newRolesCall(token, "acc").execute() }.kind,
        )

        val garbage = RecordingToolsTransport { jsonResponse(200, "<html>") }
        assertEquals(
            CloudflareToolsFailureKind.INVALID_RESPONSE,
            assertThrows(CloudflareToolsException::class.java) { CloudflareToolsApi(garbage).newAccountDetailCall(token, "acc").execute() }.kind,
        )
    }

    @Test
    fun cancellationReachesTheActiveTransportCall() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cancelled = false
        val transport = CloudflareToolsTransport { _, _ ->
            object : CancelableCall<HttpResponse> {
                override fun execute(): HttpResponse {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    if (cancelled) throw CancellationException("cancelled")
                    return jsonResponse(200, "{}")
                }

                override fun cancel() {
                    cancelled = true
                    release.countDown()
                }
            }
        }
        val call = CloudflareToolsApi(transport).newRawRequestCall(token, CloudflareExplorerDraft(), null)
        var failure: Throwable? = null
        val thread = Thread { failure = runCatching { call.execute() }.exceptionOrNull() }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        call.cancel()
        thread.join(5_000)
        assertTrue(cancelled)
        assertTrue(failure is CancellationException)
    }

    @Test
    fun explorerHintsAndSafeMessages() {
        assertEquals(
            "Cloudflare rejected this API token. Reconnect Cloudflare with an active token.",
            CloudflareToolsErrors.explorerHint(401, emptyList()),
        )
        assertEquals(
            "This API token is missing a permission or resource for this endpoint. Cloudflare accepts any of: A, B, C, D and 2 more.",
            CloudflareToolsErrors.explorerHint(403, listOf("A", "B", "C", "D", "E", "F")),
        )
        assertEquals(
            "This API token is missing a permission or resource for this endpoint. Add the matching permission to the token, then try again.",
            CloudflareToolsErrors.explorerHint(403, emptyList()),
        )
        assertNull(CloudflareToolsErrors.explorerHint(200, listOf("A")))
        assertNull(CloudflareToolsErrors.explorerHint(500, emptyList()))

        assertEquals(
            "Cloudflare could not be reached. Check your connection and try again.",
            CloudflareToolsErrors.message(IOException("socket closed: secret detail")),
        )
        assertEquals("This device cannot send PATCH requests.", CloudflareToolsErrors.message(IOException("This device cannot send PATCH requests.")))
        assertEquals(
            "Cloudflare returned more data than the app can process safely.",
            CloudflareToolsErrors.message(ResponseTooLargeException(10)),
        )
        assertEquals(
            "Cloudflare returned an unsafe redirect, so the request stopped.",
            CloudflareToolsErrors.message(UnsafeRedirectException("to evil")),
        )
        assertEquals("Cloudflare could not complete this request.", CloudflareToolsErrors.message(IllegalStateException("x")))
    }

    @Test
    fun providerMessagesAreSanitizedAndBounded() {
        val long = "x".repeat(400)
        val response = CloudflareRawResponse(
            400,
            emptyMap(),
            """{"errors":[{"message":"line\u0007one"},{"message":"$long"},{"message":""},{"message":"a"},{"message":"b"}]}""".toByteArray(),
        )
        val messages = CloudflareToolsErrors.providerMessages(response)
        assertEquals(3, messages.size)
        assertEquals("lineone", messages[0])
        assertEquals(301, messages[1].length)
    }
}
