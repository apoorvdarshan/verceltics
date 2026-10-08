package com.apoorvdarshan.verceltics.data.registrar

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Namecheap parser against Android's platform SAX implementation. */
@RunWith(AndroidJUnit4::class)
class AndroidNamecheapXmlParserTest {
    @Test
    fun parsesNamespacedDomainListAndPaging() {
        val page = NamecheapXmlParser.parseDomainPage(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <ApiResponse Status="OK" xmlns="http://api.namecheap.com/xml.response">
              <Errors />
              <CommandResponse Type="namecheap.domains.getList">
                <DomainGetListResult>
                  <Domain ID="127" Name="domain1.com" Created="02/15/2016" Expires="02/15/2027" IsExpired="false" IsLocked="true" AutoRenew="true" WhoisGuard="ENABLED" IsOurDNS="true"/>
                </DomainGetListResult>
                <Paging><TotalItems>41</TotalItems><CurrentPage>1</CurrentPage><PageSize>20</PageSize></Paging>
              </CommandResponse>
            </ApiResponse>
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(41, page.totalItems)
        assertEquals(20, page.pageSize)
        val domain = page.domains.single()
        assertEquals("domain1.com", domain.name)
        assertEquals(true, domain.locked)
        assertEquals(true, domain.autoRenew)
        assertEquals(true, domain.privacyEnabled)
        assertEquals(java.time.Instant.parse("2027-02-15T00:00:00Z").toEpochMilli(), domain.expiresAtMillis)
    }

    @Test
    fun surfacesApiErrorsAndRejectsEntities() {
        val apiError = assertThrows(RegistrarApiException::class.java) {
            NamecheapXmlParser.parseDomainPage(
                """<ApiResponse Status="ERROR"><Errors><Error Number="1011102">API Key is invalid or API access has not been enabled</Error></Errors></ApiResponse>""",
            )
        }
        assertEquals(
            "Request failed (HTTP 400): API Key is invalid or API access has not been enabled",
            apiError.message,
        )
        assertThrows(RegistrarApiException::class.java) {
            NamecheapXmlParser.parseDomainPage(
                """<!DOCTYPE a [<!ENTITY b "c">]><ApiResponse><Errors><Error>&b;</Error></Errors></ApiResponse>""",
            )
        }
    }
}
