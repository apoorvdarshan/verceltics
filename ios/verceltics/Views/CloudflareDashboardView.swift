import SwiftUI

@Observable
@MainActor
final class CloudflareDashboardViewModel {
    private struct CachedResources {
        let zones: [CloudflareZone]
        let pagesProjects: [CloudflarePagesProject]
        let workers: [CloudflareWorkerScript]
        let sectionWarnings: [String]
        let updatedAt: Date
    }

    private struct CachedDashboard {
        let accounts: [CloudflareAccountSummary]
        let selectedAccountID: String?
        let resourcesByAccountID: [String: CachedResources]
        let updatedAt: Date
    }

    @ResettableMemoryCache private static var dashboards: [String: CachedDashboard] = [:]
    private static let cacheLifetime: TimeInterval = 3 * 60

    private(set) var api: CloudflareAPI?
    private var loadedEmail: String?
    private var loadedCredential: String?
    private var loadedAuthenticationMode: CloudflareAuthenticationMode?
    private var loadedCacheKey: String?
    private var dashboardUpdatedAt: Date?
    private var inFlightCacheKey: String?
    private var dashboardLoadGeneration = 0
    private var resourceLoadGeneration = 0
    private var resourcesByAccountID: [String: CachedResources] = [:]

    var accounts: [CloudflareAccountSummary] = []
    var selectedAccountID: String?
    var zones: [CloudflareZone] = []
    var pagesProjects: [CloudflarePagesProject] = []
    var workers: [CloudflareWorkerScript] = []
    var isLoading = true
    var isRefreshing = false
    var error: String?
    var sectionWarnings: [String] = []

    var selectedAccount: CloudflareAccountSummary? {
        accounts.first { $0.id == selectedAccountID }
    }

    init(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String
    ) {
        let client = CloudflareAPI(
            authenticationMode: authenticationMode,
            email: email,
            credential: credential
        )
        let cacheKey = client.cacheScope
        api = client
        loadedEmail = email
        loadedCredential = credential
        loadedAuthenticationMode = authenticationMode
        loadedCacheKey = cacheKey

        guard let cached = Self.dashboards[cacheKey] else { return }
        accounts = cached.accounts
        selectedAccountID = cached.selectedAccountID ?? cached.accounts.first?.id
        resourcesByAccountID = cached.resourcesByAccountID
        dashboardUpdatedAt = cached.updatedAt
        _ = applyCachedResources(for: selectedAccountID)
        isLoading = false
    }

    func load(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String,
        preferredAccountID: String? = nil,
        forceRefresh: Bool = false
    ) async {
        let client = CloudflareAPI(
            authenticationMode: authenticationMode,
            email: email,
            credential: credential
        )
        let cacheKey = client.cacheScope
        let sameCredential = cacheKey == loadedCacheKey
        if !forceRefresh,
           sameCredential,
           isFresh(dashboardUpdatedAt),
           isFresh(resourcesByAccountID[selectedAccountID ?? ""]?.updatedAt) {
            return
        }
        guard inFlightCacheKey != cacheKey else { return }

        dashboardLoadGeneration += 1
        let dashboardGeneration = dashboardLoadGeneration
        let previousCacheKey = loadedCacheKey
        inFlightCacheKey = cacheKey
        defer {
            if dashboardGeneration == dashboardLoadGeneration {
                inFlightCacheKey = nil
                isLoading = false
                isRefreshing = false
            }
        }

        var restoredCachedDashboard = false
        if !forceRefresh, let cached = Self.dashboards[cacheKey] {
            api = client
            loadedEmail = email
            loadedCredential = credential
            loadedAuthenticationMode = authenticationMode
            loadedCacheKey = cacheKey
            accounts = cached.accounts
            let preferredSelection = preferredAccountID.flatMap { preferred in
                cached.accounts.contains(where: { $0.id == preferred }) ? preferred : nil
            }
            let cachedSelection = cached.selectedAccountID.flatMap { selected in
                cached.accounts.contains(where: { $0.id == selected }) ? selected : nil
            }
            selectedAccountID = preferredSelection ?? cachedSelection ?? cached.accounts.first?.id
            resourcesByAccountID = cached.resourcesByAccountID
            dashboardUpdatedAt = cached.updatedAt
            error = nil
            isLoading = false
            restoredCachedDashboard = true
            guard let accountID = selectedAccountID else { return }
            let hasCachedResources = applyCachedResources(for: accountID)
            if hasCachedResources,
               isFresh(cached.updatedAt),
               isFresh(cached.resourcesByAccountID[accountID]?.updatedAt) {
                return
            }

            if !hasCachedResources {
                // Account selection is persisted independently of the resource
                // cache. Load only that account while retaining the cached
                // account list instead of doing a second full dashboard fetch.
                zones = []
                pagesProjects = []
                workers = []
                sectionWarnings = []
                isRefreshing = true
                do {
                    try await loadSelectedAccount(
                        client: client,
                        accountID: accountID,
                        dashboardGeneration: dashboardGeneration
                    )
                    guard dashboardGeneration == dashboardLoadGeneration,
                          selectedAccountID == accountID else { return }
                    saveCache(cacheKey: cacheKey)
                } catch is CancellationError {
                    // A superseded credential or account selection owns the UI.
                } catch {
                    guard dashboardGeneration == dashboardLoadGeneration,
                          selectedAccountID == accountID else { return }
                    self.error = error.localizedDescription
                }
                return
            }
        }

        let credentialChanged = previousCacheKey != nil && previousCacheKey != cacheKey
        if credentialChanged && !restoredCachedDashboard {
            accounts = []
            selectedAccountID = nil
            zones = []
            pagesProjects = []
            workers = []
            sectionWarnings = []
            resourcesByAccountID = [:]
            dashboardUpdatedAt = nil
        }
        let isInitialLoad = accounts.isEmpty
        if isInitialLoad {
            isLoading = true
        } else {
            isRefreshing = true
        }
        error = nil

        api = client
        loadedEmail = email
        loadedCredential = credential
        loadedAuthenticationMode = authenticationMode
        loadedCacheKey = cacheKey

        do {
            let fetchedAccounts = try await client.fetchAccounts()
            guard dashboardGeneration == dashboardLoadGeneration else { return }
            guard !fetchedAccounts.isEmpty else {
                accounts = []
                zones = []
                pagesProjects = []
                workers = []
                error = "This Cloudflare user does not have access to an account."
                isLoading = false
                isRefreshing = false
                return
            }

            accounts = fetchedAccounts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            if let preferredAccountID,
               accounts.contains(where: { $0.id == preferredAccountID }) {
                selectedAccountID = preferredAccountID
            }
            if selectedAccountID == nil || !accounts.contains(where: { $0.id == selectedAccountID }) {
                selectedAccountID = accounts.first?.id
            }
            guard let accountID = selectedAccountID else { return }
            if resourcesByAccountID[accountID] == nil {
                zones = []
                pagesProjects = []
                workers = []
                sectionWarnings = []
            } else {
                _ = applyCachedResources(for: accountID)
            }
            try await loadSelectedAccount(
                client: client,
                accountID: accountID,
                dashboardGeneration: dashboardGeneration
            )
            guard dashboardGeneration == dashboardLoadGeneration else { return }
            dashboardUpdatedAt = Date.now
            saveCache(cacheKey: cacheKey)
        } catch is CancellationError {
            // Account switching can cancel an in-flight refresh.
        } catch {
            guard dashboardGeneration == dashboardLoadGeneration else { return }
            self.error = error.localizedDescription
        }
    }

    func selectAccount(_ id: String) async {
        guard selectedAccountID != id else { return }
        selectedAccountID = id
        let hasCachedResources = applyCachedResources(for: id)
        if !hasCachedResources {
            zones = []
            pagesProjects = []
            workers = []
            sectionWarnings = []
        }
        error = nil
        if hasCachedResources,
           isFresh(resourcesByAccountID[id]?.updatedAt) {
            if let loadedCacheKey { saveCache(cacheKey: loadedCacheKey) }
            return
        }
        isRefreshing = true
        guard let client = api else {
            isRefreshing = false
            return
        }
        let dashboardGeneration = dashboardLoadGeneration
        do {
            try await loadSelectedAccount(
                client: client,
                accountID: id,
                dashboardGeneration: dashboardGeneration
            )
            guard dashboardGeneration == dashboardLoadGeneration,
                  selectedAccountID == id else { return }
            if let loadedCacheKey { saveCache(cacheKey: loadedCacheKey) }
        } catch is CancellationError {
            // A superseded account selection must not replace a valid cache.
        } catch {
            guard dashboardGeneration == dashboardLoadGeneration,
                  selectedAccountID == id else { return }
            self.error = error.localizedDescription
        }
        if dashboardGeneration == dashboardLoadGeneration,
           selectedAccountID == id {
            isRefreshing = false
        }
    }

    func refresh() async {
        guard let loadedCredential, let loadedAuthenticationMode else { return }
        await load(
            authenticationMode: loadedAuthenticationMode,
            email: loadedEmail,
            credential: loadedCredential,
            forceRefresh: true
        )
    }

    func invalidateForExternalMutation() {
        guard let loadedCacheKey else { return }
        Self.dashboards.removeValue(forKey: loadedCacheKey)
        dashboardUpdatedAt = nil
        if let selectedAccountID,
           let resources = resourcesByAccountID[selectedAccountID] {
            resourcesByAccountID[selectedAccountID] = CachedResources(
                zones: resources.zones,
                pagesProjects: resources.pagesProjects,
                workers: resources.workers,
                sectionWarnings: resources.sectionWarnings,
                updatedAt: .distantPast
            )
        }
    }

    func reconcileZone(_ updatedZone: CloudflareZone) {
        guard let accountID = selectedAccountID,
              let index = zones.firstIndex(where: { $0.id == updatedZone.id }) else { return }
        zones[index] = updatedZone
        zones.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        persistCurrentResources(for: accountID)
    }

    func reconcileWorker(_ updatedWorker: CloudflareWorkerScript?, workerID: String) {
        guard let accountID = selectedAccountID else { return }
        if let updatedWorker {
            if let index = workers.firstIndex(where: { $0.id == workerID }) {
                workers[index] = updatedWorker
            } else {
                workers.append(updatedWorker)
            }
        } else {
            workers.removeAll { $0.id == workerID }
        }
        workers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        persistCurrentResources(for: accountID)
    }

    func reconcilePagesProject(_ updatedProject: CloudflarePagesProject?, projectID: String) {
        guard let accountID = selectedAccountID else { return }
        if let updatedProject {
            if let index = pagesProjects.firstIndex(where: { $0.id == projectID }) {
                pagesProjects[index] = updatedProject
            } else {
                pagesProjects.append(updatedProject)
            }
        } else {
            pagesProjects.removeAll { $0.id == projectID }
        }
        pagesProjects.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        persistCurrentResources(for: accountID)
    }

    private func loadSelectedAccount(
        client: CloudflareAPI,
        accountID: String,
        dashboardGeneration: Int
    ) async throws {
        resourceLoadGeneration += 1
        let generation = resourceLoadGeneration
        sectionWarnings = []
        async let zoneResult = capture { try await client.fetchZones(accountID: accountID) }
        async let pagesResult = capture { try await client.fetchPagesProjects(accountID: accountID) }
        async let workersResult = capture { try await client.fetchWorkerScripts(accountID: accountID) }

        let (zoneResponse, pagesResponse, workerResponse) = await (zoneResult, pagesResult, workersResult)
        guard dashboardGeneration == dashboardLoadGeneration,
              selectedAccountID == accountID,
              resourceLoadGeneration == generation else {
            throw CancellationError()
        }
        if isCancellation(zoneResponse)
            || isCancellation(pagesResponse)
            || isCancellation(workerResponse) {
            throw CancellationError()
        }
        apply(zoneResponse, to: &zones, section: "Zones")
        apply(pagesResponse, to: &pagesProjects, section: "Pages")
        apply(workerResponse, to: &workers, section: "Workers")

        zones.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        pagesProjects.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        workers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        let allSectionsSucceeded = succeeded(zoneResponse)
            && succeeded(pagesResponse)
            && succeeded(workerResponse)
        resourcesByAccountID[accountID] = CachedResources(
            zones: zones,
            pagesProjects: pagesProjects,
            workers: workers,
            sectionWarnings: sectionWarnings,
            // A partial response remains visible, but is immediately stale so
            // the next quiet background pass retries only after navigation has
            // settled instead of hiding the failure for the full TTL.
            updatedAt: allSectionsSucceeded ? Date.now : .distantPast
        )
    }

    private func saveCache(cacheKey: String) {
        Self.dashboards[cacheKey] = CachedDashboard(
            accounts: accounts,
            selectedAccountID: selectedAccountID,
            resourcesByAccountID: resourcesByAccountID,
            updatedAt: dashboardUpdatedAt ?? .distantPast
        )
    }

    private func persistCurrentResources(for accountID: String) {
        let previousUpdate = resourcesByAccountID[accountID]?.updatedAt ?? .distantPast
        resourcesByAccountID[accountID] = CachedResources(
            zones: zones,
            pagesProjects: pagesProjects,
            workers: workers,
            sectionWarnings: sectionWarnings,
            // Updating one child must not extend the TTL for unrelated
            // dashboard sections that were fetched earlier.
            updatedAt: previousUpdate
        )
        if let loadedCacheKey { saveCache(cacheKey: loadedCacheKey) }
    }

    @discardableResult
    private func applyCachedResources(for accountID: String?) -> Bool {
        guard let accountID, let cached = resourcesByAccountID[accountID] else { return false }
        zones = cached.zones
        pagesProjects = cached.pagesProjects
        workers = cached.workers
        sectionWarnings = cached.sectionWarnings
        return true
    }

    private func isFresh(_ date: Date?) -> Bool {
        guard let date else { return false }
        return Date.now.timeIntervalSince(date) < Self.cacheLifetime
    }

    private func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do {
            return .success(try await operation())
        } catch {
            return .failure(error)
        }
    }

    private func isCancellation<T>(_ result: Result<T, Error>) -> Bool {
        guard case .failure(let error) = result else { return false }
        return error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func succeeded<T>(_ result: Result<T, Error>) -> Bool {
        if case .success = result { return true }
        return false
    }

    private func apply<T>(_ result: Result<[T], Error>, to value: inout [T], section: String) {
        switch result {
        case .success(let items):
            value = items
        case .failure(let error):
            if error is CancellationError
                || (error as? URLError)?.code == .cancelled {
                return
            }
            sectionWarnings.append("\(section): \(error.localizedDescription)")
        }
    }
}

struct CloudflareDashboardView: View {
    let authenticationMode: CloudflareAuthenticationMode
    let email: String?
    let credential: String
    var startWithSearch = false
    var searchRequestID = 0
    var backgroundRefreshRequestID = 0

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(AuthManager.self) private var authManager
    @State private var viewModel: CloudflareDashboardViewModel
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var refreshSpin = 0.0
    @AppStorage("cloudflare.selectedNestedAccountID") private var persistedAccountID = ""

    init(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String,
        startWithSearch: Bool = false,
        searchRequestID: Int = 0,
        backgroundRefreshRequestID: Int = 0
    ) {
        self.authenticationMode = authenticationMode
        self.email = email
        self.credential = credential
        self.startWithSearch = startWithSearch
        self.searchRequestID = searchRequestID
        self.backgroundRefreshRequestID = backgroundRefreshRequestID
        _viewModel = State(initialValue: CloudflareDashboardViewModel(
            authenticationMode: authenticationMode,
            email: email,
            credential: credential
        ))
    }

    private var credentialCacheScope: String {
        CredentialCacheScope.cloudflare(
            authenticationMode: authenticationMode,
            email: email,
            credential: credential
        )
    }

    private var filteredZones: [CloudflareZone] {
        guard !searchText.isEmpty else { return viewModel.zones }
        return viewModel.zones.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            ($0.status?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    private var filteredPagesProjects: [CloudflarePagesProject] {
        guard !searchText.isEmpty else { return viewModel.pagesProjects }
        return viewModel.pagesProjects.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            ($0.subdomain?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            $0.domains.contains { $0.localizedCaseInsensitiveContains(searchText) }
        }
    }

    private var filteredWorkers: [CloudflareWorkerScript] {
        guard !searchText.isEmpty else { return viewModel.workers }
        return viewModel.workers.filter {
            $0.id.localizedCaseInsensitiveContains(searchText) ||
            $0.routes.contains { $0.pattern.localizedCaseInsensitiveContains(searchText) } ||
            $0.tags.contains { $0.localizedCaseInsensitiveContains(searchText) }
        }
    }

    private var hasSearchResults: Bool {
        !filteredZones.isEmpty || !filteredPagesProjects.isEmpty || !filteredWorkers.isEmpty
    }

    private var credentialLabel: String {
        email ?? "Scoped API token"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                if viewModel.isLoading && viewModel.accounts.isEmpty {
                    CloudflareLoadingView()
                } else if let error = viewModel.error, viewModel.accounts.isEmpty {
                    CloudflareErrorView(message: error) {
                        Task { await viewModel.refresh() }
                    }
                } else {
                    dashboardContent
                }
            }
            .navigationTitle("Cloudflare")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search Cloudflare")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ProviderAccountMenu()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if !reduceMotion {
                            withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
                        }
                        Task { await viewModel.refresh() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(viewModel.isRefreshing)
                    .accessibilityLabel(viewModel.isRefreshing ? "Refreshing Cloudflare" : "Refresh Cloudflare")
                    .sensoryFeedback(.impact(weight: .light), trigger: refreshSpin)
                }
            }
            .task {
                await viewModel.load(
                    authenticationMode: authenticationMode,
                    email: email,
                    credential: credential,
                    preferredAccountID: persistedAccountID.isEmpty ? nil : persistedAccountID
                )
            }
            .onChange(of: backgroundRefreshRequestID) { _, _ in
                Task {
                    await viewModel.load(
                        authenticationMode: authenticationMode,
                        email: email,
                        credential: credential,
                        preferredAccountID: persistedAccountID.isEmpty ? nil : persistedAccountID
                    )
                }
            }
            .onChange(of: viewModel.selectedAccountID) { _, selectedID in
                if let selectedID { persistedAccountID = selectedID }
            }
            .onAppear {
                if startWithSearch { isSearching = true }
            }
            .onChange(of: searchRequestID) { _, _ in
                isSearching = true
            }
            .onReceive(NotificationCenter.default.publisher(for: .cloudflareDataDidChange)) { notification in
                guard notification.object as? String == credentialCacheScope,
                      let path = notification.userInfo?["path"] as? String,
                      mutationAffectsDashboardSummary(path) else { return }
                viewModel.invalidateForExternalMutation()
                Task { await viewModel.refresh() }
            }
        }
        .tint(CloudflareStyle.orange)
    }

    private func mutationAffectsDashboardSummary(_ path: String) -> Bool {
        let segments = path.split(separator: "/").map(String.init)
        guard let root = segments.first else { return false }

        if root == "zones" {
            // Zone create/update/delete affects the dashboard; nested DNS,
            // analytics, settings, and security mutations do not.
            return segments.count <= 2
        }
        guard root == "accounts" else { return false }
        if segments.count <= 2 { return true }
        guard segments.count >= 4 else { return false }

        let product = segments[2]
        let collection = segments[3]
        if product == "pages", collection == "projects" {
            return segments.count <= 5
        }
        if product == "workers", collection == "scripts" {
            return segments.count <= 5
        }
        return false
    }

    private var dashboardContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let account = viewModel.selectedAccount {
                    accountHeader(account)
                }

                if let error = authManager.error {
                    AppFeedbackBanner(
                        title: "Saved account change failed",
                        message: error,
                        icon: "lock.trianglebadge.exclamationmark.fill",
                        tint: AppTheme.danger
                    )
                }

                if let error = viewModel.error {
                    AppFeedbackBanner(
                        title: "Cloudflare refresh failed",
                        message: error,
                        tint: AppTheme.warning,
                        actionTitle: "Retry"
                    ) {
                        Task { await viewModel.refresh() }
                    }
                }

                CloudflareWriteNotice()

                if !viewModel.sectionWarnings.isEmpty {
                    sectionWarningCard
                }

                if !searchText.isEmpty && !hasSearchResults {
                    CloudflareSearchEmptyView(searchText: searchText)
                } else {
                    resourceSections
                }

                advancedTools
            }
            .padding(AppLayout.pagePadding(for: horizontalSizeClass))
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await viewModel.refresh() }
        .overlay(alignment: .top) {
            if viewModel.isRefreshing {
                ProgressView()
                    .tint(CloudflareStyle.orange)
                    .padding(.top, 6)
            }
        }
    }

    private var resourceSections: some View {
        AppAdaptiveTwoPane(primaryMinimumWidth: 360, secondaryMinimumWidth: 360) {
            zoneSection
        } secondary: {
            LazyVStack(spacing: 16) {
                pagesSection
                workersSection
            }
        }
    }

    private func accountHeader(_ account: CloudflareAccountSummary) -> some View {
        VStack(spacing: 12) {
            if let api = viewModel.api {
                NavigationLink {
                    CloudflareAccountDetailView(
                        api: api,
                        account: account,
                        email: credentialLabel,
                        zoneCount: viewModel.zones.count,
                        pagesCount: viewModel.pagesProjects.count,
                        workerCount: viewModel.workers.count
                    )
                } label: {
                    CloudflareEdgeHeader(
                        accountName: account.name,
                        email: credentialLabel,
                        zones: viewModel.zones.count,
                        pages: viewModel.pagesProjects.count,
                        workers: viewModel.workers.count
                    )
                }
                .buttonStyle(.plain)
            }

            if viewModel.accounts.count > 1 {
                Menu {
                    ForEach(viewModel.accounts, id: \.id) { item in
                        Button {
                            Task { await viewModel.selectAccount(item.id) }
                        } label: {
                            Label(
                                item.name,
                                systemImage: item.id == viewModel.selectedAccountID
                                    ? "checkmark.circle.fill"
                                    : "building.2"
                            )
                        }
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "building.2.fill")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(CloudflareStyle.orange)
                        Text("Cloudflare account")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                        Spacer()
                        Text(account.name)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                    )
                }
            }
        }
    }

    private var zoneSection: some View {
        resourcePanel(
            title: "Zones & DNS",
            icon: "globe.americas.fill",
            count: filteredZones.count,
            emptyTitle: "No zones",
            emptyMessage: searchText.isEmpty
                ? "No zones are available to this Cloudflare user."
                : "No zones match your search."
        ) {
            ForEach(filteredZones, id: \.id) { zone in
                NavigationLink {
                    if let api = viewModel.api {
                        CloudflareZoneDetailView(api: api, zone: zone) { updatedZone in
                            viewModel.reconcileZone(updatedZone)
                        }
                    }
                } label: {
                    CloudflareResourceRow(
                        icon: "globe",
                        title: zone.name,
                        subtitle: zoneSubtitle(zone),
                        tint: statusColor(zone.status)
                    ) {
                        HStack(spacing: 8) {
                            if let status = zone.status {
                                CloudflareStatusPill(text: status.uppercased(), color: statusColor(status))
                            }
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
                rowDivider(unlessLast: zone.id != filteredZones.last?.id)
            }
        }
    }

    private var pagesSection: some View {
        resourcePanel(
            title: "Pages",
            icon: "doc.badge.gearshape.fill",
            count: filteredPagesProjects.count,
            emptyTitle: "No Pages projects",
            emptyMessage: searchText.isEmpty
                ? "Pages projects will appear here when this account has them."
                : "No Pages projects match your search."
        ) {
            ForEach(filteredPagesProjects, id: \.id) { project in
                NavigationLink {
                    if let api = viewModel.api, let accountID = viewModel.selectedAccountID {
                        CloudflarePagesProjectDetailView(
                            api: api,
                            accountID: accountID,
                            project: project
                        ) { updatedProject in
                            viewModel.reconcilePagesProject(updatedProject, projectID: project.id)
                        }
                    }
                } label: {
                    CloudflareResourceRow(
                        icon: "doc.badge.gearshape",
                        title: project.name,
                        subtitle: projectSubtitle(project)
                    )
                }
                .buttonStyle(.plain)
                rowDivider(unlessLast: project.id != filteredPagesProjects.last?.id)
            }
        }
    }

    private var workersSection: some View {
        resourcePanel(
            title: "Workers",
            icon: "shippingbox.fill",
            count: filteredWorkers.count,
            emptyTitle: "No Workers",
            emptyMessage: searchText.isEmpty
                ? "Worker scripts will appear here when this account has them."
                : "No Workers match your search."
        ) {
            ForEach(filteredWorkers, id: \.id) { worker in
                NavigationLink {
                    if let api = viewModel.api, let accountID = viewModel.selectedAccountID {
                        CloudflareWorkerDetailView(
                            api: api,
                            accountID: accountID,
                            worker: worker
                        ) { updatedWorker in
                            viewModel.reconcileWorker(updatedWorker, workerID: worker.id)
                        }
                    }
                } label: {
                    CloudflareResourceRow(
                        icon: "shippingbox",
                        title: worker.id,
                        subtitle: workerSubtitle(worker),
                        tint: CloudflareStyle.amber
                    )
                }
                .buttonStyle(.plain)
                rowDivider(unlessLast: worker.id != filteredWorkers.last?.id)
            }
        }
    }

    private func resourcePanel<Content: View>(
        title: String,
        icon: String,
        count: Int,
        emptyTitle: String,
        emptyMessage: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon, count: count)
            Divider().overlay(AppTheme.divider)
            if count == 0 {
                CloudflareEmptySection(icon: icon, title: emptyTitle, message: emptyMessage)
            } else {
                content()
            }
        }
        .cloudflarePanel()
    }

    private var sectionWarningCard: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(viewModel.sectionWarnings, id: \.self) { warning in
                    Text(warning)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 10)
        } label: {
            Label(
                "\(viewModel.sectionWarnings.count) product \(viewModel.sectionWarnings.count == 1 ? "issue" : "issues")",
                systemImage: "exclamationmark.triangle.fill"
            )
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(CloudflareStyle.amber)
        }
        .padding(16)
        .cloudflarePanel()
    }

    private var advancedTools: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Advanced", icon: "terminal.fill")
            Divider().overlay(AppTheme.divider)

            if let api = viewModel.api {
                if let accountID = viewModel.selectedAccountID,
                   let account = viewModel.selectedAccount {
                    NavigationLink {
                        CloudflareFullAPICatalogView(
                            api: api,
                            accountID: accountID,
                            zones: viewModel.zones,
                            authenticationMode: authenticationMode
                        )
                    } label: {
                        CloudflareResourceRow(
                            icon: "point.3.filled.connected.trianglepath.dotted",
                            title: "Complete Cloudflare API",
                            subtitle: "Every official REST operation · generated parameters and upload forms",
                            tint: CloudflareStyle.green
                        )
                    }
                    .buttonStyle(.plain)

                    Divider().overlay(AppTheme.divider).padding(.leading, 64)

                    NavigationLink {
                        CloudflareGraphQLDatasetCatalogView(
                            api: api,
                            accountID: accountID,
                            zones: viewModel.zones
                        )
                    } label: {
                        CloudflareResourceRow(
                            icon: "chart.xyaxis.line",
                            title: "GraphQL dataset directory",
                            subtitle: "Live plan availability, fields, retention and query limits",
                            tint: CloudflareStyle.orange
                        )
                    }
                    .buttonStyle(.plain)

                    Divider().overlay(AppTheme.divider).padding(.leading, 64)

                    NavigationLink {
                        CloudflareProductCenterView(
                            api: api,
                            accountID: accountID,
                            accountName: account.name,
                            zones: viewModel.zones,
                            authenticationMode: authenticationMode
                        )
                    } label: {
                        CloudflareResourceRow(
                            icon: "cloud.bolt.rain.fill",
                            title: "Guided product operations",
                            subtitle: "Curated shortcuts for common Cloudflare tasks",
                            tint: CloudflareStyle.orange
                        )
                    }
                    .buttonStyle(.plain)

                    Divider().overlay(AppTheme.divider).padding(.leading, 64)

                    NavigationLink {
                        CloudflareStorageDashboardView(
                            api: api,
                            accountID: accountID,
                            accountName: account.name,
                            allowsR2: authenticationMode == .apiToken
                        )
                    } label: {
                        CloudflareResourceRow(
                            icon: "externaldrive.fill",
                            title: "Storage & databases",
                            subtitle: authenticationMode == .apiToken
                                ? "D1 SQL, Workers KV and R2 object storage"
                                : "D1 SQL and Workers KV · R2 requires a scoped token",
                            tint: CloudflareStyle.amber
                        )
                    }
                    .buttonStyle(.plain)

                    Divider().overlay(AppTheme.divider).padding(.leading, 64)
                }

                NavigationLink {
                    CloudflareAPIExplorerView(api: api, accountID: viewModel.selectedAccountID)
                } label: {
                    CloudflareResourceRow(
                        icon: "terminal",
                        title: "Cloudflare API Explorer",
                        subtitle: "Any v4 REST request · JSON, text, Base64 or multipart",
                        tint: CloudflareStyle.orange
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .cloudflarePanel(accentOpacity: 0.045)
    }

    @ViewBuilder
    private func rowDivider(unlessLast: Bool) -> some View {
        if unlessLast {
            Divider()
                .overlay(AppTheme.divider)
                .padding(.leading, 64)
        }
    }

    private func zoneSubtitle(_ zone: CloudflareZone) -> String? {
        let values = [zone.type, zone.plan?.name]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .map { $0.capitalized }
        return values.isEmpty ? zone.nameServers.first : values.joined(separator: " · ")
    }

    private func projectSubtitle(_ project: CloudflarePagesProject) -> String? {
        if let branch = project.productionBranch, !branch.isEmpty {
            return "Production · \(branch)"
        }
        return project.domains.first ?? project.subdomain
    }

    private func workerSubtitle(_ worker: CloudflareWorkerScript) -> String? {
        var values: [String] = []
        if worker.hasModules == true { values.append("Modules") }
        if worker.hasAssets == true { values.append("Assets") }
        if !worker.routes.isEmpty { values.append("\(worker.routes.count) routes") }
        return values.isEmpty ? worker.usageModel?.capitalized : values.joined(separator: " · ")
    }

    private func statusColor(_ status: String?) -> Color {
        switch status?.lowercased() {
        case "active", "ready": CloudflareStyle.green
        case "pending", "initializing", "building": CloudflareStyle.amber
        case "moved", "deactivated", "error", "failed": CloudflareStyle.red
        default: AppTheme.textSecondary
        }
    }
}
