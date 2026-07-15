import XCTest
@testable import verceltics

final class ProviderAPIRequestEncodingTests: XCTestCase {
    func testPathParameterEncodesSeparatorsAndUnicode() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.pathParameter("folder/a b/✓", allowReserved: false),
            "folder%2Fa%20b%2F%E2%9C%93"
        )
    }

    func testReservedPathExpansionKeepsPathSeparatorsButBlocksQueryAndFragment() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.pathParameter("folder/a b?token=x#part", allowReserved: true),
            "folder/a%20b%3Ftoken=x%23part"
        )
    }

    func testAWSQueryEncodingUsesOnlyRFC3986UnreservedCharacters() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.awsQueryComponent("a+b /?=&~"),
            "a%2Bb%20%2F%3F%3D%26~"
        )
    }

    func testDigitalOceanFirstClassPathsReceiveExactlyOneVersionPrefix() {
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/account"),
            "/v2/account"
        )
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/v2/apps?per_page=200"),
            "/v2/apps?per_page=200"
        )
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/v2?example=true"),
            "/v2?example=true"
        )
    }

    func testOtherHostingProviderPathsRemainUnchanged() {
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .netlify, path: "/sites"),
            "/sites"
        )
    }

    func testAmplifyBranchAndJobPageSizeStaysWithinAWSLimit() {
        XCTAssertEqual(HostingProviderAPI.awsAmplifyBranchAndJobPageSize, 50)
    }

    func testAmplifyEndpointUsesExactAWSHostForValidRegion() throws {
        let endpoint = try HostingProviderAPI.awsAmplifyEndpoint(
            region: "ap-southeast-2",
            path: "/apps?maxResults=1"
        )

        XCTAssertEqual(endpoint.host, "amplify.ap-southeast-2.amazonaws.com")
        XCTAssertEqual(endpoint.url.scheme, "https")
        XCTAssertEqual(endpoint.url.host, endpoint.host)
        XCTAssertNil(endpoint.url.user)
        XCTAssertNil(endpoint.url.password)
        XCTAssertNil(endpoint.url.port)
        XCTAssertEqual(endpoint.url.path, "/apps")
        XCTAssertEqual(endpoint.url.query, "maxResults=1")
        XCTAssertEqual(
            endpoint.url.absoluteString,
            "https://amplify.ap-southeast-2.amazonaws.com/apps?maxResults=1"
        )
    }

    func testAmplifyEndpointRejectsRegionsThatCouldChangeAuthority() {
        for region in [
            "evil.example/",
            "us-east-1.evil.example",
            "us-east-1@evil.example",
            "us-east-1.amazonaws.com@evil.example/",
            "us_east_1",
            "US-EAST-1",
            "us-east-0",
            "us-east-01",
            "us-gov-west-1",
            "cn-north-1",
            "zz-east-1",
            "us-east-1\n",
        ] {
            XCTAssertThrowsError(
                try HostingProviderAPI.awsAmplifyEndpoint(region: region, path: "/apps"),
                region
            )
        }
    }

    func testRailwayPaginationRequiresAndDeduplicatesContinuationCursors() throws {
        var pagination = RailwayPaginationGuard()
        XCTAssertEqual(
            try pagination.continuation(hasNextPage: true, endCursor: "next-page"),
            "next-page"
        )
        XCTAssertThrowsError(
            try pagination.continuation(hasNextPage: true, endCursor: "next-page")
        )

        var missingCursor = RailwayPaginationGuard()
        XCTAssertThrowsError(
            try missingCursor.continuation(hasNextPage: true, endCursor: nil)
        )
        XCTAssertNil(try missingCursor.continuation(hasNextPage: false, endCursor: nil))
    }

    func testRailwayPaginationEnforcesMaximumPages() throws {
        var pagination = RailwayPaginationGuard(maximumPages: 1)
        XCTAssertEqual(try pagination.continuation(hasNextPage: true, endCursor: "one"), "one")
        XCTAssertThrowsError(
            try pagination.continuation(hasNextPage: false, endCursor: nil)
        )
    }

    func testHerokuStatusUsesLiveDynoStates() {
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: true, dynoStates: ["up"]), "Maintenance")
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: false, dynoStates: nil), "Unknown")
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: false, dynoStates: []), "Stopped")
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: false, dynoStates: ["up", "up"]), "Running")
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: false, dynoStates: ["up", "crashed"]), "Degraded")
        XCTAssertEqual(HostingProviderAPI.herokuAppStatus(maintenance: false, dynoStates: ["crashed"]), "Crashed")
    }

    func testFlyStatusUsesLiveMachineStates() {
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: nil), "Unknown")
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: []), "Stopped")
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: ["started", "started"]), "Running")
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: ["started", "failed"]), "Degraded")
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: ["failed"]), "Failed")
        XCTAssertEqual(HostingProviderAPI.flyAppStatus(machineStates: ["suspended"]), "Suspended")
    }

    func testFirebaseOAuthUsesHostingWriteScope() {
        XCTAssertEqual(
            GoogleOAuthService.firebaseHostingScopes,
            [
                "openid",
                "email",
                "https://www.googleapis.com/auth/firebase.hosting",
            ]
        )

        let configuration = GoogleOAuthClientConfiguration.current
        XCTAssertNotNil(configuration)
        XCTAssertTrue(configuration?.clientID.hasSuffix(".apps.googleusercontent.com") == true)
        XCTAssertEqual(
            configuration?.redirectURI,
            "\(configuration?.redirectScheme ?? ""):/oauthredirect"
        )
    }

    func testExpiredFirebaseCredentialRetainsOfflineRefreshCapabilityInKeychainValue() throws {
        let expired = GoogleOAuthCredential(
            accessToken: "expired-access-token",
            refreshToken: "durable-refresh-token",
            tokenType: "Bearer",
            scopes: GoogleOAuthService.firebaseHostingScopes,
            expiresAt: .distantPast,
            subject: "google-subject",
            email: "owner@example.com"
        )

        let restored = try GoogleOAuthCredential.fromKeychainValue(expired.keychainValue())
        XCTAssertTrue(restored.needsRefresh)
        XCTAssertEqual(restored.refreshToken, "durable-refresh-token")
        XCTAssertFalse(restored.accessToken.isEmpty)
    }

    func testRailwayTemplatesExpandRequiredInputsAndSelectUsefulFields() throws {
        let nonNull: ([String: Any]) -> [String: Any] = { nested in
            ["kind": "NON_NULL", "name": NSNull(), "ofType": nested]
        }
        let scalar: (String) -> [String: Any] = { name in
            ["kind": "SCALAR", "name": name, "ofType": NSNull()]
        }
        let named: (String, String) -> [String: Any] = { kind, name in
            ["kind": kind, "name": name, "ofType": NSNull()]
        }
        let argument: (String, [String: Any], Any) -> [String: Any] = { name, type, defaultValue in
            ["name": name, "type": type, "defaultValue": defaultValue]
        }
        let field: (String, [String: Any], [[String: Any]]) -> [String: Any] = { name, type, arguments in
            [
                "name": name,
                "description": "",
                "isDeprecated": false,
                "deprecationReason": NSNull(),
                "type": type,
                "args": arguments,
            ]
        }

        let schema: [String: Any] = [
            "queryType": ["name": "Query"],
            "mutationType": ["name": "Mutation"],
            "types": [
                [
                    "kind": "OBJECT", "name": "Query",
                    "fields": [field(
                        "project",
                        named("OBJECT", "Project"),
                        [
                            argument("id", nonNull(scalar("ID")), NSNull()),
                            argument("includeServices", scalar("Boolean"), NSNull()),
                        ]
                    )],
                ],
                [
                    "kind": "OBJECT", "name": "Mutation",
                    "fields": [field(
                        "deploy",
                        named("OBJECT", "Deployment"),
                        [
                            argument("input", nonNull(named("INPUT_OBJECT", "DeployInput")), NSNull()),
                            argument("dryRun", scalar("Boolean"), NSNull()),
                        ]
                    )],
                ],
                [
                    "kind": "INPUT_OBJECT", "name": "DeployInput",
                    "inputFields": [
                        argument("environment", nonNull(named("ENUM", "Environment")), NSNull()),
                        argument("config", nonNull(named("INPUT_OBJECT", "DeployConfigInput")), NSNull()),
                        argument("note", scalar("String"), NSNull()),
                    ],
                ],
                [
                    "kind": "INPUT_OBJECT", "name": "DeployConfigInput",
                    "inputFields": [
                        argument("replicas", nonNull(scalar("Int")), NSNull()),
                        argument("region", scalar("String"), NSNull()),
                    ],
                ],
                ["kind": "ENUM", "name": "Environment", "enumValues": [["name": "PRODUCTION"], ["name": "STAGING"]]],
                [
                    "kind": "OBJECT", "name": "Project",
                    "fields": [
                        field("id", scalar("ID"), []),
                        field("name", scalar("String"), []),
                        field("updatedAt", scalar("DateTime"), []),
                    ],
                ],
                [
                    "kind": "OBJECT", "name": "Deployment",
                    "fields": [
                        field("id", scalar("ID"), []),
                        field("status", scalar("String"), []),
                        field("createdAt", scalar("DateTime"), []),
                    ],
                ],
                ["kind": "SCALAR", "name": "ID"],
                ["kind": "SCALAR", "name": "String"],
                ["kind": "SCALAR", "name": "Boolean"],
                ["kind": "SCALAR", "name": "Int"],
                ["kind": "SCALAR", "name": "DateTime"],
            ],
        ]
        let operations = try RailwayGraphQLTemplateBuilder.operations(
            schemaData: JSONSerialization.data(withJSONObject: schema)
        )

        let deploy = try XCTUnwrap(operations.first(where: { $0.summary == "deploy" }))
        let deployBody = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(deploy.bodyTemplate.utf8)) as? [String: Any]
        )
        let deployQuery = try XCTUnwrap(deployBody["query"] as? String)
        let deployVariables = try XCTUnwrap(deployBody["variables"] as? [String: Any])
        let input = try XCTUnwrap(deployVariables["input"] as? [String: Any])
        let config = try XCTUnwrap(input["config"] as? [String: Any])

        XCTAssertEqual(input["environment"] as? String, "PRODUCTION")
        XCTAssertEqual(config["replicas"] as? Int, 1)
        XCTAssertNil(input["note"])
        XCTAssertNil(deployVariables["dryRun"])
        XCTAssertTrue(deployQuery.contains("deploy(input: $input)"))
        XCTAssertTrue(deployQuery.contains("id"))
        XCTAssertTrue(deployQuery.contains("status"))
        XCTAssertFalse(deployQuery.contains("{ __typename }"))

        let project = try XCTUnwrap(operations.first(where: { $0.summary == "project" }))
        XCTAssertTrue(project.bodyTemplate.contains("REPLACE_ME"))
        XCTAssertTrue(project.description.contains("includeServices: Boolean"))
    }

    func testFaviconHostSafetyRejectsPrivateAndReservedAddresses() {
        for address in [
            "127.0.0.1", "10.1.2.3", "100.64.0.1", "169.254.1.1", "172.31.1.1",
            "192.168.1.1", "198.18.0.1", "203.0.113.1", "::1", "fc00::1", "fe80::1",
            "::ffff:192.168.1.1", "64:ff9b::c0a8:0101",
        ] {
            XCTAssertFalse(FaviconHostSafety.isPublicIPAddress(address), address)
        }
    }

    func testFaviconHostSafetyAcceptsPublicAddresses() {
        XCTAssertTrue(FaviconHostSafety.isPublicIPAddress("1.1.1.1"))
        XCTAssertTrue(FaviconHostSafety.isPublicIPAddress("2606:4700:4700::1111"))
    }
}
