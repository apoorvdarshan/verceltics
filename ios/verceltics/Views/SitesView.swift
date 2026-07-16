import SwiftUI

struct SitesView: View {
    @Environment(SiteStore.self) private var store
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var startWithSearch = false
    var searchRequestID = 0
    var backgroundRefreshRequestID = 0

    @State private var searchText = ""
    @State private var isSearching = false
    @State private var showingConnection = false
    @State private var refreshSpin = 0.0

    private var snapshots: [SiteIntegrationSnapshot] {
        store.accounts.compactMap { store.snapshot(for: $0.id) }
    }

    private var sourcedResources: [SourcedSiteResource] {
        store.accounts.flatMap { account -> [SourcedSiteResource] in
            guard let snapshot = store.snapshot(for: account.id) else { return [] }
            return snapshot.resources.map {
                SourcedSiteResource(accountID: account.id, resource: $0)
            }
        }
    }

    private var allResources: [SiteIntegrationResource] {
        sourcedResources.map(\.resource)
    }

    private var sites: [AggregatedSite] {
        let grouped = Dictionary(grouping: sourcedResources) {
            canonicalSiteKey($0.resource)
        }
        return grouped.map { key, sources in
            AggregatedSite(key: key, sources: sources)
        }
        .filter { site in
            searchText.isEmpty
                || site.name.localizedCaseInsensitiveContains(searchText)
                || site.providers.contains { $0.displayName.localizedCaseInsensitiveContains(searchText) }
                || site.resources.contains {
                    ($0.status?.localizedCaseInsensitiveContains(searchText) ?? false)
                        || ($0.subtitle?.localizedCaseInsensitiveContains(searchText) ?? false)
                }
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private var warningCount: Int {
        snapshots.reduce(0) { $0 + $1.warnings.count }
            + allResources.filter { resourceHasIssue($0) }.count
            + refreshFailures.count
    }

    private var refreshFailures: [(account: SiteIntegrationAccount, message: String)] {
        store.accounts.compactMap { account in
            guard let message = store.refreshErrors[account.id], !message.isEmpty else { return nil }
            return (account, message)
        }
        .sorted { $0.account.provider.displayName < $1.account.provider.displayName }
    }

    private var refreshFailureSummary: String {
        var lines = refreshFailures.prefix(3).map {
            "\($0.account.provider.displayName): \($0.message)"
        }
        let remaining = refreshFailures.count - lines.count
        if remaining > 0 {
            lines.append("And \(remaining) more \(remaining == 1 ? "service" : "services").")
        }
        return lines.joined(separator: "\n")
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                if store.accounts.isEmpty {
                    emptyState
                } else if store.isRefreshing && snapshots.isEmpty {
                    AppDashboardLoadingView(accent: AppTheme.signal)
                } else {
                    dashboard
                }
            }
            .navigationTitle("Sites")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search sites and signals")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    SiteAccountMenu()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: refreshAll) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(store.isRefreshing || store.accounts.isEmpty)
                    .accessibilityLabel(store.isRefreshing ? "Refreshing site services" : "Refresh site services")
                }
            }
            .task {
                await loadAll(force: false)
            }
            .onChange(of: siteAccountIdentity) { _, _ in
                Task { await loadAll(force: false) }
            }
            .onChange(of: backgroundRefreshRequestID) { _, _ in
                Task { await loadAll(force: false) }
            }
            .onAppear {
                if startWithSearch { isSearching = true }
            }
            .onChange(of: searchRequestID) { _, _ in
                isSearching = true
            }
            .sheet(isPresented: $showingConnection) {
                LoginView(initialCategory: .sites)
                    .presentationSizing(.page)
                    .presentationDragIndicator(.visible)
            }
        }
    }

    private var dashboard: some View {
        ScrollView {
            LazyVStack(spacing: 18) {
                signalOverview

                if !refreshFailures.isEmpty {
                    AppFeedbackBanner(
                        title: "\(refreshFailures.count) site \(refreshFailures.count == 1 ? "service" : "services") could not refresh",
                        message: refreshFailureSummary,
                        actionTitle: "Try again"
                    ) {
                        refreshAll()
                    }
                } else if let error = store.error, !error.isEmpty {
                    AppFeedbackBanner(
                        title: "A site service needs attention",
                        message: error
                    )
                }

                if !snapshotWarnings.isEmpty {
                    AppFeedbackBanner(
                        title: "\(snapshotWarnings.count) data \(snapshotWarnings.count == 1 ? "note" : "notes")",
                        message: snapshotWarnings.prefix(3).joined(separator: "\n"),
                        icon: "info.circle.fill",
                        tint: AppTheme.warning
                    )
                }

                AppSectionHeader(title: "Connected signals", count: store.accounts.count, accent: AppTheme.signal)

                LazyVGrid(columns: serviceColumns, spacing: 14) {
                    ForEach(store.accounts) { account in
                        NavigationLink {
                            SiteServiceDetailView(accountID: account.id)
                        } label: {
                            serviceCard(account)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                    }

                    Button { showingConnection = true } label: {
                        addServiceCard
                    }
                    .buttonStyle(PressScaleButtonStyle())
                }

                AppSectionHeader(title: "Discovered sites", count: sites.count, accent: AppTheme.success)

                if sites.isEmpty {
                    AppEmptyState(
                        icon: searchText.isEmpty ? "network.slash" : "magnifyingglass",
                        title: searchText.isEmpty ? "No sites returned yet" : "No matching sites",
                        message: searchText.isEmpty
                            ? "Refresh the connected services, or add a source that can discover website properties."
                            : "Nothing matches “\(searchText)”.",
                        actionTitle: searchText.isEmpty ? "Add another service" : "Clear search"
                    ) {
                        if searchText.isEmpty { showingConnection = true }
                        else { searchText = "" }
                    }
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    LazyVGrid(columns: siteColumns, spacing: 14) {
                        ForEach(sites) { site in
                            NavigationLink {
                                AggregatedSiteDetailView(siteKey: site.key)
                            } label: {
                                siteCard(site)
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                    }
                }
            }
            .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
            .padding(.top, 18)
            .padding(.bottom, 28)
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await loadAll(force: true) }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            if let error = store.error, !error.isEmpty {
                AppFeedbackBanner(
                    title: "Saved site services need attention",
                    message: error,
                    icon: "lock.trianglebadge.exclamationmark.fill",
                    tint: AppTheme.danger
                )
            }

            AppEmptyState(
                icon: "chart.xyaxis.line",
                title: "Connect your site signals",
                message: "Bring search, traffic, performance, experience, and uptime into one site-level dashboard.",
                actionTitle: "Connect a service"
            ) {
                showingConnection = true
            }
        }
        .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
        .appContentWidth(560, horizontalSizeClass: horizontalSizeClass)
    }

    private var signalOverview: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("SITE SIGNAL")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.3)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text("\(sites.count.formatted()) \(sites.count == 1 ? "site" : "sites") in view")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Text("Discovery, experience, and availability stay attached to the site—not the vendor.")
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 10)

                AppStatusBadge(
                    text: warningCount == 0 ? "Clear" : "\(warningCount) to review",
                    tone: warningCount == 0 ? .success : (refreshFailures.isEmpty ? .warning : .danger)
                )
            }

            SignalCoverageRail(
                rows: [
                    .init(
                        label: "Discovery",
                        detail: discoveryDetail,
                        tint: Color(red: 0.33, green: 0.62, blue: 1.0),
                        active: hasDiscoverySignal,
                        hasIssue: discoveryRefreshFailureCount > 0
                    ),
                    .init(
                        label: "Experience",
                        detail: experienceDetail,
                        tint: Color(red: 0.62, green: 0.43, blue: 1.0),
                        active: hasExperienceSignal,
                        hasIssue: experienceRefreshFailureCount > 0
                    ),
                    .init(
                        label: "Availability",
                        detail: availabilityDetail,
                        tint: AppTheme.success,
                        active: hasAvailabilitySignal,
                        hasIssue: availabilityRefreshFailureCount > 0
                    ),
                ]
            )
        }
        .padding(18)
        .providerSurface(accent: AppTheme.signal)
    }

    private func serviceCard(_ account: SiteIntegrationAccount) -> some View {
        let snapshot = store.snapshot(for: account.id)
        let status = siteServiceStatus(snapshot: snapshot, refreshError: store.refreshErrors[account.id])
        return VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                SiteProviderIconTile(provider: account.provider, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text(account.provider.displayName)
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(account.name)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 6)
                AppStatusBadge(
                    text: status.text,
                    tone: status.tone
                )
            }

            HStack(spacing: 18) {
                compactMetric(
                    value: (snapshot?.resources.count ?? 0).formatted(),
                    label: resourceNoun(for: account.provider, count: snapshot?.resources.count ?? 0)
                )

                if let metric = snapshot?.metrics.first(where: { !isResourceCountMetric($0) }) {
                    compactMetric(value: format(metric), label: metric.label)
                } else {
                    compactMetric(value: "—", label: "Latest signal")
                }

                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .topLeading)
        .providerSurface(accent: account.provider.accentColor)
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open \(account.provider.displayName) details")
    }

    private var addServiceCard: some View {
        HStack(spacing: 13) {
            AppIconTile(icon: "plus", tint: AppTheme.textSecondary, size: 42)
            VStack(alignment: .leading, spacing: 3) {
                Text("Add a site service")
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Connect another source of site intelligence")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 6)
            Image(systemName: "arrow.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
        .appSurface()
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open the connection picker")
    }

    private func siteCard(_ site: AggregatedSite) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                AppIconTile(icon: "globe", tint: site.accentColor, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text(site.name)
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(site.subtitle)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }

            HStack(spacing: 6) {
                ForEach(Array(site.providers.prefix(4))) { provider in
                    SiteProviderIconTile(provider: provider, size: 25)
                }
                if site.providers.count > 4 {
                    Text("+\(site.providers.count - 4)")
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppTheme.textSecondary)
                }
                Spacer()
                if let metric = site.metrics.first {
                    Text("\(metric.label) \(format(metric))")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 126, alignment: .topLeading)
        .appSurface()
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open \(site.name) details")
    }

    private func compactMetric(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .lineLimit(1)
            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.7)
                .foregroundStyle(AppTheme.textTertiary)
                .lineLimit(1)
        }
    }

    private func resourceNoun(for provider: SiteIntegrationProvider, count: Int) -> String {
        let singular: String
        switch provider {
        case .googleSearchConsole, .googleAnalytics:
            singular = "Property"
        case .pageSpeed, .bingWebmaster, .clarity, .plausible, .umami:
            singular = "Site"
        case .uptimeRobot, .betterStack:
            singular = "Monitor"
        }
        return count == 1 ? singular : "\(singular)s"
    }

    private func isResourceCountMetric(_ metric: SiteIntegrationMetric) -> Bool {
        let label = metric.label
            .replacingOccurrences(of: " · Partial", with: "")
            .lowercased()
        return ["properties", "sites", "monitors"].contains(label)
    }

    private var serviceColumns: [GridItem] {
        AppLayout.adaptiveColumns(for: horizontalSizeClass, regularMinimum: 330, regularMaximum: 520, spacing: 14)
    }

    private var siteColumns: [GridItem] {
        AppLayout.adaptiveColumns(for: horizontalSizeClass, regularMinimum: 310, regularMaximum: 500, spacing: 14)
    }

    private var snapshotWarnings: [String] {
        snapshots.flatMap(\.warnings)
    }

    private var connectedProviders: Set<SiteIntegrationProvider> {
        Set(store.accounts.map(\.provider))
    }

    private var hasDiscoverySignal: Bool {
        !connectedProviders.isDisjoint(with: [.googleSearchConsole, .bingWebmaster])
    }

    private var hasExperienceSignal: Bool {
        !connectedProviders.isDisjoint(with: [.googleAnalytics, .clarity, .plausible, .umami])
    }

    private var hasAvailabilitySignal: Bool {
        !connectedProviders.isDisjoint(with: [.pageSpeed, .uptimeRobot, .betterStack])
    }

    private var discoveryRefreshFailureCount: Int {
        refreshFailureCount(for: [.googleSearchConsole, .bingWebmaster])
    }

    private var experienceRefreshFailureCount: Int {
        refreshFailureCount(for: [.googleAnalytics, .clarity, .plausible, .umami])
    }

    private var availabilityRefreshFailureCount: Int {
        refreshFailureCount(for: [.pageSpeed, .uptimeRobot, .betterStack])
    }

    private var discoveryDetail: String {
        coverageDetail(
            failureCount: discoveryRefreshFailureCount,
            connected: hasDiscoverySignal,
            connectedText: "Search connected",
            missingText: "Add search data"
        )
    }

    private var experienceDetail: String {
        coverageDetail(
            failureCount: experienceRefreshFailureCount,
            connected: hasExperienceSignal,
            connectedText: "Traffic connected",
            missingText: "Add analytics"
        )
    }

    private var availabilityDetail: String {
        coverageDetail(
            failureCount: availabilityRefreshFailureCount,
            connected: hasAvailabilitySignal,
            connectedText: "Health connected",
            missingText: "Add performance"
        )
    }

    private func refreshFailureCount(for providers: Set<SiteIntegrationProvider>) -> Int {
        refreshFailures.count { providers.contains($0.account.provider) }
    }

    private func coverageDetail(
        failureCount: Int,
        connected: Bool,
        connectedText: String,
        missingText: String
    ) -> String {
        if failureCount > 0 {
            return "\(failureCount) refresh \(failureCount == 1 ? "failed" : "failures")"
        }
        return connected ? connectedText : missingText
    }

    private func refreshAll() {
        if !reduceMotion {
            withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
        }
        Task { await loadAll(force: true) }
    }

    private func loadAll(force: Bool) async {
        await refreshSiteAccounts(
            store,
            accountIDs: store.accounts.map(\.id),
            force: force
        )
    }

    private var siteAccountIdentity: String {
        store.accounts.map(\.id.uuidString).sorted().joined(separator: ",")
    }

    private func resourceHasIssue(_ resource: SiteIntegrationResource) -> Bool {
        guard let status = resource.status else { return false }
        switch statusTone(status) {
        case .warning, .danger:
            return true
        case .success, .progress, .neutral:
            return false
        }
    }
}

private struct SiteProviderIconTile: View {
    let provider: SiteIntegrationProvider
    var size: CGFloat

    var body: some View {
        SiteProviderMark(provider: provider, size: size * 0.52)
            .frame(width: size, height: size)
            .background(provider.accentColor.opacity(0.105))
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous)
                    .strokeBorder(provider.accentColor.opacity(0.12), lineWidth: 0.5)
            }
            .accessibilityHidden(true)
    }
}

private struct SignalCoverageRail: View {
    struct Row: Identifiable {
        let label: String
        let detail: String
        let tint: Color
        let active: Bool
        let hasIssue: Bool
        var id: String { label }

        init(label: String, detail: String, tint: Color, active: Bool, hasIssue: Bool = false) {
            self.label = label
            self.detail = detail
            self.tint = tint
            self.active = active
            self.hasIssue = hasIssue
        }

        var effectiveTint: Color {
            hasIssue ? AppTheme.danger : tint
        }
    }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let rows: [Row]

    var body: some View {
        VStack(spacing: 10) {
            ForEach(rows) { row in
                coverageRow(row)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func coverageRow(_ row: Row) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 10) {
                    statusDot(row)
                    Text(row.label)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                }

                Text(row.detail)
                    .font(.caption)
                    .foregroundStyle(detailColor(row))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 17)

                coverageTrack(row)
                    .padding(.leading, 17)
            }
        } else {
            HStack(spacing: 10) {
                statusDot(row)

                Text(row.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(1)
                    .layoutPriority(2)

                coverageTrack(row)

                Text(row.detail)
                    .font(.caption2)
                    .foregroundStyle(detailColor(row))
                    .lineLimit(1)
                    .multilineTextAlignment(.trailing)
                    .layoutPriority(1)
            }
        }
    }

    private func statusDot(_ row: Row) -> some View {
        Circle()
            .fill(row.active || row.hasIssue ? row.effectiveTint : AppTheme.surfaceRaised)
            .frame(width: 7, height: 7)
    }

    private func coverageTrack(_ row: Row) -> some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(AppTheme.surfaceRaised)
                Capsule()
                    .fill(row.effectiveTint.opacity(row.active || row.hasIssue ? 0.9 : 0.12))
                    .frame(width: geometry.size.width * (row.active || row.hasIssue ? 1 : 0.16))
            }
        }
        .frame(minWidth: 28, maxWidth: .infinity)
        .frame(height: 3)
    }

    private func detailColor(_ row: Row) -> Color {
        if row.hasIssue { return AppTheme.danger }
        return row.active ? AppTheme.textSecondary : AppTheme.textTertiary
    }
}

private struct SourcedSiteResource: Identifiable {
    let accountID: UUID
    let resource: SiteIntegrationResource

    var id: String {
        "\(accountID.uuidString)|\(resource.provider.rawValue)|\(resource.id)"
    }
}

private struct AggregatedSite: Identifiable {
    let key: String
    let sources: [SourcedSiteResource]

    var id: String { key }
    var resources: [SiteIntegrationResource] { sources.map(\.resource) }
    var name: String {
        if !key.contains(":") { return key }
        return resources.first?.name ?? "Site"
    }
    var subtitle: String {
        let statuses = resources.compactMap(\.status).uniqued()
        if let first = statuses.first { return "\(providers.count) signals · \(first)" }
        return "\(providers.count) connected \(providers.count == 1 ? "signal" : "signals")"
    }
    var providers: [SiteIntegrationProvider] {
        resources.map(\.provider).uniqued().sorted { $0.displayName < $1.displayName }
    }
    var metrics: [SiteIntegrationMetric] {
        resources.flatMap(\.metrics)
    }
    var accentColor: Color { providers.first?.accentColor ?? AppTheme.signal }
}

private struct SiteServiceDetailView: View {
    @Environment(SiteStore.self) private var store
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let accountID: UUID
    var initialResourceID: String? = nil

    @State private var preparedSearchConsoleAccount: SiteIntegrationAccount?
    @State private var preparationError: String?
    @State private var isPreparing = false

    private var account: SiteIntegrationAccount? {
        store.accounts.first { $0.id == accountID }
    }

    private var taskIdentity: String {
        account.map(CredentialCacheScope.siteIntegrationAccount)
            ?? "\(accountID.uuidString.lowercased())|removed"
    }

    private var preferredSearchConsoleSiteURL: String? {
        guard let initialResourceID,
              let resource = store.snapshot(for: accountID)?.resources.first(where: { $0.id == initialResourceID }) else {
            return nil
        }
        return resource.metadata["propertyID"] ?? resource.subtitle
    }

    var body: some View {
        Group {
            if let account {
                if account.provider == .googleSearchConsole {
                    searchConsoleDestination(account)
                } else {
                    SiteIntegrationProviderDetailView(
                        accountID: accountID,
                        initialResourceID: initialResourceID
                    )
                }
            } else {
                ZStack {
                    AppTheme.canvas.ignoresSafeArea()
                    AppFeedbackBanner(
                        title: "This service is no longer connected",
                        message: "Return to Sites to choose another connected service.",
                        icon: "link.badge.plus",
                        tint: AppTheme.textSecondary
                    )
                    .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                    .appContentWidth(560, horizontalSizeClass: horizontalSizeClass)
                }
                .navigationTitle("Site service")
            }
        }
        .task(id: taskIdentity) {
            guard account?.provider == .googleSearchConsole else { return }
            await prepareSearchConsoleAccount(forceCredentialRefresh: false)
        }
    }

    @ViewBuilder
    private func searchConsoleDestination(_ account: SiteIntegrationAccount) -> some View {
        if let preparedSearchConsoleAccount {
            SearchConsoleDetailView(
                account: preparedSearchConsoleAccount,
                initialSiteURL: preferredSearchConsoleSiteURL,
                refreshAccount: {
                    try await store.accountForDirectRequest(
                        id: accountID,
                        forceCredentialRefresh: true
                    )
                }
            )
        } else if let preparationError {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                AppFeedbackBanner(
                    title: "Couldn’t open Search Console",
                    message: preparationError,
                    tint: AppTheme.danger,
                    actionTitle: "Try again"
                ) {
                    Task { await prepareSearchConsoleAccount(forceCredentialRefresh: true) }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .appContentWidth(560, horizontalSizeClass: horizontalSizeClass)
            }
            .navigationTitle(account.provider.displayName)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                AppDashboardLoadingView(
                    accent: SiteIntegrationProvider.googleSearchConsole.accentColor,
                    showsMetrics: true
                )
            }
            .navigationTitle(account.provider.displayName)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    @MainActor
    private func prepareSearchConsoleAccount(forceCredentialRefresh: Bool) async {
        guard !isPreparing else { return }
        isPreparing = true
        preparationError = nil
        defer { isPreparing = false }
        do {
            preparedSearchConsoleAccount = try await store.accountForDirectRequest(
                id: accountID,
                forceCredentialRefresh: forceCredentialRefresh
            )
        } catch is CancellationError {
            return
        } catch {
            preparedSearchConsoleAccount = nil
            preparationError = error.localizedDescription
        }
    }
}

private struct AggregatedSiteDetailView: View {
    @Environment(SiteStore.self) private var store
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let siteKey: String

    private var site: AggregatedSite {
        AggregatedSite(key: siteKey, sources: currentSources)
    }

    private var currentSources: [SourcedSiteResource] {
        store.accounts.flatMap { account -> [SourcedSiteResource] in
            guard let snapshot = store.snapshot(for: account.id) else { return [] }
            return snapshot.resources.compactMap { resource in
                guard canonicalSiteKey(resource) == siteKey else { return nil }
                return SourcedSiteResource(accountID: account.id, resource: resource)
            }
        }
    }

    private var sourceAccountIDs: [UUID] {
        currentSources.map(\.accountID).uniqued()
    }

    private var sourceRefreshFailures: [(provider: SiteIntegrationProvider, message: String)] {
        let accountIDs = Set(sourceAccountIDs)
        return store.accounts.compactMap { account -> (provider: SiteIntegrationProvider, message: String)? in
            guard accountIDs.contains(account.id),
                  let message = store.refreshErrors[account.id] else { return nil }
            return (account.provider, message)
        }
    }

    private var sourceRefreshFailureSummary: String {
        var lines = sourceRefreshFailures.prefix(3).map {
            "\($0.provider.displayName): \($0.message)"
        }
        let remaining = sourceRefreshFailures.count - lines.count
        if remaining > 0 {
            lines.append("And \(remaining) more \(remaining == 1 ? "source" : "sources").")
        }
        return lines.joined(separator: "\n")
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 13) {
                            AppIconTile(icon: "globe", tint: site.accentColor, size: 52)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(site.name)
                                    .font(.title3.weight(.semibold))
                                    .foregroundStyle(AppTheme.textPrimary)
                                Text(site.subtitle)
                                    .font(.footnote)
                                    .foregroundStyle(AppTheme.textSecondary)
                            }
                            Spacer()
                        }
                        SignalCoverageRail(rows: siteCoverageRows)
                    }
                    .padding(18)
                    .providerSurface(accent: site.accentColor)

                    if !sourceRefreshFailures.isEmpty {
                        AppFeedbackBanner(
                            title: "Some source data is saved",
                            message: sourceRefreshFailureSummary,
                            tint: AppTheme.danger,
                            actionTitle: "Try again"
                        ) {
                            refreshSources()
                        }
                    }

                    if site.resources.isEmpty {
                        AppFeedbackBanner(
                            title: "This site is no longer available",
                            message: "Its connected sources were removed or no longer return this site.",
                            icon: "network.slash",
                            tint: AppTheme.textSecondary
                        )
                    } else {
                        AppSectionHeader(title: "Connected sources", count: site.resources.count)
                        ForEach(site.sources) { source in
                            let resource = source.resource
                            NavigationLink {
                                SiteServiceDetailView(
                                    accountID: source.accountID,
                                    initialResourceID: resource.id
                                )
                            } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack(spacing: 11) {
                                        SiteProviderIconTile(provider: resource.provider, size: 38)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(resource.provider.displayName)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(AppTheme.textPrimary)
                                            Text(resource.status ?? resource.subtitle ?? "Connected")
                                                .font(.footnote)
                                                .foregroundStyle(AppTheme.textSecondary)
                                        }
                                        Spacer()
                                        Image(systemName: "chevron.right")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(AppTheme.textTertiary)
                                    }

                                    if !resource.metrics.isEmpty {
                                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 8)], spacing: 8) {
                                            ForEach(resource.metrics.prefix(8)) { metric in
                                                VStack(alignment: .leading, spacing: 2) {
                                                    Text(format(metric))
                                                        .font(.caption.weight(.semibold).monospacedDigit())
                                                        .foregroundStyle(AppTheme.textPrimary)
                                                    Text(metric.label)
                                                        .font(.caption2)
                                                        .foregroundStyle(AppTheme.textTertiary)
                                                        .lineLimit(1)
                                                }
                                                .frame(maxWidth: .infinity, alignment: .leading)
                                                .padding(9)
                                                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                                            }
                                        }
                                    }
                                }
                                .padding(15)
                                .appSurface()
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.top, 18)
                .padding(.bottom, 28)
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(site.name)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await refreshSiteAccounts(store, accountIDs: sourceAccountIDs, force: true)
        }
    }

    private var siteCoverageRows: [SignalCoverageRail.Row] {
        let providers = Set(site.providers)
        let discovery = !providers.isDisjoint(with: [.googleSearchConsole, .bingWebmaster])
        let experience = !providers.isDisjoint(with: [.googleAnalytics, .clarity, .plausible, .umami])
        let availability = !providers.isDisjoint(with: [.pageSpeed, .uptimeRobot, .betterStack])
        return [
            .init(label: "Discovery", detail: discovery ? "Connected" : "Not connected", tint: Color(red: 0.33, green: 0.62, blue: 1.0), active: discovery),
            .init(label: "Experience", detail: experience ? "Connected" : "Not connected", tint: Color(red: 0.62, green: 0.43, blue: 1.0), active: experience),
            .init(label: "Availability", detail: availability ? "Connected" : "Not connected", tint: AppTheme.success, active: availability),
        ]
    }

    private func refreshSources() {
        let accountIDs = sourceAccountIDs
        Task {
            await refreshSiteAccounts(store, accountIDs: accountIDs, force: true)
        }
    }
}

private struct SiteServiceStatusPresentation {
    let text: String
    let tone: AppStatusTone
}

private func siteServiceStatus(
    snapshot: SiteIntegrationSnapshot?,
    refreshError: String?
) -> SiteServiceStatusPresentation {
    if refreshError != nil {
        return SiteServiceStatusPresentation(text: "Refresh failed", tone: .danger)
    }
    guard let snapshot else {
        return SiteServiceStatusPresentation(text: "Loading", tone: .progress)
    }
    if let status = snapshot.status, !status.isEmpty {
        let tone = statusTone(status)
        if case .danger = tone {
            return SiteServiceStatusPresentation(text: status, tone: tone)
        }
        if !snapshot.warnings.isEmpty {
            return SiteServiceStatusPresentation(text: "Needs review", tone: .warning)
        }
        return SiteServiceStatusPresentation(text: status, tone: tone)
    }
    if !snapshot.warnings.isEmpty {
        return SiteServiceStatusPresentation(text: "Needs review", tone: .warning)
    }
    return SiteServiceStatusPresentation(text: "Connected", tone: .success)
}

@MainActor
private func refreshSiteAccounts(
    _ store: SiteStore,
    accountIDs: [UUID],
    force: Bool
) async {
    await withTaskGroup(of: Void.self) { group in
        for accountID in accountIDs {
            group.addTask { @MainActor in
                await store.refresh(accountID: accountID, force: force)
            }
        }
        await group.waitForAll()
    }
}

private func canonicalSiteKey(_ resource: SiteIntegrationResource) -> String {
    if let key = canonicalURLSiteKey(resource.url) {
        return key
    }
    for key in ["domain", "siteURL", "url"] {
        if let raw = resource.metadata[key], let value = canonicalURLSiteKey(URL(string: raw)) {
            return value
        }
    }
    let name = resource.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if name.contains("."), !name.contains(" ") {
        return name.removingPrefix("www.")
    }
    return "\(resource.provider.rawValue):\(resource.id)"
}

private func canonicalURLSiteKey(_ url: URL?) -> String? {
    guard let url,
          var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let host = components.host?.lowercased() else { return nil }
    let canonicalHost = host.removingPrefix("www.")
    components.query = nil
    components.fragment = nil
    if components.path == "/" { components.path = "" }
    while components.path.count > 1, components.path.hasSuffix("/") {
        components.path.removeLast()
    }
    let port = components.port.map { ":\($0)" } ?? ""
    return "\(canonicalHost)\(port)\(components.percentEncodedPath)".lowercased()
}

private func format(_ metric: SiteIntegrationMetric) -> String {
    if let formatted = metric.formattedValue, !formatted.isEmpty { return formatted }
    switch metric.unit {
    case .count:
        return metric.value.formatted(.number.precision(.fractionLength(0)))
    case .percent:
        return metric.value.formatted(.number.precision(.fractionLength(1))) + "%"
    case .milliseconds:
        return metric.value.formatted(.number.precision(.fractionLength(0))) + " ms"
    case .seconds:
        return metric.value.formatted(.number.precision(.fractionLength(1))) + " s"
    case .bytes:
        return ByteCountFormatter.string(fromByteCount: Int64(metric.value), countStyle: .file)
    case .score:
        let value = metric.value <= 1 ? metric.value * 100 : metric.value
        return value.formatted(.number.precision(.fractionLength(0)))
    case .ratio:
        return metric.value.formatted(.number.precision(.fractionLength(2)))
    case .position:
        return metric.value.formatted(.number.precision(.fractionLength(1)))
    case .none:
        return metric.value.formatted(.number.precision(.fractionLength(0...2)))
    }
}

private func statusTone(_ status: String) -> AppStatusTone {
    let value = status.lowercased()
    if ["down", "error", "fail", "offline", "poor", "expired", "blocked", "disabled", "inactive", "unhealthy"].contains(where: value.contains) {
        return .danger
    }
    if ["paused", "warning", "pending", "unknown", "degraded", "attention", "needs work", "not checked", "not ready", "not connected", "not verified", "unverified", "not ok"].contains(where: value.contains) {
        return .warning
    }
    if ["refreshing", "loading", "checking", "progress", "initializing"].contains(where: value.contains) {
        return .progress
    }
    if value.containsStatusWord("up")
        || ["active", "verified", "connected", "good", "healthy", "operational", "ok", "clear"].contains(where: value.contains) {
        return .success
    }
    return .neutral
}

private extension String {
    func containsStatusWord(_ word: String) -> Bool {
        split { !$0.isLetter && !$0.isNumber }
            .contains { $0 == Substring(word) }
    }

    func removingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }
}

private extension Sequence where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
