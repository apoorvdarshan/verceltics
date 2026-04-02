import Foundation

enum APIError: LocalizedError {
    case unauthorized
    case serverError(Int)
    case decodingError
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
    private let baseURL = "https://api.vercel.com"
    private let token: String
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    init(token: String) {
        self.token = token
    }

    private func request<T: Decodable>(_ path: String, queryItems: [URLQueryItem] = []) async throws -> T {
        var components = URLComponents(string: baseURL + path)!
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
            throw APIError.decodingError
        }
    }

    func fetchProjects() async throws -> [Project] {
        let response: ProjectsResponse = try await request("/v9/projects")
        return response.projects
    }

    func fetchAnalyticsSummary(projectId: String, range: TimeRange) async throws -> AnalyticsSummary {
        let items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: "\(range.fromTimestamp)"),
            URLQueryItem(name: "to", value: "\(range.toTimestamp)")
        ]
        return try await request("/v1/web/analytics", queryItems: items)
    }

    func fetchTimeseries(projectId: String, range: TimeRange) async throws -> [TimeseriesDataPoint] {
        let items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: "\(range.fromTimestamp)"),
            URLQueryItem(name: "to", value: "\(range.toTimestamp)")
        ]
        let response: TimeseriesResponse = try await request("/v1/web/analytics/timeseries", queryItems: items)
        return response.data
    }

    func fetchPages(projectId: String, range: TimeRange) async throws -> [PageData] {
        let items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: "\(range.fromTimestamp)"),
            URLQueryItem(name: "to", value: "\(range.toTimestamp)")
        ]
        let response: PagesResponse = try await request("/v1/web/analytics/pages", queryItems: items)
        return response.data
    }

    func fetchReferrers(projectId: String, range: TimeRange) async throws -> [ReferrerData] {
        let items = [
            URLQueryItem(name: "projectId", value: projectId),
            URLQueryItem(name: "from", value: "\(range.fromTimestamp)"),
            URLQueryItem(name: "to", value: "\(range.toTimestamp)")
        ]
        let response: ReferrersResponse = try await request("/v1/web/analytics/referrers", queryItems: items)
        return response.data
    }
}
