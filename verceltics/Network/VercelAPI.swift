import Foundation

enum APIError: LocalizedError {
    case unauthorized
    case serverError(Int)
    case decodingError(Error)
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .unauthorized: "Session expired. Please log in again."
        case .serverError(let code): "Server error (\(code)). Try again later."
        case .decodingError: "Failed to parse response."
        case .networkError(let err): err.localizedDescription
        }
    }
}

actor VercelAPI {
    private let token: String
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    init(token: String) {
        self.token = token
    }

    // MARK: - Projects (api.vercel.com)

    func fetchProjects() async throws -> [Project] {
        let response: ProjectsResponse = try await request(
            base: "https://api.vercel.com",
            path: "/v9/projects"
        )
        return response.projects
    }

    // MARK: - Analytics (vercel.com/api)

    func fetchOverview(projectId: String, teamId: String?, range: TimeRange) async throws -> AnalyticsOverview {
        try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/overview",
            queryItems: analyticsParams(projectId: projectId, teamId: teamId, range: range)
        )
    }

    func fetchPreviousOverview(projectId: String, teamId: String?, range: TimeRange) async throws -> AnalyticsOverview {
        var items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: range.previousFromDate),
            URLQueryItem(name: "to", value: range.previousToDate)
        ]
        if let teamId { items.append(URLQueryItem(name: "teamId", value: teamId)) }
        return try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/overview",
            queryItems: items
        )
    }

    func fetchTimeseries(projectId: String, teamId: String?, range: TimeRange) async throws -> [TimeseriesPoint] {
        let response: TimeseriesResponse = try await request(
            base: "https://vercel.com/api",
            path: "/web-analytics/timeseries",
            queryItems: analyticsParams(projectId: projectId, teamId: teamId, range: range)
        )
        return response.data.groups["all"] ?? []
    }

    func fetchBreakdown(projectId: String, teamId: String?, range: TimeRange, groupBy: String) async throws -> [BreakdownItem] {
        var params = analyticsParams(projectId: projectId, teamId: teamId, range: range)
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

    private func analyticsParams(projectId: String, teamId: String?, range: TimeRange) -> [URLQueryItem] {
        var items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: range.fromDate),
            URLQueryItem(name: "to", value: range.toDate)
        ]
        if let teamId { items.append(URLQueryItem(name: "teamId", value: teamId)) }
        return items
    }

    private func request<T: Decodable>(base: String, path: String, queryItems: [URLQueryItem] = []) async throws -> T {
        var components = URLComponents(string: base + path)!
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        var request = URLRequest(url: components.url!)
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
