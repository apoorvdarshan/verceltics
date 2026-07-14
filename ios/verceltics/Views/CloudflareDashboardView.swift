import SwiftUI

@Observable
@MainActor
final class CloudflareDashboardViewModel {
    private struct CachedResources {
        let zones: [CloudflareZone]
        let pagesProjects: [CloudflarePagesProject]
        let workers: [CloudflareWorkerScript]
        let sectionWarnings: [String]
    }

    private struct CachedDashboard {
        let accounts: [CloudflareAccountSummary]
        let selectedAccountID: String?
        let resourcesByAccountID: [String: CachedResources]
    }

    private static var dashboards: [String: CachedDashboard] = [:]

    private(set) var api: CloudflareAPI?
    private var loadedEmail: String?
    private var loadedCredential: String?
    private var loadedAuthenticationMode: CloudflareAuthenticationMode?
    private var loadedCacheKey: String?
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

    func load(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String,
        forceRefresh: Bool = false
    ) async {
        let client = CloudflareAPI(
            authenticationMode: authenticationMode,
            email: email,
            credential: credential
        )
        let cacheKey = "\(authenticationMode.rawValue)|\(email ?? "")|\(credential.hashValue)"

        if !forceRefresh, let cached = Self.dashboards[cacheKey] {
            api = client
            loadedEmail = email
            loadedCredential = credential
            loadedAuthenticationMode = authenticationMode
            loadedCacheKey = cacheKey
            accounts = cached.accounts
            selectedAccountID = cached.selectedAccountID
            resourcesByAccountID = cached.resourcesByAccountID
            applyCachedResources(for: cached.selectedAccountID)
            error = nil
            isLoading = false
            isRefreshing = false
            return
        }

        if !forceRefresh,
           email == loadedEmail,
           credential == loadedCredential,
           authenticationMode == loadedAuthenticationMode,
           !accounts.isEmpty {
            return
        }

        let credentialChanged = email != loadedEmail || credential != loadedCredential
            || authenticationMode != loadedAuthenticationMode
        if credentialChanged {
            accounts = []
            selectedAccountID = nil
            zones = []
            pagesProjects = []
            workers = []
            sectionWarnings = []
            resourcesByAccountID = [:]
        }
        let isInitialLoad = accounts.isEmpty || credentialChanged
        if isInitialLoad {
            isLoading = true
        } else {
            isRefreshing = true
        }
        error = nil
        sectionWarnings = []

        api = client
        loadedEmail = email
        loadedCredential = credential
        loadedAuthenticationMode = authenticationMode
        loadedCacheKey = cacheKey

        do {
            let fetchedAccounts = try await client.fetchAccounts()
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
            if selectedAccountID == nil || !accounts.contains(where: { $0.id == selectedAccountID }) {
                selectedAccountID = accounts.first?.id
            }
            await loadSelectedAccount()
            saveCache()
        } catch is CancellationError {
            // Account switching can cancel an in-flight refresh.
        } catch {
            self.error = error.localizedDescription
        }

        isLoading = false
        isRefreshing = false
    }

    func selectAccount(_ id: String) async {
        guard selectedAccountID != id else { return }
        selectedAccountID = id
        if !applyCachedResources(for: id) {
            zones = []
            pagesProjects = []
            workers = []
            sectionWarnings = []
        }
        isRefreshing = true
        error = nil
        await loadSelectedAccount()
        saveCache()
        isRefreshing = false
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

    private func loadSelectedAccount() async {
        guard let api, let accountID = selectedAccountID else { return }

        resourceLoadGeneration += 1
        let generation = resourceLoadGeneration
        sectionWarnings = []
        async let zoneResult = capture { try await api.fetchZones(accountID: accountID) }
        async let pagesResult = capture { try await api.fetchPagesProjects(accountID: accountID) }
        async let workersResult = capture { try await api.fetchWorkerScripts(accountID: accountID) }

        let (zoneResponse, pagesResponse, workerResponse) = await (zoneResult, pagesResult, workersResult)
        guard selectedAccountID == accountID, resourceLoadGeneration == generation else {
            return
        }
        apply(zoneResponse, to: &zones, section: "Zones")
        apply(pagesResponse, to: &pagesProjects, section: "Pages")
        apply(workerResponse, to: &workers, section: "Workers")

        zones.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        pagesProjects.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        workers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        resourcesByAccountID[accountID] = CachedResources(
            zones: zones,
            pagesProjects: pagesProjects,
            workers: workers,
            sectionWarnings: sectionWarnings
        )
    }

    private func saveCache() {
        guard let loadedCacheKey else { return }
        Self.dashboards[loadedCacheKey] = CachedDashboard(
            accounts: accounts,
            selectedAccountID: selectedAccountID,
            resourcesByAccountID: resourcesByAccountID
        )
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

    private func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do {
            return .success(try await operation())
        } catch {
            return .failure(error)
        }
    }

    private func apply<T>(_ result: Result<[T], Error>, to value: inout [T], section: String) {
        switch result {
        case .success(let items):
            value = items
        case .failure(let error):
            sectionWarnings.append("\(section): \(error.localizedDescription)")
        }
    }
}

struct CloudflareDashboardView: View {
    let authenticationMode: CloudflareAuthenticationMode
    let email: String?
    let credential: String
    var startWithSearch = false

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var viewModel = CloudflareDashboardViewModel()
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var refreshSpin = 0.0

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
            .toolbarColorScheme(.dark, for: .navigationBar)
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
                            .foregroundStyle(.white.opacity(0.7))
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(viewModel.isRefreshing)
                    .sensoryFeedback(.impact(weight: .light), trigger: refreshSpin)
                }
            }
            .task(id: "\(authenticationMode.rawValue)|\(email ?? "")|\(credential.hashValue)") {
                await viewModel.load(
                    authenticationMode: authenticationMode,
                    email: email,
                    credential: credential
                )
            }
            .onAppear {
                if startWithSearch { isSearching = true }
            }
        }
        .tint(CloudflareStyle.orange)
    }

    private var dashboardContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let account = viewModel.selectedAccount {
                    accountHeader(account)
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
                            .foregroundStyle(.white.opacity(0.4))
                        Spacer()
                        Text(account.name)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white.opacity(0.76))
                            .lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.3))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(Color.white.opacity(0.045))
                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
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
                        CloudflareZoneDetailView(api: api, zone: zone)
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
                                .foregroundStyle(.white.opacity(0.2))
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
                        CloudflarePagesProjectDetailView(api: api, accountID: accountID, project: project)
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
                        CloudflareWorkerDetailView(api: api, accountID: accountID, worker: worker)
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
            Divider().overlay(Color.white.opacity(0.06))
            if count == 0 {
                CloudflareEmptySection(icon: icon, title: emptyTitle, message: emptyMessage)
            } else {
                content()
            }
        }
        .cloudflarePanel()
    }

    private var sectionWarningCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(CloudflareStyle.amber)
                Text("Some products could not load")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.8))
            }
            ForEach(viewModel.sectionWarnings, id: \.self) { warning in
                Text(warning)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.35))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cloudflarePanel()
    }

    private var advancedTools: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Advanced", icon: "terminal.fill")
            Divider().overlay(Color.white.opacity(0.06))

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

                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)

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

                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)

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

                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)

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

                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
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
                .overlay(Color.white.opacity(0.055))
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
        default: CloudflareStyle.orange
        }
    }
}
