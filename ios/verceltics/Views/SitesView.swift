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

    private var selection: SiteDashboardSelection? {
        SiteDashboardSelection.active(
            accounts: store.accounts,
            snapshots: store.snapshots,
            activeAccountID: store.activeAccountID
        )
    }

    private var activeAccount: SiteIntegrationAccount? {
        selection?.account
    }

    private var activeSnapshot: SiteIntegrationSnapshot? {
        selection?.snapshot
    }

    private var resources: [SiteIntegrationResource] {
        (activeSnapshot?.resources ?? [])
        .filter { resource in
            searchText.isEmpty
                || resource.name.localizedCaseInsensitiveContains(searchText)
                || resource.provider.displayName.localizedCaseInsensitiveContains(searchText)
                || (resource.status?.localizedCaseInsensitiveContains(searchText) ?? false)
                || (resource.subtitle?.localizedCaseInsensitiveContains(searchText) ?? false)
                || resource.metrics.contains {
                    $0.label.localizedCaseInsensitiveContains(searchText)
                        || (format($0).localizedCaseInsensitiveContains(searchText))
                }
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private var activeRefreshError: String? {
        guard let id = activeAccount?.id,
              let message = store.refreshErrors[id],
              !message.isEmpty else { return nil }
        return message
    }

    private var persistenceError: String? {
        guard let message = store.persistenceError, !message.isEmpty else { return nil }
        return message
    }

    private var isRefreshingActiveAccount: Bool {
        store.isRefreshing(accountID: activeAccount?.id)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                if activeAccount == nil {
                    emptyState
                } else if isRefreshingActiveAccount && activeSnapshot == nil {
                    AppDashboardLoadingView(accent: activeAccount?.provider.accentColor ?? AppTheme.signal)
                } else {
                    dashboard
                }
            }
            .navigationTitle("Sites")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: searchPrompt)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    SiteAccountMenu()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: refreshActive) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(isRefreshingActiveAccount || activeAccount == nil)
                    .accessibilityLabel(isRefreshingActiveAccount ? "Refreshing selected site service" : "Refresh selected site service")
                }
            }
            .task(id: siteAccountIdentity) {
                await loadActive(force: false)
            }
            .onChange(of: backgroundRefreshRequestID) { _, _ in
                Task { await loadActive(force: false) }
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
        .id(siteAccountIdentity)
        .onChange(of: store.activeAccountID) { _, _ in
            searchText = ""
        }
    }

    private var dashboard: some View {
        ScrollView {
            LazyVStack(spacing: 18) {
                if let account = activeAccount {
                    NavigationLink {
                        SiteServiceDetailView(accountID: account.id)
                    } label: {
                        serviceOverview(account)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                }

                if let activeRefreshError, let account = activeAccount {
                    AppFeedbackBanner(
                        title: "\(account.provider.displayName) could not refresh",
                        message: activeRefreshError,
                        actionTitle: "Try again"
                    ) {
                        refreshActive()
                    }
                }

                if let persistenceError {
                    AppFeedbackBanner(
                        title: "Saved site data needs attention",
                        message: persistenceError,
                        icon: "lock.trianglebadge.exclamationmark.fill",
                        tint: AppTheme.danger
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

                AppSectionHeader(
                    title: resourceSectionTitle,
                    count: resources.count,
                    accent: activeAccount?.provider.accentColor ?? AppTheme.signal
                )

                if resources.isEmpty {
                    AppEmptyState(
                        icon: searchText.isEmpty ? "network.slash" : "magnifyingglass",
                        title: searchText.isEmpty ? "No \(resourceSectionTitle.lowercased()) returned yet" : "No matching \(resourceSectionTitle.lowercased())",
                        message: searchText.isEmpty
                            ? emptyResourceMessage
                            : "Nothing matches “\(searchText)”.",
                        actionTitle: searchText.isEmpty ? "Refresh service" : "Clear search"
                    ) {
                        if searchText.isEmpty { refreshActive() }
                        else { searchText = "" }
                    }
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    LazyVGrid(columns: siteColumns, spacing: 14) {
                        ForEach(resources) { resource in
                            NavigationLink {
                                if let account = activeAccount {
                                    SiteServiceDetailView(
                                        accountID: account.id,
                                        initialResourceID: resource.id
                                    )
                                }
                            } label: {
                                resourceCard(resource)
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                    }
                }

                Button { showingConnection = true } label: {
                    addServiceCard
                }
                .buttonStyle(PressScaleButtonStyle())
            }
            .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
            .padding(.top, 18)
            .padding(.bottom, 28)
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await loadActive(force: true) }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            if let error = persistenceError {
                AppFeedbackBanner(
                    title: "Saved site services need attention",
                    message: error,
                    icon: "lock.trianglebadge.exclamationmark.fill",
                    tint: AppTheme.danger
                )
            }

            AppEmptyState(
                icon: "chart.xyaxis.line",
                title: "Connect a site service",
                message: "View search, analytics, performance, and uptime providers in separate focused dashboards.",
                actionTitle: "Connect a service"
            ) {
                showingConnection = true
            }
        }
        .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
        .appContentWidth(560, horizontalSizeClass: horizontalSizeClass)
    }

    private func serviceOverview(_ account: SiteIntegrationAccount) -> some View {
        let snapshot = activeSnapshot
        let status = siteServiceStatus(snapshot: snapshot, refreshError: activeRefreshError)
        let summaryMetrics = Array(
            (snapshot?.metrics ?? [])
                .filter { !isResourceCountMetric($0) }
                .prefix(2)
        )

        return VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                SiteProviderIconTile(provider: account.provider, size: 48)
                VStack(alignment: .leading, spacing: 3) {
                    Text(account.provider.displayName)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(account.name)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                AppStatusBadge(text: status.text, tone: status.tone)
            }

            Text(account.provider.connectionSubtitle)
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(alignment: .bottom, spacing: 12) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 20) {
                        resourceCountMetric(snapshot: snapshot, provider: account.provider)
                        ForEach(summaryMetrics) { metric in
                            compactMetric(value: format(metric), label: metric.label)
                        }
                    }

                    HStack(spacing: 20) {
                        resourceCountMetric(snapshot: snapshot, provider: account.provider)
                        if let metric = summaryMetrics.first {
                            compactMetric(value: format(metric), label: metric.label)
                        }
                    }

                    resourceCountMetric(snapshot: snapshot, provider: account.provider)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .providerSurface(accent: account.provider.accentColor)
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open the selected \(account.provider.displayName) workspace")
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

    private func resourceCard(_ resource: SiteIntegrationResource) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                SiteProviderIconTile(provider: resource.provider, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text(resource.name)
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(resource.status ?? resource.subtitle ?? "Connected")
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }

            HStack(spacing: 16) {
                ForEach(Array(resource.metrics.prefix(2))) { metric in
                    compactMetric(value: format(metric), label: metric.label)
                }
                Spacer()
                if resource.metrics.isEmpty, let subtitle = resource.subtitle, resource.status != nil {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AppTheme.textTertiary)
                        .lineLimit(1)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 126, alignment: .topLeading)
        .providerSurface(accent: resource.provider.accentColor)
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open \(resource.name) in \(resource.provider.displayName)")
    }

    private func compactMetric(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .lineLimit(1)
            Text(label.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.4)
                .foregroundStyle(AppTheme.textTertiary)
                .lineLimit(1)
        }
    }

    private func resourceCountMetric(
        snapshot: SiteIntegrationSnapshot?,
        provider: SiteIntegrationProvider
    ) -> some View {
        let count = snapshot?.resources.count ?? 0
        return compactMetric(
            value: count.formatted(),
            label: resourceNoun(for: provider, count: count)
        )
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

    private var siteColumns: [GridItem] {
        AppLayout.adaptiveColumns(for: horizontalSizeClass, regularMinimum: 310, regularMaximum: 500, spacing: 14)
    }

    private var snapshotWarnings: [String] {
        activeSnapshot?.warnings ?? []
    }

    private var resourceSectionTitle: String {
        guard let provider = activeAccount?.provider else { return "Resources" }
        return resourceNoun(for: provider, count: 2)
    }

    private var searchPrompt: String {
        guard let account = activeAccount else { return "Search site services" }
        return "Search \(account.provider.displayName) \(resourceSectionTitle.lowercased())"
    }

    private var emptyResourceMessage: String {
        guard let account = activeAccount else { return "Choose a site service and refresh it." }
        return "\(account.provider.displayName) has not returned any \(resourceSectionTitle.lowercased()) yet. Refresh this service or reconnect it from the service menu."
    }

    private func refreshActive() {
        if !reduceMotion {
            withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
        }
        Task { await loadActive(force: true) }
    }

    private func loadActive(force: Bool) async {
        guard let accountID = activeAccount?.id else { return }
        // Keep the provider-owned refresh alive when SwiftUI cancels this view task
        // during a rapid service switch. Returning to the service then observes the
        // in-flight result instead of getting stranded without a snapshot.
        let refreshTask = Task { await store.refresh(accountID: accountID, force: force) }
        await refreshTask.value
    }

    private var siteAccountIdentity: UUID? {
        selection?.dashboardID
    }

}

struct SiteDashboardSelection: Equatable {
    let account: SiteIntegrationAccount
    let snapshot: SiteIntegrationSnapshot?

    var dashboardID: UUID { account.id }

    static func active(
        accounts: [SiteIntegrationAccount],
        snapshots: [UUID: SiteIntegrationSnapshot],
        activeAccountID: UUID?
    ) -> SiteDashboardSelection? {
        guard let activeAccountID,
              let account = accounts.first(where: { $0.id == activeAccountID }) else {
            return nil
        }
        return SiteDashboardSelection(
            account: account,
            snapshot: snapshots[activeAccountID]
        )
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
        .task(id: accountID) {
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
}
