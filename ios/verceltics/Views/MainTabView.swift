import SwiftUI

struct MainTabView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        TabView {
            Tab(homeTabTitle, systemImage: homeTabIcon) {
                providerHome()
                    .id(authManager.activeAccountId)
            }

            Tab(role: .search) {
                providerHome(startWithSearch: true)
                    .id(authManager.activeAccountId)
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

    private var homeTabTitle: String {
        authManager.activeProvider == .cloudflare ? "Cloudflare" : "Projects"
    }

    private var homeTabIcon: String {
        authManager.activeProvider == .cloudflare ? "cloud.fill" : "triangle.fill"
    }

    @ViewBuilder
    private func providerHome(startWithSearch: Bool = false) -> some View {
        if let credentials = authManager.cloudflareCredentials {
            CloudflareDashboardView(
                email: credentials.email,
                globalAPIKey: credentials.globalAPIKey,
                startWithSearch: startWithSearch
            )
        } else {
            ProjectsView(startWithSearch: startWithSearch)
        }
    }
}
