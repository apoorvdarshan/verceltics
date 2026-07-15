import CryptoKit
import Foundation

struct RailwayPaginationGuard {
    private let maximumPages: Int
    private var pageCount = 0
    private var seenCursors = Set<String>()

    init(maximumPages: Int = 200) {
        self.maximumPages = maximumPages
    }

    mutating func continuation(hasNextPage: Bool, endCursor: String?) throws -> String? {
        pageCount += 1
        guard pageCount <= maximumPages else {
            throw HostingProviderAPIError.decoding("Railway pagination exceeded \(maximumPages) pages.")
        }
        guard hasNextPage else { return nil }
        guard let endCursor, !endCursor.isEmpty else {
            throw HostingProviderAPIError.decoding("Railway pagination indicated another page without a cursor.")
        }
        guard seenCursors.insert(endCursor).inserted else {
            throw HostingProviderAPIError.decoding("Railway pagination repeated a cursor.")
        }
        return endCursor
    }
}

struct HostingProviderAPI {
    static let awsAmplifyBranchAndJobPageSize = 50

    private static let awsStandardRegionPattern =
        #"^(?:af|ap|ca|eu|il|me|mx|sa|us)-[a-z]+-[1-9][0-9]*$"#

    let provider: AccountProvider
    let credential: String
    let metadata: [String: String]

    init(provider: AccountProvider, credential: String, metadata: [String: String] = [:]) {
        self.provider = provider
        self.credential = credential
        self.metadata = metadata
    }

    init(account: VercelAccount) {
        self.init(provider: account.provider, credential: account.token, metadata: account.providerMetadata)
    }

    func validateProfile() async throws -> HostingProviderProfile {
        switch provider {
        case .netlify:
            let user = object(try await requestJSON(path: "/user"))
            return HostingProviderProfile(
                id: string(user, "id", "uid", "email") ?? credentialFingerprint,
                name: string(user, "full_name", "name", "email") ?? "Netlify Account",
                email: string(user, "email"),
                avatarURL: string(user, "avatar_url")
            )

        case .railway:
            if metadata["railwayTokenType"] == "project" {
                let response = try await graphql(query: "query { projectToken { projectId environmentId } }")
                let token = object(object(response["data"])["projectToken"])
                let projectID = string(token, "projectId") ?? credentialFingerprint
                return HostingProviderProfile(
                    id: projectID,
                    name: "Railway Project",
                    email: nil,
                    avatarURL: nil
                )
            }
            let response = try await graphql(query: "query { me { id name email avatar } }")
            let me = object(object(response["data"])["me"])
            return HostingProviderProfile(
                id: string(me, "id", "email") ?? credentialFingerprint,
                name: string(me, "name", "email") ?? "Railway Account",
                email: string(me, "email"),
                avatarURL: string(me, "avatar")
            )

        case .render:
            let values = array(try await requestJSON(path: "/owners?limit=100"))
            let owner = object(object(values.first)["owner"] ?? values.first)
            guard !owner.isEmpty else { throw HostingProviderAPIError.decoding("No Render workspace was returned.") }
            return HostingProviderProfile(
                id: string(owner, "id", "email") ?? credentialFingerprint,
                name: string(owner, "name", "email") ?? "Render Workspace",
                email: string(owner, "email"),
                avatarURL: nil
            )

        case .digitalOcean:
            let root = object(try await requestJSON(path: "/account"))
            let account = object(root["account"])
            return HostingProviderProfile(
                id: string(account, "uuid", "email") ?? credentialFingerprint,
                name: string(account, "name", "email") ?? "DigitalOcean Account",
                email: string(account, "email"),
                avatarURL: nil
            )

        case .heroku:
            let account = object(try await requestJSON(path: "/account"))
            return HostingProviderProfile(
                id: string(account, "id", "email") ?? credentialFingerprint,
                name: string(account, "name", "email") ?? "Heroku Account",
                email: string(account, "email"),
                avatarURL: nil
            )

        case .fly:
            let organization = try requiredMetadata("organization", label: "Fly.io organization slug")
            _ = try await requestJSON(path: "/apps?org_slug=\(queryComponent(organization))")
            return HostingProviderProfile(
                id: organization,
                name: organization == "personal" ? "Fly.io Personal" : organization,
                email: nil,
                avatarURL: nil
            )

        case .firebase:
            let projectID = try requiredMetadata("projectID", label: "Firebase project ID")
            _ = try await requestJSON(path: "/projects/\(pathComponent(projectID))/sites?pageSize=1")
            return HostingProviderProfile(id: projectID, name: projectID, email: nil, avatarURL: nil)

        case .awsAmplify:
            let accessKeyID = try requiredMetadata("accessKeyID", label: "AWS access key ID")
            let region = try requiredMetadata("region", label: "AWS region")
            _ = try await requestJSON(path: "/apps?maxResults=1")
            return HostingProviderProfile(
                id: "\(accessKeyID.suffix(4))-\(region)",
                name: "AWS \(region)",
                email: nil,
                avatarURL: nil
            )

        case .vercel, .cloudflare:
            throw HostingProviderAPIError.invalidConfiguration("This provider uses its dedicated API client.")
        }
    }

    func fetchResources() async throws -> [HostingResource] {
        switch provider {
        case .netlify:
            let sites = try await collectNumberedPages(pageSize: 100) { page in
                array(try await requestJSON(path: "/sites?per_page=100&page=\(page)"))
            }
            return sites.compactMap { value in
                let site = object(value)
                guard let id = stableIdentifier(
                    string(site, "id"),
                    namespace: "netlify-site",
                    values: [string(site, "name", "custom_domain"), string(site, "ssl_url", "url")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(site, "name", "custom_domain") ?? "Untitled site",
                    subtitle: string(site, "custom_domain", "url"),
                    url: string(site, "ssl_url", "url"),
                    status: string(site, "state") ?? (bool(site, "published_deploy") == true ? "Published" : nil),
                    region: nil,
                    kind: "Site",
                    updatedAt: date(site["updated_at"]),
                    metadata: ["adminURL": string(site, "admin_url") ?? ""]
                )
            }

        case .railway:
            if metadata["railwayTokenType"] == "project" {
                let tokenResponse = try await graphql(query: "query { projectToken { projectId environmentId } }")
                let token = object(object(tokenResponse["data"])["projectToken"])
                guard let projectID = string(token, "projectId") else {
                    throw HostingProviderAPIError.decoding("Railway did not return the project attached to this token.")
                }
                let response = try await graphql(
                    query: "query project($id: String!) { project(id: $id) { id name description createdAt updatedAt } }",
                    variables: ["id": projectID]
                )
                let project = object(object(response["data"])["project"])
                return [HostingResource(
                    id: projectID,
                    name: string(project, "name") ?? "Railway Project",
                    subtitle: string(project, "description"),
                    url: nil,
                    status: "Project",
                    region: nil,
                    kind: "Project",
                    updatedAt: date(project["updatedAt"]),
                    metadata: ["environmentID": string(token, "environmentId") ?? ""]
                )]
            }
            let projects = try await collectRailwayConnection { cursor in
                var variables: [String: Any] = ["first": 100]
                if let cursor { variables["after"] = cursor }
                let response = try await graphql(
                    query: """
                        query projects($first: Int, $after: String) {
                          projects(first: $first, after: $after) {
                            edges { node { id name description createdAt updatedAt } }
                            pageInfo { hasNextPage endCursor }
                          }
                        }
                        """,
                    variables: variables
                )
                return object(object(response["data"])["projects"])
            }
            return projects.compactMap { project in
                guard let id = stableIdentifier(
                    string(project, "id"),
                    namespace: "railway-project",
                    values: [string(project, "name"), string(project, "createdAt")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(project, "name") ?? "Untitled project",
                    subtitle: string(project, "description"),
                    url: nil,
                    status: "Project",
                    region: nil,
                    kind: "Project",
                    updatedAt: date(project["updatedAt"]),
                    metadata: [:]
                )
            }

        case .render:
            let values = try await fetchRenderCursorPages(path: "/services?limit=100&includePreviews=true")
            return values.compactMap { value in
                let service = object(object(value)["service"] ?? value)
                let details = object(service["serviceDetails"])
                guard let id = stableIdentifier(
                    string(service, "id"),
                    namespace: "render-service",
                    values: [string(service, "name"), string(service, "url", "repo", "imagePath")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(service, "name") ?? "Untitled service",
                    subtitle: string(service, "repo", "imagePath"),
                    url: string(service, "url"),
                    status: bool(service, "suspended") == true ? "Suspended" : "Active",
                    region: string(service, "region") ?? string(details, "region"),
                    kind: string(service, "type")?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Service",
                    updatedAt: date(service["updatedAt"]),
                    metadata: [:]
                )
            }

        case .digitalOcean:
            let apps = try await collectNumberedPages(pageSize: 200) { page in
                let root = object(try await requestJSON(path: "/apps?per_page=200&page=\(page)"))
                return array(root["apps"])
            }
            return apps.compactMap { value in
                let app = object(value)
                let active = object(app["active_deployment"])
                let region = object(app["region"])
                guard let id = stableIdentifier(
                    string(app, "id"),
                    namespace: "digitalocean-app",
                    values: [string(object(app["spec"]), "name"), string(app, "live_url", "default_ingress")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(app, "spec.name", "name") ?? string(object(app["spec"]), "name") ?? "Untitled app",
                    subtitle: string(app, "default_ingress", "live_url"),
                    url: string(app, "live_url", "default_ingress"),
                    status: string(active, "phase") ?? string(app, "phase"),
                    region: string(region, "slug", "label"),
                    kind: "App",
                    updatedAt: date(app["updated_at"]),
                    metadata: [:]
                )
            }

        case .heroku:
            return try await fetchHerokuRangePages(path: "/apps").compactMap { value in
                let app = object(value)
                guard let id = stableIdentifier(
                    string(app, "id", "name"),
                    namespace: "heroku-app",
                    values: [string(app, "name"), string(app, "web_url")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(app, "name") ?? "Untitled app",
                    subtitle: string(object(app["stack"]), "name"),
                    url: string(app, "web_url"),
                    status: bool(app, "maintenance") == true ? "Maintenance" : "Running",
                    region: string(object(app["region"]), "name"),
                    kind: "App",
                    updatedAt: date(app["updated_at"]),
                    metadata: [:]
                )
            }

        case .fly:
            let organization = try requiredMetadata("organization", label: "Fly.io organization slug")
            let root = object(try await requestJSON(path: "/apps?org_slug=\(queryComponent(organization))"))
            return array(root["apps"]).map { value in
                let app = object(value)
                let name = string(app, "name") ?? "untitled-app"
                return HostingResource(
                    id: string(app, "id", "name") ?? name,
                    name: name,
                    subtitle: "\(integer(app["machine_count"]) ?? 0) Machines · \(integer(app["volume_count"]) ?? 0) volumes",
                    url: "https://\(name).fly.dev",
                    status: "Active",
                    region: nil,
                    kind: "App",
                    updatedAt: nil,
                    metadata: ["appName": name]
                )
            }

        case .firebase:
            let projectID = try requiredMetadata("projectID", label: "Firebase project ID")
            let sites = try await fetchTokenPages(
                initialPath: "/projects/\(pathComponent(projectID))/sites?pageSize=100",
                itemKey: "sites",
                tokenQueryName: "pageToken"
            )
            return sites.compactMap { value in
                let site = object(value)
                guard let fullName = string(site, "name") else { return nil }
                let siteID = fullName.split(separator: "/").last.map(String.init) ?? fullName
                return HostingResource(
                    id: siteID,
                    name: siteID,
                    subtitle: string(site, "appId", "type"),
                    url: string(site, "defaultUrl"),
                    status: "Hosting",
                    region: nil,
                    kind: string(site, "type")?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Site",
                    updatedAt: nil,
                    metadata: ["fullName": fullName]
                )
            }

        case .awsAmplify:
            let apps = try await fetchTokenPages(
                initialPath: "/apps?maxResults=100",
                itemKey: "apps",
                tokenQueryName: "nextToken"
            )
            return apps.compactMap { value in
                let app = object(value)
                let production = object(app["productionBranch"])
                guard let id = stableIdentifier(
                    string(app, "appId"),
                    namespace: "amplify-app",
                    values: [string(app, "name"), string(app, "repository", "defaultDomain")]
                ) else { return nil }
                return HostingResource(
                    id: id,
                    name: string(app, "name") ?? "Untitled app",
                    subtitle: string(app, "repository", "description"),
                    url: string(app, "defaultDomain").map { "https://\($0)" },
                    status: string(production, "status") ?? "Configured",
                    region: metadata["region"],
                    kind: string(app, "platform") ?? "Amplify app",
                    updatedAt: date(app["updateTime"]),
                    metadata: ["branch": string(production, "branchName") ?? ""]
                )
            }

        case .vercel, .cloudflare:
            return []
        }
    }

    func fetchDeployments(for resource: HostingResource) async throws -> [HostingDeployment] {
        switch provider {
        case .netlify:
            let values = try await collectNumberedPages(pageSize: 100) { page in
                array(try await requestJSON(
                    path: "/sites/\(pathComponent(resource.id))/deploys?per_page=100&page=\(page)"
                ))
            }
            return deduplicatedDeployments(values.map {
                deployment(from: object($0), provider: .netlify)
            })

        case .railway:
            return try await fetchRailwayDeployments(projectID: resource.id)

        case .render:
            let values = try await fetchRenderCursorPages(
                path: "/services/\(pathComponent(resource.id))/deploys?limit=100"
            )
            return deduplicatedDeployments(values.map {
                deployment(from: object(object($0)["deploy"] ?? $0), provider: .render)
            })

        case .digitalOcean:
            let values = try await collectNumberedPages(pageSize: 200) { page in
                let root = object(try await requestJSON(
                    path: "/apps/\(pathComponent(resource.id))/deployments?per_page=200&page=\(page)"
                ))
                return array(root["deployments"])
            }
            return deduplicatedDeployments(values.map { deployment(from: object($0), provider: .digitalOcean) })

        case .heroku:
            return deduplicatedDeployments(try await fetchHerokuRangePages(
                path: "/apps/\(pathComponent(resource.id))/releases"
            ).map {
                deployment(from: object($0), provider: .heroku)
            })

        case .fly:
            let appName = resource.metadata["appName"] ?? resource.name
            return array(try await requestJSON(path: "/apps/\(pathComponent(appName))/machines?include_deleted=true")).map {
                deployment(from: object($0), provider: .fly)
            }

        case .firebase:
            let values = try await fetchTokenPages(
                initialPath: "/sites/\(pathComponent(resource.id))/releases?pageSize=100",
                itemKey: "releases",
                tokenQueryName: "pageToken"
            )
            return deduplicatedDeployments(values.map { deployment(from: object($0), provider: .firebase) })

        case .awsAmplify:
            let branches = try await fetchTokenPages(
                initialPath: "/apps/\(pathComponent(resource.id))/branches?maxResults=\(Self.awsAmplifyBranchAndJobPageSize)",
                itemKey: "branches",
                tokenQueryName: "nextToken"
            )
            var results: [HostingDeployment] = []
            for branchValue in branches {
                try Task.checkCancellation()
                let branch = object(branchValue)
                guard let branchName = string(branch, "branchName") else { continue }
                let jobs = try await fetchTokenPages(
                    initialPath: "/apps/\(pathComponent(resource.id))/branches/\(pathComponent(branchName))/jobs?maxResults=\(Self.awsAmplifyBranchAndJobPageSize)",
                    itemKey: "jobSummaries",
                    tokenQueryName: "nextToken"
                )
                results += jobs.map {
                    var value = object($0)
                    value["branchName"] = branchName
                    return deployment(from: value, provider: .awsAmplify)
                }
            }
            return deduplicatedDeployments(results).sorted {
                ($0.createdAt ?? .distantPast) > ($1.createdAt ?? .distantPast)
            }

        case .vercel, .cloudflare:
            return []
        }
    }

    func performPrimaryAction(for resource: HostingResource, latestDeployment: HostingDeployment?) async throws {
        switch provider {
        case .netlify:
            _ = try await rawRequest(method: "POST", path: "/sites/\(pathComponent(resource.id))/builds", body: "{}")
        case .railway:
            guard let id = latestDeployment?.id else {
                throw HostingProviderAPIError.invalidConfiguration("No Railway deployment is available to redeploy.")
            }
            _ = try await graphql(
                query: "mutation deploymentRedeploy($id: String!) { deploymentRedeploy(id: $id) { id status } }",
                variables: ["id": id]
            )
        case .render:
            _ = try await rawRequest(method: "POST", path: "/services/\(pathComponent(resource.id))/deploys", body: "{}")
        case .digitalOcean:
            _ = try await rawRequest(method: "POST", path: "/apps/\(pathComponent(resource.id))/deployments", body: "{}")
        case .heroku:
            _ = try await rawRequest(method: "DELETE", path: "/apps/\(pathComponent(resource.id))/dynos", body: nil)
        case .fly:
            let appName = resource.metadata["appName"] ?? resource.name
            for machineValue in array(try await requestJSON(path: "/apps/\(pathComponent(appName))/machines")) {
                let machine = object(machineValue)
                guard let machineID = string(machine, "id") else { continue }
                _ = try await rawRequest(
                    method: "POST",
                    path: "/apps/\(pathComponent(appName))/machines/\(pathComponent(machineID))/restart",
                    body: "{}"
                )
            }
        case .awsAmplify:
            var branch = resource.metadata["branch"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if branch.isEmpty {
                let root = object(try await requestJSON(path: "/apps/\(pathComponent(resource.id))/branches?maxResults=1"))
                branch = string(object(array(root["branches"]).first), "branchName") ?? ""
            }
            guard !branch.isEmpty else {
                throw HostingProviderAPIError.invalidConfiguration("This Amplify app has no branch to release.")
            }
            _ = try await rawRequest(
                method: "POST",
                path: "/apps/\(pathComponent(resource.id))/branches/\(pathComponent(branch))/jobs",
                body: "{\"jobType\":\"RELEASE\"}"
            )
        case .firebase, .vercel, .cloudflare:
            throw HostingProviderAPIError.invalidConfiguration("This provider has no safe one-tap action here. Use its API Explorer for an explicit request.")
        }
    }

    func dashboardURL(for resource: HostingResource? = nil) -> URL? {
        let value: String
        switch provider {
        case .netlify: value = resource.map { "https://app.netlify.com/sites/\($0.name)/overview" } ?? "https://app.netlify.com/"
        case .railway: value = resource.map { "https://railway.com/project/\($0.id)" } ?? "https://railway.com/dashboard"
        case .render: value = resource.map { "https://dashboard.render.com/\($0.id)" } ?? "https://dashboard.render.com/"
        case .digitalOcean: value = resource.map { "https://cloud.digitalocean.com/apps/\($0.id)" } ?? "https://cloud.digitalocean.com/apps"
        case .heroku: value = resource.map { "https://dashboard.heroku.com/apps/\($0.name)" } ?? "https://dashboard.heroku.com/apps"
        case .fly: value = resource.map { "https://fly.io/apps/\($0.name)" } ?? "https://fly.io/dashboard"
        case .firebase:
            let project = metadata["projectID"] ?? ""
            value = "https://console.firebase.google.com/project/\(project)/hosting"
        case .awsAmplify:
            let region = metadata["region"] ?? "us-east-1"
            value = resource.map { "https://\(region).console.aws.amazon.com/amplify/apps/\($0.id)" }
                ?? "https://\(region).console.aws.amazon.com/amplify/apps"
        case .vercel: value = "https://vercel.com/dashboard"
        case .cloudflare: value = "https://dash.cloudflare.com/"
        }
        return URL(string: value)
    }

    func rawRequest(
        method: String,
        path: String,
        body: String?,
        additionalHeaders: [String: String] = [:],
        contentType: String? = nil,
        bodyIsBase64: Bool = false,
        returnHTTPErrorResponse: Bool = false
    ) async throws -> HostingRawResponse {
        let normalizedMethod = method.uppercased()
        guard ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].contains(normalizedMethod) else {
            throw HostingProviderAPIError.invalidConfiguration("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.")
        }
        guard path.hasPrefix("/"), !path.hasPrefix("//") else {
            throw HostingProviderAPIError.invalidConfiguration("Enter a provider-relative path beginning with /.")
        }
        let requestPath = Self.normalizedRequestPath(for: provider, path: path)

        let bodyData: Data?
        if bodyIsBase64, let body {
            guard let decoded = Data(base64Encoded: body) else {
                throw ProviderRequestSecurityError.invalidBase64Body
            }
            bodyData = decoded
        } else {
            bodyData = body?.data(using: .utf8)
        }
        let validatedContentType = try ProviderRequestSecurity.validatedContentType(contentType)
        let validatedHeaders = try ProviderRequestSecurity.validatedHeaders(
            additionalHeaders,
            protectedHeaders: Self.protectedHeaders
        )
        var request: URLRequest

        if provider == .awsAmplify {
            request = try awsSignedRequest(
                method: normalizedMethod,
                path: requestPath,
                body: bodyData,
                contentType: validatedContentType ?? "application/json"
            )
        } else {
            let bearerCredential = try await resolvedBearerCredential()
            guard let baseURL, let url = URL(string: baseURL + requestPath) else {
                throw HostingProviderAPIError.invalidConfiguration("This provider has no API base URL.")
            }
            request = URLRequest(url: url)
            request.httpMethod = provider == .railway ? "POST" : normalizedMethod
            request.httpBody = bodyData
            request.setValue(validatedContentType ?? "application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            if provider == .railway, metadata["railwayTokenType"] == "project" {
                request.setValue(credential, forHTTPHeaderField: "Project-Access-Token")
            } else {
                request.setValue("Bearer \(bearerCredential)", forHTTPHeaderField: "Authorization")
            }
            if provider == .heroku {
                request.setValue("application/vnd.heroku+json; version=3", forHTTPHeaderField: "Accept")
                request.setValue("Verceltics/2.0", forHTTPHeaderField: "User-Agent")
            }
        }

        for (name, value) in validatedHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (data, response) = try await ProviderRequestSecurity.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw HostingProviderAPIError.invalidResponse }
        let responseBody = String(data: data, encoding: .utf8) ?? data.base64EncodedString()
        if !(200...299).contains(http.statusCode), !returnHTTPErrorResponse {
            throw HostingProviderAPIError.requestFailed(http.statusCode, errorMessage(from: data))
        }
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { result, pair in
            result[String(describing: pair.key)] = String(describing: pair.value)
        }
        return HostingRawResponse(statusCode: http.statusCode, headers: headers, body: responseBody)
    }

    private static let protectedHeaders = Set(["authorization", "project-access-token", "host", "content-length", "content-type", "x-amz-date", "x-amz-security-token"])

    // MARK: - Railway

    private func fetchRailwayDeployments(projectID: String) async throws -> [HostingDeployment] {
        let services = try await collectRailwayConnection { cursor in
            var variables: [String: Any] = ["id": projectID, "first": 100]
            if let cursor { variables["after"] = cursor }
            let response = try await graphql(
                query: """
                    query projectServices($id: String!, $first: Int, $after: String) {
                      project(id: $id) {
                        services(first: $first, after: $after) {
                          edges { node { id name } }
                          pageInfo { hasNextPage endCursor }
                        }
                      }
                    }
                    """,
                variables: variables
            )
            let project = object(object(response["data"])["project"])
            return object(project["services"])
        }
        let environments = try await collectRailwayConnection { cursor in
            var variables: [String: Any] = ["id": projectID, "first": 100]
            if let cursor { variables["after"] = cursor }
            let response = try await graphql(
                query: """
                    query projectEnvironments($id: String!, $first: Int, $after: String) {
                      project(id: $id) {
                        environments(first: $first, after: $after) {
                          edges { node { id name } }
                          pageInfo { hasNextPage endCursor }
                        }
                      }
                    }
                    """,
                variables: variables
            )
            let project = object(object(response["data"])["project"])
            return object(project["environments"])
        }
        var deployments: [HostingDeployment] = []

        for service in services {
            guard let serviceID = string(service, "id") else { continue }
            for environment in environments {
                guard let environmentID = string(environment, "id") else { continue }
                let nodes = try await collectRailwayConnection { cursor in
                    var variables: [String: Any] = [
                        "input": [
                            "projectId": projectID,
                            "serviceId": serviceID,
                            "environmentId": environmentID
                        ],
                        "first": 100
                    ]
                    if let cursor { variables["after"] = cursor }
                    let response = try await graphql(
                        query: """
                            query deployments($input: DeploymentListInput!, $first: Int, $after: String) {
                              deployments(input: $input, first: $first, after: $after) {
                                edges { node { id status createdAt url staticUrl meta canRedeploy canRollback } }
                                pageInfo { hasNextPage endCursor }
                              }
                            }
                            """,
                        variables: variables
                    )
                    return object(object(response["data"])["deployments"])
                }
                deployments += nodes.map { node in
                    HostingDeployment(
                        id: string(node, "id")
                            ?? fallbackIdentifier(namespace: "railway-deploy", value: node),
                        title: "\(string(service, "name") ?? "Service") · \(string(environment, "name") ?? "Environment")",
                        status: string(node, "status") ?? "UNKNOWN",
                        createdAt: date(node["createdAt"]),
                        url: string(node, "url", "staticUrl"),
                        branch: nil,
                        commitMessage: displayJSON(node["meta"]),
                        metadata: [
                            "serviceID": serviceID,
                            "environmentID": environmentID,
                            "canRedeploy": String(bool(node, "canRedeploy") ?? false),
                            "canRollback": String(bool(node, "canRollback") ?? false)
                        ]
                    )
                }
            }
        }
        var seenDeploymentIDs = Set<String>()
        return deployments
            .filter { seenDeploymentIDs.insert($0.id).inserted }
            .sorted { ($0.createdAt ?? .distantPast) > ($1.createdAt ?? .distantPast) }
    }

    private func collectRailwayConnection(
        maximumPages: Int = 200,
        fetchPage: (String?) async throws -> [String: Any]
    ) async throws -> [[String: Any]] {
        var cursor: String?
        var result: [[String: Any]] = []
        var seenItems = Set<String>()
        var guardrail = RailwayPaginationGuard(maximumPages: maximumPages)

        for _ in 1...maximumPages {
            try Task.checkCancellation()
            let connection = try await fetchPage(cursor)
            let nodes = edgeNodes(connection)
            for node in nodes {
                guard let fingerprint = jsonFingerprint(node) else {
                    result.append(node)
                    continue
                }
                if seenItems.insert(fingerprint).inserted { result.append(node) }
            }

            let pageInfo = object(connection["pageInfo"])
            cursor = try guardrail.continuation(
                hasNextPage: bool(pageInfo, "hasNextPage") == true,
                endCursor: string(pageInfo, "endCursor")
            )
            if cursor == nil { return result }
        }
        // The guardrail is authoritative; reaching this branch is defensive.
        throw HostingProviderAPIError.decoding("Railway pagination exceeded \(maximumPages) pages.")
    }

    private func graphql(query: String, variables: [String: Any] = [:]) async throws -> [String: Any] {
        let data = try JSONSerialization.data(withJSONObject: ["query": query, "variables": variables])
        guard let body = String(data: data, encoding: .utf8) else {
            throw HostingProviderAPIError.invalidConfiguration("Could not encode the GraphQL request.")
        }
        let raw = try await rawRequest(method: "POST", path: "/graphql/v2", body: body)
        let value = try parseJSON(raw.body)
        let root = object(value)
        if let firstError = array(root["errors"]).first {
            let message = string(object(firstError), "message") ?? "GraphQL request failed."
            throw HostingProviderAPIError.requestFailed(raw.statusCode, message)
        }
        return root
    }

    // MARK: - Shared request helpers

    /// First-class DigitalOcean calls use provider-relative paths while the
    /// bundled OpenAPI catalog already includes `/v2`. Normalize both forms to
    /// exactly one version prefix so raw catalog operations never become `/v2/v2`.
    static func normalizedRequestPath(for provider: AccountProvider, path: String) -> String {
        guard provider == .digitalOcean else { return path }
        if path == "/v2" || path.hasPrefix("/v2/") || path.hasPrefix("/v2?") { return path }
        return "/v2\(path)"
    }

    private var baseURL: String? {
        switch provider {
        case .netlify: "https://api.netlify.com/api/v1"
        case .railway: "https://backboard.railway.com"
        case .render: "https://api.render.com/v1"
        case .digitalOcean: "https://api.digitalocean.com"
        case .heroku: "https://api.heroku.com"
        case .fly: "https://api.machines.dev/v1"
        case .firebase: "https://firebasehosting.googleapis.com/v1beta1"
        case .awsAmplify, .vercel, .cloudflare: nil
        }
    }

    private func requestJSON(path: String) async throws -> Any {
        let response = try await rawRequest(method: "GET", path: path, body: nil)
        if response.body.isEmpty { return [:] }
        return try parseJSON(response.body)
    }

    private func collectNumberedPages(
        pageSize: Int,
        maximumPages: Int = 200,
        fetchPage: (Int) async throws -> [Any]
    ) async throws -> [Any] {
        var result: [Any] = []
        var seenItems = Set<String>()
        for page in 1...maximumPages {
            try Task.checkCancellation()
            let items = try await fetchPage(page)
            if items.isEmpty { return result }
            let uniqueItems = items.filter { item in
                guard let fingerprint = jsonFingerprint(item) else { return true }
                return seenItems.insert(fingerprint).inserted
            }
            guard !uniqueItems.isEmpty else {
                throw HostingProviderAPIError.decoding("Pagination repeated a page without returning new items.")
            }
            result.append(contentsOf: uniqueItems)
            if items.count < pageSize { return result }
        }
        throw HostingProviderAPIError.decoding("Pagination exceeded \(maximumPages) pages.")
    }

    private func fetchTokenPages(
        initialPath: String,
        itemKey: String,
        tokenQueryName: String,
        maximumPages: Int = 200
    ) async throws -> [Any] {
        var path = initialPath
        var result: [Any] = []
        var seenTokens = Set<String>()
        var seenItems = Set<String>()
        let responseTokenName = tokenQueryName == "pageToken" ? "nextPageToken" : tokenQueryName

        for _ in 1...maximumPages {
            try Task.checkCancellation()
            let root = object(try await requestJSON(path: path))
            let items = array(root[itemKey])
            for item in items {
                guard let fingerprint = jsonFingerprint(item) else {
                    result.append(item)
                    continue
                }
                if seenItems.insert(fingerprint).inserted { result.append(item) }
            }
            guard let token = string(root, responseTokenName), !token.isEmpty else { return result }
            guard seenTokens.insert(token).inserted else {
                throw HostingProviderAPIError.decoding("Pagination repeated a continuation token.")
            }
            path = initialPath + (initialPath.contains("?") ? "&" : "?")
                + "\(tokenQueryName)=\(queryComponent(token))"
        }
        throw HostingProviderAPIError.decoding("Pagination exceeded \(maximumPages) pages.")
    }

    private func fetchRenderCursorPages(path initialPath: String, maximumPages: Int = 200) async throws -> [Any] {
        var path = initialPath
        var result: [Any] = []
        var seenCursors = Set<String>()
        var seenItems = Set<String>()

        for _ in 1...maximumPages {
            try Task.checkCancellation()
            let items = array(try await requestJSON(path: path))
            if items.isEmpty { return result }
            for item in items {
                guard let fingerprint = jsonFingerprint(item) else {
                    result.append(item)
                    continue
                }
                if seenItems.insert(fingerprint).inserted { result.append(item) }
            }
            guard let cursor = string(object(items.last), "cursor"), !cursor.isEmpty else { return result }
            guard seenCursors.insert(cursor).inserted else {
                throw HostingProviderAPIError.decoding("Render pagination repeated a cursor.")
            }
            path = initialPath + (initialPath.contains("?") ? "&" : "?")
                + "cursor=\(queryComponent(cursor))"
        }
        throw HostingProviderAPIError.decoding("Render pagination exceeded \(maximumPages) pages.")
    }

    private func fetchHerokuRangePages(path: String, maximumPages: Int = 200) async throws -> [Any] {
        var nextRange: String? = "id ..; max=200; order=asc"
        var seenRanges = Set<String>()
        var seenItems = Set<String>()
        var result: [Any] = []

        for _ in 1...maximumPages {
            try Task.checkCancellation()
            guard let range = nextRange, seenRanges.insert(range).inserted else {
                if nextRange == nil { return result }
                throw HostingProviderAPIError.decoding("Heroku pagination repeated a range.")
            }
            let raw = try await rawRequest(
                method: "GET",
                path: path,
                body: nil,
                additionalHeaders: ["Range": range]
            )
            let items = array(try parseJSON(raw.body))
            for item in items {
                guard let fingerprint = jsonFingerprint(item) else {
                    result.append(item)
                    continue
                }
                if seenItems.insert(fingerprint).inserted { result.append(item) }
            }
            nextRange = header(named: "Next-Range", in: raw.headers)
            if nextRange?.isEmpty != false { return result }
        }
        throw HostingProviderAPIError.decoding("Heroku pagination exceeded \(maximumPages) pages.")
    }

    private func header(named name: String, in headers: [String: String]) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    private func jsonFingerprint(_ value: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return nil }
        return sha256Hex(data)
    }

    private func stableIdentifier(_ existing: String?, namespace: String, values: [String?]) -> String? {
        if let existing, !existing.isEmpty { return existing }
        let components = values.compactMap { value -> String? in
            guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
            return value
        }
        guard !components.isEmpty else { return nil }
        return "\(namespace)-\(sha256Hex(Data(components.joined(separator: "\u{1F}").utf8)).prefix(20))"
    }

    private func deduplicatedDeployments(_ deployments: [HostingDeployment]) -> [HostingDeployment] {
        var seen = Set<String>()
        return deployments.filter { seen.insert($0.id).inserted }
    }

    private func parseJSON(_ body: String) throws -> Any {
        guard let data = body.data(using: .utf8) else {
            throw HostingProviderAPIError.decoding("Response is not UTF-8.")
        }
        do {
            return try JSONSerialization.jsonObject(with: data)
        } catch {
            throw HostingProviderAPIError.decoding(error.localizedDescription)
        }
    }

    private func errorMessage(from data: Data) -> String {
        guard let value = try? JSONSerialization.jsonObject(with: data) else {
            return String(data: data, encoding: .utf8) ?? ""
        }
        let root = object(value)
        if let message = string(root, "message", "error", "error_description") { return message }
        if let first = array(root["errors"]).first {
            return string(object(first), "message", "detail") ?? displayJSON(first) ?? ""
        }
        return displayJSON(value) ?? ""
    }

    private var credentialFingerprint: String {
        String(SHA256.hash(data: Data(credential.utf8)).compactMap { String(format: "%02x", $0) }.joined().prefix(16))
    }

    private func requiredMetadata(_ key: String, label: String) throws -> String {
        guard let value = metadata[key]?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            throw HostingProviderAPIError.invalidConfiguration("Enter the \(label).")
        }
        return value
    }

    private func resolvedBearerCredential() async throws -> String {
        guard provider == .firebase, metadata["firebaseAuthMode"] == "refreshToken" else {
            return credential
        }
        let clientID = try requiredMetadata("firebaseClientID", label: "Google OAuth client ID")
        var items = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "refresh_token", value: credential),
            URLQueryItem(name: "grant_type", value: "refresh_token"),
        ]
        if let secret = metadata["firebaseClientSecret"], !secret.isEmpty {
            items.append(URLQueryItem(name: "client_secret", value: secret))
        }
        var components = URLComponents()
        components.queryItems = items
        guard let encoded = components.percentEncodedQuery?.data(using: .utf8),
              let url = URL(string: "https://oauth2.googleapis.com/token") else {
            throw HostingProviderAPIError.invalidConfiguration("Could not encode the Google OAuth refresh request.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = encoded
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await ProviderRequestSecurity.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HostingProviderAPIError.invalidResponse
        }
        guard (200...299).contains(http.statusCode),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = value["access_token"] as? String,
              !token.isEmpty else {
            let message = errorMessage(from: data)
            throw HostingProviderAPIError.requestFailed(
                http.statusCode,
                message.isEmpty ? "Google could not exchange this Firebase refresh token." : message
            )
        }
        return token
    }

    // MARK: - AWS Signature Version 4

    static func awsAmplifyEndpoint(region: String, path: String) throws -> (host: String, url: URL) {
        guard region.utf8.count <= 63,
              region.range(of: awsStandardRegionPattern, options: .regularExpression)
            == region.startIndex..<region.endIndex else {
            throw HostingProviderAPIError.invalidConfiguration(
                "Enter a valid AWS region such as us-east-1."
            )
        }

        let host = "amplify.\(region).amazonaws.com"
        guard path.hasPrefix("/"), !path.hasPrefix("//"),
              var components = URLComponents(string: path),
              components.scheme == nil,
              components.host == nil,
              components.user == nil,
              components.password == nil,
              components.port == nil else {
            throw HostingProviderAPIError.invalidConfiguration("The Amplify request path is invalid.")
        }
        components.scheme = "https"
        components.host = host

        guard components.host == host,
              let url = components.url,
              url.scheme == "https",
              url.host == host,
              url.user == nil,
              url.password == nil,
              url.port == nil else {
            throw HostingProviderAPIError.invalidConfiguration("The Amplify endpoint is invalid.")
        }
        return (host, url)
    }

    private func awsSignedRequest(method: String, path: String, body: Data?, contentType: String) throws -> URLRequest {
        let accessKeyID = try requiredMetadata("accessKeyID", label: "AWS access key ID")
        let region = try requiredMetadata("region", label: "AWS region")
        let endpoint = try Self.awsAmplifyEndpoint(region: region, path: path)
        let host = endpoint.host
        let url = endpoint.url
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw HostingProviderAPIError.invalidConfiguration("The Amplify request path is invalid.")
        }

        let now = Date()
        let amzDate = Self.awsTimestamp.string(from: now)
        let dateStamp = Self.awsDateStamp.string(from: now)
        let payload = body ?? Data()
        let payloadHash = sha256Hex(payload)
        var headers: [(String, String)] = [
            ("content-type", contentType),
            ("host", host),
            ("x-amz-date", amzDate)
        ]
        if let sessionToken = metadata["sessionToken"], !sessionToken.isEmpty {
            headers.append(("x-amz-security-token", sessionToken))
        }
        headers.sort { $0.0 < $1.0 }

        let canonicalHeaders = headers.map { "\($0.0):\($0.1.trimmingCharacters(in: .whitespacesAndNewlines))\n" }.joined()
        let signedHeaders = headers.map(\.0).joined(separator: ";")
        let canonicalQuery = (components.queryItems ?? [])
            .map {
                (
                    ProviderAPIRequestEncoding.awsQueryComponent($0.name),
                    ProviderAPIRequestEncoding.awsQueryComponent($0.value ?? "")
                )
            }
            .sorted { $0 < $1 }
            .map { "\($0.0)=\($0.1)" }
            .joined(separator: "&")
        let canonicalRequest = [
            method,
            components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ].joined(separator: "\n")
        let scope = "\(dateStamp)/\(region)/amplify/aws4_request"
        let stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(Data(canonicalRequest.utf8))].joined(separator: "\n")
        let signingKey = hmac(Data("AWS4\(credential)".utf8), Data(dateStamp.utf8))
        let regionKey = hmac(signingKey, Data(region.utf8))
        let serviceKey = hmac(regionKey, Data("amplify".utf8))
        let finalKey = hmac(serviceKey, Data("aws4_request".utf8))
        let signature = hmac(finalKey, Data(stringToSign.utf8)).map { String(format: "%02x", $0) }.joined()

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.setValue(amzDate, forHTTPHeaderField: "X-Amz-Date")
        if let sessionToken = metadata["sessionToken"], !sessionToken.isEmpty {
            request.setValue(sessionToken, forHTTPHeaderField: "X-Amz-Security-Token")
        }
        request.setValue(
            "AWS4-HMAC-SHA256 Credential=\(accessKeyID)/\(scope), SignedHeaders=\(signedHeaders), Signature=\(signature)",
            forHTTPHeaderField: "Authorization"
        )
        return request
    }

    private static let awsTimestamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return formatter
    }()

    private static let awsDateStamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd"
        return formatter
    }()

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func hmac(_ key: Data, _ data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    // MARK: - Dynamic JSON normalization

    private func deployment(from value: [String: Any], provider: AccountProvider) -> HostingDeployment {
        switch provider {
        case .netlify:
            return HostingDeployment(
                id: string(value, "id") ?? fallbackIdentifier(namespace: "netlify-deploy", value: value),
                title: string(value, "title", "context") ?? "Deploy",
                status: string(value, "state") ?? "unknown",
                createdAt: date(value["created_at"]),
                url: string(value, "ssl_url", "deploy_ssl_url", "url"),
                branch: string(value, "branch"),
                commitMessage: string(value, "title", "commit_ref"),
                metadata: [:]
            )
        case .render:
            let commit = object(value["commit"])
            return HostingDeployment(
                id: string(value, "id") ?? fallbackIdentifier(namespace: "render-deploy", value: value),
                title: string(commit, "message") ?? "Deploy",
                status: string(value, "status") ?? "unknown",
                createdAt: date(value["createdAt"]),
                url: nil,
                branch: nil,
                commitMessage: string(commit, "message", "id"),
                metadata: [:]
            )
        case .digitalOcean:
            return HostingDeployment(
                id: string(value, "id") ?? fallbackIdentifier(namespace: "digitalocean-deploy", value: value),
                title: string(value, "cause") ?? "Deployment",
                status: string(value, "phase") ?? "UNKNOWN",
                createdAt: date(value["created_at"]),
                url: nil,
                branch: nil,
                commitMessage: string(value, "cause"),
                metadata: [:]
            )
        case .heroku:
            let version = integer(value["version"])
            return HostingDeployment(
                id: string(value, "id")
                    ?? version.map { "release-\($0)" }
                    ?? fallbackIdentifier(namespace: "heroku-release", value: value),
                title: version.map { "Release v\($0)" } ?? "Release",
                status: string(value, "status") ?? (bool(value, "current") == true ? "current" : "released"),
                createdAt: date(value["created_at"]),
                url: string(value, "output_stream_url"),
                branch: nil,
                commitMessage: string(value, "description"),
                metadata: ["rollbackEligible": String(bool(value, "eligible_for_rollback") ?? false)]
            )
        case .fly:
            let config = object(value["config"])
            return HostingDeployment(
                id: string(value, "id") ?? fallbackIdentifier(namespace: "fly-machine", value: value),
                title: string(value, "name") ?? "Machine",
                status: string(value, "state") ?? "unknown",
                createdAt: date(value["created_at"]),
                url: nil,
                branch: string(value, "region"),
                commitMessage: string(config, "image"),
                metadata: ["region": string(value, "region") ?? ""]
            )
        case .firebase:
            let version = object(value["version"])
            return HostingDeployment(
                id: string(value, "name") ?? fallbackIdentifier(namespace: "firebase-release", value: value),
                title: string(value, "type")?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Release",
                status: string(version, "status") ?? "RELEASED",
                createdAt: date(value["releaseTime"]) ?? date(version["finalizeTime"]),
                url: nil,
                branch: nil,
                commitMessage: string(value, "message"),
                metadata: ["version": string(version, "name") ?? ""]
            )
        case .awsAmplify:
            return HostingDeployment(
                id: string(value, "jobId") ?? fallbackIdentifier(namespace: "amplify-job", value: value),
                title: string(value, "jobType")?.capitalized ?? "Build job",
                status: string(value, "status") ?? "UNKNOWN",
                createdAt: date(value["startTime"]),
                url: nil,
                branch: string(value, "branchName"),
                commitMessage: string(value, "commitMessage", "commitId"),
                metadata: [:]
            )
        default:
            return HostingDeployment(
                id: string(value, "id") ?? fallbackIdentifier(namespace: "hosting-deployment", value: value),
                title: "Deployment",
                status: string(value, "status") ?? "unknown",
                createdAt: nil,
                url: nil,
                branch: nil,
                commitMessage: nil,
                metadata: [:]
            )
        }
    }

    private func object(_ value: Any?) -> [String: Any] {
        value as? [String: Any] ?? [:]
    }

    private func array(_ value: Any?) -> [Any] {
        value as? [Any] ?? []
    }

    private func edgeNodes(_ connection: [String: Any]) -> [[String: Any]] {
        array(connection["edges"]).map { object(object($0)["node"]) }
    }

    private func string(_ object: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if key.contains("."), let nested = nestedValue(in: object, path: key), let result = nested as? String, !result.isEmpty {
                return result
            }
            if let result = object[key] as? String, !result.isEmpty { return result }
            if let number = object[key] as? NSNumber { return number.stringValue }
        }
        return nil
    }

    private func nestedValue(in object: [String: Any], path: String) -> Any? {
        var value: Any = object
        for component in path.split(separator: ".").map(String.init) {
            guard let current = value as? [String: Any], let next = current[component] else { return nil }
            value = next
        }
        return value
    }

    private func bool(_ object: [String: Any], _ key: String) -> Bool? {
        object[key] as? Bool ?? (object[key] as? NSNumber)?.boolValue
    }

    private func integer(_ value: Any?) -> Int? {
        (value as? Int) ?? (value as? NSNumber)?.intValue ?? (value as? String).flatMap(Int.init)
    }

    private func date(_ value: Any?) -> Date? {
        if let number = value as? NSNumber {
            let raw = number.doubleValue
            return Date(timeIntervalSince1970: raw > 10_000_000_000 ? raw / 1000 : raw)
        }
        guard let string = value as? String else { return nil }
        if let numeric = Double(string) {
            return Date(timeIntervalSince1970: numeric > 10_000_000_000 ? numeric / 1000 : numeric)
        }
        return ISO8601DateFormatter().date(from: string)
            ?? Self.fractionalISO8601.date(from: string)
    }

    private static let fractionalISO8601: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private func displayJSON(_ value: Any?) -> String? {
        guard let value else { return nil }
        if let string = value as? String { return string }
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else { return nil }
        return string
    }

    private func fallbackIdentifier(namespace: String, value: [String: Any]) -> String {
        let fingerprint = jsonFingerprint(value)
            ?? sha256Hex(Data("\(namespace)|\(String(describing: value))".utf8))
        return "\(namespace)-\(fingerprint.prefix(20))"
    }

    private func pathComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathComponentAllowed) ?? value
    }

    private func queryComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? value
    }
}

private extension CharacterSet {
    static let urlPathComponentAllowed: CharacterSet = {
        var set = CharacterSet.urlPathAllowed
        set.remove(charactersIn: "/?#")
        return set
    }()

    static let urlQueryValueAllowed: CharacterSet = {
        var set = CharacterSet.urlQueryAllowed
        set.remove(charactersIn: "+&=?#")
        return set
    }()
}
