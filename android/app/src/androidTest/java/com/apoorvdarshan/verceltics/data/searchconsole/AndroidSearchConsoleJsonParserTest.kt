package com.apoorvdarshan.verceltics.data.searchconsole

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.searchconsole.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSearchConsoleJsonParserTest {
    private val parser = AndroidSearchConsoleJsonParser()

    @Test
    fun parsesPropertiesAndTruthfullyCountsMalformedEntries() {
        val result = parser.parseProperties(
            """
            {
              "siteEntry": [
                {"siteUrl":"sc-domain:example.com","permissionLevel":"siteOwner"},
                {"siteUrl":"https://example.org/","permissionLevel":"siteUnverifiedUser"},
                {"permissionLevel":"siteOwner"}
              ]
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(2, result.properties.size)
        assertEquals(1, result.skippedEntries)
        assertTrue(result.properties.first().isVerified)
        assertFalse(result.properties[1].isVerified)
    }

    @Test
    fun parsesAnalyticsDefaultsAndIncompleteMetadata() {
        val response = parser.parseAnalytics(
            """
            {
              "rows":[
                {"keys":["2026-08-27","swiftui"],"clicks":12,"impressions":120,
                 "ctr":0.1,"position":2.75}
              ],
              "responseAggregationType":"byPage",
              "metadata":{"first_incomplete_date":"2026-08-27",
                          "first_incomplete_hour":"2026-08-27T10:00:00-07:00"}
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(listOf("2026-08-27", "swiftui"), response.rows.single().keys)
        assertEquals(12.0, response.rows.single().clicks, 0.0)
        assertEquals("byPage", response.responseAggregationType)
        assertEquals("2026-08-27", response.metadata?.firstIncompleteDate)
    }

    @Test
    fun parsesSitemapsWithGoogleStringIntegersAndMissingOptionalCounts() {
        val sitemaps = parser.parseSitemaps(
            """
            {"sitemap":[{
              "path":"https://example.com/sitemap.xml",
              "lastSubmitted":"2026-07-01T10:00:00Z",
              "lastDownloaded":"2026-07-02T11:00:00.123456789Z",
              "isPending":true,"isSitemapsIndex":false,"type":"sitemap",
              "warnings":"3","errors":2,
              "contents":[{"type":"web","submitted":"100","indexed":91}]
            },{
              "path":"https://example.com/pending.xml",
              "contents":[{"type":"web","submitted":5}]
            }]}
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(3L, sitemaps.first().warnings)
        assertEquals(91L, sitemaps.first().contents.single().indexed)
        assertEquals(0L, sitemaps[1].warnings)
        assertNull(sitemaps[1].contents.single().indexed)

        val single = parser.parseSitemap(
            """{"path":"https://example.com/sitemap.xml","warnings":"1"}"""
                .encodeToByteArray(),
        )
        assertEquals(1L, single.warnings)
    }

    @Test
    fun parsesCompleteUrlInspectionSurfaceWithoutInventingMissingValues() {
        val result = parser.parseInspection(
            """
            {"inspectionResult":{
              "inspectionResultLink":"https://search.google.com/search-console/inspect?x=1",
              "indexStatusResult":{
                "sitemap":["https://example.com/sitemap.xml"],
                "referringUrls":["https://example.com/"],
                "verdict":"PASS","coverageState":"Submitted and indexed",
                "robotsTxtState":"ALLOWED","indexingState":"INDEXING_ALLOWED",
                "lastCrawlTime":"2026-08-27T10:00:00Z","pageFetchState":"SUCCESSFUL",
                "googleCanonical":"https://example.com/a","userCanonical":"https://example.com/a",
                "crawledAs":"MOBILE"
              },
              "ampResult":{"verdict":"PASS","issues":[{"severity":"WARNING","issueMessage":"note"}]},
              "mobileUsabilityResult":{"verdict":"FAIL","issues":[{
                "issueType":"TAP_TARGETS_TOO_CLOSE","severity":"ERROR","message":"targets"
              }]},
              "richResultsResult":{"verdict":"PASS","detectedItems":[{
                "richResultType":"Breadcrumbs","items":[{"name":"BreadcrumbList","issues":[]}]
              }]}
            }}
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("PASS", result.indexStatus?.verdict)
        assertEquals("MOBILE", result.indexStatus?.crawledAs)
        assertEquals("note", result.ampResult?.issues?.single()?.message)
        assertEquals("TAP_TARGETS_TOO_CLOSE", result.mobileUsabilityResult?.issues?.single()?.type)
        assertEquals("Breadcrumbs", result.richResultsResult?.detectedItems?.single()?.richResultType)
    }

    @Test
    fun parsesAmpDetailsAndRichResultTypesThatHaveNoIssues() {
        val result = parser.parseInspection(
            """
            {"inspectionResult":{
              "ampResult":{
                "verdict":"FAIL","ampUrl":"https://example.com/amp","robotsTxtState":"ALLOWED",
                "indexingState":"INDEXING_ALLOWED","ampIndexStatusVerdict":"PASS",
                "lastCrawlTime":"2026-08-25T08:00:00Z","pageFetchState":"SUCCESSFUL",
                "issues":[{"severity":"ERROR","issueMessage":"Referenced AMP URL is not an AMP"}]
              },
              "richResultsResult":{"verdict":"PASS","detectedItems":[
                {"richResultType":"Breadcrumbs","items":[{"name":"Unnamed item"}]},
                {"richResultType":"FAQ","items":[
                  {"name":"Q1","issues":[{"severity":"WARNING","issueMessage":"Missing field"}]},
                  {"issues":[]}
                ]}
              ]}
            }}
            """.trimIndent().encodeToByteArray(),
        )
        val ui = result.toUi("https://example.com/")

        assertEquals("https://example.com/amp", result.ampResult?.ampUrl)
        assertEquals("PASS", result.ampResult?.ampIndexStatusVerdict)
        assertEquals("2026-08-25T08:00:00Z", result.ampResult?.lastCrawlTime)
        assertEquals(listOf("Breadcrumbs", "FAQ"), ui.richResultTypes.map { it.type })
        assertEquals(listOf(0, 1), ui.richResultTypes.map { it.issueCount })
        assertEquals(2, ui.richResultTypes.last().items.size)
        assertEquals("Referenced AMP URL is not an AMP", ui.amp?.issues?.single()?.title)
    }

    @Test
    fun inspectionBudgetIsSharedAcrossSiblingArrays() {
        val sitemaps = List(200) { "\"https://example.com/sitemap-$it.xml\"" }.joinToString(",")
        val referringUrls = List(57) { "\"https://example.com/referrer-$it\"" }.joinToString(",")

        assertThrows(SearchConsoleResponseFormatException::class.java) {
            parser.parseInspection(
                """
                {"inspectionResult":{"indexStatusResult":{
                  "sitemap":[$sitemaps],
                  "referringUrls":[$referringUrls]
                }}}
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    @Test
    fun inspectionBudgetIsSharedAcrossNestedRichResultItemsAndIssues() {
        val detectedItems = List(2) { detectedIndex ->
            val items = List(70) { itemIndex ->
                """{"name":"item-$detectedIndex-$itemIndex","issues":[{"severity":"ERROR","issueMessage":"invalid"}]}"""
            }.joinToString(",")
            """{"richResultType":"type-$detectedIndex","items":[$items]}"""
        }.joinToString(",")

        assertThrows(SearchConsoleResponseFormatException::class.java) {
            parser.parseInspection(
                """
                {"inspectionResult":{"richResultsResult":{"detectedItems":[$detectedItems]}}}
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    @Test
    fun parsesRefreshResponseButNeverPrintsTokens() {
        val access = "new-access-secret"
        val refresh = "new-refresh-secret"
        val response = parser.parseTokenResponse(
            """
            {"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer",
             "scope":"openid email https://www.googleapis.com/auth/webmasters.readonly",
             "expires_in":"3600"}
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(SecretValue.of(access), response.accessToken)
        assertEquals(SecretValue.of(refresh), response.refreshToken)
        assertEquals(3, response.scopes?.size)
        assertFalse(response.toString().contains(access))
        assertFalse(response.toString().contains(refresh))
    }

    @Test
    fun malformedRequiredAnalyticsMetricAndTrailingJsonAreRejected() {
        assertThrows(SearchConsoleResponseFormatException::class.java) {
            parser.parseAnalytics(
                """{"rows":[{"clicks":1,"impressions":2,"ctr":0.5}]}""".encodeToByteArray(),
            )
        }
        assertThrows(SearchConsoleResponseFormatException::class.java) {
            parser.parseProperties("{} {}".encodeToByteArray())
        }
    }
}
