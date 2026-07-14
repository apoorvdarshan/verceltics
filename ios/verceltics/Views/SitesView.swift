import SwiftUI

struct SitesView: View {
    @Environment(SiteStore.self) private var store
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var startWithSearch = false

    @State private var searchText = ""
    @State private var isSearching = false
    @State private var showingConnection = false
    @State private var refreshSpin = 0.0

    private var snapshots: [SiteIntegrationSnapshot] {
        store.accounts.compactMap { store.snapshot(for: $0.id) }
    }

    private var allResources: [SiteIntegrationResource] {
        snapshots.flatMap(\.resources)
    }

    private var sites: [AggregatedSite] {
        let grouped = Dictionary(grouping: allResources, by: canonicalSiteKey)
        return grouped.map { key, resources in
            AggregatedSite(key: key, resources: resources)
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
            .toolbarColorScheme(.dark, for: .navigationBar)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search sites and signals")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    SiteAccountMenu()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: refreshAll) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.72))
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(store.isRefreshing || store.accounts.isEmpty)
                    .accessibilityLabel(store.isRefreshing ? "Refreshing site services" : "Refresh site services")
                }
            }
            .task(id: store.accounts.map(\.id)) {
                await loadAll(force: false)
            }
            .onAppear {
                if startWithSearch { isSearching = true }
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

                if let error = store.error {
                    AppFeedbackBanner(
                        title: "Some site signals could not refresh",
                        message: error,
                        actionTitle: "Try again"
                    ) {
                        refreshAll()
                    }
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
                                AggregatedSiteDetailView(site: site)
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
        AppEmptyState(
            icon: "chart.xyaxis.line",
            title: "Connect your site signals",
            message: "Bring search, traffic, performance, experience, and uptime into one site-level dashboard.",
            actionTitle: "Connect a service"
        ) {
            showingConnection = true
        }
    }

    private var signalOverview: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("SITE SIGNAL")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.3)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text("\(uniqueSiteCount.formatted()) \(uniqueSiteCount == 1 ? "site" : "sites") in view")
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
                    tone: warningCount == 0 ? .success : .warning
                )
            }

            SignalCoverageRail(
                rows: [
                    .init(label: "Discovery", detail: discoveryDetail, tint: Color(red: 0.33, green: 0.62, blue: 1.0), active: hasDiscoverySignal),
                    .init(label: "Experience", detail: experienceDetail, tint: Color(red: 0.62, green: 0.43, blue: 1.0), active: hasExperienceSignal),
                    .init(label: "Availability", detail: availabilityDetail, tint: AppTheme.success, active: hasAvailabilitySignal),
                ]
            )
        }
        .padding(18)
        .providerSurface(accent: AppTheme.signal)
    }

    private func serviceCard(_ account: SiteIntegrationAccount) -> some View {
        let snapshot = store.snapshot(for: account.id)
        return VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                AppIconTile(icon: account.provider.systemImage, tint: account.provider.accentColor, size: 42)
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
                    text: snapshot == nil ? "Ready" : (snapshot?.status ?? "Connected"),
                    tone: snapshot?.warnings.isEmpty == false ? .warning : .success
                )
            }

            HStack(spacing: 18) {
                compactMetric(
                    value: (snapshot?.resources.count ?? 0).formatted(),
                    label: "Properties"
                )

                if let metric = snapshot?.metrics.first {
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
                    Image(systemName: provider.systemImage)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(provider.accentColor)
                        .frame(width: 25, height: 25)
                        .background(provider.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                        .accessibilityLabel(provider.displayName)
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

    private var serviceColumns: [GridItem] {
        AppLayout.adaptiveColumns(for: horizontalSizeClass, regularMinimum: 330, regularMaximum: 520, spacing: 14)
    }

    private var siteColumns: [GridItem] {
        AppLayout.adaptiveColumns(for: horizontalSizeClass, regularMinimum: 310, regularMaximum: 500, spacing: 14)
    }

    private var snapshotWarnings: [String] {
        snapshots.flatMap(\.warnings)
    }

    private var uniqueSiteCount: Int { Set(allResources.map(canonicalSiteKey)).count }

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

    private var discoveryDetail: String { hasDiscoverySignal ? "Search connected" : "Add search data" }
    private var experienceDetail: String { hasExperienceSignal ? "Traffic connected" : "Add analytics" }
    private var availabilityDetail: String { hasAvailabilitySignal ? "Health connected" : "Add performance" }

    private func refreshAll() {
        if !reduceMotion {
            withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
        }
        Task { await loadAll(force: true) }
    }

    private func loadAll(force: Bool) async {
        for account in store.accounts {
            await store.refresh(accountID: account.id, force: force)
        }
    }

    private func canonicalSiteKey(_ resource: SiteIntegrationResource) -> String {
        if let host = resource.url?.host?.lowercased() {
            return host.removingPrefix("www.")
        }
        for key in ["domain", "siteURL", "url"] {
            if let raw = resource.metadata[key],
               let host = URL(string: raw)?.host?.lowercased() {
                return host.removingPrefix("www.")
            }
        }
        let name = resource.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if name.contains("."), !name.contains(" ") {
            return name.removingPrefix("www.")
        }
        return "\(resource.provider.rawValue):\(resource.id)"
    }

    private func resourceHasIssue(_ resource: SiteIntegrationResource) -> Bool {
        guard let status = resource.status?.lowercased() else { return false }
        return ["down", "error", "failed", "failing", "offline", "paused", "poor"].contains { status.contains($0) }
    }
}

private struct SignalCoverageRail: View {
    struct Row: Identifiable {
        let label: String
        let detail: String
        let tint: Color
        let active: Bool
        var id: String { label }
    }

    let rows: [Row]

    var body: some View {
        VStack(spacing: 10) {
            ForEach(rows) { row in
                HStack(spacing: 10) {
                    Circle()
                        .fill(row.active ? row.tint : AppTheme.surfaceRaised)
                        .frame(width: 7, height: 7)

                    Text(row.label)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .frame(width: 78, alignment: .leading)

                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(AppTheme.surfaceRaised)
                            Capsule()
                                .fill(row.tint.opacity(row.active ? 0.9 : 0.12))
                                .frame(width: geometry.size.width * (row.active ? 1 : 0.16))
                        }
                    }
                    .frame(height: 3)

                    Text(row.detail)
                        .font(.caption2)
                        .foregroundStyle(row.active ? AppTheme.textSecondary : AppTheme.textTertiary)
                        .frame(width: 102, alignment: .trailing)
                        .lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct AggregatedSite: Identifiable {
    let key: String
    let resources: [SiteIntegrationResource]

    var id: String { key }
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

    private var account: SiteIntegrationAccount? {
        store.accounts.first { $0.id == accountID }
    }
    private var snapshot: SiteIntegrationSnapshot? { store.snapshot(for: accountID) }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 16) {
                    if let account {
                        sourceHeader(account)
                    }

                    if let snapshot {
                        if !snapshot.metrics.isEmpty {
                            AppSectionHeader(title: "Account metrics", count: snapshot.metrics.count)
                            LazyVGrid(columns: metricColumns, spacing: 10) {
                                ForEach(snapshot.metrics) { metric in
                                    metricCard(metric)
                                }
                            }
                        }

                        AppSectionHeader(title: "Properties", count: snapshot.resources.count)
                        ForEach(snapshot.resources) { resource in
                            resourceCard(resource)
                        }
                    } else {
                        AppDashboardLoadingView(accent: account?.provider.accentColor ?? AppTheme.signal)
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.top, 18)
                .padding(.bottom, 28)
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(account?.provider.displayName ?? "Site service")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await store.refresh(accountID: accountID, force: false) }
        .refreshable { await store.refresh(accountID: accountID, force: true) }
    }

    private func sourceHeader(_ account: SiteIntegrationAccount) -> some View {
        HStack(spacing: 13) {
            AppIconTile(icon: account.provider.systemImage, tint: account.provider.accentColor, size: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(account.name)
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
                Text(account.provider.connectionSubtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            AppStatusBadge(text: snapshot?.status ?? "Connected", tone: snapshot?.warnings.isEmpty == false ? .warning : .success)
        }
        .padding(18)
        .providerSurface(accent: account.provider.accentColor)
    }

    private var metricColumns: [GridItem] {
        [GridItem(.adaptive(minimum: 132), spacing: 10)]
    }

    private func metricCard(_ metric: SiteIntegrationMetric) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(metric.label.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.9)
                .foregroundStyle(AppTheme.textSecondary)
            Text(format(metric))
                .font(.title3.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
        }
        .frame(maxWidth: .infinity, minHeight: 78, alignment: .leading)
        .padding(14)
        .appSurface()
    }

    private func resourceCard(_ resource: SiteIntegrationResource) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                AppIconTile(icon: resource.provider.systemImage, tint: resource.provider.accentColor, size: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text(resource.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    if let subtitle = resource.subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                if let status = resource.status {
                    AppStatusBadge(text: status, tone: statusTone(status))
                }
            }

            if !resource.metrics.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 105), spacing: 8)], spacing: 8) {
                    ForEach(resource.metrics.prefix(6)) { metric in
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

            if let url = resource.url {
                Link(destination: url) {
                    Label("Open site", systemImage: "arrow.up.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(resource.provider.accentColor)
                }
            }
        }
        .padding(15)
        .appSurface()
    }
}

private struct AggregatedSiteDetailView: View {
    let site: AggregatedSite
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

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

                    AppSectionHeader(title: "Connected sources", count: site.resources.count)
                    ForEach(site.resources) { resource in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 11) {
                                AppIconTile(icon: resource.provider.systemImage, tint: resource.provider.accentColor, size: 38)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(resource.provider.displayName)
                                        .font(.subheadline.weight(.semibold))
                                    Text(resource.status ?? resource.subtitle ?? "Connected")
                                        .font(.footnote)
                                        .foregroundStyle(AppTheme.textSecondary)
                                }
                                Spacer()
                            }
                            if !resource.metrics.isEmpty {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 8)], spacing: 8) {
                                    ForEach(resource.metrics.prefix(8)) { metric in
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(format(metric))
                                                .font(.caption.weight(.semibold).monospacedDigit())
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
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.top, 18)
                .padding(.bottom, 28)
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(site.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
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
    if ["down", "error", "failed", "offline", "poor"].contains(where: value.contains) { return .danger }
    if ["paused", "warning", "pending", "unknown"].contains(where: value.contains) { return .warning }
    if ["up", "active", "verified", "connected", "good", "ok"].contains(where: value.contains) { return .success }
    return .neutral
}

private extension String {
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
