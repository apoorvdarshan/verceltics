import SwiftUI

struct MainTabView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        TabView {
            Tab {
                providerHome()
                    .id(authManager.activeAccountId)
            } label: {
                if let provider = authManager.activeProvider {
                    Label {
                        Text(provider.displayName)
                    } icon: {
                        ProviderMark(provider: provider, size: 22, monochrome: true)
                    }
                } else {
                    Label("Hosting", systemImage: "server.rack")
                }
            }

            Tab(role: .search) {
                providerHome(startWithSearch: true)
                    .id(authManager.activeAccountId)
            }

            Tab("Registrars", systemImage: "globe.americas.fill") {
                RegistrarsView()
            }

            Tab("Support", systemImage: "heart.fill") {
                SupportView()
            }

            Tab("About", systemImage: "info.circle") {
                AboutView()
            }
            .badge(appUpdateChecker.isUpdateAvailable ? Text("") : nil)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(.white)
        .task {
            await appUpdateChecker.checkForUpdates()
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
}

private struct HostingEmptyStateView: View {
    @State private var showConnection = false

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                AppEmptyState(
                    icon: "server.rack",
                    title: "No hosting account",
                    message: "Connect a hosting platform to see projects, deployments, logs, domains, and analytics.",
                    actionTitle: "Connect hosting"
                ) {
                    showConnection = true
                }
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
            }
        }
    }
}
