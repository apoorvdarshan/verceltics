package com.apoorvdarshan.verceltics.data.cloudflare

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCloudflareJsonParserTest {
    private val parser = AndroidCloudflareJsonParser()

    @Test
    fun tokenVerificationParsesIosParityFieldsAndUnknownValues() {
        val verification = parser.parseTokenVerification(
            json(
                """
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "id": "token-id",
                    "status": "active",
                    "not_before": "2026-01-01T00:00:00Z",
                    "expires_on": null,
                    "future_field": {"ignored": true}
                  }
                }
                """,
            ),
        )

        assertEquals("token-id", verification.id)
        assertEquals("active", verification.status)
        assertEquals("2026-01-01T00:00:00Z", verification.notBefore)
        assertNull(verification.expiresOn)
        assertTrue(verification.isActive)
    }

    @Test
    fun globalApiKeyUserProfileAndWorkerRoutesAndTagsParse() {
        val user = parser.parseUser(
            json(
                """
                {"success": true, "errors": [], "messages": [],
                 "result": {"id": "user-1", "email": "owner@example.com", "first_name": "Ada", "last_name": null, "suspended": false, "betas": []}}
                """,
            ),
        )
        assertEquals("user-1", user.id)
        assertEquals("Ada", user.displayName)
        assertEquals(false, user.suspended)

        val workers = parser.parseWorkerScripts(
            json(
                """
                {"success": true, "errors": [], "messages": [],
                 "result": [{"id": "api", "routes": [{"id": "r1", "pattern": "example.com/api/*", "script": "api"}], "tags": ["billing"]},
                            {"id": "cron", "routes": null}]}
                """,
            ),
        )
        assertEquals(listOf("example.com/api/*"), workers[0].routes)
        assertEquals(listOf("billing"), workers[0].tags)
        assertTrue(workers[1].routes.isEmpty())
    }

    @Test
    fun accountsAndPaginationMetadataAreParsedWithoutOptionalFields() {
        val page = parser.parseAccountsPage(
            json(
                """
                {
                  "result": [
                    {"id":"a1","name":"Personal","type":"standard","created_on":"2024-01-01T00:00:00Z"},
                    {"id":"a2","name":"Team"}
                  ],
                  "result_info":{"page":2,"per_page":50,"total_pages":4,"count":2},
                  "success":true,
                  "errors":[]
                }
                """,
            ),
        )

        assertEquals(2, page.page)
        assertEquals(4, page.totalPages)
        assertEquals(listOf("a1", "a2"), page.items.map { it.id })
        assertNull(page.items[1].type)
    }

    @Test
    fun zonesParseAccountPlanAndActiveSemantics() {
        val zone = parser.parseZonesPage(
            json(
                """
                {
                  "success":true,
                  "errors":[],
                  "result":[{
                    "id":"zone-id",
                    "name":"example.com",
                    "status":"active",
                    "type":"full",
                    "paused":false,
                    "account":{"id":"account-id","name":"Apoorv"},
                    "plan":{"id":"free","name":"Free Website"},
                    "name_servers":["ns1.example.com"]
                  }],
                  "result_info":{"page":1,"total_pages":1}
                }
                """,
            ),
        ).items.single()

        assertEquals("account-id", zone.accountId)
        assertEquals("Apoorv", zone.accountName)
        assertEquals("Free Website", zone.planName)
        assertTrue(zone.isActive)
    }

    @Test
    fun pagesAndWorkersParseDashboardFieldsWithBoundedNestedArrays() {
        val pages = parser.parsePagesProjectsPage(
            json(
                """
                {
                  "success":true,
                  "errors":[],
                  "result":[{
                    "id":"pages-id",
                    "name":"verceltics",
                    "subdomain":"verceltics.pages.dev",
                    "domains":["verceltics.pages.dev","example.com"],
                    "production_branch":"main",
                    "created_on":"2024-01-01T00:00:00Z",
                    "latest_deployment":{"latest_stage":{"status":"success"}}
                  }],
                  "result_info":{"page":1,"total_pages":1}
                }
                """,
            ),
        ).items.single()
        val worker = parser.parseWorkerScripts(
            json(
                """
                {
                  "success":true,
                  "errors":[],
                  "result":[{
                    "id":"worker-id",
                    "created_on":"2024-01-01T00:00:00Z",
                    "modified_on":"2024-01-02T00:00:00Z",
                    "compatibility_date":"2026-08-01",
                    "handlers":["fetch","scheduled"],
                    "has_assets":true,
                    "has_modules":false
                  }]
                }
                """,
            ),
        ).single()

        assertEquals("success", pages.latestDeploymentStatus)
        assertEquals(2, pages.domains.size)
        assertEquals(listOf("fetch", "scheduled"), worker.handlers)
        assertEquals(true, worker.hasAssets)
        assertEquals(false, worker.hasModules)
    }

    @Test
    fun unsuccessfulEnvelopeIsRejectedAndProviderCodeNeverRenders() {
        val providerCode = "secret-reflected-code"
        val error = assertThrows(CloudflareEnvelopeRejectedException::class.java) {
            parser.parseAccountsPage(
                json(
                    """
                    {"success":false,"result":null,"errors":[{"code":"$providerCode","message":"ignored"}]}
                    """,
                ),
            )
        }

        assertEquals(providerCode, error.errorCode)
        assertFalse(error.toString().contains(providerCode))
        assertTrue(error.toString().contains("<redacted>"))
    }

    @Test
    fun malformedRequiredRecordsAndTrailingJsonAreRejected() {
        assertThrows(CloudflareResponseFormatException::class.java) {
            parser.parseAccountsPage(
                json("""{"success":true,"result":[{"id":"missing-name"}],"errors":[]}"""),
            )
        }
        assertThrows(CloudflareResponseFormatException::class.java) {
            parser.parseTokenVerification(
                json("""{"success":true,"result":{"status":"active"}} {}"""),
            )
        }
    }

    @Test
    fun malformedErrorBodyReturnsNullWhileValidNumericCodeIsAccepted() {
        assertNull(parser.parseErrorCode(byteArrayOf(0, 1, 2)))
        assertEquals(
            "9109",
            parser.parseErrorCode(
                json("""{"success":false,"errors":[{"code":9109,"message":"Invalid token"}]}"""),
            ),
        )
    }

    private fun json(value: String): ByteArray = value.trimIndent().encodeToByteArray()
}
