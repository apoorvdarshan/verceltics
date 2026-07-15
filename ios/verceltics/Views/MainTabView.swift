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
    @AppStorage(lastPrimaryWorkspaceKey) private var lastPrimaryWorkspace = PrimaryWorkspace.hosting.rawValue
    @State private var selectedTab: MainTabDestination

    init() {
        let storedValue = UserDefaults.standard.string(forKey: lastPrimaryWorkspaceKey)
        let workspace = storedValue.flatMap(PrimaryWorkspace.init(rawValue:)) ?? .hosting
        _selectedTab = State(initialValue: workspace.destination)
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("Hosting", systemImage: "server.rack", value: MainTabDestination.hosting) {
                providerHome()
                    .id(activeHostingViewIdentity)
            }

            Tab(value: MainTabDestination.search, role: .search) {
                searchHome()
            }

            Tab("Registrars", systemImage: "globe.americas.fill", value: MainTabDestination.registrars) {
                RegistrarsView()
            }

            Tab("Sites", systemImage: "chart.xyaxis.line", value: MainTabDestination.sites) {
                SitesView()
            }

            Tab("About", systemImage: "info.circle", value: MainTabDestination.about) {
                AboutView()
            }
            .badge(appUpdateChecker.isUpdateAvailable ? Text("") : nil)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(.white)
        .onChange(of: selectedTab) { _, newValue in
            if let workspace = newValue.primaryWorkspace {
                lastPrimaryWorkspace = workspace.rawValue
            }
        }
        .task {
            await appUpdateChecker.checkForUpdates()
        }
    }

    @ViewBuilder
    private func searchHome() -> some View {
        switch PrimaryWorkspace(rawValue: lastPrimaryWorkspace) ?? .hosting {
        case .hosting:
            providerHome(startWithSearch: true)
                .id(activeHostingViewIdentity)
        case .registrars:
            RegistrarsView(startWithSearch: true)
        case .sites:
            SitesView(startWithSearch: true)
        }
    }

    @ViewBuilder
    private func providerHome(startWithSearch: Bool = false) -> some View {
        if let credentials = authManager.cloudflareCredentials {
            CloudflareDashboardView(
                authenticationMode: credentials.mode,
                email: credentials.email,
                credential: credentials.credential,
                startWithSearch: startWithSearch
            )
        } else if let account = authManager.activeHostingAccount {
            HostingDashboardView(account: account, startWithSearch: startWithSearch)
        } else if authManager.activeProvider == .vercel {
            ProjectsView(startWithSearch: startWithSearch)
        } else {
            HostingEmptyStateView()
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
            .toolbarColorScheme(.dark, for: .navigationBar)
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
