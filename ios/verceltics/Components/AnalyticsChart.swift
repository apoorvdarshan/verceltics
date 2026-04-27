import SwiftUI
import Charts

struct AnalyticsChart: View {
    let data: [TimeseriesPoint]
    @State private var selectedPoint: (date: Date, visitors: Int)?

    private var filteredData: [(date: Date, visitors: Int)] {
        let raw = data.compactMap { point -> (date: Date, visitors: Int)? in
            guard let date = point.date else { return nil }
            return (date, point.devices)
        }
        // Aggregate to daily if more than 48 data points (hourly data)
        if raw.count > 48 {
            return aggregateDaily(raw)
        }
        return raw
    }

    private var totalVisitors: Int { filteredData.reduce(0) { $0 + $1.visitors } }

    private var averageVisitors: Double {
        guard !filteredData.isEmpty else { return 0 }
        return Double(totalVisitors) / Double(filteredData.count)
    }

    private var peakPoint: (date: Date, visitors: Int)? {
        filteredData.max(by: { $0.visitors < $1.visitors })
    }

    private func aggregateDaily(_ points: [(date: Date, visitors: Int)]) -> [(date: Date, visitors: Int)] {
        let calendar = Calendar.current
        var grouped: [DateComponents: Int] = [:]
        for point in points {
            let day = calendar.dateComponents([.year, .month, .day], from: point.date)
            grouped[day, default: 0] += point.visitors
        }
        return grouped.compactMap { (components, visitors) -> (date: Date, visitors: Int)? in
            guard let date = calendar.date(from: components) else { return nil }
            return (date, visitors)
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
                    Text("No visitor data")
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
            Text("VISITORS")
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.45))
                .tracking(1.4)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                if let selectedPoint {
                    Text("\(selectedPoint.visitors)")
                        .font(.system(size: 28, weight: .heavy, design: .rounded).monospacedDigit())
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
                    Text("\(totalVisitors)")
                        .font(.system(size: 28, weight: .heavy, design: .rounded).monospacedDigit())
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())

                    if let peakPoint, totalVisitors > 0 {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.up.forward")
                                .font(.system(size: 8, weight: .heavy))
                            Text("Peak \(peakPoint.visitors)")
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
            .animation(.snappy(duration: 0.18), value: selectedPoint?.date)
        }
    }

    // MARK: - Chart

    private var chartBody: some View {
        Chart {
            // Subtle average reference line
            if averageVisitors > 0 {
                RuleMark(y: .value("Average", averageVisitors))
                    .foregroundStyle(.white.opacity(0.08))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 4]))
            }

            ForEach(filteredData, id: \.date) { item in
                AreaMark(
                    x: .value("Date", item.date),
                    y: .value("Visitors", item.visitors)
                )
                .foregroundStyle(
                    LinearGradient(
                        stops: [
                            .init(color: Color.blue.opacity(0.32), location: 0.00),
                            .init(color: Color.blue.opacity(0.10), location: 0.55),
                            .init(color: Color.blue.opacity(0.00), location: 1.00),
                        ],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                .interpolationMethod(.catmullRom)

                LineMark(
                    x: .value("Date", item.date),
                    y: .value("Visitors", item.visitors)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color.blue, Color(red: 0.45, green: 0.65, blue: 1.0)],
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
                    y: .value("Visitors", selectedPoint.visitors)
                )
                .symbolSize(320)
                .foregroundStyle(.blue.opacity(0.18))

                // Inner dot
                PointMark(
                    x: .value("Date", selectedPoint.date),
                    y: .value("Visitors", selectedPoint.visitors)
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
                                withAnimation(.snappy(duration: 0.15)) {
                                    selectedPoint = closest
                                }
                            }
                            .onEnded { _ in
                                withAnimation(.easeOut(duration: 0.35)) {
                                    selectedPoint = nil
                                }
                            }
                    )
            }
        }
        .sensoryFeedback(.selection, trigger: selectedPoint?.date)
    }
}
