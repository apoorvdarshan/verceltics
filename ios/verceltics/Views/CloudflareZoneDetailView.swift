import Charts
import SwiftUI

@Observable
@MainActor
final class CloudflareZoneDetailViewModel {
    private struct CachedZoneAnalytics {
        let analytics: CloudflareZoneAnalyticsSummary
        let analyticsBreakdowns: CloudflareZoneAnalyticsBreakdowns?
    }

    private static var analyticsCache: [String: CachedZoneAnalytics] = [:]
    private static var dnsCache: [String: [CloudflareDNSRecord]] = [:]

    let api: CloudflareAPI
    let zone: CloudflareZone

    var analytics: CloudflareZoneAnalyticsSummary?
    var analyticsBreakdowns: CloudflareZoneAnalyticsBreakdowns?
    var dnsRecords: [CloudflareDNSRecord] = []
    var isLoading = true
    var analyticsError: String?
    var dnsError: String?
    var workingResourceID: String?
    var actionMessage: String?
    var actionFailed = false
    var selectedAnalyticsRange: CloudflareAnalyticsRange = .days7
    var customAnalyticsFrom = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
    var customAnalyticsTo = Date()
    private var loadGeneration = 0
    private var hasLoadedAnalytics = false
    private var hasLoadedDNS = false

    private var dnsCacheKey: String { "\(api.cacheScope)|\(zone.id)|dns" }

    private var analyticsCacheKey: String {
        Self.analyticsCacheKey(
            credentialScope: api.cacheScope,
            zoneID: zone.id,
            range: selectedAnalyticsRange,
            customFrom: customAnalyticsFrom,
            customTo: customAnalyticsTo
        )
    }

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
        if let cached = Self.analyticsCache[
            Self.analyticsCacheKey(
                credentialScope: api.cacheScope,
                zoneID: zone.id,
                range: .days7,
                customFrom: customAnalyticsFrom,
                customTo: customAnalyticsTo
            )
        ] {
            analytics = cached.analytics
            analyticsBreakdowns = cached.analyticsBreakdowns
            hasLoadedAnalytics = true
        }
        if let cachedDNS = Self.dnsCache["\(api.cacheScope)|\(zone.id)|dns"] {
            dnsRecords = cachedDNS
            hasLoadedDNS = true
        }
        isLoading = !hasLoadedAnalytics && !hasLoadedDNS
    }

    func load(forceRefresh: Bool = false) async {
        if hasLoadedAnalytics && hasLoadedDNS && !forceRefresh { return }
        loadGeneration += 1
        let generation = loadGeneration
        let requestedAnalyticsCacheKey = analyticsCacheKey
        isLoading = analytics == nil && dnsRecords.isEmpty
        analyticsError = nil
        dnsError = nil

        let dates = selectedAnalyticsRange.dates() ?? (customAnalyticsFrom, customAnalyticsTo)
        let from = dates.from
        let to = dates.to

        async let analyticsResult = capture {
            try await api.fetchZoneAnalytics(zoneID: zone.id, from: from, to: to)
        }
        async let dnsResult = capture {
            try await api.fetchDNSRecords(zoneID: zone.id)
        }
        async let breakdownResult = capture {
            try await api.fetchZoneAnalyticsBreakdowns(zoneID: zone.id, from: from, to: to)
        }

        let (analyticsResponse, dnsResponse, breakdownResponse) = await (
            analyticsResult,
            dnsResult,
            breakdownResult
        )
        guard generation == loadGeneration else { return }
        if isCancellation(analyticsResponse)
            || isCancellation(dnsResponse)
            || isCancellation(breakdownResponse) {
            isLoading = false
            return
        }

        var receivedFreshAnalytics = false
        switch analyticsResponse {
        case .success(let analytics):
            self.analytics = analytics
            hasLoadedAnalytics = true
            receivedFreshAnalytics = true
        case .failure(let error):
            if !isCancellation(error) { analyticsError = error.localizedDescription }
        }

        switch dnsResponse {
        case .success(let records):
            dnsRecords = records.sorted {
                if $0.name == $1.name { return $0.type < $1.type }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
            hasLoadedDNS = true
            Self.dnsCache[dnsCacheKey] = dnsRecords
        case .failure(let error):
            if !isCancellation(error) { dnsError = error.localizedDescription }
        }

        if receivedFreshAnalytics, let analytics {
            if case .success(let breakdowns) = breakdownResponse {
                analyticsBreakdowns = breakdowns
            } else {
                analyticsBreakdowns = nil
            }
            Self.analyticsCache[requestedAnalyticsCacheKey] = CachedZoneAnalytics(
                analytics: analytics,
                analyticsBreakdowns: analyticsBreakdowns
            )
        }
        isLoading = false
    }

    func selectAnalyticsRange(_ range: CloudflareAnalyticsRange) async {
        selectedAnalyticsRange = range
        restoreAnalyticsCacheForSelectedRange()
        await load(forceRefresh: true)
    }

    func selectCustomAnalyticsRange(from: Date, to: Date) async {
        customAnalyticsFrom = from
        customAnalyticsTo = to
        selectedAnalyticsRange = .custom
        restoreAnalyticsCacheForSelectedRange()
        await load(forceRefresh: true)
    }

    func saveDNSRecord(existing: CloudflareDNSRecord?, input: CloudflareDNSRecordInput) async throws {
        let resourceID = existing?.id ?? "new-dns-record"
        workingResourceID = resourceID
        defer { workingResourceID = nil }

        do {
            if let existing {
                _ = try await api.updateDNSRecord(
                    zoneID: zone.id,
                    recordID: existing.id,
                    record: input,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: existing.id)
                )
                actionMessage = "DNS record updated."
            } else {
                _ = try await api.createDNSRecord(
                    zoneID: zone.id,
                    record: input,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: zone.id)
                )
                actionMessage = "DNS record created."
            }
            actionFailed = false
            dnsRecords = try await api.fetchDNSRecords(zoneID: zone.id)
            hasLoadedDNS = true
            Self.dnsCache[dnsCacheKey] = dnsRecords
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }
    }

    func deleteDNSRecord(_ record: CloudflareDNSRecord) async {
        workingResourceID = record.id
        defer { workingResourceID = nil }

        do {
            try await api.deleteDNSRecord(
                zoneID: zone.id,
                recordID: record.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: record.id)
            )
            actionMessage = "DNS record deleted."
            actionFailed = false
            dnsRecords.removeAll { $0.id == record.id }
            hasLoadedDNS = true
            Self.dnsCache[dnsCacheKey] = dnsRecords
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
    }

    func purgeCache(_ purge: CloudflareCachePurge) async throws {
        workingResourceID = "cache-purge"
        defer { workingResourceID = nil }

        do {
            try await api.purgeCache(
                zoneID: zone.id,
                purge: purge,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: zone.id)
            )
            actionMessage = "Cache purge accepted by Cloudflare."
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }
    }

    private func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }

    private func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func isCancellation<T>(_ result: Result<T, Error>) -> Bool {
        guard case .failure(let error) = result else { return false }
        return isCancellation(error)
    }

    private func restoreAnalyticsCacheForSelectedRange() {
        if let cached = Self.analyticsCache[analyticsCacheKey] {
            analytics = cached.analytics
            analyticsBreakdowns = cached.analyticsBreakdowns
            hasLoadedAnalytics = true
        } else {
            analytics = nil
            analyticsBreakdowns = nil
            hasLoadedAnalytics = false
        }
    }

    private static func analyticsCacheKey(
        credentialScope: String,
        zoneID: String,
        range: CloudflareAnalyticsRange,
        customFrom: Date,
        customTo: Date
    ) -> String {
        let rangeScope: String
        if range == .custom {
            rangeScope = "custom|\(customFrom.timeIntervalSinceReferenceDate.bitPattern)|\(customTo.timeIntervalSinceReferenceDate.bitPattern)"
        } else {
            rangeScope = range.rawValue
        }
        return "\(credentialScope)|\(zoneID)|analytics|\(rangeScope)"
    }
}

struct CloudflareZoneDetailView: View {
    let api: CloudflareAPI
    let zone: CloudflareZone

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareZoneDetailViewModel
    @State private var searchText = ""
    @State private var editingRecord: DNSRecordSheetItem?
    @State private var deletingRecord: CloudflareDNSRecord?
    @State private var showingPurgeCache = false
    @State private var showingCustomAnalyticsRange = false

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
        _viewModel = State(wrappedValue: CloudflareZoneDetailViewModel(api: api, zone: zone))
    }

    private var filteredDNSRecords: [CloudflareDNSRecord] {
        guard !searchText.isEmpty else { return viewModel.dnsRecords }
        return viewModel.dnsRecords.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.type.localizedCaseInsensitiveContains(searchText) ||
            ($0.content?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            ($0.comment?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            $0.tags.contains { $0.localizedCaseInsensitiveContains(searchText) }
        }
    }

    private var metricColumns: [GridItem] {
        if horizontalSizeClass == .regular {
            return AppLayout.adaptiveColumns(
                for: horizontalSizeClass,
                regularMinimum: 200,
                regularMaximum: 250,
                spacing: 10
            )
        }
        return [GridItem(.flexible()), GridItem(.flexible())]
    }

    private var breakdownColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 360,
            regularMaximum: 520,
            spacing: 12
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    zoneHeader
                    controlCenterLinks
                    analyticsRangeRail

                    if let analytics = viewModel.analytics {
                        analyticsSection(analytics)
                        if let breakdowns = viewModel.analyticsBreakdowns {
                            analyticsBreakdownSection(breakdowns)
                        }
                    } else if let error = viewModel.analyticsError {
                        unavailableCard(title: "Analytics unavailable", message: error)
                    }

                    zoneDetails
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    dnsSection
                }
                .padding(AppLayout.pagePadding(for: horizontalSizeClass))
                .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(zone.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search DNS records")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .sheet(item: $editingRecord) { item in
            NavigationStack {
                CloudflareDNSRecordEditor(
                    zoneName: zone.name,
                    record: item.record,
                    onSave: { input in
                        try await viewModel.saveDNSRecord(existing: item.record, input: input)
                    }
                )
            }
            .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $showingPurgeCache) {
            NavigationStack {
                CloudflareCachePurgeView(zone: zone) { purge in
                    try await viewModel.purgeCache(purge)
                }
            }
            .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $showingCustomAnalyticsRange) {
            NavigationStack {
                CloudflareCustomAnalyticsRangeView(
                    initialFrom: viewModel.customAnalyticsFrom,
                    initialTo: viewModel.customAnalyticsTo
                ) { from, to in
                    await viewModel.selectCustomAnalyticsRange(from: from, to: to)
                }
            }
            .preferredColorScheme(.dark)
        }
        .confirmationDialog(
            "Delete this DNS record?",
            isPresented: Binding(
                get: { deletingRecord != nil },
                set: { if !$0 { deletingRecord = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let record = deletingRecord {
                Button("Delete \(record.type) Record", role: .destructive) {
                    let selected = record
                    deletingRecord = nil
                    Task { await viewModel.deleteDNSRecord(selected) }
                }
                Button("Cancel", role: .cancel) { deletingRecord = nil }
            }
        } message: {
            if let record = deletingRecord {
                Text("\(record.name) → \(record.content ?? "structured data") will be permanently removed.")
            }
        }
        .tint(CloudflareStyle.orange)
    }

    private var controlCenterLinks: some View {
        VStack(spacing: 0) {
            NavigationLink {
                CloudflareZoneOperationsView(api: api, zone: zone)
            } label: {
                CloudflareResourceRow(
                    icon: "switch.2",
                    title: "Zone & DNS operations",
                    subtitle: "DNSSEC, settings, activation, quotas and DNS analytics",
                    tint: CloudflareStyle.orange
                )
            }
            .buttonStyle(.plain)

            Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)

            NavigationLink {
                CloudflareSecurityCenterView(api: api, zone: zone)
            } label: {
                CloudflareResourceRow(
                    icon: "lock.shield.fill",
                    title: "Security center",
                    subtitle: "WAF, firewall, rate limits, certificates, bots and API Shield",
                    tint: CloudflareStyle.amber
                )
            }
            .buttonStyle(.plain)
        }
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var analyticsRangeRail: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Traffic interval", systemImage: "clock.arrow.trianglehead.counterclockwise.rotate.90")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textSecondary)
                Spacer()
                if viewModel.isLoading {
                    ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(CloudflareAnalyticsRange.allCases) { range in
                        Button {
                            if range == .custom {
                                showingCustomAnalyticsRange = true
                            } else {
                                Task { await viewModel.selectAnalyticsRange(range) }
                            }
                        } label: {
                            Text(range.displayName)
                                .font(.caption.weight(.semibold).monospacedDigit())
                                .foregroundStyle(
                                    viewModel.selectedAnalyticsRange == range ? .black : AppTheme.textSecondary
                                )
                                .padding(.horizontal, 13)
                                .frame(height: 34)
                                .background(
                                    viewModel.selectedAnalyticsRange == range
                                        ? CloudflareStyle.orange
                                        : Color.white.opacity(0.055),
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.isLoading)
                    }
                }
            }

            Text("Cloudflare automatically applies this zone’s retention and query-width limits.")
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
        }
        .padding(14)
        .cloudflarePanel(accentOpacity: 0.045)
    }

    private var zoneHeader: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(alignment: .top, spacing: 13) {
                AppIconTile(icon: "globe.americas.fill", tint: CloudflareStyle.orange, size: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text(zone.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(zone.plan?.name ?? zone.type?.capitalized ?? "Cloudflare zone")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AppTheme.textSecondary)
                }

                Spacer(minLength: 8)
                CloudflareStatusPill(
                    text: (zone.paused == true ? "PAUSED" : zone.status?.uppercased()) ?? "UNKNOWN",
                    color: zone.isActive ? CloudflareStyle.green : CloudflareStyle.amber
                )
            }

            HStack(spacing: 9) {
                if let url = URL(string: "https://\(zone.name)") {
                    openButton("Open site", icon: "arrow.up.right", url: url)
                }
                if let accountID = zone.account?.id,
                   !accountID.isEmpty,
                   let url = URL(string: "https://dash.cloudflare.com/\(accountID)/\(zone.name)") {
                    openButton("Dashboard", icon: "safari", url: url)
                }
                CloudflareActionButton(
                    title: "Purge cache",
                    icon: "arrow.triangle.2.circlepath",
                    isWorking: viewModel.workingResourceID == "cache-purge"
                ) {
                    showingPurgeCache = true
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private func analyticsSection(_ analytics: CloudflareZoneAnalyticsSummary) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Text(analytics.chartTitle)
                    .font(.caption.weight(.semibold))
                    .tracking(0.8)
                    .foregroundStyle(AppTheme.textSecondary)
                Spacer()
                Text(analytics.granularity.displayName)
                    .font(.caption2.weight(.semibold))
                    .tracking(0.7)
                    .foregroundStyle(CloudflareStyle.orange)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(CloudflareStyle.orange.opacity(0.1), in: Capsule())
            }
            .padding(.horizontal, 4)

            if analytics.isWindowLimited {
                Label(
                    "Cloudflare shortened this range to fit the zone's analytics limit",
                    systemImage: "clock.badge.checkmark"
                )
                .font(.footnote.weight(.medium))
                .foregroundStyle(CloudflareStyle.amber)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
            }

            LazyVGrid(
                columns: metricColumns,
                spacing: 10
            ) {
                CloudflareMetricCard(
                    title: "Requests",
                    value: analytics.totals.requests.formatted(.number.notation(.compactName)),
                    icon: "arrow.left.arrow.right"
                )
                CloudflareMetricCard(
                    title: "Visitors",
                    value: analytics.totals.uniqueVisitors.formatted(.number.notation(.compactName)),
                    icon: "person.2.fill",
                    accent: CloudflareStyle.amber
                )
                CloudflareMetricCard(
                    title: "Bandwidth",
                    value: ByteCountFormatter.string(fromByteCount: analytics.totals.bytes, countStyle: .file),
                    icon: "network"
                )
                CloudflareMetricCard(
                    title: "Cache hit",
                    value: analytics.totals.cacheHitRate.map { "\($0.formatted(.number.precision(.fractionLength(1))))%" } ?? "—",
                    icon: "bolt.horizontal.fill",
                    accent: CloudflareStyle.green
                )
                CloudflareMetricCard(
                    title: "Page views",
                    value: analytics.totals.pageViews.formatted(.number.notation(.compactName)),
                    icon: "eye.fill",
                    accent: CloudflareStyle.amber
                )
                CloudflareMetricCard(
                    title: "Cached bytes",
                    value: ByteCountFormatter.string(fromByteCount: analytics.totals.cachedBytes, countStyle: .file),
                    icon: "externaldrive.fill",
                    accent: CloudflareStyle.green
                )
                CloudflareMetricCard(
                    title: "Threats",
                    value: analytics.totals.threats.formatted(.number.notation(.compactName)),
                    icon: "shield.lefthalf.filled.badge.checkmark",
                    accent: analytics.totals.threats > 0 ? CloudflareStyle.red : CloudflareStyle.green
                )
                CloudflareMetricCard(
                    title: "HTTPS",
                    value: analytics.totals.encryptedRequestRate.map { "\($0.formatted(.number.precision(.fractionLength(1))))%" } ?? "—",
                    icon: "lock.fill",
                    accent: CloudflareStyle.green
                )
            }

            if !analytics.series.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("TRAFFIC TREND")
                            .font(.caption.weight(.semibold))
                            .tracking(0.8)
                            .foregroundStyle(AppTheme.textSecondary)
                        Spacer()
                        Text(analytics.totals.pageViews.formatted() + " page views")
                            .font(.caption.weight(.medium).monospacedDigit())
                            .foregroundStyle(AppTheme.textSecondary)
                    }

                    Chart(analytics.series) { point in
                        AreaMark(
                            x: .value("Time", point.timestamp),
                            y: .value("Requests", point.metrics.requests)
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: [CloudflareStyle.orange.opacity(0.28), .clear],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )

                        LineMark(
                            x: .value("Time", point.timestamp),
                            y: .value("Requests", point.metrics.requests)
                        )
                        .foregroundStyle(CloudflareStyle.orange)
                        .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
                    }
                    .chartXAxis {
                        AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                            AxisValueLabel(format: .dateTime.weekday(.abbreviated))
                                .font(.caption2)
                                .foregroundStyle(AppTheme.textSecondary)
                            AxisGridLine().foregroundStyle(.white.opacity(0.04))
                        }
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { _ in
                            AxisValueLabel()
                                .font(.caption2)
                                .foregroundStyle(AppTheme.textSecondary)
                            AxisGridLine().foregroundStyle(.white.opacity(0.05))
                        }
                    }
                    .frame(height: horizontalSizeClass == .regular ? 300 : 210)
                }
                .padding(16)
                .cloudflarePanel(accentOpacity: 0.055)
            }
        }
    }

    @ViewBuilder
    private func analyticsBreakdownSection(_ breakdowns: CloudflareZoneAnalyticsBreakdowns) -> some View {
        let sections: [(String, String, [CloudflareAnalyticsBreakdownItem])] = [
            ("Countries", "globe.americas.fill", breakdowns.countries),
            ("Status codes", "number.square.fill", breakdowns.statusCodes),
            ("Content types", "doc.fill", breakdowns.contentTypes),
            ("TLS protocols", "lock.shield.fill", breakdowns.tlsProtocols),
            ("Browsers", "safari.fill", breakdowns.browsers),
            ("IP classes", "network", breakdowns.ipClasses),
            ("Threat paths", "shield.lefthalf.filled", breakdowns.threatTypes)
        ].filter { !$0.2.isEmpty }

        if !sections.isEmpty || breakdowns.encryptedBytes > 0 {
            VStack(spacing: 12) {
                HStack {
                    Text("TRAFFIC BREAKDOWNS")
                        .font(.caption.weight(.semibold))
                        .tracking(0.8)
                        .foregroundStyle(AppTheme.textSecondary)
                    Spacer()
                    if breakdowns.encryptedBytes > 0 {
                        Text(ByteCountFormatter.string(fromByteCount: breakdowns.encryptedBytes, countStyle: .file) + " encrypted")
                            .font(.caption2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(CloudflareStyle.green)
                    }
                }
                .padding(.horizontal, 4)

                LazyVGrid(columns: breakdownColumns, alignment: .leading, spacing: 12) {
                    ForEach(sections, id: \.0) { section in
                        analyticsBreakdownPanel(title: section.0, icon: section.1, items: section.2)
                    }
                }
            }
        }
    }

    private func analyticsBreakdownPanel(
        title: String,
        icon: String,
        items: [CloudflareAnalyticsBreakdownItem]
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon, count: items.count)
            Divider().overlay(Color.white.opacity(0.06))
            ForEach(Array(items.prefix(12))) { item in
                CloudflareResourceRow(
                    icon: icon,
                    title: item.label,
                    subtitle: analyticsBreakdownSubtitle(item),
                    tint: item.threats > 0 ? CloudflareStyle.red : CloudflareStyle.orange
                ) {
                    Text(analyticsBreakdownValue(item))
                        .font(.footnote.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppTheme.textPrimary)
                }
                if item.id != items.prefix(12).last?.id {
                    Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 64)
                }
            }
        }
        .cloudflarePanel()
    }

    private func analyticsBreakdownValue(_ item: CloudflareAnalyticsBreakdownItem) -> String {
        let value = item.requests > 0 ? item.requests : item.pageViews
        return value.formatted(.number.notation(.compactName))
    }

    private func analyticsBreakdownSubtitle(_ item: CloudflareAnalyticsBreakdownItem) -> String? {
        var values: [String] = []
        if item.bytes > 0 {
            values.append(ByteCountFormatter.string(fromByteCount: item.bytes, countStyle: .file))
        }
        if item.threats > 0 {
            values.append("\(item.threats.formatted()) threats")
        }
        if item.pageViews > 0 {
            values.append("\(item.pageViews.formatted()) page views")
        }
        return values.isEmpty ? nil : values.joined(separator: " · ")
    }

    private var zoneDetails: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Zone", icon: "info.circle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "number", title: "Zone ID", value: zone.id)
            CloudflareDetailRow(icon: "building.2", title: "Account", value: zone.account?.name ?? "Unknown")
            CloudflareDetailRow(icon: "building.columns", title: "Registrar", value: zone.originalRegistrar ?? "Unknown")
            CloudflareDetailRow(
                icon: "server.rack",
                title: "Name servers",
                value: zone.nameServers.isEmpty ? "None returned" : zone.nameServers.joined(separator: ", ")
            )
            CloudflareDetailRow(
                icon: "hammer.fill",
                title: "Development mode",
                value: (zone.developmentMode ?? 0) > 0 ? "Active" : "Off"
            )
        }
        .cloudflarePanel()
    }

    private var dnsSection: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "DNS records",
                icon: "server.rack",
                count: filteredDNSRecords.count,
                actionTitle: "Add record"
            ) {
                editingRecord = DNSRecordSheetItem(record: nil)
            }
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.isLoading {
                ProgressView()
                    .tint(CloudflareStyle.orange)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 34)
            } else if let error = viewModel.dnsError {
                CloudflareEmptySection(
                    icon: "exclamationmark.triangle.fill",
                    title: "DNS unavailable",
                    message: error
                )
            } else if filteredDNSRecords.isEmpty {
                CloudflareEmptySection(
                    icon: searchText.isEmpty ? "server.rack" : "magnifyingglass",
                    title: searchText.isEmpty ? "No DNS records" : "No matches",
                    message: searchText.isEmpty
                        ? "Create a DNS record to begin routing this zone."
                        : "No DNS records match “\(searchText)”."
                )
            } else {
                ForEach(filteredDNSRecords, id: \.id) { record in
                    dnsRecordRow(record)
                    if record.id != filteredDNSRecords.last?.id {
                        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func dnsRecordRow(_ record: CloudflareDNSRecord) -> some View {
        HStack(spacing: 12) {
            Text(record.type)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(record.proxied == true ? CloudflareStyle.orange : .white.opacity(0.52))
                .frame(width: 42, height: 34)
                .background((record.proxied == true ? CloudflareStyle.orange : .white).opacity(0.09))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(record.name)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white.opacity(0.84))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if record.locked == true {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.3))
                    }
                }
                Text(record.content ?? "Structured record data")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.33))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 6)

            if viewModel.workingResourceID == record.id {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Menu {
                    Button {
                        editingRecord = DNSRecordSheetItem(record: record)
                    } label: {
                        Label("Edit record", systemImage: "pencil")
                    }

                    Button {
                        UIPasteboard.general.string = record.content ?? record.name
                    } label: {
                        Label("Copy value", systemImage: "doc.on.doc")
                    }

                    if record.locked != true {
                        Button(role: .destructive) {
                            deletingRecord = record
                        } label: {
                            Label("Delete record", systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.45))
                        .frame(width: 44, height: 44)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func unavailableCard(title: String, message: String) -> some View {
        CloudflareEmptySection(icon: "chart.xyaxis.line", title: title, message: message)
            .cloudflarePanel()
    }

    private func openButton(_ title: String, icon: String, url: URL) -> some View {
        Button {
            UIApplication.shared.open(url)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: icon).font(.caption.weight(.semibold))
                Text(title).font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(.white.opacity(0.78))
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(Color.white.opacity(0.07))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
    }
}

private struct DNSRecordSheetItem: Identifiable {
    let id = UUID()
    let record: CloudflareDNSRecord?
}

private struct CloudflareCustomAnalyticsRangeView: View {
    let onApply: (Date, Date) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var from: Date
    @State private var to: Date
    @State private var isApplying = false
    @State private var errorMessage: String?

    init(initialFrom: Date, initialTo: Date, onApply: @escaping (Date, Date) async -> Void) {
        self.onApply = onApply
        _from = State(initialValue: initialFrom)
        _to = State(initialValue: initialTo)
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("CUSTOM TRAFFIC WINDOW")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(CloudflareStyle.orange)
                        Text("Choose any range. Cloudflare will shorten it only when your zone’s plan or dataset retention requires it.")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.white.opacity(0.42))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .cloudflarePanel(accentOpacity: 0.07)

                    VStack(spacing: 0) {
                        DatePicker("From", selection: $from, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                            .padding(16)
                        Divider().overlay(Color.white.opacity(0.06))
                        DatePicker("To", selection: $to, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                            .padding(16)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.78))
                    .cloudflarePanel()

                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }

                    Button {
                        apply()
                    } label: {
                        HStack(spacing: 8) {
                            if isApplying { ProgressView().controlSize(.small).tint(.black) }
                            Text(isApplying ? "Loading range…" : "Apply range")
                                .font(.system(size: 14, weight: .semibold))
                        }
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(CloudflareStyle.orange, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(isApplying)
                }
                .padding()
            }
        }
        .navigationTitle("Custom interval")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private func apply() {
        errorMessage = nil
        guard from < to else {
            errorMessage = "The start must be earlier than the end."
            return
        }
        isApplying = true
        Task {
            await onApply(from, to)
            isApplying = false
            dismiss()
        }
    }
}
