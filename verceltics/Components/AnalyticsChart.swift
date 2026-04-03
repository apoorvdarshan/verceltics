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
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Visitors")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                Spacer()
                if let selectedPoint {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("\(selectedPoint.visitors)")
                            .font(.system(size: 20, weight: .heavy, design: .rounded).monospacedDigit())
                            .foregroundStyle(.blue)
                        Text(selectedPoint.date, format: .dateTime.month(.abbreviated).day())
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.35))
                    }
                    .transition(.opacity)
                }
            }

            if filteredData.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "chart.xyaxis.line")
                        .font(.title2)
                        .foregroundStyle(.white.opacity(0.15))
                    Text("No visitor data")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.25))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Chart {
                    ForEach(filteredData, id: \.date) { item in
                        AreaMark(
                            x: .value("Date", item.date),
                            y: .value("Visitors", item.visitors)
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.blue.opacity(0.2), .blue.opacity(0.02), .clear],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .interpolationMethod(.catmullRom)

                        LineMark(
                            x: .value("Date", item.date),
                            y: .value("Visitors", item.visitors)
                        )
                        .foregroundStyle(.blue)
                        .interpolationMethod(.catmullRom)
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    }

                    if let selectedPoint {
                        RuleMark(x: .value("Selected", selectedPoint.date))
                            .foregroundStyle(.white.opacity(0.1))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))

                        PointMark(
                            x: .value("Date", selectedPoint.date),
                            y: .value("Visitors", selectedPoint.visitors)
                        )
                        .foregroundStyle(.white)
                        .symbolSize(40)
                        .annotation(position: .top, spacing: 6) {
                            Text("\(selectedPoint.visitors)")
                                .font(.system(size: 11, weight: .bold).monospacedDigit())
                                .foregroundStyle(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.white.opacity(0.1))
                                .clipShape(Capsule())
                        }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) {
                        AxisValueLabel()
                            .foregroundStyle(.white.opacity(0.3))
                            .font(.system(size: 9, weight: .medium))
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                            .foregroundStyle(Color.white.opacity(0.04))
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                        AxisValueLabel()
                            .foregroundStyle(.white.opacity(0.3))
                            .font(.system(size: 9, weight: .medium))
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
                                        let x = value.location.x - geo[proxy.plotAreaFrame].origin.x
                                        guard let date: Date = proxy.value(atX: x) else { return }
                                        let closest = filteredData.min(by: {
                                            abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date))
                                        })
                                        withAnimation(.snappy(duration: 0.12)) {
                                            selectedPoint = closest
                                        }
                                    }
                                    .onEnded { _ in
                                        withAnimation(.easeOut(duration: 0.3)) {
                                            selectedPoint = nil
                                        }
                                    }
                            )
                    }
                }
            }
        }
    }
}
