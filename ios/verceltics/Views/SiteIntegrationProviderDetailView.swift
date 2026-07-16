import Charts
import SwiftUI

// MARK: - Provider detail state

@Observable
@MainActor
private final class SiteIntegrationProviderDetailViewModel {
    private struct CacheEntry {
        let payload: SiteIntegrationDetailPayload
        let updatedAt: Date
    }

    enum RangePreset: String, CaseIterable, Identifiable {
        case days7 = "7D"
        case days30 = "30D"
        case days90 = "90D"
        case custom = "Custom"

        var id: Self { self }

        var dayCount: Int? {
            switch self {
            case .days7: 7
            case .days30: 30
            case .days90: 90
            case .custom: nil
            }
        }
    }

    @ResettableMemoryCache(limit: 2)
    private static var payloadCache: [String: CacheEntry] = [:]

    let accountID: UUID
    private let preferredResourceID: String?
    private let client = SiteIntegrationDetailClient()

    var account: SiteIntegrationAccount?
    var resources: [SiteIntegrationResource] = []
    var selectedResourceID: String?
    var selectedRange: RangePreset = .days30
    var customStartDate: Date
    var customEndDate: Date
    var clarityDays = 3
    var clarityDimensions: [String] = []

    var payload: SiteIntegrationDetailPayload?
    var isLoading = false
    var isRefreshing = false
    var error: String?

    private var loadGeneration = 0

    init(accountID: UUID, preferredResourceID: String?) {
        self.accountID = accountID
        self.preferredResourceID = preferredResourceID
        let end = Date.now
        customEndDate = end
        customStartDate = Calendar.current.date(byAdding: .day, value: -29, to: end) ?? end
    }

    var provider: SiteIntegrationProvider? { account?.provider }

    var selectedResource: SiteIntegrationResource? {
        guard let selectedResourceID else { return resources.first }
        return resources.first { $0.id == selectedResourceID } ?? resources.first
    }

    var usesDateRange: Bool {
        guard let provider else { return false }
        switch provider {
        case .pageSpeed, .clarity, .googleSearchConsole:
            return false
        case .googleAnalytics, .plausible, .umami, .uptimeRobot, .betterStack:
            return true
        case .bingWebmaster:
            return false
        }
    }

    var queryIdentity: String {
        [
            selectedResourceID ?? "",
            selectedRange.rawValue,
            Self.dayString(customStartDate),
            Self.dayString(customEndDate),
            String(clarityDays),
            clarityDimensions.joined(separator: ",")
        ].joined(separator: "|")
    }

    func load(using store: SiteStore, forceRefresh: Bool = false) async {
        loadGeneration += 1
        let generation = loadGeneration

        do {
            var requestAccount = try await store.accountForDirectRequest(id: accountID)
            guard generation == loadGeneration else { return }
            account = requestAccount
            resources = store.snapshot(for: accountID)?.resources ?? []
            resolveResourceSelection()

            guard requestAccount.provider != .googleSearchConsole else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration(
                    "Search Console uses its dedicated performance workspace."
                )
            }

            var request = try detailRequest(account: requestAccount)
            var payloadCacheKey = cacheKey(for: requestAccount)
            let cached = Self.payloadCache[payloadCacheKey]
            if let cached {
                payload = cached.payload
                error = nil
                isLoading = false
                if !forceRefresh,
                   Date.now.timeIntervalSince(cached.updatedAt) < cacheLifetime(for: requestAccount.provider) {
                    isRefreshing = false
                    return
                }
            } else if !forceRefresh {
                payload = nil
            }

            isLoading = cached == nil
            isRefreshing = cached != nil
            error = nil
            let loaded: SiteIntegrationDetailPayload
            do {
                loaded = try await client.fetch(request)
            } catch SiteIntegrationDetailAPIError.requestFailed(401)
                where requestAccount.provider == .googleAnalytics {
                // Google access tokens can be revoked between the local expiry check and the
                // provider request. Refresh once, then rebuild both the request and cache scope.
                requestAccount = try await store.accountForDirectRequest(
                    id: accountID,
                    forceCredentialRefresh: true
                )
                guard generation == loadGeneration else { return }
                account = requestAccount
                request = try detailRequest(account: requestAccount)
                payloadCacheKey = cacheKey(for: requestAccount)
                loaded = try await client.fetch(request)
            }
            guard generation == loadGeneration else { return }
            payload = loaded
            Self.payloadCache[payloadCacheKey] = CacheEntry(payload: loaded, updatedAt: .now)
            isLoading = false
            isRefreshing = false
        } catch is CancellationError {
            guard generation == loadGeneration else { return }
            isLoading = false
            isRefreshing = false
        } catch let urlError as URLError where urlError.code == .cancelled {
            guard generation == loadGeneration else { return }
            isLoading = false
            isRefreshing = false
        } catch {
            guard generation == loadGeneration else { return }
            self.error = error.localizedDescription
            isLoading = false
            isRefreshing = false
        }
    }

    func toggleClarityDimension(_ dimension: String) {
        if let index = clarityDimensions.firstIndex(of: dimension) {
            clarityDimensions.remove(at: index)
        } else if clarityDimensions.count < 3 {
            clarityDimensions.append(dimension)
        }
    }

    private func resolveResourceSelection() {
        if let selectedResourceID,
           resources.contains(where: { $0.id == selectedResourceID }) {
            return
        }
        if let preferredResourceID {
            selectedResourceID = resources.first(where: { resource in
                resource.id == preferredResourceID
                    || resource.metadata["propertyID"] == preferredResourceID
                    || resource.subtitle == preferredResourceID
                    || resource.url?.absoluteString == preferredResourceID
            })?.id
        }
        selectedResourceID = selectedResourceID ?? resources.first?.id
    }

    private func detailRequest(account: SiteIntegrationAccount) throws -> SiteIntegrationDetailRequest {
        let range = selectedDateRange
        let resource = selectedResource
        let credential = account.credential.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !credential.isEmpty else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration(
                "The saved \(account.provider.displayName) credential is empty."
            )
        }

        switch account.provider {
        case .googleAnalytics:
            let googleCredential = try GoogleOAuthCredential.fromKeychainValue(account.credential)
            guard let propertyID = nonEmpty(resource?.metadata["propertyID"])
                ?? nonEmpty(resource?.id.replacingOccurrences(of: "properties/", with: "")) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("Choose a GA4 property first.")
            }
            return .googleAnalytics(
                propertyID: propertyID,
                accessToken: googleCredential.accessToken,
                range: range
            )

        case .pageSpeed:
            guard let siteURL = resource?.url
                ?? nonEmpty(account.metadata["siteURL"]).flatMap(URL.init(string:)) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("The PageSpeed site URL is missing.")
            }
            return .pageSpeed(url: siteURL, apiKey: credential)

        case .bingWebmaster:
            guard let siteURL = nonEmpty(resource?.subtitle)
                ?? resource?.url?.absoluteString
                ?? nonEmpty(resource?.name) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("Choose a Bing site first.")
            }
            return .bingWebmaster(siteURL: siteURL, apiKey: credential)

        case .clarity:
            return .clarity(
                apiToken: credential,
                days: clarityDays,
                dimensions: clarityDimensions
            )

        case .plausible:
            guard let siteID = nonEmpty(account.metadata["siteID"])
                ?? nonEmpty(resource?.name) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("The Plausible site ID is missing.")
            }
            return .plausible(siteID: siteID, apiKey: credential, range: range)

        case .umami:
            guard let websiteID = nonEmpty(resource?.id) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("Choose an Umami site first.")
            }
            let mode = nonEmpty(account.metadata["authMode"]) ?? "cloud"
            if mode == "cloud" {
                return .umami(
                    websiteID: websiteID,
                    baseURL: URL(string: "https://api.umami.is/v1/")!,
                    authentication: .cloudAPIKey(credential),
                    range: range
                )
            }
            guard mode == "selfHosted",
                  let rawBase = nonEmpty(account.metadata["baseURL"]),
                  let baseURL = normalizedUmamiAPIBase(rawBase) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration(
                    "The self-hosted Umami API URL is invalid."
                )
            }
            return .umami(
                websiteID: websiteID,
                baseURL: baseURL,
                authentication: .bearerToken(credential),
                range: range
            )

        case .uptimeRobot:
            guard let monitorID = nonEmpty(resource?.id) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("Choose an UptimeRobot monitor first.")
            }
            return .uptimeRobot(monitorID: monitorID, readOnlyAPIKey: credential, range: range)

        case .betterStack:
            guard let monitorID = nonEmpty(resource?.id) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("Choose a Better Stack monitor first.")
            }
            return .betterStack(monitorID: monitorID, token: credential, range: range)

        case .googleSearchConsole:
            throw SiteIntegrationDetailAPIError.invalidConfiguration(
                "Search Console uses its dedicated performance workspace."
            )
        }
    }

    private var selectedDateRange: SiteIntegrationDetailRange {
        if let days = selectedRange.dayCount {
            let end = Date.now
            let start = Calendar.current.date(byAdding: .day, value: -(days - 1), to: end) ?? end
            return SiteIntegrationDetailRange(start: start, end: end)
        }
        return SiteIntegrationDetailRange(start: customStartDate, end: customEndDate)
    }

    private func cacheKey(for account: SiteIntegrationAccount) -> String {
        let metadata = account.metadata.keys.sorted().map {
            "\($0)=\(account.metadata[$0] ?? "")"
        }.joined(separator: "&")
        return CredentialCacheScope.fingerprint(fields: [
            "site-provider-detail",
            account.id.uuidString.lowercased(),
            account.provider.rawValue,
            account.credential,
            metadata,
            queryIdentity
        ])
    }

    private func cacheLifetime(for provider: SiteIntegrationProvider) -> TimeInterval {
        switch provider {
        case .pageSpeed: 30 * 60
        case .clarity: 6 * 60 * 60
        case .bingWebmaster: 15 * 60
        case .googleAnalytics, .plausible, .umami: 5 * 60
        case .uptimeRobot, .betterStack: 2 * 60
        case .googleSearchConsole: 5 * 60
        }
    }

    private func normalizedUmamiAPIBase(_ rawValue: String) -> URL? {
        guard var components = URLComponents(string: rawValue),
              components.scheme?.lowercased() == "https",
              components.host != nil else { return nil }
        components.query = nil
        components.fragment = nil
        var path = components.path
        while path.hasSuffix("/") { path.removeLast() }
        if !path.hasSuffix("/api") { path += "/api" }
        components.path = path + "/"
        return components.url
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func dayString(_ date: Date) -> String {
        SiteIntegrationDetailRange.dateString(date)
    }
}

// MARK: - Provider detail workspace

struct SiteIntegrationProviderDetailView: View {
    @Environment(SiteStore.self) private var store
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: SiteIntegrationProviderDetailViewModel
    @State private var didCompleteInitialLoad = false
    @State private var queryReloadTask: Task<Void, Never>?

    private let clarityDimensionOptions = [
        "Browser", "Device", "Country/Region", "OS", "Source",
        "Medium", "Campaign", "Channel", "URL"
    ]

    init(accountID: UUID, initialResourceID: String? = nil) {
        _viewModel = State(initialValue: SiteIntegrationProviderDetailViewModel(
            accountID: accountID,
            preferredResourceID: initialResourceID
        ))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 16) {
                    if let account = viewModel.account {
                        providerHeader(account)
                        queryControls(for: account.provider)
                    }

                    if let error = viewModel.error {
                        AppFeedbackBanner(
                            title: "Couldn’t load provider details",
                            message: error,
                            tint: AppTheme.danger,
                            actionTitle: "Try again"
                        ) {
                            Task { await viewModel.load(using: store, forceRefresh: true) }
                        }
                    }

                    if viewModel.isLoading, viewModel.payload == nil {
                        loadingView
                    } else if let payload = viewModel.payload {
                        payloadContent(payload)
                    } else if viewModel.error == nil {
                        AppEmptyState(
                            icon: viewModel.provider?.systemImage ?? "chart.xyaxis.line",
                            title: "No detail data",
                            message: "The provider did not return a detailed report for this resource."
                        )
                        .frame(maxWidth: .infinity)
                        .appSurface()
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.top, 14)
                .padding(.bottom, 32)
                .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
            .refreshable { await viewModel.load(using: store, forceRefresh: true) }
        }
        .navigationTitle(viewModel.provider?.displayName ?? "Site details")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.load(using: store)
            didCompleteInitialLoad = true
        }
        .onChange(of: viewModel.queryIdentity) { oldValue, newValue in
            guard didCompleteInitialLoad, oldValue != newValue else { return }
            queryReloadTask?.cancel()
            queryReloadTask = Task {
                try? await Task.sleep(for: .milliseconds(180))
                guard !Task.isCancelled else { return }
                await viewModel.load(using: store)
            }
        }
        .onDisappear { queryReloadTask?.cancel() }
    }

    private func providerHeader(_ account: SiteIntegrationAccount) -> some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(account.provider.accentColor.opacity(0.11))
                SiteProviderMark(provider: account.provider, size: 32)
            }
            .frame(width: 56, height: 56)

            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.payload?.title ?? viewModel.selectedResource?.name ?? account.name)
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)
                Text(account.provider.connectionSubtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            if viewModel.isRefreshing {
                ProgressView()
                    .controlSize(.small)
                    .tint(account.provider.accentColor)
                    .accessibilityLabel("Refreshing")
            } else if let fetchedAt = viewModel.payload?.fetchedAt {
                VStack(alignment: .trailing, spacing: 2) {
                    AppStatusBadge(text: "Current", tone: .success)
                    Text(fetchedAt, style: .relative)
                        .font(.caption2)
                        .foregroundStyle(AppTheme.textTertiary)
                }
            }
        }
        .padding(17)
        .providerSurface(accent: account.provider.accentColor)
    }

    @ViewBuilder
    private func queryControls(for provider: SiteIntegrationProvider) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 10) {
                AppIconTile(icon: "slider.horizontal.3", tint: provider.accentColor, size: 34)
                Text("Report controls")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Spacer()
            }

            if viewModel.resources.count > 1 {
                Picker("Resource", selection: Binding(
                    get: { viewModel.selectedResourceID ?? "" },
                    set: { viewModel.selectedResourceID = $0 }
                )) {
                    ForEach(viewModel.resources) { resource in
                        Text(resource.name).tag(resource.id)
                    }
                }
                .pickerStyle(.menu)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if provider == .clarity {
                clarityControls
            } else if viewModel.usesDateRange {
                dateRangeControls
            } else if provider == .pageSpeed {
                Label("Live Lighthouse audit with current CrUX field data and history", systemImage: "bolt.fill")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            }
        }
        .padding(15)
        .appSurface()
    }

    private var dateRangeControls: some View {
        VStack(alignment: .leading, spacing: 11) {
            Picker("Date range", selection: $viewModel.selectedRange) {
                ForEach(SiteIntegrationProviderDetailViewModel.RangePreset.allCases) { range in
                    Text(range.rawValue).tag(range)
                }
            }
            .pickerStyle(.segmented)

            if viewModel.selectedRange == .custom {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        customDatePicker("From", selection: $viewModel.customStartDate)
                        customDatePicker("To", selection: $viewModel.customEndDate)
                    }
                    VStack(spacing: 10) {
                        customDatePicker("From", selection: $viewModel.customStartDate)
                        customDatePicker("To", selection: $viewModel.customEndDate)
                    }
                }
            }
        }
    }

    private func customDatePicker(_ title: String, selection: Binding<Date>) -> some View {
        DatePicker(title, selection: selection, in: ...Date.now, displayedComponents: .date)
            .datePickerStyle(.compact)
            .font(.footnote)
            .padding(.horizontal, 11)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private var clarityControls: some View {
        VStack(alignment: .leading, spacing: 11) {
            Picker("History", selection: $viewModel.clarityDays) {
                Text("24H").tag(1)
                Text("48H").tag(2)
                Text("72H").tag(3)
            }
            .pickerStyle(.segmented)

            Menu {
                ForEach(clarityDimensionOptions, id: \.self) { dimension in
                    Button {
                        viewModel.toggleClarityDimension(dimension)
                    } label: {
                        if viewModel.clarityDimensions.contains(dimension) {
                            Label(dimension, systemImage: "checkmark")
                        } else {
                            Text(dimension)
                        }
                    }
                    .disabled(
                        viewModel.clarityDimensions.count >= 3
                            && !viewModel.clarityDimensions.contains(dimension)
                    )
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "square.grid.3x3")
                    Text(viewModel.clarityDimensions.isEmpty
                         ? "Add dimensions (up to 3)"
                         : viewModel.clarityDimensions.joined(separator: " · "))
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2)
                }
                .font(.footnote.weight(.medium))
                .foregroundStyle(AppTheme.textPrimary)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
        }
    }

    private var loadingView: some View {
        VStack(spacing: 13) {
            ProgressView()
                .tint(viewModel.provider?.accentColor ?? AppTheme.signal)
            Text("Loading the complete provider report…")
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 44)
        .appSurface()
    }

    @ViewBuilder
    private func payloadContent(_ payload: SiteIntegrationDetailPayload) -> some View {
        ForEach(Array(payload.warnings.enumerated()), id: \.offset) { _, warning in
            AppFeedbackBanner(
                title: "Partial provider response",
                message: warning,
                icon: "exclamationmark.circle.fill",
                tint: AppTheme.warning
            )
        }

        if !payload.sections.isEmpty {
            AppSectionHeader(
                title: "Overview",
                count: payload.sections.count,
                accent: payload.provider.accentColor
            )
            LazyVGrid(columns: sectionColumns, spacing: 14) {
                ForEach(payload.sections) { section in
                    SiteDetailSectionCard(section: section, accent: payload.provider.accentColor)
                }
            }
        }

        if !payload.series.isEmpty {
            AppSectionHeader(
                title: "Timeline",
                count: payload.series.count,
                accent: payload.provider.accentColor
            )
            ForEach(payload.series) { series in
                SiteDetailSeriesCard(series: series, accent: payload.provider.accentColor)
            }
        }

        if !payload.tables.isEmpty {
            AppSectionHeader(
                title: "Provider records",
                count: payload.tables.reduce(0) { $0 + $1.rows.count },
                accent: payload.provider.accentColor
            )
            ForEach(payload.tables) { table in
                SiteDetailTableCard(table: table, accent: payload.provider.accentColor)
            }
        }

        if !payload.rawResponses.isEmpty {
            NavigationLink {
                SiteDetailRawResponseExplorer(
                    provider: payload.provider,
                    responses: payload.rawResponses,
                    fetchedAt: payload.fetchedAt
                )
            } label: {
                HStack(spacing: 12) {
                    AppIconTile(icon: "curlybraces.square", tint: payload.provider.accentColor, size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Complete API response")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                        Text("Browse every returned non-secret field across \(payload.rawResponses.count) endpoints")
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                }
                .padding(15)
                .appSurface(raised: true)
            }
            .buttonStyle(.plain)
        }
    }

    private var sectionColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 310,
            regularMaximum: 520,
            spacing: 14
        )
    }
}

// MARK: - Normalized report cards

private struct SiteDetailSectionCard: View {
    let section: SiteIntegrationDetailSection
    let accent: Color

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 128), spacing: 9)]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 1)
                    .fill(accent)
                    .frame(width: 3, height: 17)
                Text(section.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
            }

            LazyVGrid(columns: columns, spacing: 9) {
                ForEach(section.fields) { field in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(field.label.uppercased())
                            .font(.caption2.weight(.semibold))
                            .tracking(0.7)
                            .foregroundStyle(AppTheme.textTertiary)
                            .lineLimit(2)
                        Text(SiteDetailValueFormatter.display(field.value))
                            .font(.subheadline.weight(.medium).monospacedDigit())
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(4)
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity, minHeight: 67, alignment: .topLeading)
                    .padding(11)
                    .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
            }
        }
        .padding(15)
        .providerSurface(accent: accent)
    }
}

private struct SiteDetailSeriesCard: View {
    let series: SiteIntegrationDetailSeries
    let accent: Color
    @State private var selectedMetric: String

    init(series: SiteIntegrationDetailSeries, accent: Color) {
        self.series = series
        self.accent = accent
        let declared = Set(series.metricLabels.keys)
        let returned = Set(series.points.flatMap { $0.values.keys })
        _selectedMetric = State(initialValue: declared.union(returned).sorted().first ?? "")
    }

    private var metricKeys: [String] {
        let declared = Set(series.metricLabels.keys)
        let returned = Set(series.points.flatMap { $0.values.keys })
        return declared.union(returned).sorted()
    }

    private var chartPoints: [SiteIntegrationDetailSeriesPoint] {
        series.points.filter { $0.values[selectedMetric] != nil }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(series.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Spacer()
                if metricKeys.count > 1 {
                    Picker("Metric", selection: $selectedMetric) {
                        ForEach(metricKeys, id: \.self) { metric in
                            Text(series.metricLabels[metric] ?? SiteDetailValueFormatter.humanized(metric))
                                .tag(metric)
                        }
                    }
                    .pickerStyle(.menu)
                    .font(.footnote)
                }
            }

            if chartPoints.isEmpty {
                Text("No points were returned for this metric.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 120)
            } else {
                Chart(chartPoints) { point in
                    if let value = point.values[selectedMetric] {
                        AreaMark(
                            x: .value("Period", point.x),
                            y: .value("Value", value)
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: [accent.opacity(0.23), accent.opacity(0.015)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )

                        LineMark(
                            x: .value("Period", point.x),
                            y: .value("Value", value)
                        )
                        .foregroundStyle(accent)
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        .interpolationMethod(.monotone)
                    }
                }
                .chartLegend(.hidden)
                .chartYAxis {
                    AxisMarks(position: .leading) {
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                            .foregroundStyle(AppTheme.divider)
                        AxisValueLabel()
                            .font(.caption2)
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) {
                        AxisGridLine().foregroundStyle(.clear)
                        AxisValueLabel()
                            .font(.caption2)
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                }
                .frame(height: 220)
                .accessibilityLabel(series.title)
            }

            HStack {
                Text(series.metricLabels[selectedMetric] ?? SiteDetailValueFormatter.humanized(selectedMetric))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accent)
                Spacer()
                Text("\(chartPoints.count.formatted()) points")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .padding(15)
        .providerSurface(accent: accent)
        .onChange(of: series.id) { _, _ in
            selectedMetric = metricKeys.first ?? ""
        }
    }
}

private struct SiteDetailTableCard: View {
    private struct RowSlice {
        let indices: [Int]
        let hasMore: Bool
    }

    private static let rowBatchSize = 200

    let table: SiteIntegrationDetailTable
    let accent: Color
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var searchText = ""
    @State private var sortColumn: String?
    @State private var sortAscending = true
    @State private var displayedRowLimit = Self.rowBatchSize

    /// Keep only integer indices while searching/sorting. Copying thousands of row dictionaries
    /// here used to briefly double the payload, and `Array(enumerated())` copied them again.
    private var visibleRowSlice: RowSlice {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var indices: [Int] = []
        let scanLimit = sortColumn == nil ? displayedRowLimit + 1 : Int.max
        for index in table.rows.indices where row(table.rows[index], matches: query) {
            indices.append(index)
            if indices.count >= scanLimit { break }
        }
        if let sortColumn {
            indices.sort { leftIndex, rightIndex in
                let comparison = SiteDetailValueFormatter.compare(
                    table.rows[leftIndex][sortColumn] ?? .null,
                    table.rows[rightIndex][sortColumn] ?? .null
                )
                if comparison == .orderedSame { return leftIndex < rightIndex }
                return sortAscending ? comparison == .orderedAscending : comparison == .orderedDescending
            }
        }
        return RowSlice(
            indices: Array(indices.prefix(displayedRowLimit)),
            hasMore: indices.count > displayedRowLimit
        )
    }

    private var columns: [String] {
        var returned: Set<String> = []
        for row in table.rows { returned.formUnion(row.keys) }
        return table.columns + returned.subtracting(table.columns).sorted()
    }

    var body: some View {
        let rowSlice = visibleRowSlice
        VStack(alignment: .leading, spacing: 13) {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Text(table.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(table.rows.count.formatted())
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(accent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(accent.opacity(0.10), in: Capsule())
                Spacer()

                Menu {
                    Button("Provider order") { sortColumn = nil }
                    Divider()
                    ForEach(columns, id: \.self) { column in
                        Button {
                            displayedRowLimit = Self.rowBatchSize
                            if sortColumn == column {
                                sortAscending.toggle()
                            } else {
                                sortColumn = column
                                sortAscending = true
                            }
                        } label: {
                            Label(
                                SiteDetailValueFormatter.humanized(column),
                                systemImage: sortColumn == column
                                    ? (sortAscending ? "arrow.up" : "arrow.down")
                                    : "arrow.up.arrow.down"
                            )
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(width: 36, height: 36)
                        .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .accessibilityLabel("Sort \(table.title)")
            }

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(AppTheme.textTertiary)
                TextField("Search all returned rows", text: $searchText)
                    .font(.footnote)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if !searchText.isEmpty {
                    Button { searchText = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 11)
            .frame(minHeight: 42)
            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 11, style: .continuous))

            if rowSlice.indices.isEmpty {
                Text(searchText.isEmpty ? "No rows returned." : "No rows match your search.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 72)
            } else if horizontalSizeClass == .regular {
                regularTable(rowIndices: rowSlice.indices)
            } else {
                compactRows(rowIndices: rowSlice.indices)
            }

            if rowSlice.hasMore {
                Button {
                    displayedRowLimit += Self.rowBatchSize
                } label: {
                    Label("Show \(Self.rowBatchSize.formatted()) more", systemImage: "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .foregroundStyle(accent)
                .padding(.vertical, 8)
            }

            if let cursor = table.nextCursor, !cursor.isEmpty {
                Label(cursor, systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(AppTheme.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(15)
        .appSurface()
        .onChange(of: searchText) { _, _ in displayedRowLimit = Self.rowBatchSize }
        .onChange(of: table.id) { _, _ in displayedRowLimit = Self.rowBatchSize }
    }

    private func regularTable(rowIndices: [Int]) -> some View {
        ScrollView(.horizontal) {
            LazyVStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    ForEach(columns, id: \.self) { column in
                        Text(SiteDetailValueFormatter.humanized(column).uppercased())
                            .font(.caption2.weight(.semibold))
                            .tracking(0.45)
                            .foregroundStyle(AppTheme.textSecondary)
                            .frame(width: width(for: column), alignment: .leading)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 10)
                    }
                }
                .background(AppTheme.surfaceRaised)

                ForEach(Array(rowIndices.enumerated()), id: \.element) { index, rowIndex in
                    let row = table.rows[rowIndex]
                    HStack(alignment: .top, spacing: 0) {
                        ForEach(columns, id: \.self) { column in
                            Text(SiteDetailValueFormatter.display(row[column] ?? .null))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(AppTheme.textPrimary)
                                .lineLimit(nil)
                                .textSelection(.enabled)
                                .frame(width: width(for: column), alignment: .leading)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 10)
                        }
                    }
                    .background(index.isMultiple(of: 2) ? Color.clear : AppTheme.surfaceRaised.opacity(0.42))
                    Divider().overlay(AppTheme.divider)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
        }
    }

    private func compactRows(rowIndices: [Int]) -> some View {
        LazyVStack(spacing: 9) {
            ForEach(Array(rowIndices.enumerated()), id: \.element) { index, rowIndex in
                let row = table.rows[rowIndex]
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("ROW \((index + 1).formatted())")
                            .font(.caption2.weight(.semibold))
                            .tracking(0.65)
                            .foregroundStyle(accent)
                        Spacer()
                    }
                    ForEach(columns, id: \.self) { column in
                        if let value = row[column] {
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Text(SiteDetailValueFormatter.humanized(column))
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.textSecondary)
                                    .frame(maxWidth: 118, alignment: .leading)
                                Text(SiteDetailValueFormatter.display(value))
                                    .font(.caption.weight(.medium).monospacedDigit())
                                    .foregroundStyle(AppTheme.textPrimary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
                .padding(12)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
        }
    }

    private func row(
        _ row: [String: SiteIntegrationJSONValue],
        matches query: String
    ) -> Bool {
        guard !query.isEmpty else { return true }
        return row.contains { key, value in
            key.lowercased().contains(query)
                || SiteDetailValueFormatter.display(value).lowercased().contains(query)
        }
    }

    private func width(for column: String) -> CGFloat {
        let lower = column.lowercased()
        if lower.contains("url") || lower.contains("path") || lower.contains("title")
            || lower.contains("name") || lower.contains("message") || lower.contains("reason") {
            return 220
        }
        return 145
    }
}

// MARK: - Lossless raw response explorer

private struct SiteDetailRawLeaf: Identifiable {
    let path: String
    let value: SiteIntegrationJSONValue
    var id: String { path }
}

private struct SiteDetailRawResponseExplorer: View {
    let provider: SiteIntegrationProvider
    let responses: [String: SiteIntegrationJSONValue]
    let fetchedAt: Date

    @State private var selectedEndpoint: String
    @State private var searchText = ""
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(
        provider: SiteIntegrationProvider,
        responses: [String: SiteIntegrationJSONValue],
        fetchedAt: Date
    ) {
        self.provider = provider
        self.responses = responses
        self.fetchedAt = fetchedAt
        _selectedEndpoint = State(initialValue: responses.keys.sorted().first ?? "")
    }

    private var leaves: [SiteDetailRawLeaf] {
        guard let value = responses[selectedEndpoint] else { return [] }
        var output: [SiteDetailRawLeaf] = []
        flatten(value, path: "$", into: &output)
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return output }
        return output.filter {
            $0.path.lowercased().contains(query)
                || SiteDetailValueFormatter.display($0.value).lowercased().contains(query)
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 12) {
                    HStack(spacing: 12) {
                        SiteProviderMark(provider: provider, size: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Sanitized provider payload")
                                .font(.headline)
                                .foregroundStyle(AppTheme.textPrimary)
                            Text("Secrets are redacted; all other returned leaf fields are shown.")
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                        }
                        Spacer()
                    }
                    .padding(15)
                    .providerSurface(accent: provider.accentColor)

                    Picker("Endpoint", selection: $selectedEndpoint) {
                        ForEach(responses.keys.sorted(), id: \.self) { key in
                            Text(key).tag(key)
                        }
                    }
                    .pickerStyle(.menu)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 46)
                    .appSurface(raised: true)

                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(AppTheme.textTertiary)
                        TextField("Search field paths and values", text: $searchText)
                            .font(.footnote)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .appSurface()

                    AppSectionHeader(
                        title: "Fields",
                        count: leaves.count,
                        accent: provider.accentColor
                    )

                    ForEach(leaves) { leaf in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(leaf.path)
                                .font(.caption2.monospaced().weight(.semibold))
                                .foregroundStyle(provider.accentColor)
                                .textSelection(.enabled)
                            Text(SiteDetailValueFormatter.display(leaf.value))
                                .font(.footnote.monospaced())
                                .foregroundStyle(AppTheme.textPrimary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                        .padding(12)
                        .appSurface()
                    }

                    Text("Fetched \(fetchedAt.formatted(date: .abbreviated, time: .standard))")
                        .font(.caption2)
                        .foregroundStyle(AppTheme.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding()
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("API response")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func flatten(
        _ value: SiteIntegrationJSONValue,
        path: String,
        into output: inout [SiteDetailRawLeaf]
    ) {
        switch value {
        case .object(let object):
            if object.isEmpty { output.append(SiteDetailRawLeaf(path: path, value: value)) }
            for key in object.keys.sorted() {
                if let child = object[key] {
                    flatten(child, path: "\(path).\(key)", into: &output)
                }
            }
        case .array(let array):
            if array.isEmpty { output.append(SiteDetailRawLeaf(path: path, value: value)) }
            for (index, child) in array.enumerated() {
                flatten(child, path: "\(path)[\(index)]", into: &output)
            }
        case .string, .integer, .unsignedInteger, .decimal, .number, .bool, .null:
            output.append(SiteDetailRawLeaf(path: path, value: value))
        }
    }
}

private enum SiteDetailValueFormatter {
    nonisolated static func display(_ value: SiteIntegrationJSONValue) -> String {
        switch value {
        case .string(let value):
            return value.isEmpty ? "—" : value
        case .integer(let value):
            return value.formatted(.number.grouping(.automatic))
        case .unsignedInteger(let value):
            return value.formatted(.number.grouping(.automatic))
        case .decimal:
            return value.stringValue ?? "—"
        case .number(let value):
            guard value.isFinite else { return "—" }
            if value.rounded() == value {
                return value.formatted(.number.grouping(.automatic).precision(.fractionLength(0)))
            }
            return value.formatted(.number.grouping(.automatic).precision(.fractionLength(0...3)))
        case .bool(let value):
            return value ? "Yes" : "No"
        case .null:
            return "—"
        case .array(let values):
            if values.isEmpty { return "[]" }
            return values.map(display).joined(separator: ", ")
        case .object(let object):
            if object.isEmpty { return "{}" }
            return object.keys.sorted().map { key in
                "\(humanized(key)): \(display(object[key] ?? .null))"
            }.joined(separator: " · ")
        }
    }

    nonisolated static func humanized(_ value: String) -> String {
        value
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .split(separator: " ")
            .map { token in
                let word = String(token)
                if word == word.uppercased(), word.count <= 5 { return word }
                return word.prefix(1).uppercased() + word.dropFirst()
            }
            .joined(separator: " ")
    }

    static func compare(
        _ left: SiteIntegrationJSONValue,
        _ right: SiteIntegrationJSONValue
    ) -> ComparisonResult {
        if let leftNumber = left.decimalValue, let rightNumber = right.decimalValue {
            if leftNumber < rightNumber { return .orderedAscending }
            if leftNumber > rightNumber { return .orderedDescending }
            return .orderedSame
        }
        return display(left).localizedStandardCompare(display(right))
    }
}
