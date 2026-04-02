import Foundation

struct AnalyticsSummary: Decodable {
    let visitors: MetricData?
    let pageViews: MetricData?
    let bounceRate: MetricData?

    struct MetricData: Decodable {
        let total: Int?
        let devices: Int?
        let change: Double?

        var displayValue: Int {
            total ?? devices ?? 0
        }
    }

    enum CodingKeys: String, CodingKey {
        case visitors = "visitorsCount"
        case pageViews = "pageViewsCount"
        case bounceRate
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        visitors = try container.decodeIfPresent(MetricData.self, forKey: .visitors)
        pageViews = try container.decodeIfPresent(MetricData.self, forKey: .pageViews)
        bounceRate = try container.decodeIfPresent(MetricData.self, forKey: .bounceRate)
    }

    init(visitors: MetricData?, pageViews: MetricData?, bounceRate: MetricData?) {
        self.visitors = visitors
        self.pageViews = pageViews
        self.bounceRate = bounceRate
    }
}

struct TimeseriesDataPoint: Identifiable, Decodable {
    var id: String { key }
    let key: String
    let total: Int

    var date: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: key) ?? ISO8601DateFormatter().date(from: key)
    }
}

struct TimeseriesResponse: Decodable {
    let data: [TimeseriesDataPoint]
}

struct PageData: Identifiable, Decodable {
    var id: String { key }
    let key: String
    let devices: Int
}

struct PagesResponse: Decodable {
    let data: [PageData]
}

struct ReferrerData: Identifiable, Decodable {
    var id: String { key }
    let key: String
    let devices: Int
}

struct ReferrersResponse: Decodable {
    let data: [ReferrerData]
}

enum TimeRange: String, CaseIterable, Identifiable {
    case day = "24h"
    case week = "7d"
    case month = "30d"
    case quarter = "90d"

    var id: String { rawValue }

    var label: String { rawValue }

    var fromTimestamp: Int {
        let now = Date()
        let seconds: TimeInterval = switch self {
        case .day: -86400
        case .week: -604800
        case .month: -2592000
        case .quarter: -7776000
        }
        return Int(now.addingTimeInterval(seconds).timeIntervalSince1970 * 1000)
    }

    var toTimestamp: Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}
