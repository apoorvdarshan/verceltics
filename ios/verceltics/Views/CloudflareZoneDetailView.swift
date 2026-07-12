import Charts
import SwiftUI

@Observable
@MainActor
final class CloudflareZoneDetailViewModel {
    let api: CloudflareAPI
    let zone: CloudflareZone

    var analytics: CloudflareZoneAnalyticsSummary?
    var dnsRecords: [CloudflareDNSRecord] = []
    var isLoading = true
    var analyticsError: String?
    var dnsError: String?
    var workingResourceID: String?
    var actionMessage: String?
    var actionFailed = false

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
    }

    func load() async {
        isLoading = true
        analyticsError = nil
        dnsError = nil

        let to = Date()
        let from = Calendar.current.date(byAdding: .day, value: -7, to: to) ?? to.addingTimeInterval(-604_800)

        async let analyticsResult = capture {
            try await api.fetchZoneAnalytics(zoneID: zone.id, from: from, to: to)
        }
        async let dnsResult = capture {
            try await api.fetchDNSRecords(zoneID: zone.id)
        }

        switch await analyticsResult {
        case .success(let analytics): self.analytics = analytics
        case .failure(let error): analyticsError = error.localizedDescription
        }

        switch await dnsResult {
        case .success(let records):
            dnsRecords = records.sorted {
                if $0.name == $1.name { return $0.type < $1.type }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
        case .failure(let error):
            dnsRecords = []
            dnsError = error.localizedDescription
        }

        isLoading = false
    }

    func saveDNSRecord(existing: CloudflareDNSRecord?, input: CloudflareDNSRecordInput) async throws {
        let resourceID = existing?.id ?? "new-dns-record"
        workingResourceID = resourceID
        defer { workingResourceID = nil }

        do {
            if let existing {
                _ = try await api.updateDNSRecord(zoneID: zone.id, recordID: existing.id, record: input)
                actionMessage = "DNS record updated."
            } else {
                _ = try await api.createDNSRecord(zoneID: zone.id, record: input)
                actionMessage = "DNS record created."
            }
            actionFailed = false
            dnsRecords = try await api.fetchDNSRecords(zoneID: zone.id)
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

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    zoneHeader

                    if let analytics = viewModel.analytics {
                        analyticsSection(analytics)
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
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 980 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(zone.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search DNS records")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
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

    private var zoneHeader: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "globe.americas.fill")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(.black.opacity(0.82))
                    .frame(width: 46, height: 46)
                    .background(
                        LinearGradient(
                            colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                            startPoint: .bottomLeading,
                            endPoint: .topTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(zone.name)
                        .font(.system(size: 21, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(zone.plan?.name ?? zone.type?.capitalized ?? "Cloudflare zone")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
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
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
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
            }

            if !analytics.series.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("REQUESTS · LAST 7 DAYS")
                            .font(.system(size: 10, weight: .heavy))
                            .tracking(1.0)
                            .foregroundStyle(.white.opacity(0.4))
                        Spacer()
                        Text(analytics.totals.pageViews.formatted() + " page views")
                            .font(.system(size: 10, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.white.opacity(0.32))
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
                                .foregroundStyle(.white.opacity(0.32))
                            AxisGridLine().foregroundStyle(.white.opacity(0.04))
                        }
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { _ in
                            AxisValueLabel().foregroundStyle(.white.opacity(0.32))
                            AxisGridLine().foregroundStyle(.white.opacity(0.05))
                        }
                    }
                    .frame(height: 210)
                }
                .padding(16)
                .cloudflarePanel(accentOpacity: 0.055)
            }
        }
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
                .font(.system(size: 9, weight: .heavy, design: .monospaced))
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
                            .font(.system(size: 8, weight: .heavy))
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
                        .frame(width: 34, height: 34)
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
                Image(systemName: icon).font(.system(size: 9, weight: .heavy))
                Text(title).font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(.white.opacity(0.78))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
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
