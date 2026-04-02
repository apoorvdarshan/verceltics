import SwiftUI

@Observable
@MainActor
final class AnalyticsViewModel {
    let project: Project

    var summary: AnalyticsSummary?
    var timeseries: [TimeseriesDataPoint] = []
    var pages: [PageData] = []
    var referrers: [ReferrerData] = []
    var selectedRange: TimeRange = .week
    var isLoading = true
    var error: String?

    init(project: Project) {
        self.project = project
    }

    func load(token: String) async {
        isLoading = true
        error = nil
        let api = VercelAPI(token: token)

        do {
            async let s = api.fetchAnalyticsSummary(projectId: project.id, range: selectedRange)
            async let t = api.fetchTimeseries(projectId: project.id, range: selectedRange)
            async let p = api.fetchPages(projectId: project.id, range: selectedRange)
            async let r = api.fetchReferrers(projectId: project.id, range: selectedRange)

            let (summaryResult, timeseriesResult, pagesResult, referrersResult) = try await (s, t, p, r)
            summary = summaryResult
            timeseries = timeseriesResult
            pages = pagesResult
            referrers = referrersResult
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

struct AnalyticsView: View {
    let project: Project
    @Environment(AuthManager.self) private var authManager
    @State private var vm: AnalyticsViewModel

    init(project: Project) {
        self.project = project
        _vm = State(wrappedValue: AnalyticsViewModel(project: project))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if vm.isLoading {
                AnalyticsSkeletonView()
            } else if let error = vm.error {
                ErrorStateView(message: error) {
                    Task { await loadData() }
                }
            } else {
                analyticsContent
            }
        }
        .navigationTitle(project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await loadData() }
        .onChange(of: vm.selectedRange) {
            Task { await loadData() }
        }
    }

    private var analyticsContent: some View {
        ScrollView {
            VStack(spacing: 20) {
                timeRangePicker
                statsCards
                chartSection
                pagesSection
                referrersSection
            }
            .padding()
        }
        .refreshable { await loadData() }
    }

    // MARK: - Time Range Picker

    private var timeRangePicker: some View {
        HStack(spacing: 8) {
            ForEach(TimeRange.allCases) { range in
                Button {
                    vm.selectedRange = range
                } label: {
                    Text(range.label)
                        .font(.caption.bold())
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(vm.selectedRange == range ? Color.white : Color.white.opacity(0.06))
                        .foregroundStyle(vm.selectedRange == range ? .black : .gray)
                        .clipShape(Capsule())
                }
            }
            Spacer()
        }
    }

    // MARK: - Stats Cards

    private var statsCards: some View {
        HStack(spacing: 12) {
            StatCard(
                title: "Visitors",
                value: formatNumber(vm.summary?.visitors?.displayValue ?? 0),
                change: vm.summary?.visitors?.change,
                icon: "person.2"
            )
            StatCard(
                title: "Page Views",
                value: formatNumber(vm.summary?.pageViews?.displayValue ?? 0),
                change: vm.summary?.pageViews?.change,
                icon: "eye"
            )
            StatCard(
                title: "Bounce Rate",
                value: formatBounceRate(vm.summary?.bounceRate?.displayValue),
                change: vm.summary?.bounceRate?.change,
                invertChange: true,
                icon: "arrow.uturn.left"
            )
        }
    }

    // MARK: - Chart

    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Visitors")
                .font(.subheadline.bold())
                .foregroundStyle(.white)

            if vm.timeseries.isEmpty {
                Text("No data for this period")
                    .font(.caption)
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity, minHeight: 180)
            } else {
                AnalyticsChart(data: vm.timeseries)
                    .frame(height: 200)
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Pages

    private var pagesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "doc.text")
                    .foregroundStyle(.gray)
                Text("Top Pages")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
            }

            if vm.pages.isEmpty {
                Text("No page data available")
                    .font(.caption)
                    .foregroundStyle(.gray)
            } else {
                ForEach(vm.pages.prefix(10)) { page in
                    HStack {
                        Text(page.key)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Spacer()
                        Text("\(page.devices)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.gray)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Referrers

    private var referrersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "link")
                    .foregroundStyle(.gray)
                Text("Top Referrers")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
            }

            if vm.referrers.isEmpty {
                Text("No referrer data available")
                    .font(.caption)
                    .foregroundStyle(.gray)
            } else {
                ForEach(vm.referrers.prefix(10)) { ref in
                    HStack {
                        Text(ref.key.isEmpty ? "Direct" : ref.key)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Spacer()
                        Text("\(ref.devices)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.gray)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Helpers

    private func loadData() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
    }

    private func formatNumber(_ value: Int) -> String {
        if value >= 1_000_000 { return String(format: "%.1fM", Double(value) / 1_000_000) }
        if value >= 1_000 { return String(format: "%.1fK", Double(value) / 1_000) }
        return "\(value)"
    }

    private func formatBounceRate(_ value: Int?) -> String {
        guard let value else { return "—" }
        return "\(value)%"
    }
}

// MARK: - Skeleton

struct AnalyticsSkeletonView: View {
    @State private var shimmer = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack(spacing: 8) {
                    ForEach(0..<4, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 20)
                            .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                            .frame(width: 50, height: 32)
                    }
                    Spacer()
                }

                HStack(spacing: 12) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                                .frame(width: 60, height: 12)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(shimmer ? 0.12 : 0.06))
                                .frame(width: 50, height: 24)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(Color.white.opacity(0.06))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }

                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.white.opacity(0.06))
                    .frame(height: 230)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                            .padding(16)
                    )

                ForEach(0..<2, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 10) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                            .frame(width: 100, height: 14)
                        ForEach(0..<5, id: \.self) { _ in
                            HStack {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                                    .frame(height: 12)
                                Spacer()
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                                    .frame(width: 30, height: 12)
                            }
                        }
                    }
                    .padding(16)
                    .background(Color.white.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
            .padding()
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                shimmer = true
            }
        }
    }
}
