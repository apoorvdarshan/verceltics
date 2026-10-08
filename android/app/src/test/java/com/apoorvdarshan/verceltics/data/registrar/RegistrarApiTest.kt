package com.apoorvdarshan.verceltics.data.registrar

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarApiTest {
    // region Name.com

    @Test
    fun nameDotComUsesBasicAuthPaginatesAndNormalizes() {
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("page")) {
                "1" -> ok(
                    """
                    {"domains":[
                      {"domainName":"Example.org","locked":true,"autorenewEnabled":true,
                       "expireDate":"2026-03-10T21:12:07Z","createDate":"2019-03-10T21:12:07Z",
                       "privacyEnabled":false,"nameservers":["ns1.name.com","ns2.name.com"]},
                      {"domainName":"second.dev","locked":false,"autorenewEnabled":false,"expireDate":"2025-01-02T00:00:00Z"}
                    ],"nextPage":2,"lastPage":2,"totalCount":3}
                    """,
                )
                "2" -> ok("""{"domains":[{"domainName":"example.org"},{"domain":"third.app"}],"lastPage":2}""")
                else -> error("Unexpected page")
            }
        }
        val credentials = credentials(RegistrarProvider.NAME_DOT_COM, key = "api-token", username = "alice")

        val validation = RegistrarApi(transport).newValidateCredentialsCall(credentials).execute()

        assertEquals("alice", validation.accountName)
        assertEquals(listOf("Example.org", "second.dev", "third.app"), validation.domains.map { it.name })
        val first = validation.domains.first()
        assertEquals(utcMillis("2026-03-10T21:12:07Z"), first.expiresAtMillis)
        assertEquals(utcMillis("2019-03-10T21:12:07Z"), first.createdAtMillis)
        assertEquals(true, first.autoRenew)
        assertEquals(true, first.locked)
        assertEquals(false, first.privacyEnabled)
        assertEquals(listOf("ns1.name.com", "ns2.name.com"), first.nameservers)
        assertEquals(2, transport.requests.size)
        transport.requests.forEach { request ->
            assertEquals("https://api.name.com/", request.origin)
            assertEquals("/core/v1/domains", request.path)
            assertEquals("250", request.queryValue("perPage"))
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("alice:api-token".toByteArray(StandardCharsets.UTF_8)),
                request.headers["Authorization"],
            )
            assertTrue("Authorization" in request.secretHeaderNames)
            assertFalse(request.query.any { it.first.equals("clientip", ignoreCase = true) })
        }
    }

    @Test
    fun nameDotComStopsWhenNextPageDoesNotAdvance() {
        val transport = FakeRegistrarTransport { ok("""{"domains":[{"domainName":"a.example"}],"nextPage":1}""") }

        val domains = RegistrarApi(transport)
            .newFetchDomainsCall(credentials(RegistrarProvider.NAME_DOT_COM))
            .execute()

        assertEquals(listOf("a.example"), domains.map { it.name })
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun paginationIsCappedAtTwoHundredPages() {
        val transport = FakeRegistrarTransport { request ->
            val page = request.queryValue("page")!!.toInt()
            ok("""{"domains":[{"domainName":"d$page.example"}],"nextPage":${page + 1}}""")
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAME_DOT_COM)).execute()
        }

        assertEquals("Could not read the registrar response: Pagination exceeded 200 pages.", error.message)
        assertEquals(RegistrarApi.MAXIMUM_PAGINATION_PAGES, transport.requests.size)
    }

    // endregion

    // region Namecheap

    @Test
    fun namecheapRejectsInvalidClientIpBeforeSendingRequest() {
        val transport = FakeRegistrarTransport { error("No request should be sent.") }
        val credentials = RegistrarCredentials(
            provider = RegistrarProvider.NAMECHEAP,
            primary = com.apoorvdarshan.verceltics.data.account.SecretValue.of("api-key"),
            secondary = null,
            metadata = mapOf("username" to "alice", "clientIP" to "192.168.1.5"),
        )

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials).execute()
        }

        assertEquals("Enter a valid public IPv4 address that is whitelisted in Namecheap.", error.message)
        assertEquals(RegistrarFailureKind.CONFIGURATION, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun namecheapSendsCanonicalClientIpAndParsesXmlPages() {
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("Page")) {
                "1" -> ok(namecheapPage(totalItems = 3, pageSize = 2, NAMECHEAP_DOMAIN_1, NAMECHEAP_DOMAIN_2))
                "2" -> ok(namecheapPage(totalItems = 3, pageSize = 2, NAMECHEAP_DOMAIN_3))
                else -> error("Unexpected page")
            }
        }
        val credentials = RegistrarCredentials(
            provider = RegistrarProvider.NAMECHEAP,
            primary = com.apoorvdarshan.verceltics.data.account.SecretValue.of("api-key"),
            secondary = null,
            metadata = mapOf("username" to "alice", "clientIP" to "008.008.004.004"),
        )

        val validation = RegistrarApi(transport).newValidateCredentialsCall(credentials).execute()

        assertEquals("alice", validation.accountName)
        assertEquals(listOf("domain1.com", "domain2.net", "domain3.io"), validation.domains.map { it.name })
        val first = validation.domains[0]
        assertEquals("Active", first.status)
        assertEquals(utcMillis("2016-02-15T00:00:00Z"), first.createdAtMillis)
        assertEquals(utcMillis("2027-02-15T00:00:00Z"), first.expiresAtMillis)
        assertEquals(false, first.autoRenew)
        assertEquals(false, first.locked)
        assertEquals(true, first.privacyEnabled)
        assertEquals(mapOf("isOurDNS" to "true"), first.metadata)
        val second = validation.domains[1]
        assertEquals("Expired", second.status)
        assertEquals(true, second.locked)
        assertEquals(true, second.autoRenew)
        assertEquals(false, second.privacyEnabled)
        assertEquals(true, validation.domains[2].privacyEnabled)

        assertEquals(2, transport.requests.size)
        val request = transport.requests.first()
        assertEquals("https://api.namecheap.com/", request.origin)
        assertEquals("/xml.response", request.path)
        assertEquals("namecheap.domains.getList", request.queryValue("Command"))
        assertEquals("ALL", request.queryValue("ListType"))
        assertEquals("100", request.queryValue("PageSize"))
        assertEquals(listOf("alice"), request.queryValues("ApiUser"))
        assertEquals(listOf("api-key"), request.queryValues("ApiKey"))
        assertEquals(listOf("alice"), request.queryValues("UserName"))
        assertEquals(listOf("8.8.4.4"), request.queryValues("ClientIp"))
        assertEquals(setOf("ApiKey"), request.secretQueryNames)
        assertTrue(request.secretHeaderNames.isEmpty())
    }

    @Test
    fun namecheapApiErrorBecomesRequestFailure() {
        val transport = FakeRegistrarTransport {
            ok(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ApiResponse Status="ERROR" xmlns="http://api.namecheap.com/xml.response">
                  <Errors><Error Number="1011150">Parameter RequestIP is invalid</Error></Errors>
                  <Warnings />
                  <RequestedCommand>namecheap.domains.getList</RequestedCommand>
                  <CommandResponse />
                </ApiResponse>
                """.trimIndent(),
            )
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAMECHEAP)).execute()
        }

        assertEquals("Request failed (HTTP 400): Parameter RequestIP is invalid", error.message)
    }

    @Test
    fun namecheapEmptyPageBeforeReportedTotalIsADecodingError() {
        val transport = FakeRegistrarTransport { request ->
            if (request.queryValue("Page") == "1") {
                ok(namecheapPage(totalItems = 5, pageSize = 1, NAMECHEAP_DOMAIN_1))
            } else {
                ok(namecheapPage(totalItems = 5, pageSize = 1))
            }
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAMECHEAP)).execute()
        }

        assertEquals(
            "Could not read the registrar response: Namecheap pagination returned no domains before reaching the reported total.",
            error.message,
        )
    }

    @Test
    fun namecheapXmlRejectsDocumentTypeDeclarationsAndMalformedXml() {
        val withEntity = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <ApiResponse><Errors><Error>&xxe;</Error></Errors></ApiResponse>
        """.trimIndent()
        listOf(withEntity, "<ApiResponse><Unclosed></ApiResponse>").forEach { xml ->
            val error = assertThrows(RegistrarApiException::class.java) { NamecheapXmlParser.parseDomainPage(xml) }
            assertEquals("Could not read the registrar response: Namecheap XML parsing failed.", error.message)
        }
    }

    @Test
    fun namecheapPagingDefaultsMatchIosWhenPagingIsMissing() {
        val page = NamecheapXmlParser.parseDomainPage(
            """<ApiResponse><CommandResponse><DomainGetListResult>$NAMECHEAP_DOMAIN_1$NAMECHEAP_DOMAIN_2</DomainGetListResult></CommandResponse></ApiResponse>""",
        )

        assertEquals(2, page.totalItems)
        assertEquals(2, page.pageSize)
    }

    // endregion

    // region Porkbun

    @Test
    fun porkbunListAllUsesOfficialZeroBasedStartOffset() {
        assertEquals("/domain/listAll?start=0", RegistrarApi.porkbunListAllPath(0))
        assertEquals("/domain/listAll?start=1000", RegistrarApi.porkbunListAllPath(1_000))
        assertEquals("/domain/listAll?start=0", RegistrarApi.porkbunListAllPath(-1))
    }

    @Test
    fun porkbunPaginationContinuesOnlyAfterAFullProductivePage() {
        assertEquals(
            PorkbunPaginationAction.LOAD_NEXT_PAGE,
            RegistrarApi.porkbunPaginationAction(RegistrarApi.PORKBUN_PAGE_SIZE, RegistrarApi.PORKBUN_PAGE_SIZE),
        )
        assertEquals(
            PorkbunPaginationAction.COMPLETE,
            RegistrarApi.porkbunPaginationAction(RegistrarApi.PORKBUN_PAGE_SIZE - 1, RegistrarApi.PORKBUN_PAGE_SIZE - 1),
        )
    }

    @Test
    fun porkbunPaginationRejectsARepeatedFullPage() {
        assertEquals(
            PorkbunPaginationAction.NO_PROGRESS,
            RegistrarApi.porkbunPaginationAction(RegistrarApi.PORKBUN_PAGE_SIZE, 0),
        )
    }

    @Test
    fun porkbunSendsKeyHeadersAndWalksStartOffsets() {
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("start")) {
                "0" -> ok(porkbunPage((0 until 1_000).map { "d$it.example" }))
                "1000" -> ok(
                    """
                    {"status":"SUCCESS","domains":[
                      {"domain":"borseth.ink","status":"ACTIVE","tld":"ink","createDate":"2018-08-20 17:52:51",
                       "expireDate":"2023-08-20 17:52:51","securityLock":"1","whoisPrivacy":"1","autoRenew":0,"notLocal":0},
                      {"domain":"D0.example","status":"ACTIVE"}
                    ]}
                    """,
                )
                else -> error("Unexpected offset")
            }
        }
        val credentials = credentials(RegistrarProvider.PORKBUN, key = "pk1_key", secret = "sk1_secret")

        val validation = RegistrarApi(transport).newValidateCredentialsCall(credentials).execute()

        assertEquals(1_001, validation.domains.size)
        val porkbun = validation.domains.last()
        assertEquals("borseth.ink", porkbun.name)
        assertEquals("ACTIVE", porkbun.status)
        assertEquals(utcMillis("2018-08-20T17:52:51Z"), porkbun.createdAtMillis)
        assertEquals(utcMillis("2023-08-20T17:52:51Z"), porkbun.expiresAtMillis)
        assertEquals(true, porkbun.locked)
        assertEquals(true, porkbun.privacyEnabled)
        assertEquals(false, porkbun.autoRenew)
        val expectedLabel = "Porkbun · " + RegistrarApi.credentialFingerprint(credentials.primary).takeLast(4)
        assertEquals(expectedLabel, validation.accountName)
        assertEquals(8, RegistrarApi.credentialFingerprint(credentials.primary).length)

        assertEquals(listOf("0", "1000"), transport.requests.map { it.queryValue("start") })
        transport.requests.forEach { request ->
            assertEquals("https://api.porkbun.com/", request.origin)
            assertEquals("/api/json/v3/domain/listAll", request.path)
            assertEquals("pk1_key", request.headers["X-API-Key"])
            assertEquals("sk1_secret", request.headers["X-Secret-API-Key"])
            assertEquals(setOf("X-API-Key", "X-Secret-API-Key"), request.secretHeaderNames)
        }
    }

    @Test
    fun porkbunRepeatedFullPageFailsInsteadOfLooping() {
        val transport = FakeRegistrarTransport { ok(porkbunPage((0 until 1_000).map { "same$it.example" })) }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.PORKBUN)).execute()
        }

        assertEquals(
            "Could not read the registrar response: Porkbun pagination returned no new domains for a full page.",
            error.message,
        )
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun porkbunErrorStatusBecomesRequestFailure() {
        val transport = FakeRegistrarTransport { ok("""{"status":"ERROR","message":"Invalid API key. (002)"}""") }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.PORKBUN)).execute()
        }

        assertEquals("Request failed (HTTP 400): Invalid API key. (002)", error.message)
    }

    // endregion

    // region Spaceship

    @Test
    fun spaceshipWalksSkipOffsetsAndNormalizesPrivacyLocksAndHosts() {
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("skip")) {
                "0" -> ok(
                    """
                    {"items":[
                      {"name":"example.com","unicodeName":"exämple.com","isPremium":false,"autoRenew":true,
                       "registrationDate":"2023-08-01T12:14:30.398Z","expirationDate":"2026-08-01T12:14:30.398Z",
                       "lifecycleStatus":"registered","verificationStatus":"verified",
                       "eppStatuses":["clientTransferProhibited","clientDeleteProhibited"],
                       "privacyProtection":{"contactForm":true,"level":"high"},
                       "nameservers":{"provider":"custom","hosts":["ns1.example.net","ns2.example.net"]}},
                      {"name":"plain.dev","autoRenew":false,"eppStatuses":[],"privacyProtection":{"level":"none"}}
                    ],"total":3}
                    """,
                )
                "2" -> ok("""{"items":[{"name":"third.app","privacyProtection":{"level":"public"}}],"total":3}""")
                else -> error("Unexpected offset")
            }
        }
        val credentials = credentials(RegistrarProvider.SPACESHIP, key = "ss-key", secret = "ss-secret")

        val domains = RegistrarApi(transport).newFetchDomainsCall(credentials).execute()

        assertEquals(listOf("exämple.com", "plain.dev", "third.app"), domains.map { it.name })
        val first = domains[0]
        assertEquals("registered", first.status)
        assertEquals(true, first.locked)
        assertEquals(true, first.privacyEnabled)
        assertEquals(true, first.autoRenew)
        assertEquals(utcMillis("2026-08-01T12:14:30.398Z"), first.expiresAtMillis)
        assertEquals(listOf("ns1.example.net", "ns2.example.net"), first.nameservers)
        assertEquals(mapOf("verification" to "verified"), first.metadata)
        assertEquals(false, domains[1].locked)
        assertEquals(false, domains[1].privacyEnabled)
        assertEquals(true, domains[2].privacyEnabled)
        assertEquals(listOf("0", "2"), transport.requests.map { it.queryValue("skip") })
        transport.requests.forEach { request ->
            assertEquals("https://spaceship.dev/", request.origin)
            assertEquals("/api/v1/domains", request.path)
            assertEquals("100", request.queryValue("take"))
            assertEquals("ss-key", request.headers["X-API-Key"])
            assertEquals("ss-secret", request.headers["X-API-Secret"])
        }
    }

    // endregion

    // region Dynadot

    @Test
    fun dynadotParsesMainDomainsWithMillisecondDatesAndKeyQuery() {
        val transport = FakeRegistrarTransport {
            ok(
                """
                {"ListDomainInfoResponse":{"ResponseCode":0,"Status":"success","MainDomains":[
                  {"Name":"domain1.com","Expiration":"1767225600000","Registration":"1609459200000",
                   "NameServerSettings":{"Type":"Dynadot Parking"},"Locked":"yes","Disabled":"no",
                   "Privacy":"full","RenewOption":"auto"},
                  "bare-name.net"
                ]}}
                """,
            )
        }

        val domains = RegistrarApi(transport)
            .newFetchDomainsCall(credentials(RegistrarProvider.DYNADOT, key = "dyn-key"))
            .execute()

        assertEquals(listOf("domain1.com", "bare-name.net"), domains.map { it.name })
        assertEquals(1_767_225_600_000L, domains[0].expiresAtMillis)
        assertEquals(1_609_459_200_000L, domains[0].createdAtMillis)
        assertEquals(true, domains[0].locked)
        assertEquals(true, domains[0].autoRenew)
        assertNull(domains[0].privacyEnabled)
        assertNull(domains[1].expiresAtMillis)
        val request = transport.requests.single()
        assertEquals("https://api.dynadot.com/", request.origin)
        assertEquals("/api3.json", request.path)
        assertEquals("list_domain", request.queryValue("command"))
        assertEquals("100", request.queryValue("count_per_page"))
        assertEquals("0", request.queryValue("page_index"))
        assertEquals("dyn-key", request.queryValue("key"))
        assertEquals(setOf("key"), request.secretQueryNames)
    }

    @Test
    fun dynadotFailureStatusesBecomeRequestFailures() {
        val listError = FakeRegistrarTransport {
            ok("""{"ListDomainInfoResponse":{"ResponseCode":-1,"Status":"error","Error":"invalid key"}}""")
        }
        assertEquals(
            "Request failed (HTTP -1): invalid key",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(listError).newFetchDomainsCall(credentials(RegistrarProvider.DYNADOT)).execute()
            }.message,
        )

        val envelopeError = FakeRegistrarTransport { ok("""{"Response":{"ResponseCode":"-1","Error":"invalid key"}}""") }
        assertEquals(
            "Request failed (HTTP 400): invalid key",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(envelopeError).newFetchDomainsCall(credentials(RegistrarProvider.DYNADOT)).execute()
            }.message,
        )
    }

    @Test
    fun dynadotRepeatedResultsPageFails() {
        val page = (0 until 100).joinToString(",") { "{\"Name\":\"d$it.example\"}" }
        val transport = FakeRegistrarTransport {
            ok("""{"ListDomainInfoResponse":{"ResponseCode":0,"Status":"success","MainDomains":[$page]}}""")
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.DYNADOT)).execute()
        }

        assertEquals("Could not read the registrar response: Dynadot pagination repeated a results page.", error.message)
        assertEquals(listOf("0", "1"), transport.requests.map { it.queryValue("page_index") })
    }

    // endregion

    // region NameSilo

    @Test
    fun nameSiloPaginatesWithPagerTotalAndUsesQueryKey() {
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("page")) {
                "1" -> ok(
                    """
                    {"request":{"operation":"listDomains","ip":"203.0.113.9"},
                     "reply":{"code":300,"detail":"success","domains":[
                       {"domain":"example.com","created":"2019-03-01","expires":"2026-03-01","auto_renew":"1","locked":"Yes","private":"No"},
                       "bare.org"
                     ],"pager":{"total":3,"pageSize":2,"page":1}}}
                    """,
                )
                "2" -> ok("""{"reply":{"code":"300","detail":"success","domains":[{"name":"third.net"}],"pager":{"total":3}}}""")
                else -> error("Unexpected page")
            }
        }

        val domains = RegistrarApi(transport)
            .newFetchDomainsCall(credentials(RegistrarProvider.NAME_SILO, key = "silo-key"))
            .execute()

        assertEquals(listOf("example.com", "bare.org", "third.net"), domains.map { it.name })
        assertEquals(utcMillis("2026-03-01T00:00:00Z"), domains[0].expiresAtMillis)
        assertEquals(utcMillis("2019-03-01T00:00:00Z"), domains[0].createdAtMillis)
        assertEquals(true, domains[0].autoRenew)
        assertEquals(true, domains[0].locked)
        assertEquals(false, domains[0].privacyEnabled)
        transport.requests.forEach { request ->
            assertEquals("https://www.namesilo.com/", request.origin)
            assertEquals("/api/listDomains", request.path)
            assertEquals("1", request.queryValue("version"))
            assertEquals("json", request.queryValue("type"))
            assertEquals("silo-key", request.queryValue("key"))
            assertEquals("100", request.queryValue("pageSize"))
            assertEquals(setOf("key"), request.secretQueryNames)
        }
    }

    @Test
    fun nameSiloRejectsNonSuccessReplyCodes() {
        val transport = FakeRegistrarTransport { ok("""{"reply":{"code":110,"detail":"Invalid API Key"}}""") }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAME_SILO)).execute()
        }

        assertEquals("Request failed (HTTP 110): Invalid API Key", error.message)
    }

    @Test
    fun nameSiloAcceptsTheXmlConvertedDomainShape() {
        val transport = FakeRegistrarTransport {
            ok("""{"reply":{"code":300,"detail":"success","domains":{"domain":["a.example","b.example"]}}}""")
        }

        val domains = RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAME_SILO)).execute()

        assertEquals(listOf("a.example", "b.example"), domains.map { it.name })
    }

    // endregion

    // region Gandi

    @Test
    fun gandiUsesBearerTokenAndNormalizesStatusDatesAndSharing() {
        val transport = FakeRegistrarTransport {
            ok(
                """
                [{"fqdn":"example.net","fqdn_unicode":"example.net","owner":"alice","sharing_id":"abc-123",
                  "status":["clientTransferProhibited","clientHold"],"tld":"net","autorenew":true,
                  "dates":{"created_at":"2019-02-13T11:04:18Z","registry_created_at":"2019-02-13T11:04:18Z",
                           "registry_ends_at":"2026-02-13T11:04:18+00:00"},
                  "nameserver":{"current":"livedns"}},
                 {"fqdn":"unlocked.org","status":[],"autorenew":false,"dates_registry_ends_at":"2025-12-01T00:00:00Z"}]
                """,
            )
        }
        val credentials = credentials(RegistrarProvider.GANDI, key = "pat-token", organization = "  Example-Org ")

        val validation = RegistrarApi(transport).newValidateCredentialsCall(credentials).execute()

        assertEquals("Example-Org", validation.accountName)
        val first = validation.domains[0]
        assertEquals("clientTransferProhibited, clientHold", first.status)
        assertEquals(true, first.locked)
        assertEquals(true, first.autoRenew)
        assertEquals(utcMillis("2019-02-13T11:04:18Z"), first.createdAtMillis)
        assertEquals(utcMillis("2026-02-13T11:04:18Z"), first.expiresAtMillis)
        assertEquals(mapOf("sharingID" to "abc-123"), first.metadata)
        val second = validation.domains[1]
        assertNull(second.status)
        assertEquals(false, second.locked)
        assertEquals(utcMillis("2025-12-01T00:00:00Z"), second.expiresAtMillis)
        val request = transport.requests.single()
        assertEquals("https://api.gandi.net/", request.origin)
        assertEquals("/v5/domain/domains", request.path)
        assertEquals("100", request.queryValue("per_page"))
        assertEquals("1", request.queryValue("page"))
        assertEquals("Bearer pat-token", request.headers["Authorization"])
    }

    @Test
    fun gandiAccountLabelFallsBackWithoutOrganization() {
        assertEquals("Gandi Account", RegistrarApi.accountName(credentials(RegistrarProvider.GANDI)))
        assertEquals("Name.com", RegistrarApi.accountName(
            RegistrarCredentials(
                RegistrarProvider.NAME_DOT_COM,
                com.apoorvdarshan.verceltics.data.account.SecretValue.of("t"),
                null,
                emptyMap(),
            ),
        ))
    }

    @Test
    fun gandiFullPagesContinueAndRepeatedPagesFail() {
        val transport = FakeRegistrarTransport { request ->
            val page = request.queryValue("page")!!.toInt()
            val names = if (page == 1) (0 until 100).map { "g$it.example" } else (0 until 100).map { "g$it.example" }
            ok(names.joinToString(",", "[", "]") { "{\"fqdn\":\"$it\"}" })
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.GANDI)).execute()
        }

        assertEquals("Could not read the registrar response: Gandi pagination repeated a results page.", error.message)
    }

    // endregion

    // region GoDaddy

    @Test
    fun goDaddyUsesSsoKeyAndMarkerPagination() {
        val firstPage = (0 until 1_000).map { "gd$it.example" }
        val transport = FakeRegistrarTransport { request ->
            when (request.queryValue("marker")) {
                null -> ok(goDaddyPage(firstPage))
                "gd999.example" -> ok(
                    """
                    [{"createdAt":"2015-06-15T13:10:43.000Z","domain":"000.biz","domainId":1002111,
                      "expires":"2026-06-14T23:59:59.000Z","locked":true,
                      "nameServers":["ns05.domaincontrol.com","ns06.domaincontrol.com"],
                      "privacy":false,"renewAuto":true,"renewable":true,"status":"ACTIVE"}]
                    """,
                )
                else -> error("Unexpected marker")
            }
        }
        val credentials = credentials(RegistrarProvider.GO_DADDY, key = "gd-key", secret = "gd-secret")

        val domains = RegistrarApi(transport).newFetchDomainsCall(credentials).execute()

        assertEquals(1_001, domains.size)
        val last = domains.last()
        assertEquals("000.biz", last.name)
        assertEquals("ACTIVE", last.status)
        assertEquals(utcMillis("2026-06-14T23:59:59Z"), last.expiresAtMillis)
        assertEquals(utcMillis("2015-06-15T13:10:43Z"), last.createdAtMillis)
        assertEquals(true, last.autoRenew)
        assertEquals(true, last.locked)
        assertEquals(false, last.privacyEnabled)
        assertEquals(listOf("ns05.domaincontrol.com", "ns06.domaincontrol.com"), last.nameservers)
        assertEquals(mapOf("domainID" to "1002111"), last.metadata)
        assertEquals(listOf(null, "gd999.example"), transport.requests.map { it.queryValue("marker") })
        transport.requests.forEach { request ->
            assertEquals("https://api.godaddy.com/", request.origin)
            assertEquals("/v1/domains", request.path)
            assertEquals("1000", request.queryValue("limit"))
            assertEquals("nameServers", request.queryValue("includes"))
            assertEquals("sso-key gd-key:gd-secret", request.headers["Authorization"])
        }
    }

    @Test
    fun goDaddyRepeatedMarkerFails() {
        val transport = FakeRegistrarTransport { ok(goDaddyPage((0 until 1_000).map { "same$it.example" })) }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.GO_DADDY)).execute()
        }

        assertEquals("Could not read the registrar response: GoDaddy pagination repeated a marker.", error.message)
    }

    // endregion

    // region Shared request and error handling

    @Test
    fun httpErrorsUseProviderMessageWithDetailsAndRedactSecrets() {
        val transport = FakeRegistrarTransport {
            FakeRegistrarResponse(
                401,
                """{"message":"Unauthenticated","details":"Key super-secret-key is\nnot valid"}""",
            )
        }

        val error = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(transport)
                .newFetchDomainsCall(credentials(RegistrarProvider.GANDI, key = "super-secret-key"))
                .execute()
        }

        assertEquals("Request failed (HTTP 401): Unauthenticated — Key <redacted> is not valid", error.message)
        assertEquals(401, error.statusCode)
        assertEquals(RegistrarFailureKind.REQUEST_FAILED, error.kind)
    }

    @Test
    fun httpErrorsFallBackToPlainTextAndDropMarkup() {
        val plain = FakeRegistrarTransport { FakeRegistrarResponse(429, "Too many requests") }
        assertEquals(
            "Request failed (HTTP 429): Too many requests",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(plain).newFetchDomainsCall(credentials(RegistrarProvider.GO_DADDY)).execute()
            }.message,
        )
        val html = FakeRegistrarTransport { FakeRegistrarResponse(503, "<html><body>Down</body></html>") }
        assertEquals(
            "Request failed (HTTP 503).",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(html).newFetchDomainsCall(credentials(RegistrarProvider.GO_DADDY)).execute()
            }.message,
        )
        val long = "x".repeat(1_000)
        val bounded = FakeRegistrarTransport { FakeRegistrarResponse(500, """{"error":"$long"}""") }
        val message = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(bounded).newFetchDomainsCall(credentials(RegistrarProvider.GO_DADDY)).execute()
        }.message
        assertTrue(message.length < 360)
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun malformedOrNonContainerJsonIsADecodingError() {
        listOf("not json", "\"just a string\"", "42").forEach { body ->
            val transport = FakeRegistrarTransport { ok(body) }
            val error = assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.GANDI)).execute()
            }
            assertEquals(body, RegistrarFailureKind.DECODING, error.kind)
            assertTrue(error.message.startsWith("Could not read the registrar response: "))
        }
    }

    @Test
    fun transportFailuresMapToSafeMessages() {
        val offline = FakeRegistrarTransport { FakeRegistrarResponse(error = IOException("socket details")) }
        val offlineError = assertThrows(RegistrarApiException::class.java) {
            RegistrarApi(offline).newFetchDomainsCall(credentials(RegistrarProvider.PORKBUN)).execute()
        }
        assertEquals("Porkbun could not be reached. Check your connection and try again.", offlineError.message)
        assertEquals(RegistrarFailureKind.NETWORK, offlineError.kind)

        val tooLarge = FakeRegistrarTransport { FakeRegistrarResponse(error = ResponseTooLargeException(10)) }
        assertEquals(
            "The response exceeded the safe 8.4 MB limit.",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(tooLarge).newFetchDomainsCall(credentials(RegistrarProvider.PORKBUN)).execute()
            }.message,
        )

        val redirect = FakeRegistrarTransport { FakeRegistrarResponse(error = UnsafeRedirectException("x")) }
        assertEquals(
            "Porkbun returned an unsafe redirect.",
            assertThrows(RegistrarApiException::class.java) {
                RegistrarApi(redirect).newFetchDomainsCall(credentials(RegistrarProvider.PORKBUN)).execute()
            }.message,
        )
    }

    @Test
    fun cancellationStopsPaginationAndPropagates() {
        val firstRequestStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = FakeRegistrarTransport { request ->
            val page = request.queryValue("page")!!.toInt()
            ok("""{"domains":[{"domainName":"d$page.example"}],"nextPage":${page + 1}}""")
        }
        transport.onExecute = {
            firstRequestStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val call = RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.NAME_DOT_COM))
        var thrown: Throwable? = null
        val worker = Thread {
            thrown = runCatching { call.execute() }.exceptionOrNull()
        }
        worker.start()
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))

        call.cancel()
        release.countDown()
        worker.join(5_000)

        assertTrue(thrown is CancellationException)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun deduplicatesCaseInsensitiveTrimmedNamesAndDropsBlankOnes() {
        val transport = FakeRegistrarTransport {
            ok("""[{"fqdn":"Dup.example"},{"fqdn":" dup.example "},{"fqdn":"   "},{"fqdn":"other.example"},{"name":""}]""")
        }

        val domains = RegistrarApi(transport).newFetchDomainsCall(credentials(RegistrarProvider.GANDI)).execute()

        assertEquals(listOf("Dup.example", "other.example"), domains.map { it.name })
    }

    // endregion

    private fun porkbunPage(names: List<String>): String =
        names.joinToString(",", "{\"status\":\"SUCCESS\",\"domains\":[", "]}") {
            "{\"domain\":\"$it\",\"status\":\"ACTIVE\",\"expireDate\":\"2030-01-01 00:00:00\"}"
        }

    private fun goDaddyPage(names: List<String>): String =
        names.joinToString(",", "[", "]") { "{\"domain\":\"$it\",\"status\":\"ACTIVE\"}" }

    private fun namecheapPage(totalItems: Int, pageSize: Int, vararg domains: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <ApiResponse Status="OK" xmlns="http://api.namecheap.com/xml.response">
          <Errors />
          <Warnings />
          <RequestedCommand>namecheap.domains.getList</RequestedCommand>
          <CommandResponse Type="namecheap.domains.getList">
            <DomainGetListResult>${domains.joinToString("")}</DomainGetListResult>
            <Paging>
              <TotalItems>$totalItems</TotalItems>
              <CurrentPage>1</CurrentPage>
              <PageSize>$pageSize</PageSize>
            </Paging>
          </CommandResponse>
          <Server>WEB1</Server>
          <GMTTimeDifference>--5:00</GMTTimeDifference>
          <ExecutionTime>0.008</ExecutionTime>
        </ApiResponse>
    """.trimIndent()

    private companion object {
        const val NAMECHEAP_DOMAIN_1 =
            """<Domain ID="127" Name="domain1.com" User="owner" Created="02/15/2016" Expires="02/15/2027" IsExpired="false" IsLocked="false" AutoRenew="false" WhoisGuard="ENABLED" IsPremium="false" IsOurDNS="true"/>"""
        const val NAMECHEAP_DOMAIN_2 =
            """<Domain ID="381" Name="domain2.net" User="owner" Created="04/28/2016" Expires="04/28/2023" IsExpired="true" IsLocked="true" AutoRenew="true" WhoisGuard="NOTPRESENT" IsPremium="false" IsOurDNS="false"/>"""
        const val NAMECHEAP_DOMAIN_3 =
            """<Domain ID="512" Name="domain3.io" User="owner" Created="01/01/2020" Expires="01/01/2030" IsExpired="false" IsLocked="false" AutoRenew="true" WhoisGuard="withheld" IsPremium="false" IsOurDNS="true"/>"""
    }
}
