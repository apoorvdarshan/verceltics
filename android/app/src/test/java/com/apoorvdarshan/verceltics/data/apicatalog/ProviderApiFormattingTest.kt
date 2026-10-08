package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.HostingJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiFormattingTest {
    @Test
    fun prettyJsonSortsKeysAndIndentsLikeIos() {
        val value = HostingJson.parse("{\"b\":[1,{\"z\":true,\"a\":null}],\"a\":\"x\\ny\",\"e\":{},\"f\":[]}")
        assertEquals(
            """
            {
              "a": "x\ny",
              "b": [
                1,
                {
                  "a": null,
                  "z": true
                }
              ],
              "e": {},
              "f": []
            }
            """.trimIndent(),
            ProviderApiJson.pretty(value),
        )
    }

    @Test
    fun responseBodiesArePrettyPrintedWhenTheyAreJson() {
        assertEquals("{\n  \"id\": 1,\n  \"name\": \"site\"\n}", ProviderApiResponseFormatter.formatBody("  {\"name\":\"site\",\"id\":1}  "))
        assertEquals("[\n  1,\n  2\n]", ProviderApiResponseFormatter.formatBody("[1,2]"))
        assertEquals("42", ProviderApiResponseFormatter.formatBody("42"))
        assertEquals("(empty response)", ProviderApiResponseFormatter.formatBody(""))
        assertEquals("{broken", ProviderApiResponseFormatter.formatBody("{broken"))
        assertEquals("plain text", ProviderApiResponseFormatter.formatBody("plain text"))
        // Big integers keep every digit.
        assertEquals("{\n  \"id\": 12345678901234567890\n}", ProviderApiResponseFormatter.formatBody("{\"id\":12345678901234567890}"))
    }

    @Test
    fun xmlResponsesAreIndented() {
        val xml = "<?xml version=\"1.0\"?><ApiResponse Status=\"OK\"><Errors/><CommandResponse Type=\"x\">" +
            "<Domain Name=\"a>b.com\">active</Domain><!-- note --><Paging><TotalItems>2</TotalItems></Paging></CommandResponse></ApiResponse>"
        assertEquals(
            """
            <?xml version="1.0"?>
            <ApiResponse Status="OK">
              <Errors/>
              <CommandResponse Type="x">
                <Domain Name="a>b.com">active</Domain>
                <!-- note -->
                <Paging>
                  <TotalItems>2</TotalItems>
                </Paging>
              </CommandResponse>
            </ApiResponse>
            """.trimIndent(),
            ProviderApiResponseFormatter.formatBody(xml, "text/xml"),
        )
        assertEquals("<a></a>", ProviderApiResponseFormatter.prettyXmlOrNull("<a></a>"))
    }

    @Test
    fun unbalancedMarkupIsShownAsReceived() {
        val html = "<!DOCTYPE html><html><body><br><p>Hi</body></html>"
        assertEquals(html, ProviderApiResponseFormatter.formatBody(html, "text/html"))
        assertNull(ProviderApiResponseFormatter.prettyXmlOrNull("<a><b></a>"))
        assertNull(ProviderApiResponseFormatter.prettyXmlOrNull("<a"))
        assertNull(ProviderApiResponseFormatter.prettyXmlOrNull("just text"))
    }

    @Test
    fun displayTextIsBoundedForCompose() {
        val (short, shortTruncated) = ProviderApiResponseFormatter.displayText("abc", limit = 5)
        assertEquals("abc", short)
        assertFalse(shortTruncated)
        val (long, longTruncated) = ProviderApiResponseFormatter.displayText("abcdefgh", limit = 5)
        assertEquals("abcde", long)
        assertTrue(longTruncated)
    }

    @Test
    fun hugeBodiesAreNotReformatted() {
        val huge = "[" + "1,".repeat(ProviderApiResponseFormatter.MAXIMUM_PRETTY_CHARACTERS / 2) + "1]"
        assertEquals(huge, ProviderApiResponseFormatter.formatBody(huge))
    }

    @Test
    fun headersAreListedNameValueSortedCaseInsensitively() {
        assertEquals(
            "content-type: application/json\nDate: today\nx-request-id: 1",
            ProviderApiResponseFormatter.headerText(listOf("x-request-id" to "1", "Date" to "today", "content-type" to "application/json")),
        )
    }
}
