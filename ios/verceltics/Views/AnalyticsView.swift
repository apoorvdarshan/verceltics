import SwiftUI

@Observable
@MainActor
final class AnalyticsViewModel {
    let project: Project

    var data = AnalyticsData()
    var selectedRange: TimeRange = .week
    var selectedEnvironment: VercelEnvironment = .production
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
        let from = range.fromDate
        let to = range.toDate
        let prevFrom = range.previousFromDate
        let prevTo = range.previousToDate

        do {
            async let overview = api.fetchOverview(projectId: pid, teamId: tid, from: from, to: to)
            async let previous = api.fetchPreviousOverview(projectId: pid, teamId: tid, from: prevFrom, to: prevTo)
            async let timeseries = api.fetchTimeseries(projectId: pid, teamId: tid, from: from, to: to)
            async let pages = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "path")
            async let referrers = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "referrer")
            async let countries = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "country")
            async let devices = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "device_type")
            async let browsers = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "client_name")
            async let os = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "os_name")
            async let utmSources = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "utm")
            async let routes = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "route")
            async let hostnames = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "hostname")
            async let events = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "event_name")
            async let flags = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "flags")
            async let queryParams = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "query_params")

            data.overview = try await overview
            data.previousOverview = try? await previous
            data.timeseries = try await timeseries
            data.pages = try await pages
            data.referrers = try await referrers
            data.countries = try await countries
            data.devices = (try? await devices) ?? []
            data.browsers = (try? await browsers) ?? []
            data.os = (try? await os) ?? []
            data.utmSources = (try? await utmSources) ?? []
            data.routes = (try? await routes) ?? []
            data.hostnames = (try? await hostnames) ?? []
            data.events = (try? await events) ?? []
            data.flags = (try? await flags) ?? []
            data.queryParams = (try? await queryParams) ?? []

        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

struct AnalyticsView: View {
    let project: Project
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var vm: AnalyticsViewModel
    @State private var lastUpdated: Date?
    @State private var refreshSpin: Double = 0

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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    withAnimation(.easeInOut(duration: 0.6)) {
                        refreshSpin += 360
                    }
                    Task { await loadData() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14, weight: .heavy))
                        .rotationEffect(.degrees(refreshSpin))
                        .foregroundStyle(.white.opacity(0.7))
                }
                .disabled(vm.isLoading)
                .sensoryFeedback(.impact(weight: .light), trigger: refreshSpin)
            }
        }
        .task { await loadData() }
        .onChange(of: vm.selectedRange) {
            Task { await loadData() }
        }
        .sensoryFeedback(.selection, trigger: vm.selectedRange)
    }

    private var breakdownColumns: [GridItem] {
        hSize == .regular
            ? [GridItem(.adaptive(minimum: 320, maximum: 480), spacing: 16)]
            : [GridItem(.flexible())]
    }

    private var analyticsContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                statsCards

                AnalyticsChart(data: vm.data.timeseries)
                    .frame(height: 260)
                    .padding(18)
                    .background(
                        ZStack {
                            LinearGradient(
                                colors: [Color.white.opacity(0.06), Color.white.opacity(0.02)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                            LinearGradient(
                                colors: [Color.blue.opacity(0.04), .clear],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )
                            LinearGradient(
                                colors: [Color.white.opacity(0.04), .clear],
                                startPoint: .top, endPoint: .center
                            )
                        }
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.14), Color.white.opacity(0.04)],
                                    startPoint: .top, endPoint: .bottom
                                ),
                                lineWidth: 0.5
                            )
                    )

                LazyVGrid(columns: breakdownColumns, spacing: 16) {
                    breakdownCard(title: "Pages", icon: "doc.text", items: vm.data.pages, isPath: true)
                    breakdownCard(title: "Routes", icon: "arrow.triangle.branch", items: vm.data.routes, isPath: true)
                    breakdownCard(title: "Hostnames", icon: "server.rack", items: vm.data.hostnames)

                    breakdownCard(title: "Referrers", icon: "link", items: vm.data.referrers, emptyLabel: "Direct")
                    breakdownCard(title: "UTM Parameters", icon: "tag", items: vm.data.utmSources, proHint: "Pro + Analytics Plus")

                    breakdownCard(title: "Countries", icon: "globe.americas", items: vm.data.countries, isCountry: true)

                    breakdownCard(title: "Devices", icon: "desktopcomputer", items: vm.data.devices, isPercentage: true)
                    breakdownCard(title: "Browsers", icon: "safari", items: vm.data.browsers, isPercentage: true)
                    breakdownCard(title: "Operating Systems", icon: "laptopcomputer", items: vm.data.os, isPercentage: true)

                    breakdownCard(title: "Events", icon: "bolt.fill", items: vm.data.events, proHint: "Pro")
                    breakdownCard(title: "Flags", icon: "flag.fill", items: vm.data.flags)
                    breakdownCard(title: "Query Parameters", icon: "questionmark.circle", items: vm.data.queryParams)
                }
            }
            .padding()
            .frame(maxWidth: hSize == .regular ? 1100 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await loadData() }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                if let domain = project.primaryDomain {
                    HStack(spacing: 5) {
                        Image(systemName: "link")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(.white.opacity(0.3))
                        Text(domain)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.5))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer()
            }

            HStack(spacing: 8) {
                Menu {
                    ForEach(TimeRange.allCases) { range in
                        Button {
                            vm.selectedRange = range
                        } label: {
                            HStack {
                                Text(range.label)
                                if range.isPro {
                                    Image(systemName: "lock.fill")
                                }
                                if vm.selectedRange == range {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "calendar")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.5))
                        Text(vm.selectedRange.label)
                            .font(.system(size: 13, weight: .bold))
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(
                        LinearGradient(
                            colors: [Color.white.opacity(0.10), Color.white.opacity(0.04)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5))
                }
                .buttonStyle(PressScaleButtonStyle())

                Spacer()

                if let lastUpdated {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(Color(red: 0.30, green: 0.85, blue: 0.55))
                            .frame(width: 5, height: 5)
                        Text("Updated \(lastUpdated.formatted(.relative(presentation: .named)))")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .transition(.opacity)
                }
            }
        }
        .padding(.bottom, 2)
    }

    // MARK: - Stats Cards

    private var statsCards: some View {
        HStack(spacing: 10) {
            StatCard(
                title: "Visitors",
                value: formatNumber(vm.data.overview?.devices ?? 0),
                change: vm.data.visitorsChange,
                icon: "person.2",
                appearDelay: 0.0
            )
            StatCard(
                title: "Page Views",
                value: formatNumber(vm.data.overview?.total ?? 0),
                change: vm.data.pageViewsChange,
                icon: "eye",
                appearDelay: 0.06
            )
            StatCard(
                title: "Bounce Rate",
                value: "\(vm.data.overview?.bounceRate ?? 0)%",
                change: vm.data.bounceRateChange,
                invertChange: true,
                icon: "arrow.uturn.left",
                appearDelay: 0.12
            )
        }
    }

    // MARK: - Breakdown Card

    private func breakdownCard(
        title: String,
        icon: String,
        items: [BreakdownItem],
        emptyLabel: String = "",
        isPath: Bool = false,
        isCountry: Bool = false,
        isPercentage: Bool = false,
        proHint: String? = nil
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .heavy))
                        .foregroundStyle(.blue)
                        .frame(width: 22, height: 22)
                        .background(Color.blue.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    Text(title)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.white)
                }
                Spacer()
                Text("VISITORS")
                    .font(.system(size: 9, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.35))
                    .tracking(1.0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

            Divider().overlay(Color.white.opacity(0.06))

            if items.isEmpty {
                if let proHint {
                    VStack(spacing: 8) {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(.white.opacity(0.2))
                        Text("Requires \(proHint)")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white.opacity(0.7))
                        Text("Upgrade your Vercel plan")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.35))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                } else {
                    Text("No data available")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.3))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 28)
                }
            } else {
                let total = items.reduce(0) { $0 + $1.visitors }
                let maxVal = items.first?.visitors ?? 1
                ForEach(items.prefix(8)) { item in
                    HStack(spacing: 0) {
                        ZStack(alignment: .leading) {
                            GeometryReader { geo in
                                RoundedRectangle(cornerRadius: 5)
                                    .fill(
                                        LinearGradient(
                                            colors: [Color.blue.opacity(0.18), Color.blue.opacity(0.06)],
                                            startPoint: .leading, endPoint: .trailing
                                        )
                                    )
                                    .frame(width: geo.size.width * CGFloat(item.visitors) / CGFloat(maxVal))
                            }
                            HStack(spacing: 7) {
                                if isCountry {
                                    Text(countryFlag(item.key))
                                        .font(.system(size: 13))
                                }
                                Text(displayName(item.key, emptyLabel: emptyLabel, isCountry: isCountry))
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 12)
                        }
                        .frame(height: 36)

                        if isCountry || isPercentage {
                            Text(total > 0 ? "\(item.visitors * 100 / total)%" : "0%")
                                .font(.system(size: 12, weight: .bold).monospacedDigit())
                                .foregroundStyle(.white.opacity(0.55))
                                .frame(width: 50, alignment: .trailing)
                                .padding(.trailing, 12)
                        } else {
                            Text("\(item.visitors)")
                                .font(.system(size: 12, weight: .bold).monospacedDigit())
                                .foregroundStyle(.white.opacity(0.55))
                                .frame(width: 50, alignment: .trailing)
                                .padding(.trailing, 12)
                        }
                    }
                }
                Spacer().frame(height: 4)
            }
        }
        .background(
            ZStack {
                LinearGradient(
                    colors: [Color.white.opacity(0.06), Color.white.opacity(0.02)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [Color.white.opacity(0.04), .clear],
                    startPoint: .top,
                    endPoint: .center
                )
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                        startPoint: .top, endPoint: .bottom
                    ),
                    lineWidth: 0.5
                )
        )
    }

    // MARK: - Helpers

    private func loadData() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
        lastUpdated = Date()
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
    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.white.opacity(0.06))
                        .frame(width: 100, height: 34)
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.white.opacity(0.06))
                        .frame(width: 120, height: 34)
                    Spacer()
                }

                HStack(spacing: 10) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(0.06))
                                .frame(width: 60, height: 12)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(0.08))
                                .frame(width: 44, height: 24)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(
                            LinearGradient(
                                colors: [Color.white.opacity(0.05), Color.white.opacity(0.02)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }

                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.05), Color.white.opacity(0.02)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(height: 250)

                ForEach(0..<4, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.white.opacity(0.06))
                            .frame(width: 80, height: 14)
                        ForEach(0..<4, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(0.04))
                                .frame(height: 36)
                        }
                    }
                    .padding(16)
                    .background(
                        LinearGradient(
                            colors: [Color.white.opacity(0.05), Color.white.opacity(0.02)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .padding()
            .shimmering()
        }
    }
}
