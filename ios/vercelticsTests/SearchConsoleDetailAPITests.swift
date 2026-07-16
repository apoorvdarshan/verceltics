import XCTest
@testable import verceltics

final class SearchConsoleDetailAPITests: XCTestCase {
    func testDateRangePreservesIndiaCalendarDayInsteadOfPacificOrUTCDay() throws {
        var indiaCalendar = Calendar(identifier: .gregorian)
        indiaCalendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let selectedDate = try XCTUnwrap(indiaCalendar.date(from: DateComponents(
            year: 2026,
            month: 7,
            day: 16,
            hour: 0,
            minute: 15
        )))

        let range = SearchConsoleDateRange(
            startDate: selectedDate,
            endDate: selectedDate,
            calendar: indiaCalendar
        )

        // This instant is July 15 in UTC and Pacific time, but the picker selection is July 16.
        XCTAssertEqual(range.startDate, "2026-07-16")
        XCTAssertEqual(range.endDate, "2026-07-16")
    }

    func testGoogleDateDisplayDoesNotShiftToPreviousDayWestOfPacificTime() throws {
        var hawaiiCalendar = Calendar(identifier: .gregorian)
        hawaiiCalendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Pacific/Honolulu"))

        let date = try XCTUnwrap(SearchConsoleDateRange.localDisplayDate(
            "2026-07-16",
            calendar: hawaiiCalendar
        ))
        let components = hawaiiCalendar.dateComponents([.year, .month, .day], from: date)

        XCTAssertEqual(components.year, 2026)
        XCTAssertEqual(components.month, 7)
        XCTAssertEqual(components.day, 16)
        XCTAssertNil(SearchConsoleDateRange.localDisplayDate("2026-02-29", calendar: hawaiiCalendar))
    }

    func testSearchAnalyticsRequestEncodesEverySupportedControl() throws {
        let api = try makeAPI()
        let query = SearchConsoleAnalyticsQuery(
            dateRange: SearchConsoleDateRange(startDate: "2026-06-01", endDate: "2026-06-30"),
            dimensions: [.date, .query, .country, .device, .searchAppearance],
            searchType: .image,
            dimensionFilterGroups: [
                SearchConsoleDimensionFilterGroup(filters: [
                    SearchConsoleDimensionFilter(
                        dimension: .query,
                        operator: .includingRegex,
                        expression: "swift|ios"
                    ),
                    SearchConsoleDimensionFilter(
                        dimension: .device,
                        operator: .notEquals,
                        expression: "TABLET"
                    ),
                ]),
            ],
            aggregationType: .byPage,
            rowLimit: 25_000,
            startRow: 50_000,
            dataState: .all
        )

        let request = try api.makeSearchAnalyticsRequest(
            siteURL: "sc-domain:example.com",
            query: query
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(
            request.url?.absoluteString,
            "https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Aexample.com/searchAnalytics/query"
        )

        let body = try XCTUnwrap(request.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["startDate"] as? String, "2026-06-01")
        XCTAssertEqual(json["endDate"] as? String, "2026-06-30")
        XCTAssertEqual(json["type"] as? String, "image")
        XCTAssertEqual(
            json["dimensions"] as? [String],
            ["date", "query", "country", "device", "searchAppearance"]
        )
        XCTAssertEqual(json["aggregationType"] as? String, "byPage")
        XCTAssertEqual(json["rowLimit"] as? Int, 25_000)
        XCTAssertEqual(json["startRow"] as? Int, 50_000)
        XCTAssertEqual(json["dataState"] as? String, "all")

        let groups = try XCTUnwrap(json["dimensionFilterGroups"] as? [[String: Any]])
        XCTAssertEqual(groups.first?["groupType"] as? String, "and")
        let filters = try XCTUnwrap(groups.first?["filters"] as? [[String: Any]])
        XCTAssertEqual(filters.first?["dimension"] as? String, "query")
        XCTAssertEqual(filters.first?["operator"] as? String, "includingRegex")
        XCTAssertEqual(filters.first?["expression"] as? String, "swift|ios")
    }

    func testHourlyQueryRequiresHourlyAllDataState() throws {
        let api = try makeAPI()
        let query = SearchConsoleAnalyticsQuery(
            dateRange: SearchConsoleDateRange(startDate: "2026-07-01", endDate: "2026-07-02"),
            dimensions: [.hour],
            dataState: .all
        )

        XCTAssertThrowsError(
            try api.makeSearchAnalyticsRequest(siteURL: "https://example.com/", query: query)
        ) { error in
            XCTAssertEqual(
                error as? SearchConsoleDetailAPIError,
                .invalidRequest("Hourly results require the hourly_all data state.")
            )
        }
    }

    func testQueryValidationRejectsInvalidPaginationAndAggregation() throws {
        let range = SearchConsoleDateRange(startDate: "2026-07-01", endDate: "2026-07-16")
        XCTAssertThrowsError(try SearchConsoleDetailAPI.validate(
            SearchConsoleAnalyticsQuery(dateRange: range, rowLimit: 25_001)
        ))
        XCTAssertThrowsError(try SearchConsoleDetailAPI.validate(
            SearchConsoleAnalyticsQuery(dateRange: range, startRow: -1)
        ))
        XCTAssertThrowsError(try SearchConsoleDetailAPI.validate(
            SearchConsoleAnalyticsQuery(
                dateRange: range,
                dimensions: [.page],
                aggregationType: .byProperty
            )
        ))
        XCTAssertThrowsError(try SearchConsoleDetailAPI.validate(
            SearchConsoleAnalyticsQuery(
                dateRange: SearchConsoleDateRange(
                    startDate: "2026-02-29",
                    endDate: "2026-03-01"
                )
            )
        ))
    }

    func testSitesMethodsUseOfficialMethodsAndEncodeWholePropertyAsOnePathSegment() throws {
        let api = try makeAPI()

        let list = try api.makeListSitesRequest()
        XCTAssertEqual(list.httpMethod, "GET")
        XCTAssertEqual(list.url?.absoluteString, "https://www.googleapis.com/webmasters/v3/sites")

        let get = try api.makeGetSiteRequest(siteURL: "https://example.com/path/")
        XCTAssertEqual(get.httpMethod, "GET")
        XCTAssertEqual(
            get.url?.absoluteString,
            "https://www.googleapis.com/webmasters/v3/sites/https%3A%2F%2Fexample.com%2Fpath%2F"
        )
        XCTAssertEqual(try api.makeAddSiteRequest(siteURL: "sc-domain:example.com").httpMethod, "PUT")
        XCTAssertEqual(try api.makeDeleteSiteRequest(siteURL: "sc-domain:example.com").httpMethod, "DELETE")
    }

    func testSitemapMethodsPreserveFullURLAndOptionalIndexQuery() throws {
        let api = try makeAPI()
        let site = "https://example.com/"
        let feedpath = "https://example.com/sitemap.xml?locale=en"

        let list = try api.makeListSitemapsRequest(
            siteURL: site,
            sitemapIndex: "https://example.com/sitemap-index.xml"
        )
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(list.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(
            components.queryItems,
            [URLQueryItem(name: "sitemapIndex", value: "https://example.com/sitemap-index.xml")]
        )

        let get = try api.makeGetSitemapRequest(siteURL: site, feedpath: feedpath)
        XCTAssertEqual(get.httpMethod, "GET")
        XCTAssertTrue(get.url?.absoluteString.hasSuffix(
            "/sitemaps/https%3A%2F%2Fexample.com%2Fsitemap.xml%3Flocale%3Den"
        ) == true)
        XCTAssertEqual(try api.makeSubmitSitemapRequest(siteURL: site, feedpath: feedpath).httpMethod, "PUT")
        XCTAssertEqual(try api.makeDeleteSitemapRequest(siteURL: site, feedpath: feedpath).httpMethod, "DELETE")
    }

    func testSitemapResponseDecodesAllFieldsAndGoogleInt64Strings() throws {
        let data = Data(#"""
        {
          "sitemap": [{
            "path": "https://example.com/sitemap.xml",
            "lastSubmitted": "2026-07-01T10:00:00Z",
            "isPending": true,
            "isSitemapsIndex": false,
            "type": "sitemap",
            "lastDownloaded": "2026-07-02T11:00:00.123456789Z",
            "warnings": "3",
            "errors": 2,
            "contents": [{"type": "web", "submitted": "100", "indexed": "91"}]
          }]
        }
        """#.utf8)

        let response = try JSONDecoder().decode(SearchConsoleSitemapListResponse.self, from: data)
        let sitemap = try XCTUnwrap(response.sitemap.first)
        XCTAssertEqual(sitemap.lastSubmitted, "2026-07-01T10:00:00Z")
        XCTAssertEqual(sitemap.lastDownloaded, "2026-07-02T11:00:00.123456789Z")
        XCTAssertTrue(sitemap.isPending)
        XCTAssertFalse(sitemap.isSitemapsIndex)
        XCTAssertEqual(sitemap.type, "sitemap")
        XCTAssertEqual(sitemap.warnings, 3)
        XCTAssertEqual(sitemap.errors, 2)
        XCTAssertEqual(sitemap.contents.first?.submitted, 100)
        XCTAssertEqual(sitemap.contents.first?.indexed, 91)
    }

    func testSitemapResponseToleratesOmittedOptionalCounts() throws {
        let data = Data(#"""
        {
          "sitemap": [{
            "path": "https://example.com/pending-sitemap.xml",
            "isPending": true,
            "contents": [{"type": "web", "submitted": "12"}]
          }]
        }
        """#.utf8)

        let response = try JSONDecoder().decode(SearchConsoleSitemapListResponse.self, from: data)
        let sitemap = try XCTUnwrap(response.sitemap.first)
        XCTAssertEqual(sitemap.warnings, 0)
        XCTAssertEqual(sitemap.errors, 0)
        XCTAssertNil(sitemap.contents.first?.indexed)
    }

    func testAnalyticsResponseAllowsKeysToBeOmittedForUnGroupedTotals() throws {
        let data = Data(#"""
        {
          "rows": [{
            "clicks": 12,
            "impressions": 240,
            "ctr": 0.05,
            "position": 4.25
          }]
        }
        """#.utf8)

        let response = try JSONDecoder().decode(SearchConsoleAnalyticsResponse.self, from: data)
        XCTAssertEqual(response.rows.first?.keys, [])
    }

    func testURLInspectionAcceptsArbitraryURLAndEncodesLanguage() throws {
        let api = try makeAPI()
        let request = try api.makeURLInspectionRequest(
            inspectionURL: try XCTUnwrap(URL(string: "https://example.com/articles/one?preview=false")),
            siteURL: "sc-domain:example.com",
            languageCode: "en-GB"
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(
            request.url?.absoluteString,
            "https://searchconsole.googleapis.com/v1/urlInspection/index:inspect"
        )
        let body = try XCTUnwrap(request.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(json["inspectionUrl"], "https://example.com/articles/one?preview=false")
        XCTAssertEqual(json["siteUrl"], "sc-domain:example.com")
        XCTAssertEqual(json["languageCode"], "en-GB")
    }

    func testURLInspectionDecodesIndexAMPDeprecatedMobileAndRichResults() throws {
        let data = Data(#"""
        {
          "inspectionResult": {
            "inspectionResultLink": "https://search.google.com/search-console/inspect/example",
            "indexStatusResult": {
              "sitemap": ["https://example.com/sitemap.xml"],
              "referringUrls": ["https://example.com/archive"],
              "verdict": "PASS",
              "coverageState": "Submitted and indexed",
              "robotsTxtState": "ALLOWED",
              "indexingState": "INDEXING_ALLOWED",
              "lastCrawlTime": "2026-07-15T12:34:56.123456789Z",
              "pageFetchState": "SUCCESSFUL",
              "googleCanonical": "https://example.com/page",
              "userCanonical": "https://example.com/page",
              "crawledAs": "MOBILE"
            },
            "ampResult": {
              "issues": [{"issueMessage": "Deprecated element", "severity": "WARNING"}],
              "verdict": "PASS",
              "ampUrl": "https://example.com/page.amp",
              "robotsTxtState": "ALLOWED",
              "indexingState": "AMP_INDEXING_ALLOWED",
              "ampIndexStatusVerdict": "PASS",
              "lastCrawlTime": "2026-07-15T12:34:56Z",
              "pageFetchState": "SUCCESSFUL"
            },
            "mobileUsabilityResult": {
              "issues": [{
                "issueType": "TAP_TARGETS_TOO_CLOSE",
                "severity": "WARNING",
                "message": "Some tap targets are close"
              }],
              "verdict": "FAIL"
            },
            "richResultsResult": {
              "detectedItems": [{
                "richResultType": "Product snippets",
                "items": [{
                  "name": "Example product",
                  "issues": [{"issueMessage": "Missing review", "severity": "WARNING"}]
                }]
              }],
              "verdict": "PASS"
            }
          }
        }
        """#.utf8)

        let response = try JSONDecoder().decode(SearchConsoleURLInspectionResponse.self, from: data)
        let result = response.inspectionResult
        XCTAssertEqual(result.indexStatusResult?.sitemap, ["https://example.com/sitemap.xml"])
        XCTAssertEqual(result.indexStatusResult?.referringUrls, ["https://example.com/archive"])
        XCTAssertEqual(result.indexStatusResult?.crawledAs, "MOBILE")
        XCTAssertEqual(result.ampResult?.issues.first?.severity, "WARNING")
        XCTAssertEqual(result.mobileUsabilityResult?.issues.first?.issueType, "TAP_TARGETS_TOO_CLOSE")
        XCTAssertEqual(result.richResultsResult?.detectedItems.first?.richResultType, "Product snippets")
        XCTAssertEqual(
            result.richResultsResult?.detectedItems.first?.items.first?.issues.first?.issueMessage,
            "Missing review"
        )
    }

    func testAnalyticsResponsePreservesRowsAggregationAndIncompleteMetadata() throws {
        let data = Data(#"""
        {
          "rows": [{
            "keys": ["2026-07-15", "mobile"],
            "clicks": 12,
            "impressions": 240,
            "ctr": 0.05,
            "position": 4.25
          }],
          "responseAggregationType": "byProperty",
          "metadata": {
            "first_incomplete_date": "2026-07-15",
            "first_incomplete_hour": "2026-07-15T18:00:00-07:00"
          }
        }
        """#.utf8)

        let response = try JSONDecoder().decode(SearchConsoleAnalyticsResponse.self, from: data)
        XCTAssertEqual(response.rows.first?.keys, ["2026-07-15", "mobile"])
        XCTAssertEqual(response.rows.first?.ctr, 0.05)
        XCTAssertEqual(response.responseAggregationType, "byProperty")
        XCTAssertEqual(response.metadata?.firstIncompleteDate, "2026-07-15")
        XCTAssertEqual(response.metadata?.firstIncompleteHour, "2026-07-15T18:00:00-07:00")
    }

    private func makeAPI() throws -> SearchConsoleDetailAPI {
        let credential = GoogleOAuthCredential(
            accessToken: "access-token",
            refreshToken: "refresh-token",
            tokenType: "Bearer",
            scopes: ["https://www.googleapis.com/auth/webmasters"],
            expiresAt: .distantFuture,
            subject: "subject",
            email: "owner@example.com"
        )
        return SearchConsoleDetailAPI(account: SiteIntegrationAccount(
            provider: .googleSearchConsole,
            name: "Search Console",
            credential: try credential.keychainValue()
        ))
    }
}
