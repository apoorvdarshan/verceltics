import CryptoKit
import Foundation

struct HostingProviderAPI {
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
            return array(try await requestJSON(path: "/sites?per_page=100")).map { value in
                let site = object(value)
                return HostingResource(
                    id: string(site, "id") ?? UUID().uuidString,
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
            let response = try await graphql(query: """
                query {
                  projects {
                    edges { node { id name description createdAt updatedAt } }
                  }
                }
                """)
            let projects = edgeNodes(object(object(response["data"])["projects"]))
            return projects.map { project in
                HostingResource(
                    id: string(project, "id") ?? UUID().uuidString,
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
            return array(try await requestJSON(path: "/services?limit=100&includePreviews=true")).map { value in
                let service = object(object(value)["service"] ?? value)
                let details = object(service["serviceDetails"])
                return HostingResource(
                    id: string(service, "id") ?? UUID().uuidString,
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
            let root = object(try await requestJSON(path: "/apps?per_page=200"))
            return array(root["apps"]).map { value in
                let app = object(value)
                let active = object(app["active_deployment"])
                let region = object(app["region"])
                return HostingResource(
                    id: string(app, "id") ?? UUID().uuidString,
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
            return array(try await requestJSON(path: "/apps")).map { value in
                let app = object(value)
                return HostingResource(
                    id: string(app, "id", "name") ?? UUID().uuidString,
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
            let root = object(try await requestJSON(path: "/projects/\(pathComponent(projectID))/sites?pageSize=100"))
            return array(root["sites"]).map { value in
                let site = object(value)
                let fullName = string(site, "name") ?? "sites/unknown"
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
            let root = object(try await requestJSON(path: "/apps?maxResults=100"))
            return array(root["apps"]).map { value in
                let app = object(value)
                let production = object(app["productionBranch"])
                return HostingResource(
                    id: string(app, "appId") ?? UUID().uuidString,
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
            return array(try await requestJSON(path: "/sites/\(pathComponent(resource.id))/deploys?per_page=50")).map {
                deployment(from: object($0), provider: .netlify)
            }

        case .railway:
            return try await fetchRailwayDeployments(projectID: resource.id)

        case .render:
            return array(try await requestJSON(path: "/services/\(pathComponent(resource.id))/deploys?limit=50")).map {
                deployment(from: object(object($0)["deploy"] ?? $0), provider: .render)
            }

        case .digitalOcean:
            let root = object(try await requestJSON(path: "/apps/\(pathComponent(resource.id))/deployments?per_page=50"))
            return array(root["deployments"]).map { deployment(from: object($0), provider: .digitalOcean) }

        case .heroku:
            return array(try await requestJSON(path: "/apps/\(pathComponent(resource.id))/releases")).prefix(50).map {
                deployment(from: object($0), provider: .heroku)
            }

        case .fly:
            let appName = resource.metadata["appName"] ?? resource.name
            return array(try await requestJSON(path: "/apps/\(pathComponent(appName))/machines?include_deleted=true")).map {
                deployment(from: object($0), provider: .fly)
            }

        case .firebase:
            let root = object(try await requestJSON(path: "/sites/\(pathComponent(resource.id))/releases?pageSize=50"))
            return array(root["releases"]).map { deployment(from: object($0), provider: .firebase) }

        case .awsAmplify:
            let branchesRoot = object(try await requestJSON(path: "/apps/\(pathComponent(resource.id))/branches?maxResults=100"))
            var results: [HostingDeployment] = []
            for branchValue in array(branchesRoot["branches"]) {
                let branch = object(branchValue)
                guard let branchName = string(branch, "branchName") else { continue }
                let jobsRoot = object(try await requestJSON(
                    path: "/apps/\(pathComponent(resource.id))/branches/\(pathComponent(branchName))/jobs?maxResults=50"
                ))
                results += array(jobsRoot["jobSummaries"]).map {
                    var value = object($0)
                    value["branchName"] = branchName
                    return deployment(from: value, provider: .awsAmplify)
                }
            }
            return results.sorted { ($0.createdAt ?? .distantPast) > ($1.createdAt ?? .distantPast) }

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
        returnHTTPErrorResponse: Bool = false
    ) async throws -> HostingRawResponse {
        let normalizedMethod = method.uppercased()
        guard ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].contains(normalizedMethod) else {
            throw HostingProviderAPIError.invalidConfiguration("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.")
        }
        guard path.hasPrefix("/"), !path.hasPrefix("//") else {
            throw HostingProviderAPIError.invalidConfiguration("Enter a provider-relative path beginning with /.")
        }

        let bodyData = body?.data(using: .utf8)
        var request: URLRequest

        if provider == .awsAmplify {
            request = try awsSignedRequest(method: normalizedMethod, path: path, body: bodyData)
        } else {
            guard let baseURL, let url = URL(string: baseURL + path) else {
                throw HostingProviderAPIError.invalidConfiguration("This provider has no API base URL.")
            }
            request = URLRequest(url: url)
            request.httpMethod = provider == .railway ? "POST" : normalizedMethod
            request.httpBody = bodyData
            request.setValue(contentType ?? "application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            if provider == .railway, metadata["railwayTokenType"] == "project" {
                request.setValue(credential, forHTTPHeaderField: "Project-Access-Token")
            } else {
                request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
            }
            if provider == .heroku {
                request.setValue("application/vnd.heroku+json; version=3", forHTTPHeaderField: "Accept")
                request.setValue("Verceltics/2.0", forHTTPHeaderField: "User-Agent")
            }
        }

        for (name, value) in additionalHeaders where !Self.protectedHeaders.contains(name.lowercased()) {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (data, response) = try await URLSession.shared.data(for: request)
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

    private static let protectedHeaders = Set(["authorization", "project-access-token", "host", "content-length", "x-amz-date", "x-amz-security-token"])

    // MARK: - Railway

    private func fetchRailwayDeployments(projectID: String) async throws -> [HostingDeployment] {
        let projectResponse = try await graphql(
            query: """
                query project($id: String!) {
                  project(id: $id) {
                    services { edges { node { id name } } }
                    environments { edges { node { id name } } }
                  }
                }
                """,
            variables: ["id": projectID]
        )
        let project = object(object(projectResponse["data"])["project"])
        let services = edgeNodes(object(project["services"]))
        let environments = edgeNodes(object(project["environments"]))
        var deployments: [HostingDeployment] = []

        for service in services {
            guard let serviceID = string(service, "id") else { continue }
            for environment in environments {
                guard let environmentID = string(environment, "id") else { continue }
                let response = try await graphql(
                    query: """
                        query deployments($input: DeploymentListInput!, $first: Int) {
                          deployments(input: $input, first: $first) {
                            edges { node { id status createdAt url staticUrl meta canRedeploy canRollback } }
                          }
                        }
                        """,
                    variables: [
                        "input": [
                            "projectId": projectID,
                            "serviceId": serviceID,
                            "environmentId": environmentID
                        ],
                        "first": 20
                    ]
                )
                let nodes = edgeNodes(object(object(response["data"])["deployments"]))
                deployments += nodes.map { node in
                    HostingDeployment(
                        id: string(node, "id") ?? UUID().uuidString,
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
        return deployments.sorted { ($0.createdAt ?? .distantPast) > ($1.createdAt ?? .distantPast) }
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

    private var baseURL: String? {
        switch provider {
        case .netlify: "https://api.netlify.com/api/v1"
        case .railway: "https://backboard.railway.com"
        case .render: "https://api.render.com/v1"
        case .digitalOcean: "https://api.digitalocean.com/v2"
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

    // MARK: - AWS Signature Version 4

    private func awsSignedRequest(method: String, path: String, body: Data?) throws -> URLRequest {
        let accessKeyID = try requiredMetadata("accessKeyID", label: "AWS access key ID")
        let region = try requiredMetadata("region", label: "AWS region")
        let host = "amplify.\(region).amazonaws.com"
        guard let components = URLComponents(string: "https://\(host)\(path)"), let url = components.url else {
            throw HostingProviderAPIError.invalidConfiguration("The Amplify request path is invalid.")
        }

        let now = Date()
        let amzDate = Self.awsTimestamp.string(from: now)
        let dateStamp = Self.awsDateStamp.string(from: now)
        let payload = body ?? Data()
        let payloadHash = sha256Hex(payload)
        var headers: [(String, String)] = [
            ("content-type", "application/json"),
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
            .sorted { ($0.name, $0.value ?? "") < ($1.name, $1.value ?? "") }
            .map { "\(queryComponent($0.name))=\(queryComponent($0.value ?? ""))" }
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
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
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
                id: string(value, "id") ?? UUID().uuidString,
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
                id: string(value, "id") ?? UUID().uuidString,
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
                id: string(value, "id") ?? UUID().uuidString,
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
                id: string(value, "id") ?? "release-\(version ?? 0)",
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
                id: string(value, "id") ?? UUID().uuidString,
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
                id: string(value, "name") ?? UUID().uuidString,
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
                id: string(value, "jobId") ?? UUID().uuidString,
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
                id: string(value, "id") ?? UUID().uuidString,
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
