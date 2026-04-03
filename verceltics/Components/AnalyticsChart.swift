import SwiftUI
import Charts

struct AnalyticsChart: View {
    private struct SelectionPoint: Equatable {
        let date: Date
        let visitors: Int
    }

    let data: [TimeseriesPoint]
    @State private var selectedPoint: SelectionPoint?

    private var filteredData: [(date: Date, visitors: Int)] {
        data.compactMap { point in
            guard let date = point.date else { return nil }
            return (date, point.devices)
        }
        .sorted { $0.date < $1.date }
    }

    private var chartLineGradient: LinearGradient {
        LinearGradient(
            colors: [
                Color.blue.opacity(0.95),
                Color(red: 0.44, green: 0.72, blue: 1.0),
                Color.white.opacity(0.92)
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
    }

    private var chartAreaGradient: LinearGradient {
        LinearGradient(
            colors: [
                Color.blue.opacity(0.38),
                Color.blue.opacity(0.16),
                Color.blue.opacity(0.03),
                .clear
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text("Visitors")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)

                Spacer()

                if let selectedPoint {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(compactNumber(selectedPoint.visitors))
                            .font(.system(size: 15, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.white)
                        Text(selectedPoint.date.formatted(selectionDateFormat))
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.55))
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                } else {
                    Label("Drag to inspect", systemImage: "hand.draw")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.45))
                }
            }

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
                Chart {
                    ForEach(filteredData, id: \.date) { item in
                        AreaMark(
                            x: .value("Date", item.date),
                            y: .value("Visitors", item.visitors)
                        )
                        .foregroundStyle(chartAreaGradient)
                        .interpolationMethod(.catmullRom)

                        LineMark(
                            x: .value("Date", item.date),
                            y: .value("Visitors", item.visitors)
                        )
                        .foregroundStyle(chartLineGradient)
                        .interpolationMethod(.catmullRom)
                        .lineStyle(StrokeStyle(lineWidth: 2.8, lineCap: .round, lineJoin: .round))
                    }

                    if let selectedPoint {
                        RuleMark(x: .value("Selected Date", selectedPoint.date))
                            .foregroundStyle(Color.white.opacity(0.24))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))

                        PointMark(
                            x: .value("Selected Date", selectedPoint.date),
                            y: .value("Selected Visitors", selectedPoint.visitors)
                        )
                        .foregroundStyle(.white)
                        .symbolSize(70)
                        .annotation(position: .top, spacing: 10) {
                            Text(compactNumber(selectedPoint.visitors))
                                .font(.system(size: 12, weight: .bold).monospacedDigit())
                                .foregroundStyle(.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    Capsule()
                                        .fill(Color.black.opacity(0.88))
                                )
                                .overlay(
                                    Capsule()
                                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                                )
                        }
                    }
                }
                .chartLegend(.hidden)
                .chartPlotStyle { plotContent in
                    plotContent
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(
                                    LinearGradient(
                                        colors: [
                                            Color.blue.opacity(0.14),
                                            Color.white.opacity(0.015),
                                            .clear
                                        ],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                        )
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 4)) { value in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.8, dash: [3, 5]))
                            .foregroundStyle(Color.white.opacity(0.05))
                        AxisTick(stroke: StrokeStyle(lineWidth: 0.8))
                            .foregroundStyle(Color.white.opacity(0.14))
                        AxisValueLabel {
                            if let date = value.as(Date.self) {
                                Text(axisLabel(for: date))
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.42))
                            }
                        }
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { value in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.8, dash: [3, 5]))
                            .foregroundStyle(Color.white.opacity(0.07))
                        AxisTick(length: 0)
                        AxisValueLabel {
                            if let intValue = value.as(Int.self) {
                                Text(compactNumber(intValue))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundStyle(.white.opacity(0.42))
                            } else if let doubleValue = value.as(Double.self) {
                                Text(compactNumber(Int(doubleValue)))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundStyle(.white.opacity(0.42))
                            }
                        }
                    }
                }
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        Rectangle()
                            .fill(.clear)
                            .contentShape(Rectangle())
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        updateSelection(at: value.location, proxy: proxy, geometry: geometry)
                                    }
                                    .onEnded { _ in
                                        withAnimation(.easeOut(duration: 0.18)) {
                                            selectedPoint = nil
                                        }
                                    }
                            )
                    }
                }
                .animation(.easeInOut(duration: 0.18), value: selectedPoint)
            }
        }
    }

    private var selectionDateFormat: Date.FormatStyle {
        let span = (filteredData.last?.date.timeIntervalSince(filteredData.first?.date ?? .now)) ?? 0
        if span <= 86_400 * 2 {
            return Date.FormatStyle(date: .abbreviated, time: .shortened)
        }
        return Date.FormatStyle().month(.abbreviated).day().year(.twoDigits)
    }

    private func updateSelection(at location: CGPoint, proxy: ChartProxy, geometry: GeometryProxy) {
        guard let plotFrame = proxy.plotFrame else {
            selectedPoint = nil
            return
        }

        let plotAreaFrame = geometry[plotFrame]
        let relativeX = location.x - plotAreaFrame.origin.x
        let relativeY = location.y - plotAreaFrame.origin.y

        guard relativeX >= 0,
              relativeX <= plotAreaFrame.size.width,
              relativeY >= 0,
              relativeY <= plotAreaFrame.size.height,
              let hoveredDate: Date = proxy.value(atX: relativeX) else {
            selectedPoint = nil
            return
        }

        guard let nearestPoint = filteredData.min(by: {
            abs($0.date.timeIntervalSince(hoveredDate)) < abs($1.date.timeIntervalSince(hoveredDate))
        }) else {
            return
        }

        withAnimation(.easeOut(duration: 0.12)) {
            selectedPoint = SelectionPoint(date: nearestPoint.date, visitors: nearestPoint.visitors)
        }
    }

    private func axisLabel(for date: Date) -> String {
        let span = (filteredData.last?.date.timeIntervalSince(filteredData.first?.date ?? .now)) ?? 0
        if span <= 86_400 * 2 {
            return date.formatted(.dateTime.hour(.defaultDigits(amPM: .abbreviated)))
        } else if span <= 86_400 * 40 {
            return date.formatted(.dateTime.month(.abbreviated).day())
        } else {
            return date.formatted(.dateTime.month(.abbreviated).year(.twoDigits))
        }
    }

    private func compactNumber(_ value: Int) -> String {
        if value >= 1_000_000 {
            return String(format: "%.1fM", Double(value) / 1_000_000)
        }
        if value >= 1_000 {
            return String(format: "%.1fK", Double(value) / 1_000)
        }
        return "\(value)"
    }
}
