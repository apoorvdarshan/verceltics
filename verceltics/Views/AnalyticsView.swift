import SwiftUI

@Observable
@MainActor
final class AnalyticsViewModel {
    let project: Project

    var data = AnalyticsData()
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
        let pid = project.id
        let tid = project.teamId
        let range = selectedRange

        do {
            async let overview = api.fetchOverview(projectId: pid, teamId: tid, range: range)
            async let previous = api.fetchPreviousOverview(projectId: pid, teamId: tid, range: range)
            async let timeseries = api.fetchTimeseries(projectId: pid, teamId: tid, range: range)
            async let pages = api.fetchBreakdown(projectId: pid, teamId: tid, range: range, groupBy: "path")
            async let referrers = api.fetchBreakdown(projectId: pid, teamId: tid, range: range, groupBy: "referrer")
            async let countries = api.fetchBreakdown(projectId: pid, teamId: tid, range: range, groupBy: "country")

            data.overview = try await overview
            data.previousOverview = try? await previous
            data.timeseries = try await timeseries
            data.pages = try await pages
            data.referrers = try await referrers
            data.countries = try await countries
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
            VStack(spacing: 16) {
                // Domain subtitle
                if let domain = project.primaryDomain {
                    HStack(spacing: 4) {
                        Image(systemName: "globe")
                            .font(.caption2)
                        Text(domain)
                            .font(.caption)
                    }
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                timeRangePicker
                statsCards

                AnalyticsChart(data: vm.data.timeseries)
                    .frame(height: 220)
                    .padding(16)
                    .background(Color.white.opacity(0.04))
                    .clipShape(RoundedRectangle(cornerRadius: 16))

                // Pages & Referrers side by side on larger screens, stacked on phone
                breakdownSection(title: "Pages", icon: "doc.text", items: vm.data.pages, isPath: true)
                breakdownSection(title: "Referrers", icon: "link", items: vm.data.referrers, emptyLabel: "Direct")
                breakdownSection(title: "Countries", icon: "globe.americas", items: vm.data.countries, isCountry: true)
            }
            .padding()
        }
        .refreshable { await loadData() }
    }

    // MARK: - Time Range Picker

    private var timeRangePicker: some View {
        HStack(spacing: 6) {
            ForEach(TimeRange.allCases) { range in
                Button {
                    withAnimation(.snappy(duration: 0.2)) {
                        vm.selectedRange = range
                    }
                } label: {
                    Text(range.label)
                        .font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(vm.selectedRange == range ? Color.white : Color.clear)
                        .foregroundStyle(vm.selectedRange == range ? .black : .gray)
                        .clipShape(Capsule())
                }
            }
            Spacer()
        }
        .padding(4)
        .background(Color.white.opacity(0.06))
        .clipShape(Capsule())
    }

    // MARK: - Stats Cards

    private var statsCards: some View {
        HStack(spacing: 10) {
            StatCard(
                title: "Visitors",
                value: formatNumber(vm.data.overview?.devices ?? 0),
                change: vm.data.visitorsChange,
                icon: "person.2"
            )
            StatCard(
                title: "Page Views",
                value: formatNumber(vm.data.overview?.total ?? 0),
                change: vm.data.pageViewsChange,
                icon: "eye"
            )
            StatCard(
                title: "Bounce Rate",
                value: "\(vm.data.overview?.bounceRate ?? 0)%",
                change: vm.data.bounceRateChange,
                invertChange: true,
                icon: "arrow.uturn.left"
            )
        }
    }

    // MARK: - Breakdown Section

    private func breakdownSection(
        title: String,
        icon: String,
        items: [BreakdownItem],
        emptyLabel: String = "",
        isPath: Bool = false,
        isCountry: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundStyle(.gray)
                Text(title)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Spacer()
                Text("VISITORS")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.gray)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            Divider().overlay(Color.white.opacity(0.06))

            if items.isEmpty {
                Text("No data available")
                    .font(.caption)
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else {
                let maxValue = items.first?.visitors ?? 1
                ForEach(items.prefix(8)) { item in
                    HStack(spacing: 0) {
                        ZStack(alignment: .leading) {
                            GeometryReader { geo in
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color.white.opacity(0.06))
                                    .frame(width: geo.size.width * CGFloat(item.visitors) / CGFloat(maxValue))
                            }
                            HStack(spacing: 6) {
                                if isCountry {
                                    Text(countryFlag(item.key))
                                        .font(.caption)
                                }
                                Text(displayName(item.key, emptyLabel: emptyLabel, isCountry: isCountry))
                                    .font(.system(size: 13))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 10)
                        }
                        .frame(height: 36)

                        Text("\(item.visitors)")
                            .font(.system(size: 13, weight: .medium).monospacedDigit())
                            .foregroundStyle(.gray)
                            .frame(width: 50, alignment: .trailing)
                            .padding(.trailing, 10)
                    }
                }
            }
        }
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 16))
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

    private func displayName(_ key: String, emptyLabel: String, isCountry: Bool) -> String {
        if key.isEmpty { return emptyLabel.isEmpty ? "Unknown" : emptyLabel }
        if isCountry { return countryName(key) }
        return key
    }

    private func countryFlag(_ code: String) -> String {
        guard code.count == 2 else { return "" }
        let base: UInt32 = 127397
        return code.uppercased().unicodeScalars.compactMap { UnicodeScalar(base + $0.value) }.map { String($0) }.joined()
    }

    private func countryName(_ code: String) -> String {
        Locale.current.localizedString(forRegionCode: code) ?? code
    }
}

// MARK: - Skeleton

struct AnalyticsSkeletonView: View {
    @State private var shimmer = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack(spacing: 6) {
                    ForEach(0..<4, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 20)
                            .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                            .frame(width: 50, height: 32)
                    }
                    Spacer()
                }

                HStack(spacing: 10) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                                .frame(width: 60, height: 12)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(shimmer ? 0.12 : 0.06))
                                .frame(width: 44, height: 24)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Color.white.opacity(0.04))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }

                RoundedRectangle(cornerRadius: 16)
                    .fill(Color.white.opacity(0.04))
                    .frame(height: 250)

                ForEach(0..<3, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                            .frame(width: 80, height: 14)
                        ForEach(0..<4, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(shimmer ? 0.06 : 0.03))
                                .frame(height: 36)
                        }
                    }
                    .padding(16)
                    .background(Color.white.opacity(0.04))
                    .clipShape(RoundedRectangle(cornerRadius: 16))
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
