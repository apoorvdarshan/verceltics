import Foundation

nonisolated struct CloudflareAPIOperationPreset: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let summary: String
    let method: CloudflareHTTPMethod
    let path: String
    let query: String
    let headers: String
    let body: String
    let contentType: String
    let bodyEncoding: CloudflareRequestBodyEncoding
    let multipartFields: [CloudflareOpenAPIMultipartField]
    let readOnlyGraphQL: Bool
    let requiresAPIToken: Bool

    init(
        id: String,
        title: String,
        summary: String,
        method: CloudflareHTTPMethod,
        path: String,
        query: String = "",
        headers: String = "",
        body: String = "",
        contentType: String = "application/json",
        bodyEncoding: CloudflareRequestBodyEncoding = .utf8,
        multipartFields: [CloudflareOpenAPIMultipartField] = [],
        readOnlyGraphQL: Bool = false,
        requiresAPIToken: Bool = false
    ) {
        self.id = id
        self.title = title
        self.summary = summary
        self.method = method
        self.path = path
        self.query = query
        self.headers = headers
        self.body = body
        self.contentType = contentType
        self.bodyEncoding = bodyEncoding
        self.multipartFields = multipartFields
        self.readOnlyGraphQL = readOnlyGraphQL
        self.requiresAPIToken = requiresAPIToken
    }

    func resolved(accountID: String, zoneID: String?) -> Self {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        let now = Date()
        let analyticsTo = formatter.string(from: now)
        let fromDate = Calendar(identifier: .gregorian).date(byAdding: .day, value: -7, to: now) ?? now
        let analyticsFrom = formatter.string(
            from: fromDate
        )
        let dayFormatter = DateFormatter()
        dayFormatter.calendar = Calendar(identifier: .gregorian)
        dayFormatter.locale = Locale(identifier: "en_US_POSIX")
        dayFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        dayFormatter.dateFormat = "yyyy-MM-dd"
        func resolve(_ value: String) -> String {
            value
                .replacingOccurrences(of: "{account_id}", with: accountID)
                .replacingOccurrences(of: "{zone_id}", with: zoneID ?? "ZONE_ID")
                .replacingOccurrences(of: "{analytics_from}", with: analyticsFrom)
                .replacingOccurrences(of: "{analytics_to}", with: analyticsTo)
                .replacingOccurrences(of: "{analytics_from_date}", with: dayFormatter.string(from: fromDate))
                .replacingOccurrences(of: "{analytics_to_date}", with: dayFormatter.string(from: now))
        }
        return .init(
            id: id,
            title: title,
            summary: summary,
            method: method,
            path: resolve(path),
            query: resolve(query),
            headers: resolve(headers),
            body: resolve(body),
            contentType: contentType,
            bodyEncoding: bodyEncoding,
            multipartFields: multipartFields,
            readOnlyGraphQL: readOnlyGraphQL,
            requiresAPIToken: requiresAPIToken
        )
    }
}

nonisolated struct CloudflareProductDefinition: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let summary: String
    let icon: String
    let operations: [CloudflareAPIOperationPreset]
}

nonisolated enum CloudflareProductCatalog {
    static let products: [CloudflareProductDefinition] = [
        product("analytics", "Analytics & GraphQL", "Traffic, security, Workers and every enabled GraphQL dataset", "chart.xyaxis.line", [
            post("analytics-zone-http", "Zone traffic dataset", "Query daily requests, visitors, bandwidth, cache and threats. Edit dates for any allowed window.", "/graphql", """
            {
              "query": "query ZoneTraffic($zoneTag: string, $start: Date, $end: Date) { viewer { zones(filter: {zoneTag: $zoneTag}) { httpRequests1dGroups(limit: 100, orderBy: [date_ASC], filter: {date_geq: $start, date_leq: $end}) { dimensions { date } sum { requests pageViews bytes cachedRequests cachedBytes threats encryptedRequests } uniq { uniques } } } } }",
              "variables": {"zoneTag": "{zone_id}", "start": "{analytics_from_date}", "end": "{analytics_to_date}"}
            }
            """),
            post("analytics-security", "Security events dataset", "Query firewall/security events with action, source, client and rule dimensions.", "/graphql", """
            {
              "query": "query SecurityEvents($zoneTag: string, $start: Time, $end: Time) { viewer { zones(filter: {zoneTag: $zoneTag}) { firewallEventsAdaptive(limit: 100, orderBy: [datetime_DESC], filter: {datetime_geq: $start, datetime_leq: $end}) { action clientCountryName clientIP clientRequestHTTPHost clientRequestPath datetime source userAgent } } } }",
              "variables": {"zoneTag": "{zone_id}", "start": "{analytics_from}", "end": "{analytics_to}"}
            }
            """),
            post("analytics-workers", "Workers invocations", "Query Worker requests, errors, CPU time and duration grouped by script.", "/graphql", """
            {
              "query": "query WorkerMetrics($accountTag: string, $start: Time, $end: Time) { viewer { accounts(filter: {accountTag: $accountTag}) { workersInvocationsAdaptive(limit: 100, filter: {datetime_geq: $start, datetime_leq: $end}) { dimensions { scriptName } sum { requests errors subrequests } quantiles { cpuTimeP50 cpuTimeP99 durationP50 durationP99 } } } } }",
              "variables": {"accountTag": "{account_id}", "start": "{analytics_from}", "end": "{analytics_to}"}
            }
            """),
            post("analytics-schema-zone", "Discover zone datasets", "Use GraphQL introspection to list the zone datasets available in Cloudflare’s current schema.", "/graphql", """
            {
              "query": "query DiscoverZoneDatasets { __type(name: \\"Zone\\") { fields { name description } } }"
            }
            """),
            post("analytics-schema-account", "Discover account datasets", "Use GraphQL introspection to list account-level Workers, Pages, security and product datasets.", "/graphql", """
            {
              "query": "query DiscoverAccountDatasets { __type(name: \\"Account\\") { fields { name description } } }"
            }
            """),
            post("analytics-custom", "Custom GraphQL query", "Run any enabled dataset with its plan-specific limits and inspect the complete raw response.", "/graphql", """
            {
              "query": "query { viewer { zones(filter: {zoneTag: \\"{zone_id}\\"}) { settings { httpRequests1dGroups { enabled maxDuration maxPageSize notOlderThan } } } } }"
            }
            """)
        ]),
        product("accounts", "Accounts & members", "Account settings, members, subscriptions and tokens", "building.2.fill", [
            get("account-details", "Account details", "Read the complete account object.", "/accounts/{account_id}"),
            patch("account-update", "Update account", "Edit account name or settings.", "/accounts/{account_id}", "{\n  \"name\": \"Account name\"\n}"),
            get("members-list", "List members", "Read members and invitations.", "/accounts/{account_id}/members", query: "page=1\nper_page=50"),
            post("member-add", "Add member", "Invite a member with roles or IAM policies.", "/accounts/{account_id}/members", "{\n  \"email\": \"person@example.com\",\n  \"roles\": [\"ROLE_ID\"],\n  \"status\": \"pending\"\n}"),
            put("member-update", "Update member", "Replace a member’s roles or policies.", "/accounts/{account_id}/members/MEMBER_ID", "{\n  \"roles\": [\"ROLE_ID\"]\n}"),
            delete("member-remove", "Remove member", "Remove a member from this account.", "/accounts/{account_id}/members/MEMBER_ID"),
            get("subscriptions", "Subscriptions", "Read active account subscriptions.", "/accounts/{account_id}/subscriptions"),
            get("account-tokens", "Account tokens", "List account-owned API tokens.", "/accounts/{account_id}/tokens", token: true),
            post("account-token-create", "Create account token", "Create a scoped account-owned API token.", "/accounts/{account_id}/tokens", "{\n  \"name\": \"Verceltics token\",\n  \"policies\": []\n}", token: true),
            put("account-token-update", "Update account token", "Replace an account token’s name or policies.", "/accounts/{account_id}/tokens/TOKEN_ID", "{\n  \"name\": \"Updated token\",\n  \"policies\": []\n}", token: true),
            delete("account-token-delete", "Delete account token", "Revoke an account-owned API token.", "/accounts/{account_id}/tokens/TOKEN_ID", token: true)
        ]),
        product("zones", "Zones", "Create, edit, pause, activate and remove zones", "globe.americas.fill", [
            get("zones-list", "List zones", "Read all zones in this account.", "/zones", query: "account.id={account_id}\npage=1\nper_page=50"),
            post("zone-create", "Create zone", "Onboard a domain to Cloudflare.", "/zones", "{\n  \"account\": {\"id\": \"{account_id}\"},\n  \"name\": \"example.com\",\n  \"type\": \"full\"\n}"),
            patch("zone-update", "Update zone", "Pause or change the zone type.", "/zones/{zone_id}", "{\n  \"paused\": false\n}"),
            delete("zone-delete", "Delete zone", "Permanently remove this zone.", "/zones/{zone_id}"),
            get("zone-plan", "Zone subscription", "Read the zone plan subscription.", "/zones/{zone_id}/subscription")
        ]),
        product("dns", "DNS operations", "Batch records, import/export, scan and secondary DNS", "server.rack", [
            post("dns-batch", "Batch DNS changes", "Create, patch, put and delete records atomically by group.", "/zones/{zone_id}/dns_records/batch", "{\n  \"deletes\": [],\n  \"patches\": [],\n  \"puts\": [],\n  \"posts\": []\n}"),
            get("dns-export", "Export zone file", "Export all records as a BIND zone file.", "/zones/{zone_id}/dns_records/export"),
            post("dns-import", "Import zone file", "Upload a multipart BIND zone file in the request editor.", "/zones/{zone_id}/dns_records/import", "", contentType: "multipart/form-data; boundary=BOUNDARY"),
            post("dns-scan", "Start DNS scan", "Ask Cloudflare to discover existing records.", "/zones/{zone_id}/dns_records/scan/trigger", "{}"),
            get("dns-scan-review", "Review scan", "List records discovered by the DNS scan.", "/zones/{zone_id}/dns_records/scan/review"),
            post("dns-scan-accept", "Accept scanned records", "Accept or reject discovered DNS records.", "/zones/{zone_id}/dns_records/scan/review", "{\n  \"accepts\": [\"RECORD_ID\"],\n  \"rejects\": []\n}"),
            get("secondary-zones", "Secondary zones", "List incoming secondary zones.", "/accounts/{account_id}/secondary_dns/zones"),
            post("secondary-zone-create", "Create secondary zone", "Configure an incoming secondary zone.", "/accounts/{account_id}/secondary_dns/zones", "{\n  \"name\": \"example.com\",\n  \"auto_refresh_seconds\": 3600\n}"),
            post("secondary-transfer", "Force zone transfer", "Trigger an AXFR/IXFR transfer for a secondary zone.", "/zones/{zone_id}/secondary_dns/force_axfr", "{}"),
            get("secondary-masters", "Secondary masters", "List configured secondary DNS masters.", "/accounts/{account_id}/secondary_dns/masters"),
            post("secondary-master-create", "Create secondary master", "Add a primary server used by secondary DNS.", "/accounts/{account_id}/secondary_dns/masters", "{\n  \"ip\": \"192.0.2.1\",\n  \"port\": 53\n}")
        ]),
        product("security", "Security & rules", "WAF, rulesets, rate limits, API Shield, bots and certificates", "lock.shield.fill", [
            get("rulesets", "List rulesets", "Read all zone rulesets.", "/zones/{zone_id}/rulesets"),
            post("ruleset-create", "Create ruleset", "Create a custom ruleset for a phase.", "/zones/{zone_id}/rulesets", "{\n  \"name\": \"Custom rules\",\n  \"description\": \"Managed in Verceltics\",\n  \"kind\": \"zone\",\n  \"phase\": \"http_request_firewall_custom\",\n  \"rules\": []\n}"),
            put("ruleset-update", "Update ruleset", "Replace ruleset metadata and rules.", "/zones/{zone_id}/rulesets/RULESET_ID", "{\n  \"description\": \"Updated rules\",\n  \"rules\": []\n}"),
            delete("ruleset-delete", "Delete ruleset", "Delete a custom ruleset.", "/zones/{zone_id}/rulesets/RULESET_ID"),
            get("rate-limits", "Rate limits", "List legacy rate-limit rules.", "/zones/{zone_id}/rate_limits"),
            post("rate-limit-create", "Create rate limit", "Create a rate-limit rule.", "/zones/{zone_id}/rate_limits", "{\n  \"threshold\": 100,\n  \"period\": 60,\n  \"action\": {\"mode\": \"simulate\"},\n  \"match\": {\"request\": {\"methods\": [\"GET\"], \"schemes\": [\"HTTP\", \"HTTPS\"], \"url\": \"*\"}}\n}"),
            put("rate-limit-update", "Update rate limit", "Replace a legacy rate-limit rule.", "/zones/{zone_id}/rate_limits/RATE_LIMIT_ID", "{\n  \"threshold\": 100,\n  \"period\": 60,\n  \"action\": {\"mode\": \"simulate\"}\n}"),
            delete("rate-limit-delete", "Delete rate limit", "Remove a legacy rate-limit rule.", "/zones/{zone_id}/rate_limits/RATE_LIMIT_ID"),
            get("api-shield-schemas", "API Shield schemas", "List uploaded OpenAPI schemas.", "/zones/{zone_id}/api_gateway/user_schemas"),
            post("api-shield-schema-upload", "Upload API schema", "Upload an OpenAPI schema as multipart form data.", "/zones/{zone_id}/api_gateway/user_schemas", "", contentType: "multipart/form-data; boundary=BOUNDARY"),
            get("api-shield-operations", "API discovery", "List discovered API operations.", "/zones/{zone_id}/api_gateway/discovery/operations"),
            get("api-shield-validation", "Schema validation", "Read API Shield schema-validation settings.", "/zones/{zone_id}/api_gateway/settings/schema_validation"),
            put("api-shield-validation-update", "Update schema validation", "Set the default validation action.", "/zones/{zone_id}/api_gateway/settings/schema_validation", "{\n  \"validation_default_mitigation_action\": \"log\"\n}"),
            get("page-shield", "Page Shield policies", "List client-side security policies.", "/zones/{zone_id}/page_shield/policies"),
            post("page-shield-create", "Create Page Shield policy", "Create a script or connection policy.", "/zones/{zone_id}/page_shield/policies", "{\n  \"action\": \"log\",\n  \"description\": \"New policy\",\n  \"enabled\": true,\n  \"expression\": \"true\",\n  \"value\": \"\"\n}"),
            get("bot-settings", "Bot configuration", "Read Bot Management settings.", "/zones/{zone_id}/bot_management"),
            put("bot-update", "Update bot configuration", "Update supported Bot Management controls.", "/zones/{zone_id}/bot_management", "{\n  \"fight_mode\": true\n}"),
            get("certificate-packs", "Certificate packs", "List edge certificate packs.", "/zones/{zone_id}/ssl/certificate_packs", query: "status=all"),
            post("certificate-order", "Order certificate pack", "Order a new advanced certificate pack.", "/zones/{zone_id}/ssl/certificate_packs/order", "{\n  \"type\": \"advanced\",\n  \"hosts\": [\"example.com\", \"*.example.com\"],\n  \"validation_method\": \"txt\",\n  \"validity_days\": 90,\n  \"certificate_authority\": \"lets_encrypt\",\n  \"cloudflare_branding\": false\n}"),
            delete("certificate-delete", "Delete certificate pack", "Remove an advanced certificate pack.", "/zones/{zone_id}/ssl/certificate_packs/CERTIFICATE_PACK_ID")
        ]),
        product("pages", "Pages", "Projects, configuration, domains and direct deployments", "doc.badge.gearshape.fill", [
            post("pages-project-create", "Create project", "Create a Direct Upload or Git-backed Pages project.", "/accounts/{account_id}/pages/projects", "{\n  \"name\": \"new-project\",\n  \"production_branch\": \"main\"\n}"),
            patch("pages-project-update", "Update project", "Edit builds, branches, variables and bindings.", "/accounts/{account_id}/pages/projects/PROJECT_NAME", "{\n  \"production_branch\": \"main\"\n}"),
            post("pages-deploy", "Create deployment", "Send a branch build or multipart direct-upload manifest.", "/accounts/{account_id}/pages/projects/PROJECT_NAME/deployments", "{\n  \"branch\": \"main\"\n}"),
            get("pages-deployments", "List deployments", "Read production and preview deployments.", "/accounts/{account_id}/pages/projects/PROJECT_NAME/deployments", query: "page=1\nper_page=20"),
            delete("pages-project-delete", "Delete project", "Permanently delete a Pages project.", "/accounts/{account_id}/pages/projects/PROJECT_NAME")
        ]),
        product("workers", "Workers", "Scripts, versions, bindings, routes, assets, logs and settings", "shippingbox.fill", [
            put("worker-upload", "Upload Worker", "Create or replace a Worker script with multipart metadata and modules.", "/accounts/{account_id}/workers/scripts/SCRIPT_NAME", "", contentType: "multipart/form-data; boundary=BOUNDARY"),
            post("worker-version", "Upload version", "Create a new deployable Worker version.", "/accounts/{account_id}/workers/scripts/SCRIPT_NAME/versions", "", contentType: "multipart/form-data; boundary=BOUNDARY"),
            get("worker-routes", "List routes", "Read Worker routes for the selected zone.", "/zones/{zone_id}/workers/routes"),
            post("worker-route-create", "Create route", "Attach a route pattern to a Worker.", "/zones/{zone_id}/workers/routes", "{\n  \"pattern\": \"example.com/*\",\n  \"script\": \"SCRIPT_NAME\"\n}"),
            get("worker-account-settings", "Account settings", "Read default usage and green-compute settings.", "/accounts/{account_id}/workers/account-settings"),
            put("worker-account-settings-update", "Update account settings", "Update Worker account defaults.", "/accounts/{account_id}/workers/account-settings", "{\n  \"green_compute\": false\n}"),
            post("worker-assets", "Create asset upload session", "Start a static asset upload session for a script.", "/accounts/{account_id}/workers/scripts/SCRIPT_NAME/assets-upload-session", "{\n  \"manifest\": {}\n}", token: true),
            get("worker-logs", "Tail and logs", "Read the script tail endpoints, then switch to POST to create a live tail.", "/accounts/{account_id}/workers/scripts/SCRIPT_NAME/tails"),
            delete("worker-delete", "Delete Worker", "Delete a Worker script and its settings.", "/accounts/{account_id}/workers/scripts/SCRIPT_NAME")
        ]),
        product("d1-kv", "D1 & KV", "Database lifecycle, import/export, time travel and KV bulk data", "cylinder.split.1x2.fill", [
            patch("d1-update", "Update D1 database", "Rename or configure read replication.", "/accounts/{account_id}/d1/database/DATABASE_ID", "{\n  \"name\": \"database-name\",\n  \"read_replication\": {\"mode\": \"auto\"}\n}"),
            post("d1-raw", "Raw D1 query", "Run SQL and return raw row arrays.", "/accounts/{account_id}/d1/database/DATABASE_ID/raw", "{\n  \"sql\": \"SELECT * FROM sqlite_master\"\n}"),
            post("d1-export", "Export D1", "Start or poll a SQL export.", "/accounts/{account_id}/d1/database/DATABASE_ID/export", "{}"),
            post("d1-import", "Import D1", "Start, upload or complete a SQL import.", "/accounts/{account_id}/d1/database/DATABASE_ID/import", "{\n  \"action\": \"init\",\n  \"etag\": \"\"\n}"),
            get("d1-bookmark", "Time-travel bookmark", "Get a bookmark for a timestamp.", "/accounts/{account_id}/d1/database/DATABASE_ID/time_travel/bookmark", query: "timestamp=2026-01-01T00:00:00Z"),
            post("d1-restore", "Restore D1", "Restore to a bookmark or timestamp.", "/accounts/{account_id}/d1/database/DATABASE_ID/time_travel/restore", "{\n  \"bookmark\": \"BOOKMARK\"\n}"),
            get("kv-namespace", "KV namespace details", "Read a namespace including URL-encoding support.", "/accounts/{account_id}/storage/kv/namespaces/NAMESPACE_ID"),
            put("kv-bulk-write", "Bulk write KV", "Write keys with metadata and expiration.", "/accounts/{account_id}/storage/kv/namespaces/NAMESPACE_ID/bulk", "[\n  {\"key\": \"example\", \"value\": \"value\"}\n]"),
            post("kv-bulk-read", "Bulk read KV", "Read multiple values and metadata.", "/accounts/{account_id}/storage/kv/namespaces/NAMESPACE_ID/bulk/get", "{\n  \"keys\": [\"example\"],\n  \"withMetadata\": true\n}"),
            post("kv-bulk-delete", "Bulk delete KV", "Delete multiple keys.", "/accounts/{account_id}/storage/kv/namespaces/NAMESPACE_ID/bulk/delete", "[\"example\"]")
        ]),
        product("r2", "R2", "Buckets, objects, domains, CORS and lifecycle policies", "shippingbox.circle.fill", [
            get("r2-buckets", "List buckets", "Read all R2 buckets.", "/accounts/{account_id}/r2/buckets", query: "per_page=100", token: true),
            get("r2-objects", "List objects", "Read object metadata from a bucket.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/objects", query: "per_page=100", token: true),
            get("r2-cors", "Get CORS policy", "Read bucket browser-access rules.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/cors", token: true),
            put("r2-cors-update", "Update CORS", "Replace bucket CORS rules.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/cors", "{\n  \"rules\": []\n}", token: true),
            get("r2-domains", "Custom domains", "List custom domains attached to a bucket.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/domains/custom", token: true),
            get("r2-lifecycle", "Lifecycle policy", "Read automatic object-transition and expiration rules.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/lifecycle", token: true),
            put("r2-lifecycle-update", "Update lifecycle", "Replace lifecycle rules.", "/accounts/{account_id}/r2/buckets/BUCKET_NAME/lifecycle", "{\n  \"rules\": []\n}", token: true)
        ]),
        simpleAccountProduct("turnstile", "Turnstile", "Bot-resistant challenge widgets", "checkmark.shield.fill", "/accounts/{account_id}/challenges/widgets"),
        simpleAccountProduct("images", "Images", "Image storage, variants and delivery", "photo.stack.fill", "/accounts/{account_id}/images/v1", token: true),
        simpleAccountProduct("stream", "Stream", "Video upload, playback and live inputs", "play.rectangle.fill", "/accounts/{account_id}/stream", token: true),
        simpleZoneProduct("email-routing", "Email Routing", "Rules, addresses and routing settings", "envelope.fill", "/zones/{zone_id}/email/routing/rules"),
        simpleZoneProduct("load-balancing", "Load Balancing", "Load balancers, pools and health monitors", "point.3.connected.trianglepath.dotted", "/zones/{zone_id}/load_balancers"),
        simpleAccountProduct("queues", "Queues", "Queues, consumers and message delivery", "tray.2.fill", "/accounts/{account_id}/queues"),
        simpleAccountProduct("hyperdrive", "Hyperdrive", "Database acceleration configurations", "bolt.horizontal.circle.fill", "/accounts/{account_id}/hyperdrive/configs"),
        simpleAccountProduct("vectorize", "Vectorize", "Vector indexes and metadata", "square.stack.3d.up.fill", "/accounts/{account_id}/vectorize/v2/indexes"),
        simpleAccountProduct("workers-ai", "Workers AI", "Models, inference and usage", "cpu.fill", "/accounts/{account_id}/ai/models/search"),
        simpleAccountProduct("ai-gateway", "AI Gateway", "Gateways, providers and observability", "arrow.triangle.branch", "/accounts/{account_id}/ai-gateway/gateways"),
        simpleAccountProduct("tunnels", "Tunnels & Zero Trust", "Cloudflare Tunnels and Access applications", "network.badge.shield.half.filled", "/accounts/{account_id}/cfd_tunnel", token: true),
        simpleAccountProduct("logpush", "Logpush", "Account log delivery jobs", "arrow.up.doc.fill", "/accounts/{account_id}/logpush/jobs"),
        simpleZoneProduct("zaraz", "Zaraz", "Third-party tools and consent configuration", "wand.and.stars", "/zones/{zone_id}/settings/zaraz/config"),
        simpleZoneProduct("waiting-rooms", "Waiting Rooms", "Traffic queues and events", "person.3.sequence.fill", "/zones/{zone_id}/waiting_rooms")
    ]

    private static func product(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ icon: String,
        _ operations: [CloudflareAPIOperationPreset]
    ) -> CloudflareProductDefinition {
        .init(id: id, title: title, summary: summary, icon: icon, operations: operations)
    }

    private static func simpleAccountProduct(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ icon: String,
        _ path: String,
        token: Bool = false
    ) -> CloudflareProductDefinition {
        product(id, title, summary, icon, [
            get("\(id)-list", "List resources", "Read resources available to this account.", path, token: token),
            post("\(id)-create", "Create resource", "Create a resource using the product API.", path, "{\n  \"name\": \"new-resource\"\n}", token: token)
        ])
    }

    private static func simpleZoneProduct(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ icon: String,
        _ path: String
    ) -> CloudflareProductDefinition {
        product(id, title, summary, icon, [
            get("\(id)-list", "Read configuration", "Read resources for the selected zone.", path),
            post("\(id)-create", "Create resource", "Create a resource using the product API.", path, "{\n  \"name\": \"new-resource\"\n}")
        ])
    }

    private static func get(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ path: String,
        query: String = "",
        token: Bool = false
    ) -> CloudflareAPIOperationPreset {
        .init(id: id, title: title, summary: summary, method: .get, path: path, query: query, requiresAPIToken: token)
    }

    private static func post(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ path: String,
        _ body: String,
        contentType: String = "application/json",
        token: Bool = false
    ) -> CloudflareAPIOperationPreset {
        .init(
            id: id,
            title: title,
            summary: summary,
            method: .post,
            path: path,
            body: body,
            contentType: contentType,
            readOnlyGraphQL: path == "/graphql",
            requiresAPIToken: token
        )
    }

    private static func put(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ path: String,
        _ body: String,
        contentType: String = "application/json",
        token: Bool = false
    ) -> CloudflareAPIOperationPreset {
        .init(id: id, title: title, summary: summary, method: .put, path: path, body: body, contentType: contentType, requiresAPIToken: token)
    }

    private static func patch(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ path: String,
        _ body: String
    ) -> CloudflareAPIOperationPreset {
        .init(id: id, title: title, summary: summary, method: .patch, path: path, body: body)
    }

    private static func delete(
        _ id: String,
        _ title: String,
        _ summary: String,
        _ path: String,
        token: Bool = false
    ) -> CloudflareAPIOperationPreset {
        .init(id: id, title: title, summary: summary, method: .delete, path: path, requiresAPIToken: token)
    }
}
