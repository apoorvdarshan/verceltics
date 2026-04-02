import Foundation

// MARK: - Overview Response

struct AnalyticsOverview: Decodable {
    let total: Int
    let devices: Int
    let bounceRate: Int
}

// MARK: - Timeseries Response

struct TimeseriesResponse: Decodable {
    let data: TimeseriesData
}

struct TimeseriesData: Decodable {
    let groups: [String: [TimeseriesPoint]]
}

struct TimeseriesPoint: Identifiable, Decodable {
    var id: String { key }
    let key: String
    let total: Int
    let devices: Int
    let bounceRate: Int

    var date: Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: key)
    }
}

// MARK: - Aggregated breakdown item (computed from timeseries groupBy)

struct BreakdownItem: Identifiable {
    var id: String { key }
    let key: String
    let visitors: Int
}

// MARK: - Full analytics data for a project

struct AnalyticsData {
    var overview: AnalyticsOverview?
    var previousOverview: AnalyticsOverview?
    var timeseries: [TimeseriesPoint] = []
    var pages: [BreakdownItem] = []
    var referrers: [BreakdownItem] = []
    var countries: [BreakdownItem] = []
    var devices: [BreakdownItem] = []
    var os: [BreakdownItem] = []
    var browsers: [BreakdownItem] = []

    var visitorsChange: Double? {
        percentChange(current: overview?.devices, previous: previousOverview?.devices)
    }

    var pageViewsChange: Double? {
        percentChange(current: overview?.total, previous: previousOverview?.total)
    }

    var bounceRateChange: Double? {
        guard let current = overview?.bounceRate, let previous = previousOverview?.bounceRate, previous != 0 else { return nil }
        return Double(current - previous)
    }

    private func percentChange(current: Int?, previous: Int?) -> Double? {
        guard let c = current, let p = previous, p != 0 else { return nil }
        return ((Double(c) - Double(p)) / Double(p)) * 100
    }
}

// MARK: - Time Range

enum TimeRange: String, CaseIterable, Identifiable {
    case day = "24h"
    case week = "7d"
    case month = "30d"
    case quarter = "90d"

    var id: String { rawValue }
    var label: String { rawValue }

    var interval: TimeInterval {
        switch self {
        case .day: -86400
        case .week: -604800
        case .month: -2592000
        case .quarter: -7776000
        }
    }

    var fromDate: String {
        formatDate(Date().addingTimeInterval(interval))
    }

    var toDate: String {
        formatDate(Date())
    }

    var previousFromDate: String {
        formatDate(Date().addingTimeInterval(interval * 2))
    }

    var previousToDate: String {
        fromDate
    }

    private func formatDate(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }
}
