import Charts
import SwiftUI

@Observable
@MainActor
final class CloudflareZoneDetailViewModel {
    private struct CachedZone {
        let zone: CloudflareZone
        let updatedAt: Date
    }

    private struct CachedZoneAnalytics {
        let analytics: CloudflareZoneAnalyticsSummary
        let analyticsBreakdowns: CloudflareZoneAnalyticsBreakdowns?
        let updatedAt: Date
    }

    private struct CachedDNSRecords {
        let records: [CloudflareDNSRecord]
        let updatedAt: Date
    }

    private struct AnalyticsLoadResult {
        let analytics: Result<CloudflareZoneAnalyticsSummary, Error>
        let breakdowns: Result<CloudflareZoneAnalyticsBreakdowns, Error>
    }

    private static var zoneCache: [String: CachedZone] = [:]
    private static var analyticsCache: [String: CachedZoneAnalytics] = [:]
    private static var dnsCache: [String: CachedDNSRecords] = [:]
    private static let cacheLifetime: TimeInterval = 180

    let api: CloudflareAPI
    let zoneID: String

    var zone: CloudflareZone
    var analytics: CloudflareZoneAnalyticsSummary?
    var analyticsBreakdowns: CloudflareZoneAnalyticsBreakdowns?
    var dnsRecords: [CloudflareDNSRecord] = []
    var isZoneLoading = false
    var isAnalyticsLoading = false
    var isDNSLoading = false
    var zoneError: String?
    var analyticsError: String?
    var analyticsBreakdownError: String?
    var dnsError: String?
    var workingResourceID: String?
    var actionMessage: String?
    var actionFailed = false
    var selectedAnalyticsRange: CloudflareAnalyticsRange = .days7
    var customAnalyticsFrom = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
    var customAnalyticsTo = Date()
    private var zoneLoadGeneration = 0
    private var analyticsLoadGeneration = 0
    private var dnsLoadGeneration = 0
    private var hasLoadedAnalytics = false
    private var hasLoadedDNS = false
    private var zoneLoadTask: Task<Result<CloudflareZone, Error>, Never>?
    private var analyticsLoadTask: Task<AnalyticsLoadResult, Never>?
    private var analyticsInFlightKey: String?
    private var dnsLoadTask: Task<Result<[CloudflareDNSRecord], Error>, Never>?

    private var zoneCacheKey: String { "\(api.cacheScope)|\(zoneID)|zone" }
    private var dnsCacheKey: String { "\(api.cacheScope)|\(zoneID)|dns" }

    private var analyticsCacheKey: String {
        Self.analyticsCacheKey(
            credentialScope: api.cacheScope,
            zoneID: zoneID,
            range: selectedAnalyticsRange,
            customFrom: customAnalyticsFrom,
            customTo: customAnalyticsTo
        )
    }

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        zoneID = zone.id
        let zoneKey = "\(api.cacheScope)|\(zone.id)|zone"
        if let cachedZone = Self.zoneCache[zoneKey], cachedZone.zone == zone {
            self.zone = cachedZone.zone
        } else {
            self.zone = zone
            Self.zoneCache[zoneKey] = CachedZone(zone: zone, updatedAt: .now)
        }
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
            dnsRecords = cachedDNS.records
            hasLoadedDNS = true
        }
    }

    func load(forceRefresh: Bool = false) async {
        async let zoneLoad: Void = loadZone(forceRefresh: forceRefresh)
        async let analyticsLoad: Void = loadAnalytics(forceRefresh: forceRefresh)
        async let dnsLoad: Void = loadDNS(forceRefresh: forceRefresh)
        _ = await (zoneLoad, analyticsLoad, dnsLoad)
    }

    func refreshZone(forceRefresh: Bool = false) async {
        await loadZone(forceRefresh: forceRefresh)
    }

    func selectAnalyticsRange(_ range: CloudflareAnalyticsRange) async {
        guard selectedAnalyticsRange != range else {
            await loadAnalytics(forceRefresh: false)
            return
        }
        selectedAnalyticsRange = range
        restoreAnalyticsCacheForSelectedRange()
        await loadAnalytics(forceRefresh: false, cancelPreviousRange: true)
    }

    func selectCustomAnalyticsRange(from: Date, to: Date) async {
        customAnalyticsFrom = from
        customAnalyticsTo = to
        selectedAnalyticsRange = .custom
        restoreAnalyticsCacheForSelectedRange()
        await loadAnalytics(forceRefresh: false, cancelPreviousRange: true)
    }

    func saveDNSRecord(existing: CloudflareDNSRecord?, input: CloudflareDNSRecordInput) async throws {
        cancelDNSLoad()
        let resourceID = existing?.id ?? "new-dns-record"
        workingResourceID = resourceID
        defer { workingResourceID = nil }

        let savedRecord: CloudflareDNSRecord
        do {
            if let existing {
                savedRecord = try await api.updateDNSRecord(
                    zoneID: zoneID,
                    recordID: existing.id,
                    record: input,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: existing.id)
                )
                actionMessage = "DNS record updated."
            } else {
                savedRecord = try await api.createDNSRecord(
                    zoneID: zoneID,
                    record: input,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: zoneID)
                )
                actionMessage = "DNS record created."
            }
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }

        // The mutation response is authoritative. Reconcile it directly rather
        // than turning a later list-refresh failure into a false save failure.
        dnsRecords.removeAll { $0.id == savedRecord.id || $0.id == existing?.id }
        dnsRecords.append(savedRecord)
        dnsRecords.sort(by: Self.dnsRecordSort)
        hasLoadedDNS = true
        dnsError = nil
        actionFailed = false
        Self.dnsCache[dnsCacheKey] = CachedDNSRecords(records: dnsRecords, updatedAt: .now)
    }

    func deleteDNSRecord(_ record: CloudflareDNSRecord) async {
        cancelDNSLoad()
        workingResourceID = record.id
        defer { workingResourceID = nil }

        do {
            try await api.deleteDNSRecord(
                zoneID: zoneID,
                recordID: record.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: record.id)
            )
            actionMessage = "DNS record deleted."
            actionFailed = false
            dnsRecords.removeAll { $0.id == record.id }
            hasLoadedDNS = true
            Self.dnsCache[dnsCacheKey] = CachedDNSRecords(records: dnsRecords, updatedAt: .now)
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
                zoneID: zoneID,
                purge: purge,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: zoneID)
            )
            actionMessage = "Cache purge accepted by Cloudflare."
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }
    }

    private func loadZone(forceRefresh: Bool) async {
        if let cached = Self.zoneCache[zoneCacheKey] {
            zone = cached.zone
            zoneError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt) { return }
        }

        if let task = zoneLoadTask {
            let generation = zoneLoadGeneration
            let result = await task.value
            applyZoneLoad(result, generation: generation)
            return
        }

        zoneLoadGeneration += 1
        let generation = zoneLoadGeneration
        let currentZoneID = zoneID
        let task = Task { [api] in
            await Self.capture { try await api.fetchZone(id: currentZoneID) }
        }
        zoneLoadTask = task
        zoneError = nil
        isZoneLoading = true

        let result = await task.value
        applyZoneLoad(result, generation: generation)
    }

    private func applyZoneLoad(_ result: Result<CloudflareZone, Error>, generation: Int) {
        guard generation == zoneLoadGeneration else { return }
        zoneLoadTask = nil
        isZoneLoading = false
        if isCancellation(result) { return }

        switch result {
        case .success(let refreshedZone):
            zone = refreshedZone
            Self.zoneCache[zoneCacheKey] = CachedZone(zone: refreshedZone, updatedAt: .now)
        case .failure(let error):
            zoneError = error.localizedDescription
        }
    }

    private func loadAnalytics(forceRefresh: Bool, cancelPreviousRange: Bool = false) async {
        let key = analyticsCacheKey
        if cancelPreviousRange,
           let inFlightKey = analyticsInFlightKey,
           inFlightKey != key {
            analyticsLoadTask?.cancel()
            analyticsLoadTask = nil
            analyticsInFlightKey = nil
            analyticsLoadGeneration += 1
            isAnalyticsLoading = false
        }
        if let cached = Self.analyticsCache[key] {
            analytics = cached.analytics
            analyticsBreakdowns = cached.analyticsBreakdowns
            hasLoadedAnalytics = true
            analyticsError = nil
            analyticsBreakdownError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt) { return }
        }

        if analyticsInFlightKey == key, let task = analyticsLoadTask {
            let generation = analyticsLoadGeneration
            let result = await task.value
            applyAnalyticsLoad(result, key: key, generation: generation)
            return
        }

        if cancelPreviousRange || analyticsInFlightKey != key {
            analyticsLoadTask?.cancel()
        }
        analyticsLoadGeneration += 1
        let generation = analyticsLoadGeneration
        let dates = selectedAnalyticsRange.dates() ?? (customAnalyticsFrom, customAnalyticsTo)
        let from = dates.from
        let to = dates.to
        let currentZoneID = zoneID
        let task = Task { [api] in
            async let analyticsResult = Self.capture {
                try await api.fetchZoneAnalytics(zoneID: currentZoneID, from: from, to: to)
            }
            async let breakdownResult = Self.capture {
                try await api.fetchZoneAnalyticsBreakdowns(zoneID: currentZoneID, from: from, to: to)
            }
            return await AnalyticsLoadResult(
                analytics: analyticsResult,
                breakdowns: breakdownResult
            )
        }
        analyticsInFlightKey = key
        analyticsLoadTask = task
        analyticsError = nil
        analyticsBreakdownError = nil
        isAnalyticsLoading = !hasLoadedAnalytics

        let result = await task.value
        applyAnalyticsLoad(result, key: key, generation: generation)
    }

    private func applyAnalyticsLoad(_ result: AnalyticsLoadResult, key: String, generation: Int) {
        guard generation == analyticsLoadGeneration, key == analyticsInFlightKey else { return }
        analyticsLoadTask = nil
        analyticsInFlightKey = nil
        isAnalyticsLoading = false

        if isCancellation(result.analytics) || isCancellation(result.breakdowns) { return }

        switch result.analytics {
        case .success(let value):
            analytics = value
            hasLoadedAnalytics = true
            let cacheDate: Date
            switch result.breakdowns {
            case .success(let breakdowns):
                analyticsBreakdowns = breakdowns
                analyticsBreakdownError = nil
                cacheDate = .now
            case .failure(let error):
                analyticsBreakdowns = nil
                analyticsBreakdownError = error.localizedDescription
                // Keep the new summary visible, but make the combined snapshot
                // immediately stale so the missing breakdown can retry later.
                cacheDate = .distantPast
            }
            Self.analyticsCache[key] = CachedZoneAnalytics(
                analytics: value,
                analyticsBreakdowns: analyticsBreakdowns,
                updatedAt: cacheDate
            )
        case .failure(let error):
            analyticsError = error.localizedDescription
        }
    }

    private func loadDNS(forceRefresh: Bool) async {
        if let cached = Self.dnsCache[dnsCacheKey] {
            dnsRecords = cached.records
            hasLoadedDNS = true
            dnsError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt) { return }
        }

        if let task = dnsLoadTask {
            let generation = dnsLoadGeneration
            let result = await task.value
            applyDNSLoad(result, generation: generation)
            return
        }

        dnsLoadGeneration += 1
        let generation = dnsLoadGeneration
        let currentZoneID = zoneID
        let task = Task { [api] in
            await Self.capture { try await api.fetchDNSRecords(zoneID: currentZoneID) }
        }
        dnsLoadTask = task
        dnsError = nil
        isDNSLoading = !hasLoadedDNS

        let result = await task.value
        applyDNSLoad(result, generation: generation)
    }

    private func applyDNSLoad(_ result: Result<[CloudflareDNSRecord], Error>, generation: Int) {
        guard generation == dnsLoadGeneration else { return }
        dnsLoadTask = nil
        isDNSLoading = false
        if isCancellation(result) { return }

        switch result {
        case .success(let records):
            dnsRecords = records.sorted(by: Self.dnsRecordSort)
            hasLoadedDNS = true
            Self.dnsCache[dnsCacheKey] = CachedDNSRecords(records: dnsRecords, updatedAt: .now)
        case .failure(let error):
            dnsError = error.localizedDescription
        }
    }

    private func cancelDNSLoad() {
        dnsLoadTask?.cancel()
        dnsLoadTask = nil
        dnsLoadGeneration += 1
        isDNSLoading = false
    }

    private static func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
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
        analyticsError = nil
        analyticsBreakdownError = nil
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

    private static func isFresh(_ date: Date) -> Bool {
        Date.now.timeIntervalSince(date) < cacheLifetime
    }

    private static func dnsRecordSort(_ lhs: CloudflareDNSRecord, _ rhs: CloudflareDNSRecord) -> Bool {
        if lhs.name == rhs.name { return lhs.type < rhs.type }
        return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
    }
}

struct CloudflareZoneDetailView: View {
    let api: CloudflareAPI
    let onZoneChange: (CloudflareZone) -> Void

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModel: CloudflareZoneDetailViewModel
    @State private var searchText = ""
    @State private var editingRecord: DNSRecordSheetItem?
    @State private var deletingRecord: CloudflareDNSRecord?
    @State private var showingPurgeCache = false
    @State private var showingCustomAnalyticsRange = false

    init(
        api: CloudflareAPI,
        zone: CloudflareZone,
        onZoneChange: @escaping (CloudflareZone) -> Void = { _ in }
    ) {
        self.api = api
        self.onZoneChange = onZoneChange
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
                    if let error = viewModel.zoneError {
                        unavailableCard(title: "Zone details could not refresh", message: error)
                    }
                    controlCenterLinks
                    analyticsRangeRail

                    if let analytics = viewModel.analytics {
                        analyticsSection(analytics)
                        if let breakdowns = viewModel.analyticsBreakdowns {
                            analyticsBreakdownSection(breakdowns)
                        } else if let error = viewModel.analyticsBreakdownError {
                            unavailableCard(title: "Analytics breakdowns unavailable", message: error)
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
        .navigationTitle(viewModel.zone.name)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: "Search DNS records")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .onChange(of: viewModel.zone) { _, updatedZone in
            onZoneChange(updatedZone)
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await viewModel.load() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cloudflareDataDidChange)) { notification in
            let zonePath = "/zones/\(viewModel.zoneID)"
            guard notification.object as? String == api.cacheScope,
                  let path = notification.userInfo?["path"] as? String,
                  path == zonePath || path.hasPrefix(zonePath + "/"),
                  !path.contains("/dns_records") else { return }
            Task { await viewModel.refreshZone(forceRefresh: true) }
        }
        .sheet(item: $editingRecord) { item in
            NavigationStack {
                CloudflareDNSRecordEditor(
                    zoneName: viewModel.zone.name,
                    record: item.record,
                    onSave: { input in
                        try await viewModel.saveDNSRecord(existing: item.record, input: input)
                    }
                )
            }
        }
        .sheet(isPresented: $showingPurgeCache) {
            NavigationStack {
                CloudflareCachePurgeView(zone: viewModel.zone) { purge in
                    try await viewModel.purgeCache(purge)
                }
            }
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
                CloudflareZoneOperationsView(api: api, zone: viewModel.zone)
            } label: {
                CloudflareResourceRow(
                    icon: "switch.2",
                    title: "Zone & DNS operations",
                    subtitle: "DNSSEC, settings, activation, quotas and DNS analytics",
                    tint: CloudflareStyle.orange
                )
            }
            .buttonStyle(.plain)

            Divider().overlay(AppTheme.divider).padding(.leading, 64)

            NavigationLink {
                CloudflareSecurityCenterView(api: api, zone: viewModel.zone)
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
                if viewModel.isAnalyticsLoading {
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
                                        : AppTheme.divider,
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
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
                    Text(viewModel.zone.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(viewModel.zone.plan?.name ?? viewModel.zone.type?.capitalized ?? "Cloudflare zone")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AppTheme.textSecondary)
                }

                Spacer(minLength: 8)
                CloudflareStatusPill(
                    text: (viewModel.zone.paused == true ? "PAUSED" : viewModel.zone.status?.uppercased()) ?? "UNKNOWN",
                    color: viewModel.zone.isActive ? CloudflareStyle.green : CloudflareStyle.amber
                )
            }

            HStack(spacing: 9) {
                if let url = URL(string: "https://\(viewModel.zone.name)") {
                    openButton("Open site", icon: "arrow.up.right", url: url)
                }
                if let accountID = viewModel.zone.account?.id,
                   !accountID.isEmpty,
                   let url = URL(string: "https://dash.cloudflare.com/\(accountID)/\(viewModel.zone.name)") {
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
                            AxisGridLine().foregroundStyle(AppTheme.textTertiary)
                        }
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { _ in
                            AxisValueLabel()
                                .font(.caption2)
                                .foregroundStyle(AppTheme.textSecondary)
                            AxisGridLine().foregroundStyle(AppTheme.textTertiary)
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
            Divider().overlay(AppTheme.divider)
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
                    Divider().overlay(AppTheme.strokeSoft).padding(.leading, 64)
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
            Divider().overlay(AppTheme.divider)
            CloudflareDetailRow(icon: "number", title: "Zone ID", value: viewModel.zone.id)
            CloudflareDetailRow(icon: "building.2", title: "Account", value: viewModel.zone.account?.name ?? "Unknown")
            CloudflareDetailRow(icon: "building.columns", title: "Registrar", value: viewModel.zone.originalRegistrar ?? "Unknown")
            CloudflareDetailRow(
                icon: "server.rack",
                title: "Name servers",
                value: viewModel.zone.nameServers.isEmpty ? "None returned" : viewModel.zone.nameServers.joined(separator: ", ")
            )
            CloudflareDetailRow(
                icon: "hammer.fill",
                title: "Development mode",
                value: (viewModel.zone.developmentMode ?? 0) > 0 ? "Active" : "Off"
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
            Divider().overlay(AppTheme.divider)

            if viewModel.isDNSLoading {
                ProgressView()
                    .tint(CloudflareStyle.orange)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 34)
            } else if let error = viewModel.dnsError, viewModel.dnsRecords.isEmpty {
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
                        Divider().overlay(AppTheme.divider).padding(.leading, 64)
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
                .foregroundStyle(record.proxied == true ? CloudflareStyle.orange : AppTheme.textSecondary)
                .frame(width: 42, height: 34)
                .background(record.proxied == true ? CloudflareStyle.orange.opacity(0.09) : AppTheme.stroke)
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(record.name)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if record.locked == true {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                }
                Text(record.content ?? "Structured record data")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(AppTheme.textTertiary)
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
                        .foregroundStyle(AppTheme.textSecondary)
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
            .foregroundStyle(AppTheme.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(AppTheme.stroke)
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
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
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .cloudflarePanel(accentOpacity: 0.07)

                    VStack(spacing: 0) {
                        DatePicker("From", selection: $from, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                            .padding(16)
                        Divider().overlay(AppTheme.divider)
                        DatePicker("To", selection: $to, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                            .padding(16)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppTheme.textPrimary)
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
