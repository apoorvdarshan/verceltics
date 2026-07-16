import XCTest
@testable import verceltics

@MainActor
final class SiteIntegrationDetailAPITests: XCTestCase {
    override func tearDown() {
        SiteIntegrationDetailMockURLProtocol.handler = nil
        SiteIntegrationDetailMockURLProtocol.requests = []
        super.tearDown()
    }

    func testLosslessJSONRoundTripRecursivelyRedactsOnlySecrets() throws {
        let value = SiteIntegrationJSONValue.object([
            "status": .string("up"),
            "count": .number(17),
            "config": .object([
                "request_headers": .object(["Authorization": .string("Bearer private")]),
                "ordinary_value": .string("preserved")
            ]),
            "rows": .array([.object(["token": .string("private"), "value": .number(3)])])
        ]).sanitizingSecrets()

        XCTAssertEqual(value["status"], .string("up"))
        XCTAssertEqual(value["config"]?["request_headers"], .string("[REDACTED]"))
        XCTAssertEqual(value["config"]?["ordinary_value"], .string("preserved"))
        XCTAssertEqual(value["rows"]?.arrayValue?.first?["token"], .string("[REDACTED]"))
        XCTAssertEqual(value["rows"]?.arrayValue?.first?["value"], .number(3))

        let encoded = try JSONEncoder().encode(value)
        XCTAssertEqual(try JSONDecoder().decode(SiteIntegrationJSONValue.self, from: encoded), value)
    }

    func testSecretSanitizerRedactsCamelCaseAndSeparatorVariants() throws {
        let secretKeys = [
            "accessToken", "access_token", "access-token", "Access Token",
            "refreshToken", "clientSecret", "requestHeaders", "verificationToken",
            "httpPassword", "apiSecret", "privateKey", "clientCredentials",
            "proxy-authorization", "environmentVariables", "set_cookie"
        ]
        var object = Dictionary(uniqueKeysWithValues: secretKeys.map { ($0, SiteIntegrationJSONValue.string("private")) })
        object["tokenType"] = .string("Bearer")
        object["publicIdentifier"] = .string("keep-me")

        let sanitized = SiteIntegrationJSONValue.object(object).sanitizingSecrets()

        for key in secretKeys {
            XCTAssertEqual(sanitized[key], .string("[REDACTED]"), "Expected \(key) to be redacted")
        }
        XCTAssertEqual(sanitized["tokenType"], .string("Bearer"))
        XCTAssertEqual(sanitized["publicIdentifier"], .string("keep-me"))
    }

    func testSecretSanitizerRedactsHeaderAliasesAndSecretsEmbeddedInURLs() throws {
        let sanitized = SiteIntegrationJSONValue.object([
            "X-Api-Key": .string("header-secret"),
            "X-Auth-Token": .string("header-token"),
            "Authorization-Header": .string("Bearer hidden"),
            "callbackURL": .string(
                "https://alice:password@example.com/callback?token=private&sealed_token=sealed&api_key=key&mode=full"
            ),
            "ordinaryText": .string("alice:password@example.com is not a URL")
        ]).sanitizingSecrets()

        XCTAssertEqual(sanitized["X-Api-Key"], .string("[REDACTED]"))
        XCTAssertEqual(sanitized["X-Auth-Token"], .string("[REDACTED]"))
        XCTAssertEqual(sanitized["Authorization-Header"], .string("[REDACTED]"))

        let url = try XCTUnwrap(sanitized["callbackURL"]?.stringValue)
        let components = try XCTUnwrap(URLComponents(string: url))
        XCTAssertNil(components.user)
        XCTAssertNil(components.password)
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map {
            ($0.name, $0.value)
        })
        XCTAssertEqual(query["token"]!, "[REDACTED]")
        XCTAssertEqual(query["sealed_token"]!, "[REDACTED]")
        XCTAssertEqual(query["api_key"]!, "[REDACTED]")
        XCTAssertEqual(query["mode"]!, "full")
        XCTAssertEqual(sanitized["ordinaryText"], .string("alice:password@example.com is not a URL"))
    }

    func testJSONNumbersPreserveIntegerAndHighPrecisionDecimalText() throws {
        let data = Data(#"{"id":9007199254740993,"unsigned":18446744073709551615,"ratio":0.1234567890123456789012345678}"#.utf8)
        let value = try JSONDecoder().decode(SiteIntegrationJSONValue.self, from: data)

        XCTAssertEqual(value["id"]?.stringValue, "9007199254740993")
        XCTAssertEqual(value["unsigned"]?.stringValue, "18446744073709551615")
        XCTAssertEqual(value["ratio"]?.stringValue, "0.1234567890123456789012345678")

        let encoded = try JSONEncoder().encode(value)
        let roundTrip = try JSONDecoder().decode(SiteIntegrationJSONValue.self, from: encoded)
        XCTAssertEqual(roundTrip["id"]?.stringValue, "9007199254740993")
        XCTAssertEqual(roundTrip["unsigned"]?.stringValue, "18446744073709551615")
        XCTAssertEqual(roundTrip["ratio"]?.stringValue, "0.1234567890123456789012345678")
    }

    func testDetailRangePreservesIndiaCalendarDateInsteadOfUTCDate() throws {
        var indiaCalendar = Calendar(identifier: .gregorian)
        indiaCalendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let selectedDate = try XCTUnwrap(indiaCalendar.date(from: DateComponents(
            year: 2026,
            month: 7,
            day: 16,
            hour: 0,
            minute: 15
        )))

        // This instant is July 15 in UTC, but the user selected July 16 in India.
        XCTAssertEqual(
            SiteIntegrationDetailRange.dateString(selectedDate, calendar: indiaCalendar),
            "2026-07-16"
        )
    }

    func testGoogleAnalyticsUsesInjectedSessionAndBuildsTimelineAndBreakdowns() async throws {
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer oauth-token")
            let path = request.url?.path ?? ""
            if path.hasSuffix(":runReport") {
                XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
                let requestBody = try decodedJSONBody(of: request)
                let dimensions = (requestBody["dimensions"] as? [[String: String]] ?? [])
                    .compactMap { $0["name"] }
                let metrics = (requestBody["metrics"] as? [[String: String]] ?? [])
                    .compactMap { $0["name"] }
                let offset = Int(requestBody["offset"] as? String ?? "0") ?? 0
                let expectedMetrics: [String]
                let sample: String
                let rowCount: Int
                switch dimensions {
                case []:
                    expectedMetrics = [
                        "activeUsers", "sessions", "screenPageViews", "engagementRate",
                        "eventCount", "averageSessionDuration"
                    ]
                    sample = "overview"
                    rowCount = 1
                case ["date"]:
                    expectedMetrics = [
                        "activeUsers", "sessions", "screenPageViews", "engagementRate",
                        "eventCount", "averageSessionDuration"
                    ]
                    sample = "20260715"
                    rowCount = 1
                case ["sessionDefaultChannelGroup", "sessionSource", "sessionMedium"]:
                    expectedMetrics = ["sessions", "activeUsers", "engagementRate"]
                    sample = offset == 0 ? "organic" : "direct"
                    rowCount = 2
                case ["pagePath", "pageTitle"]:
                    expectedMetrics = ["screenPageViews", "activeUsers", "averageSessionDuration"]
                    sample = "page"
                    rowCount = 1
                case ["eventName"]:
                    expectedMetrics = ["eventCount", "activeUsers"]
                    sample = "event"
                    rowCount = 1
                case ["country", "city"]:
                    expectedMetrics = ["activeUsers", "sessions"]
                    sample = "geo"
                    rowCount = 1
                case ["deviceCategory", "browser", "operatingSystem"]:
                    expectedMetrics = ["activeUsers", "sessions"]
                    sample = "tech"
                    // Larger than the initial fair-share limit so the client must reclaim the
                    // unused budgets from the sparse breakdowns and refill this report.
                    rowCount = 4_100
                default:
                    XCTFail("Unexpected GA report dimensions: \(dimensions)")
                    expectedMetrics = metrics
                    sample = "unexpected"
                    rowCount = 1
                }
                XCTAssertEqual(metrics, expectedMetrics)
                if dimensions.isEmpty {
                    XCTAssertNil(requestBody["orderBys"])
                } else {
                    let orderBys = try XCTUnwrap(requestBody["orderBys"] as? [[String: Any]])
                    XCTAssertEqual(orderBys.count, dimensions.count)
                    XCTAssertEqual(
                        orderBys.compactMap {
                            ($0["dimension"] as? [String: Any])?["dimensionName"] as? String
                        },
                        dimensions
                    )
                }
                let requestedLimit = Int(requestBody["limit"] as? String ?? "1") ?? 1
                let returnedRowCount: Int
                if dimensions == ["deviceCategory", "browser", "operatingSystem"] {
                    returnedRowCount = max(0, min(requestedLimit, rowCount - offset))
                } else {
                    returnedRowCount = 1
                }
                let rows = (0..<returnedRowCount).map { rowIndex in
                    let dimensionValues = dimensions.map { name in
                        [
                            "value": name == "date"
                                ? sample
                                : "\(sample)-\(name)-\(offset + rowIndex)"
                        ]
                    }
                    let metricValues = metrics.enumerated().map { index, _ in
                        ["value": String(index + 1)]
                    }
                    return ["dimensionValues": dimensionValues, "metricValues": metricValues]
                }
                return (200, [
                    "dimensionHeaders": dimensions.map { ["name": $0] },
                    "metricHeaders": metrics.map { ["name": $0, "type": "TYPE_INTEGER"] },
                    "rows": rows,
                    "rowCount": rowCount,
                    "metadata": ["currencyCode": "USD", "timeZone": "UTC"]
                ])
            }
            if path.hasSuffix(":runRealtimeReport") {
                let requestBody = try decodedJSONBody(of: request)
                let dimensions = (requestBody["dimensions"] as? [[String: String]] ?? [])
                    .compactMap { $0["name"] }
                let isTimeline = dimensions == ["minutesAgo"]
                if isTimeline {
                    let orderBys = try XCTUnwrap(requestBody["orderBys"] as? [[String: Any]])
                    XCTAssertEqual(
                        (orderBys.first?["dimension"] as? [String: Any])?["dimensionName"] as? String,
                        "minutesAgo"
                    )
                }
                let metrics = ["activeUsers", "eventCount", "screenPageViews"]
                return (200, [
                    "dimensionHeaders": dimensions.map { ["name": $0] },
                    "metricHeaders": metrics.map { ["name": $0, "type": "TYPE_INTEGER"] },
                    "rows": [[
                        "dimensionValues": dimensions.map { _ in ["value": "5"] },
                        "metricValues": metrics.indices.map { ["value": String($0 + 3)] }
                    ]],
                    "rowCount": 1,
                    "propertyQuota": ["tokensPerDay": ["remaining": 99]]
                ])
            }
            if path == "/v1beta/properties/1234" {
                return (200, [
                    "name": "properties/1234", "displayName": "Main property",
                    "timeZone": "UTC", "currencyCode": "USD", "industryCategory": "TECHNOLOGY"
                ])
            }
            if path == "/v1beta/properties/1234/dataStreams" {
                let components = URLComponents(
                    url: try XCTUnwrap(request.url),
                    resolvingAgainstBaseURL: false
                )
                let pageToken = components?.queryItems?.first { $0.name == "pageToken" }?.value
                if pageToken == nil {
                    XCTAssertEqual(
                        components?.queryItems?.first { $0.name == "pageSize" }?.value,
                        "200"
                    )
                    return (200, [
                        "dataStreams": [[
                            "name": "properties/1234/dataStreams/1", "type": "WEB_DATA_STREAM",
                            "displayName": "Website", "webStreamData": ["measurementId": "G-ONE"]
                        ]],
                        "nextPageToken": "stream-2"
                    ])
                }
                XCTAssertEqual(pageToken, "stream-2")
                return (200, ["dataStreams": [[
                    "name": "properties/1234/dataStreams/2", "type": "IOS_APP_DATA_STREAM",
                    "displayName": "iOS", "iosAppStreamData": ["bundleId": "com.example.app"]
                ]]])
            }
            if path == "/v1beta/properties/1234/metadata" {
                return (200, [
                    "dimensions": [["apiName": "country", "uiName": "Country", "category": "Geo"]],
                    "metrics": [["apiName": "activeUsers", "uiName": "Active users", "category": "User"]]
                ])
            }
            if path.hasSuffix("/dataRetentionSettings") {
                return (200, ["eventDataRetention": "FIFTY_MONTHS", "resetUserDataOnNewActivity": true])
            }
            if path.hasSuffix("/googleSignalsSettings") {
                return (200, ["state": "GOOGLE_SIGNALS_ENABLED", "consent": "GOOGLE_SIGNALS_CONSENT_CONSENTED"])
            }
            if path.hasSuffix("/attributionSettings") {
                return (200, ["acquisitionConversionEventLookbackWindow": "ACQUISITION_CONVERSION_EVENT_LOOKBACK_WINDOW_30_DAYS"])
            }
            if path.hasSuffix("/reportingIdentitySettings") {
                return (200, ["reportingIdentity": "BLENDED"])
            }
            if path.hasSuffix("/userProvidedDataSettings") {
                return (200, [
                    "userProvidedDataCollectionEnabled": true,
                    "automaticallyDetectedDataCollectionEnabled": false
                ])
            }
            return (404, [:])
        }
        let client = SiteIntegrationDetailClient(session: makeSession())
        let range = SiteIntegrationDetailRange(
            start: Date(timeIntervalSince1970: 1_768_435_200),
            end: Date(timeIntervalSince1970: 1_771_027_200)
        )
        let partialRecorder = SiteIntegrationPartialPayloadRecorder()
        let payload = try await client.fetch(.googleAnalytics(
            propertyID: "1234", accessToken: "oauth-token", range: range
        ), onPartial: { partial in
            await partialRecorder.append(partial)
        })

        XCTAssertEqual(payload.provider, .googleAnalytics)
        XCTAssertEqual(payload.title, "Main property")
        XCTAssertNotNil(payload.rawResponses["overview"])
        XCTAssertNotNil(payload.rawResponses["technology"])
        XCTAssertEqual(payload.rawResponses["acquisition"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.rawResponses["dataStreams"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.series.first { $0.id == "ga4.timeline" }?.points.first?.x, "2026-07-15")
        XCTAssertTrue(Set([
            "ga4.acquisition", "ga4.pages", "ga4.events", "ga4.geography", "ga4.technology"
        ]).isSubset(of: Set(payload.tables.map(\.id))))
        XCTAssertEqual(payload.tables.first { $0.id == "ga4.acquisition" }?.rows.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "ga4.technology" }?.rows.count, 4_100)
        XCTAssertEqual(payload.tables.first { $0.id == "ga4.dataStreams" }?.rows.count, 2)
        let breakdownIDs = Set([
            "ga4.acquisition", "ga4.pages", "ga4.events", "ga4.geography", "ga4.technology"
        ])
        XCTAssertLessThanOrEqual(
            payload.tables
                .filter { breakdownIDs.contains($0.id) }
                .reduce(0) { $0 + $1.rows.count },
            19_998
        )
        XCTAssertTrue(payload.warnings.contains { $0.contains("on-device memory limit") })
        XCTAssertTrue(payload.sections.contains { $0.id == "ga4.realtime.overview" })
        XCTAssertTrue(payload.sections.contains { $0.id == "ga4.dataRetentionSettings" })
        XCTAssertTrue(payload.sections.contains { $0.id == "ga4.reportingIdentitySettings" })
        XCTAssertTrue(payload.sections.contains { $0.id == "ga4.userProvidedDataSettings" })
        let partials = await partialRecorder.payloads
        XCTAssertEqual(partials.count, 1)
        let partial = try XCTUnwrap(partials.first)
        XCTAssertTrue(partial.sections.contains { $0.id == "ga4.overview" })
        XCTAssertTrue(partial.series.contains { $0.id == "ga4.timeline" })
        XCTAssertTrue(partial.tables.isEmpty)
        XCTAssertEqual(Set(partial.rawResponses.keys), Set(["overview", "timeline"]))
        let technologyRequests = try SiteIntegrationDetailMockURLProtocol.requests.compactMap {
            request -> [String: Any]? in
            guard request.url?.path.hasSuffix(":runReport") == true else { return nil }
            let body = try decodedJSONBody(of: request)
            let dimensions = (body["dimensions"] as? [[String: String]] ?? [])
                .compactMap { $0["name"] }
            return dimensions == ["deviceCategory", "browser", "operatingSystem"]
                ? body
                : nil
        }
        XCTAssertEqual(
            technologyRequests.compactMap { Int($0["limit"] as? String ?? "") },
            [3_999, 4_100]
        )
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 20)
    }

    func testPlausiblePaginatesEveryReportedResultAndPreservesPages() async throws {
        var requestIndex = 0
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v2/query")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer plausible-key")
            let json = try decodedJSONBody(of: request)
            let include = try XCTUnwrap(json["include"] as? [String: Any])
            XCTAssertEqual(include["total_rows"] as? Bool, true)
            if (json["dimensions"] as? [String]) == ["event:page"] {
                XCTAssertEqual(
                    json["metrics"] as? [String],
                    ["visitors", "pageviews", "time_on_page"]
                )
            }
            requestIndex += 1
            if requestIndex == 3 {
                return (200, [
                    "results": [["dimensions": ["Google"], "metrics": [12, 10, 30]]],
                    "meta": ["total_rows": 2]
                ])
            }
            if requestIndex == 4 {
                return (200, [
                    "results": [["dimensions": ["Direct"], "metrics": [8, 7, 20]]],
                    "meta": ["total_rows": 2]
                ])
            }
            return (200, [
                "results": [["dimensions": [], "metrics": [1, 2, 3, 4, 5, 6, 7]]],
                "meta": ["total_rows": 1]
            ])
        }
        let payload = try await SiteIntegrationDetailClient(session: makeSession()).fetch(.plausible(
            siteID: "example.com",
            apiKey: "plausible-key",
            range: .last30Days()
        ))

        XCTAssertEqual(payload.rawResponses["sources"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "plausible.sources" }?.rows.count, 2)
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 8)
    }

    func testAggregatePayloadBudgetBoundsNormalizedRowsAndRawResponseBytes() async throws {
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            let json = try decodedJSONBody(of: request)
            let pagination = try XCTUnwrap(json["pagination"] as? [String: Any])
            let limit = try XCTUnwrap(pagination["limit"] as? Int)
            let dimensions = json["dimensions"] as? [String] ?? []
            let metrics = json["metrics"] as? [String] ?? []
            let results: [[String: Any]] = (0..<limit).map { index in
                [
                    "dimensions": dimensions.map { "\($0)-\(index)" },
                    "metrics": metrics.indices.map { index + $0 + 1 }
                ]
            }
            return (200, ["results": results, "meta": ["total_rows": 1_000_000]])
        }

        let payload = try await SiteIntegrationDetailClient(session: makeSession()).fetch(.plausible(
            siteID: "high-volume.example",
            apiKey: "plausible-key",
            range: .last30Days()
        ))

        let normalizedRows = payload.tables.reduce(0) { $0 + $1.rows.count }
            + payload.series.reduce(0) { $0 + $1.points.count }
        XCTAssertLessThanOrEqual(normalizedRows, 20_000)
        XCTAssertLessThanOrEqual(try JSONEncoder().encode(payload.rawResponses).count, 1_048_576)
        XCTAssertTrue(payload.warnings.contains { $0.contains("on-device memory limit") })
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 7)
    }

    func testUptimeRobotIncludesHistoryAndReadOnlyCompanionSurfaces() async throws {
        var alertContactRequestCount = 0
        var maintenanceWindowRequestCount = 0
        var statusPageRequestCount = 0
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            let method = request.url?.lastPathComponent ?? ""
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/x-www-form-urlencoded")
            switch method {
            case "getMonitors":
                let form = try decodedFormBody(of: request)
                for field in [
                    "custom_http_headers", "custom_http_statuses", "http_request_details",
                    "auth_type", "timezone"
                ] {
                    XCTAssertNotNil(form[field], "Expected getMonitors to request \(field)")
                }
                return (200, [
                    "stat": "ok",
                    "monitors": [[
                        "id": 42, "friendly_name": "Homepage", "status": 2,
                        "custom_http_headers": ["Authorization": "Bearer monitor-secret"],
                        "post_value": "client_secret=monitor-secret",
                        "timezone": "UTC",
                        "logs": [["datetime": 1_768_435_200, "type": 2]],
                        "response_times": [
                            ["datetime": 1_768_435_200, "value": 120],
                            ["datetime": 1_768_521_600, "value": 95]
                        ]
                    ]]
                ])
            case "getAccountDetails": return (200, ["stat": "ok", "account": ["email": "owner@example.com"]])
            case "getAlertContacts":
                alertContactRequestCount += 1
                return (200, [
                    "stat": "ok",
                    "offset": alertContactRequestCount - 1, "limit": 1, "total": 2,
                    "alert_contacts": [["id": alertContactRequestCount, "type": 2]]
                ])
            case "getMWindows":
                maintenanceWindowRequestCount += 1
                return (200, [
                    "stat": "ok", "offset": maintenanceWindowRequestCount - 1,
                    "limit": 1, "total": 2,
                    "mwindows": [["id": maintenanceWindowRequestCount, "type": 1]]
                ])
            case "getPSPs":
                statusPageRequestCount += 1
                return (200, [
                    "stat": "ok", "offset": statusPageRequestCount - 1,
                    "limit": 1, "total": 2,
                    "psps": [["id": statusPageRequestCount, "friendly_name": "Status"]]
                ])
            default: return (404, [:])
            }
        }
        let client = SiteIntegrationDetailClient(session: makeSession())
        let payload = try await client.fetch(.uptimeRobot(
            monitorID: "42", readOnlyAPIKey: "read-only",
            range: SiteIntegrationDetailRange.last30Days(endingAt: Date(timeIntervalSince1970: 1_771_027_200))
        ))

        XCTAssertEqual(payload.provider, .uptimeRobot)
        XCTAssertEqual(payload.series.first?.points.count, 2)
        XCTAssertNotNil(payload.rawResponses["getAccountDetails"])
        XCTAssertNotNil(payload.rawResponses["getAlertContacts"])
        XCTAssertNotNil(payload.rawResponses["getMWindows"])
        XCTAssertNotNil(payload.rawResponses["getPSPs"])
        XCTAssertEqual(payload.rawResponses["getAlertContacts"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "uptimerobot.get-alert-contacts" }?.rows.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "uptimerobot.get-maintenance-windows" }?.rows.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "uptimerobot.get-public-status-pages" }?.rows.count, 2)
        XCTAssertEqual(
            payload.rawResponses["monitor"]?["monitors"]?.arrayValue?.first?["custom_http_headers"],
            .string("[REDACTED]")
        )
        XCTAssertEqual(
            payload.rawResponses["monitor"]?["monitors"]?.arrayValue?.first?["post_value"],
            .string("[REDACTED]")
        )
        XCTAssertEqual(
            payload.rawResponses["monitor"]?["monitors"]?.arrayValue?.first?["timezone"]?.stringValue,
            "UTC"
        )
        XCTAssertTrue(payload.warnings.contains { $0.contains("seven days") })
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 8)
    }

    func testUmamiPaginatesEveryMetricTypeAndPreservesRawPages() async throws {
        let documentedTypes = Set([
            "path", "entry", "exit", "title", "query", "referrer", "channel", "domain",
            "country", "region", "city", "browser", "os", "device", "language", "screen",
            "event", "hostname", "tag", "distinctId"
        ])
        var requestedTypes: Set<String> = []
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer umami-token")
            let path = request.url?.path ?? ""
            let query = URLComponents(
                url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false
            )?.queryItems ?? []
            if path.hasSuffix("/stats") {
                return (200, ["pageviews": 10, "visitors": 4, "visits": 6])
            }
            if path.hasSuffix("/pageviews") {
                return (200, [
                    "pageviews": [["x": "2026-07-15", "y": 10]],
                    "sessions": [["x": "2026-07-15", "y": 6]]
                ])
            }
            if path.hasSuffix("/metrics") {
                let type = try XCTUnwrap(query.first { $0.name == "type" }?.value)
                requestedTypes.insert(type)
                XCTAssertEqual(query.first { $0.name == "limit" }?.value, "500")
                let offset = Int(query.first { $0.name == "offset" }?.value ?? "")
                if type == "path", offset == 0 {
                    return (200, (0..<500).map { ["x": "/page-\($0)", "y": $0 + 1] })
                }
                if type == "path", offset == 500 {
                    return (200, [["x": "/last", "y": 1]])
                }
                return (200, [["x": type, "y": 1]])
            }
            if path.hasSuffix("/active") { return (200, ["visitors": 2]) }
            if path.hasSuffix("/events/series") {
                return (200, [["x": "signup", "t": "2026-07-15T12:00:00Z", "y": 2]])
            }
            return (404, [:])
        }

        let payload = try await SiteIntegrationDetailClient(session: makeSession()).fetch(.umami(
            websiteID: "site-id",
            baseURL: try XCTUnwrap(URL(string: "https://analytics.example.com/api/")),
            authentication: .bearerToken("umami-token"),
            range: .last30Days()
        ))

        XCTAssertEqual(requestedTypes, documentedTypes)
        XCTAssertEqual(payload.rawResponses["metrics.paths"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "umami.paths" }?.rows.count, 501)
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 25)
    }

    func testBetterStackPreservesFieldsRedactsSecretsAndBuildsP0Surfaces() async throws {
        var incidentRequestCount = 0
        SiteIntegrationDetailMockURLProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer better-token")
            let path = request.url?.path ?? ""
            if path == "/api/v2/monitors/7" {
                return (200, ["data": [
                    "id": "7", "type": "monitor",
                    "attributes": [
                        "pronounceable_name": "API", "status": "up",
                        "proxy_host": "proxy-user:proxy-password@proxy.example.com:8080",
                        "request_headers": [["name": "Authorization", "value": "secret"]],
                        "check_frequency": 30
                    ]
                ]])
            }
            if path.hasSuffix("/response-times") {
                return (200, ["data": [
                    "id": "7-response-times", "type": "monitor_response_times",
                    "attributes": ["regions": [[
                        "region": "us",
                        "response_times": [[
                            "at": "2026-07-15T12:00:00Z",
                            "response_time": 123, "name_lookup_time": 12
                        ]]
                    ]]]
                ]])
            }
            if path.hasSuffix("/sla") {
                return (200, ["data": ["attributes": ["uptime": 99.99, "downtime": 12]]])
            }
            if path == "/api/v3/incidents" {
                incidentRequestCount += 1
                if incidentRequestCount == 1 {
                    return (200, [
                        "data": [["id": "incident-1", "attributes": ["status": "resolved", "cause": "timeout"]]],
                        "links": ["next": "https://uptime.betterstack.com/api/v3/incidents?page=2"]
                    ])
                }
                XCTAssertEqual(
                    URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?
                        .queryItems?.first { $0.name == "page" }?.value,
                    "2"
                )
                return (200, [
                    "data": [["id": "incident-2", "attributes": ["status": "resolved", "cause": "dns"]]],
                    "links": ["next": NSNull()]
                ])
            }
            return (404, [:])
        }
        let client = SiteIntegrationDetailClient(session: makeSession())
        let payload = try await client.fetch(.betterStack(
            monitorID: "7", token: "better-token", range: .last30Days()
        ))

        XCTAssertEqual(payload.provider, .betterStack)
        XCTAssertEqual(
            payload.rawResponses["monitor"]?["data"]?["attributes"]?["request_headers"],
            .string("[REDACTED]")
        )
        XCTAssertEqual(
            payload.rawResponses["monitor"]?["data"]?["attributes"]?["proxy_host"]?.stringValue,
            "proxy.example.com:8080"
        )
        XCTAssertEqual(
            payload.tables.first { $0.id == "betterstack.response-times" }?
                .rows.first?["attributes.region"]?.stringValue,
            "us"
        )
        XCTAssertEqual(payload.series.first?.points.first?.values["us.response_time"], 123)
        XCTAssertTrue(payload.sections.contains { $0.id == "betterstack.sla" })
        XCTAssertEqual(payload.rawResponses["incidents"]?.arrayValue?.count, 2)
        XCTAssertEqual(payload.tables.first { $0.id == "betterstack.incidents" }?.rows.count, 2)
        XCTAssertEqual(SiteIntegrationDetailMockURLProtocol.requests.count, 5)
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SiteIntegrationDetailMockURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private func decodedJSONBody(of request: URLRequest) throws -> [String: Any] {
    let data: Data
    if let body = request.httpBody {
        data = body
    } else {
        let stream = try XCTUnwrap(
            request.httpBodyStream,
            "Expected the request to carry a JSON body or body stream."
        )
        stream.open()
        defer { stream.close() }
        var collected = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 {
                throw stream.streamError ?? URLError(.cannotDecodeRawData)
            }
            if count == 0 { break }
            collected.append(contentsOf: buffer.prefix(count))
        }
        data = collected
    }
    return try XCTUnwrap(
        JSONSerialization.jsonObject(with: data) as? [String: Any],
        "Expected a JSON object request body."
    )
}

private func decodedFormBody(of request: URLRequest) throws -> [String: String] {
    let data: Data
    if let body = request.httpBody {
        data = body
    } else {
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var collected = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 {
                throw stream.streamError ?? URLError(.cannotDecodeRawData)
            }
            if count == 0 { break }
            collected.append(contentsOf: buffer.prefix(count))
        }
        data = collected
    }
    let body = try XCTUnwrap(String(data: data, encoding: .utf8))
    var components = URLComponents()
    components.query = body
    return Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map {
        ($0.name, $0.value ?? "")
    })
}

private actor SiteIntegrationPartialPayloadRecorder {
    private(set) var payloads: [SiteIntegrationDetailPayload] = []

    func append(_ payload: SiteIntegrationDetailPayload) {
        payloads.append(payload)
    }
}

private final class SiteIntegrationDetailMockURLProtocol: URLProtocol {
    private static let stateLock = NSLock()
    nonisolated(unsafe) private static var storedHandler: ((URLRequest) throws -> (Int, Any))?
    nonisolated(unsafe) private static var storedRequests: [URLRequest] = []

    static var handler: ((URLRequest) throws -> (Int, Any))? {
        get { stateLock.withLock { storedHandler } }
        set { stateLock.withLock { storedHandler = newValue } }
    }

    static var requests: [URLRequest] {
        get { stateLock.withLock { storedRequests } }
        set { stateLock.withLock { storedRequests = newValue } }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            Self.stateLock.withLock { Self.storedRequests.append(request) }
            let handler = try XCTUnwrap(Self.handler)
            let (status, object) = try handler(request)
            let response = try XCTUnwrap(HTTPURLResponse(
                url: try XCTUnwrap(request.url), statusCode: status,
                httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]
            ))
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONSerialization.data(withJSONObject: object))
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
