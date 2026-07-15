import SwiftUI

private let lastPrimaryWorkspaceKey = "mainTab.lastPrimaryWorkspace"

private enum PrimaryWorkspace: String {
    case hosting
    case registrars
    case sites

    var destination: MainTabDestination {
        switch self {
        case .hosting: .hosting
        case .registrars: .registrars
        case .sites: .sites
        }
    }
}

private enum MainTabDestination: Hashable {
    case hosting
    case search
    case registrars
    case sites
    case about

    var primaryWorkspace: PrimaryWorkspace? {
        switch self {
        case .hosting: .hosting
        case .registrars: .registrars
        case .sites: .sites
        case .search, .about: nil
        }
    }
}

struct MainTabView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(AuthManager.self) private var authManager
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(lastPrimaryWorkspaceKey) private var lastPrimaryWorkspace = PrimaryWorkspace.hosting.rawValue
    @State private var selectedTab: MainTabDestination
    @State private var hostingSearchRequestID = 0
    @State private var registrarSearchRequestID = 0
    @State private var sitesSearchRequestID = 0
    @State private var hostingRefreshRequestID = 0
    @State private var registrarRefreshRequestID = 0
    @State private var sitesRefreshRequestID = 0

    init() {
        let storedValue = UserDefaults.standard.string(forKey: lastPrimaryWorkspaceKey)
        let workspace = storedValue.flatMap(PrimaryWorkspace.init(rawValue:)) ?? .hosting
        _selectedTab = State(initialValue: workspace.destination)
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("Hosting", systemImage: "server.rack", value: MainTabDestination.hosting) {
                providerHome(
                    searchRequestID: hostingSearchRequestID,
                    backgroundRefreshRequestID: hostingRefreshRequestID
                )
                    .id(activeHostingViewIdentity)
            }

            Tab(value: MainTabDestination.search, role: .search) {
                // Selecting the system search tab redirects to the last primary
                // workspace and presents that workspace's existing search field.
                // Keeping this destination inert prevents a second dashboard
                // tree from issuing duplicate provider requests.
                Color.clear
            }

            Tab("Registrars", systemImage: "globe.americas.fill", value: MainTabDestination.registrars) {
                RegistrarsView(
                    searchRequestID: registrarSearchRequestID,
                    backgroundRefreshRequestID: registrarRefreshRequestID
                )
            }

            Tab("Sites", systemImage: "chart.xyaxis.line", value: MainTabDestination.sites) {
                SitesView(
                    searchRequestID: sitesSearchRequestID,
                    backgroundRefreshRequestID: sitesRefreshRequestID
                )
            }

            Tab("About", systemImage: "info.circle", value: MainTabDestination.about) {
                AboutView()
            }
            .badge(appUpdateChecker.isUpdateAvailable ? Text("") : nil)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(AppTheme.textPrimary)
        .onChange(of: selectedTab) { _, newValue in
            if newValue == .search {
                let workspace = PrimaryWorkspace(rawValue: lastPrimaryWorkspace) ?? .hosting
                selectedTab = workspace.destination
                Task { @MainActor in
                    await Task.yield()
                    requestSearch(for: workspace)
                }
                return
            }
            if let workspace = newValue.primaryWorkspace {
                lastPrimaryWorkspace = workspace.rawValue
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            requestBackgroundRefreshForCurrentWorkspace()
        }
        .task {
            await appUpdateChecker.checkForUpdates()
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(60))
                } catch {
                    return
                }
                requestBackgroundRefreshForCurrentWorkspace()
            }
        }
    }

    @ViewBuilder
    private func providerHome(
        searchRequestID: Int,
        backgroundRefreshRequestID: Int
    ) -> some View {
        if let credentials = authManager.cloudflareCredentials {
            CloudflareDashboardView(
                authenticationMode: credentials.mode,
                email: credentials.email,
                credential: credentials.credential,
                searchRequestID: searchRequestID,
                backgroundRefreshRequestID: backgroundRefreshRequestID
            )
        } else if let account = authManager.activeHostingAccount {
            HostingDashboardView(
                account: account,
                searchRequestID: searchRequestID,
                backgroundRefreshRequestID: backgroundRefreshRequestID
            )
        } else if authManager.activeProvider == .vercel {
            ProjectsView(
                searchRequestID: searchRequestID,
                backgroundRefreshRequestID: backgroundRefreshRequestID,
                initialToken: authManager.token
            )
        } else {
            HostingEmptyStateView()
        }
    }

    private func requestBackgroundRefreshForCurrentWorkspace() {
        guard scenePhase == .active else { return }
        let workspace = selectedTab.primaryWorkspace
            ?? PrimaryWorkspace(rawValue: lastPrimaryWorkspace)
            ?? .hosting
        switch workspace {
        case .hosting:
            hostingRefreshRequestID &+= 1
        case .registrars:
            registrarRefreshRequestID &+= 1
        case .sites:
            sitesRefreshRequestID &+= 1
        }
    }

    private func requestSearch(for workspace: PrimaryWorkspace) {
        switch workspace {
        case .hosting:
            hostingSearchRequestID &+= 1
        case .registrars:
            registrarSearchRequestID &+= 1
        case .sites:
            sitesSearchRequestID &+= 1
        }
    }

    /// Rebuild provider-specific state when credentials are rotated in place.
    /// Account IDs intentionally remain stable during an update, so using the ID
    /// alone can leave an API client holding the previous credential.
    private var activeHostingViewIdentity: String {
        guard let account = authManager.activeAccount else { return "no-hosting-account" }
        let metadata = account.providerMetadata
            .sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: "&")
        return "\(account.id.uuidString)|\(account.token.hashValue)|\(metadata.hashValue)"
    }
}

private struct HostingEmptyStateView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var showConnection = false

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                VStack(spacing: 12) {
                    if let error = authManager.error {
                        AppFeedbackBanner(
                            title: "Saved hosting accounts need attention",
                            message: error,
                            icon: "lock.trianglebadge.exclamationmark.fill",
                            tint: AppTheme.danger
                        )
                    }
                    AppEmptyState(
                        icon: "server.rack",
                        title: "No hosting account",
                        message: "Connect a hosting platform to see projects, deployments, logs, domains, and analytics.",
                        actionTitle: "Connect hosting"
                    ) {
                        showConnection = true
                    }
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: 560)
            }
            .navigationTitle("Hosting")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ProviderAccountMenu()
                }
            }
            .sheet(isPresented: $showConnection) {
                LoginView(initialCategory: .hosting)
                    .presentationSizing(.page)
                    .presentationDragIndicator(.visible)
            }
        }
    }
}
