import Foundation

enum APIError: LocalizedError {
    case unauthorized
    case serverError(Int)
    case decodingError(Error)
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .unauthorized: 
            return "Session expired. Please log in again."
        case .serverError(let code):
            if code == 400 {
                return "Bad Request (400). Please ensure Web Analytics is enabled for this project on Vercel and your plan supports the selected time range."
            }
            return "Server error (\(code)). Try again later."
        case .decodingError: 
            return "Failed to parse response."
        case .networkError(let err): 
            return err.localizedDescription
        }
    }
}

actor VercelAPI {
    private let token: String
    private let decoder = JSONDecoder()

    init(token: String) {
        self.token = token
    }

    // MARK: - Projects

    func fetchProjects() async throws -> [Project] {
        let response: ProjectsResponse = try await request(
            base: "https://api.vercel.com",
            path: "/v9/projects"
        )
        return await enrichProjectsNeedingDomainRefresh(response.projects)
    }

    func fetchProject(id: String, teamId: String?) async throws -> Project {
        try await request(
            base: "https://api.vercel.com",
            path: "/v9/projects/\(id)",
            queryItems: projectQueryItems(teamId: teamId)
        )
    }

    func fetchProjectDomains(projectId: String, teamId: String?) async throws -> [String] {
        let response: ProjectDomainsResponse = try await request(
            base: "https://api.vercel.com",
            path: "/v9/projects/\(projectId)/domains",
            queryItems: projectQueryItems(teamId: teamId)
        )
        return response.domains
            .filter { ($0.verified ?? true) && ($0.redirect ?? "").isEmpty }
            .map(\.name)
    }

    // MARK: - Analytics

    func fetchOverview(projectId: String, teamId: String?, from: String, to: String, environment: String) async throws -> AnalyticsOverview {
        try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/overview",
            queryItems: analyticsParams(projectId: projectId, teamId: teamId, from: from, to: to, environment: environment)
        )
    }

    func fetchPreviousOverview(projectId: String, teamId: String?, from: String, to: String, environment: String) async throws -> AnalyticsOverview {
        try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/overview",
            queryItems: analyticsParams(projectId: projectId, teamId: teamId, from: from, to: to, environment: environment)
        )
    }

    func fetchTimeseries(projectId: String, teamId: String?, from: String, to: String, environment: String) async throws -> [TimeseriesPoint] {
        let response: TimeseriesResponse = try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/timeseries",
            queryItems: analyticsParams(projectId: projectId, teamId: teamId, from: from, to: to, environment: environment)
        )
        return response.data.groups["all"] ?? []
    }

    func fetchBreakdown(projectId: String, teamId: String?, from: String, to: String, groupBy: String, environment: String) async throws -> [BreakdownItem] {
        var params = analyticsParams(projectId: projectId, teamId: teamId, from: from, to: to, environment: environment)
        params.append(URLQueryItem(name: "groupBy", value: groupBy))
        let response: TimeseriesResponse = try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/timeseries",
            queryItems: params
        )
        return response.data.groups
            .filter { $0.key != "all" }
            .map { BreakdownItem(key: $0.key, visitors: $0.value.reduce(0) { $0 + $1.devices }) }
            .sorted { $0.visitors > $1.visitors }
    }

    // MARK: - Helpers

    private func enrichProjectsNeedingDomainRefresh(_ projects: [Project]) async -> [Project] {
        let candidates = projects.filter(\.needsPrimaryDomainRefresh)
        guard !candidates.isEmpty else { return projects }

        var refreshedByID: [String: Project] = [:]

        await withTaskGroup(of: (String, Project).self) { group in
            for project in candidates {
                group.addTask {
                    async let refreshedTask = try? await self.fetchProject(id: project.id, teamId: project.teamId)
                    async let domainsTask = (try? await self.fetchProjectDomains(projectId: project.id, teamId: project.teamId)) ?? []

                    var resolved = await refreshedTask ?? project
                    let domains = await domainsTask
                    if !domains.isEmpty {
                        // Merge every verified domain — primaryDomain picks the
                        // best one (custom > shortest vercel.app). The bulk
                        // listing sometimes omits short aliases that were
                        // attached at the project level after the last deploy.
                        let newEntries = domains.map { Project.AliasEntry(domain: $0) }
                        resolved.alias = (resolved.alias ?? []) + newEntries
                    }
                    return (project.id, resolved)
                }
            }

            for await (projectID, refreshed) in group {
                refreshedByID[projectID] = refreshed
            }
        }

        return projects.map { refreshedByID[$0.id] ?? $0 }
    }

    private func projectQueryItems(teamId: String?) -> [URLQueryItem] {
        var items: [URLQueryItem] = []
        if let teamId {
            items.append(URLQueryItem(name: "teamId", value: teamId))
        }
        return items
    }

    private func analyticsParams(projectId: String, teamId: String?, from: String, to: String, environment: String) -> [URLQueryItem] {
        var items = projectQueryItems(teamId: teamId)
        items.append(contentsOf: [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: from),
            URLQueryItem(name: "to", value: to),
            URLQueryItem(name: "environment", value: environment)
        ])
        return items
    }

    private func request<T: Decodable>(base: String, path: String, queryItems: [URLQueryItem] = []) async throws -> T {
        var components = URLComponents(string: base + path)!
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        let url = components.url!
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw APIError.networkError(error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw APIError.networkError(URLError(.badServerResponse))
        }

        if http.statusCode == 401 || http.statusCode == 403 {
            throw APIError.unauthorized
        }

        guard (200...299).contains(http.statusCode) else {
            throw APIError.serverError(http.statusCode)
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decodingError(error)
        }
    }
}
