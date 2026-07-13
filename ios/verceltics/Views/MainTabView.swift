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
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 18) {
                    Image(systemName: "server.rack")
                        .font(.system(size: 44, weight: .black))
                        .foregroundStyle(Color(red: 0.30, green: 0.67, blue: 1.0))

                    VStack(spacing: 7) {
                        Text("Your hosting, together")
                            .font(.system(size: 22, weight: .heavy))
                        Text("Use the account menu at the top left to connect a hosting platform and manage projects, deployments, logs, domains and analytics.")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.42))
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(34)
            }
            .navigationTitle("Hosting")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ProviderAccountMenu()
                }
            }
        }
    }
}
