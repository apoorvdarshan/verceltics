import SwiftUI
import Charts

struct AnalyticsChart: View {
    let data: [TimeseriesPoint]

    private var filteredData: [(date: Date, visitors: Int)] {
        data.compactMap { point in
            guard let date = point.date else { return nil }
            return (date, point.devices)
        }
    }

    var body: some View {
        if filteredData.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "chart.xyaxis.line")
                    .font(.title2)
                    .foregroundStyle(.gray)
                Text("No visitor data")
                    .font(.caption)
                    .foregroundStyle(.gray)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Chart(filteredData, id: \.date) { item in
                LineMark(
                    x: .value("Date", item.date),
                    y: .value("Visitors", item.visitors)
                )
                .foregroundStyle(.white)
                .interpolationMethod(.catmullRom)
                .lineStyle(StrokeStyle(lineWidth: 2))

                AreaMark(
                    x: .value("Date", item.date),
                    y: .value("Visitors", item.visitors)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [.white.opacity(0.15), .white.opacity(0)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .interpolationMethod(.catmullRom)
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 5)) {
                    AxisValueLabel()
                        .foregroundStyle(.gray)
                    AxisGridLine()
                        .foregroundStyle(Color.white.opacity(0.06))
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                    AxisValueLabel()
                        .foregroundStyle(.gray)
                    AxisGridLine()
                        .foregroundStyle(Color.white.opacity(0.06))
                }
            }
        }
    }
}
