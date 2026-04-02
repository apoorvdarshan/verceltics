import SwiftUI
import Charts

struct AnalyticsChart: View {
    let data: [TimeseriesDataPoint]

    var body: some View {
        Chart(data) { point in
            if let date = point.date {
                LineMark(
                    x: .value("Date", date),
                    y: .value("Visitors", point.total)
                )
                .foregroundStyle(.white)
                .interpolationMethod(.catmullRom)

                AreaMark(
                    x: .value("Date", date),
                    y: .value("Visitors", point.total)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [.white.opacity(0.2), .white.opacity(0)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .interpolationMethod(.catmullRom)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) {
                AxisValueLabel()
                    .foregroundStyle(.gray)
                AxisGridLine()
                    .foregroundStyle(Color.white.opacity(0.08))
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                AxisValueLabel()
                    .foregroundStyle(.gray)
                AxisGridLine()
                    .foregroundStyle(Color.white.opacity(0.08))
            }
        }
    }
}
