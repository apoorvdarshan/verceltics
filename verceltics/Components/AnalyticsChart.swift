import SwiftUI
import Charts

struct AnalyticsChart: View {
    let data: [TimeseriesPoint]
    @State private var selectedPoint: (date: Date, visitors: Int)?

    private var filteredData: [(date: Date, visitors: Int)] {
        data.compactMap { point in
            guard let date = point.date else { return nil }
            return (date, point.devices)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Visitors")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                Spacer()
                if let selectedPoint {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(selectedPoint.visitors)")
                            .font(.system(size: 18, weight: .heavy, design: .rounded).monospacedDigit())
                            .foregroundStyle(.blue)
                        Text(selectedPoint.date, format: .dateTime.month(.abbreviated).day())
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .transition(.opacity)
                }
            }

            if filteredData.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "chart.xyaxis.line")
                        .font(.title2)
                        .foregroundStyle(.white.opacity(0.2))
                    Text("No visitor data")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.3))
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
                                colors: [.blue.opacity(0.25), .blue.opacity(0.05), .clear],
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
                        .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    }

                    if let selectedPoint {
                        RuleMark(x: .value("Selected", selectedPoint.date))
                            .foregroundStyle(.white.opacity(0.15))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))

                        PointMark(
                            x: .value("Date", selectedPoint.date),
                            y: .value("Visitors", selectedPoint.visitors)
                        )
                        .foregroundStyle(.blue)
                        .symbolSize(60)
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) {
                        AxisValueLabel()
                            .foregroundStyle(.white.opacity(0.35))
                            .font(.system(size: 9, weight: .medium))
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                            .foregroundStyle(Color.white.opacity(0.04))
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                        AxisValueLabel()
                            .foregroundStyle(.white.opacity(0.35))
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
