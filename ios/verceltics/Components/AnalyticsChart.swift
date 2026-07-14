import SwiftUI
import Charts

struct AnalyticsChart: View {
    let data: [TimeseriesPoint]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var selectedMetric: Metric = .visitors
    @State private var selectedPoint: (date: Date, value: Int)?

    private enum Metric: String, CaseIterable, Identifiable {
        case visitors = "Visitors"
        case pageViews = "Page Views"
        case bounceRate = "Bounce Rate"

        var id: Self { self }
        var color: Color {
            switch self {
            case .visitors: .blue
            case .pageViews: .purple
            case .bounceRate: .orange
            }
        }
    }

    private var availableMetrics: [Metric] {
        data.contains { $0.bounceRate != nil } ? Metric.allCases : [.visitors, .pageViews]
    }

    private var filteredData: [(date: Date, value: Int)] {
        let raw = data.compactMap { point -> (date: Date, value: Int)? in
            guard let date = point.date else { return nil }
            let value: Int?
            switch selectedMetric {
            case .visitors: value = point.devices
            case .pageViews: value = point.total
            case .bounceRate: value = point.bounceRate
            }
            guard let value else { return nil }
            return (date, value)
        }
        // Aggregate to daily if more than 48 data points (hourly data)
        if raw.count > 48 {
            return aggregateDaily(raw)
        }
        return raw
    }

    private var headlineValue: Int {
        guard selectedMetric == .bounceRate else {
            return filteredData.reduce(0) { $0 + $1.value }
        }
        guard !filteredData.isEmpty else { return 0 }
        return filteredData.reduce(0) { $0 + $1.value } / filteredData.count
    }

    private var averageValue: Double {
        guard !filteredData.isEmpty else { return 0 }
        return Double(filteredData.reduce(0) { $0 + $1.value }) / Double(filteredData.count)
    }

    private var peakPoint: (date: Date, value: Int)? {
        filteredData.max(by: { $0.value < $1.value })
    }

    private func aggregateDaily(_ points: [(date: Date, value: Int)]) -> [(date: Date, value: Int)] {
        let calendar = Calendar.current
        var grouped: [DateComponents: [Int]] = [:]
        for point in points {
            let day = calendar.dateComponents([.year, .month, .day], from: point.date)
            grouped[day, default: []].append(point.value)
        }
        return grouped.compactMap { (components, values) -> (date: Date, value: Int)? in
            guard let date = calendar.date(from: components) else { return nil }
            let value = selectedMetric == .bounceRate
                ? values.reduce(0, +) / values.count
                : values.reduce(0, +)
            return (date, value)
        }
        .sorted { $0.date < $1.date }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            chartHeader

            if filteredData.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "chart.xyaxis.line")
                        .font(.system(size: 28))
                        .foregroundStyle(.white.opacity(0.15))
                    Text("No \(selectedMetric.rawValue.lowercased()) data")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.3))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                chartBody
            }
        }
    }

    // MARK: - Header

    private var chartHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker("Metric", selection: $selectedMetric) {
                ForEach(availableMetrics) { metric in
                    Text(metric.rawValue).tag(metric)
                }
            }
            .pickerStyle(.segmented)

            Text(selectedMetric.rawValue.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.white.opacity(0.45))
                .tracking(1.4)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                if let selectedPoint {
                    Text(formatted(selectedPoint.value))
                        .font(.system(size: 28, weight: .semibold, design: .default).monospacedDigit())
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())

                    Text(selectedPoint.date, format: .dateTime.month(.abbreviated).day())
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.blue)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.blue.opacity(0.14))
                        .clipShape(Capsule())
                        .transition(.opacity)
                } else {
                    Text(formatted(headlineValue))
                        .font(.system(size: 28, weight: .semibold, design: .default).monospacedDigit())
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())

                    if let peakPoint, headlineValue > 0 {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.up.forward")
                                .font(.system(size: 8, weight: .semibold))
                            Text("Peak \(formatted(peakPoint.value))")
                                .font(.system(size: 10, weight: .bold).monospacedDigit())
                        }
                        .foregroundStyle(.white.opacity(0.5))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.white.opacity(0.06))
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(Color.white.opacity(0.06), lineWidth: 0.5))
                    }
                }
            }
            .animation(reduceMotion ? nil : .snappy(duration: 0.18), value: selectedPoint?.date)
        }
    }

    // MARK: - Chart

    private var chartBody: some View {
        Chart {
            // Subtle average reference line
            if averageValue > 0 {
                RuleMark(y: .value("Average", averageValue))
                    .foregroundStyle(.white.opacity(0.08))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 4]))
            }

            ForEach(filteredData, id: \.date) { item in
                AreaMark(
                    x: .value("Date", item.date),
                    y: .value(selectedMetric.rawValue, item.value)
                )
                .foregroundStyle(
                    LinearGradient(
                        stops: [
                            .init(color: selectedMetric.color.opacity(0.32), location: 0.00),
                            .init(color: selectedMetric.color.opacity(0.10), location: 0.55),
                            .init(color: selectedMetric.color.opacity(0.00), location: 1.00),
                        ],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                .interpolationMethod(.catmullRom)

                LineMark(
                    x: .value("Date", item.date),
                    y: .value(selectedMetric.rawValue, item.value)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [selectedMetric.color, selectedMetric.color.opacity(0.65)],
                        startPoint: .leading, endPoint: .trailing
                    )
                )
                .interpolationMethod(.catmullRom)
                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }

            if let selectedPoint {
                RuleMark(x: .value("Selected", selectedPoint.date))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.clear, Color.white.opacity(0.18), Color.white.opacity(0.06)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                    .lineStyle(StrokeStyle(lineWidth: 1))

                // Outer glow ring
                PointMark(
                    x: .value("Date", selectedPoint.date),
                    y: .value(selectedMetric.rawValue, selectedPoint.value)
                )
                .symbolSize(320)
                .foregroundStyle(selectedMetric.color.opacity(0.18))

                // Inner dot
                PointMark(
                    x: .value("Date", selectedPoint.date),
                    y: .value(selectedMetric.rawValue, selectedPoint.value)
                )
                .symbolSize(60)
                .foregroundStyle(.white)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) {
                AxisValueLabel()
                    .foregroundStyle(.white.opacity(0.4))
                    .font(.system(size: 9, weight: .semibold))
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                    .foregroundStyle(Color.white.opacity(0.04))
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                AxisValueLabel()
                    .foregroundStyle(.white.opacity(0.4))
                    .font(.system(size: 9, weight: .semibold))
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                    .foregroundStyle(Color.white.opacity(0.04))
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                Rectangle().fill(.clear).contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                guard let plotFrame = proxy.plotFrame else { return }
                                let x = value.location.x - geo[plotFrame].origin.x
                                guard let date: Date = proxy.value(atX: x) else { return }
                                let closest = filteredData.min(by: {
                                    abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date))
                                })
                                if reduceMotion {
                                    selectedPoint = closest
                                } else {
                                    withAnimation(.snappy(duration: 0.15)) { selectedPoint = closest }
                                }
                            }
                            .onEnded { _ in
                                if reduceMotion {
                                    selectedPoint = nil
                                } else {
                                    withAnimation(.easeOut(duration: 0.35)) { selectedPoint = nil }
                                }
                            }
                    )
            }
        }
        .sensoryFeedback(.selection, trigger: selectedPoint?.date)
        .onChange(of: selectedMetric) { _, _ in selectedPoint = nil }
        .onChange(of: availableMetrics) { _, metrics in
            if !metrics.contains(selectedMetric) {
                selectedMetric = metrics.first ?? .visitors
                selectedPoint = nil
            }
        }
    }

    private func formatted(_ value: Int) -> String {
        selectedMetric == .bounceRate ? "\(value)%" : "\(value)"
    }
}
