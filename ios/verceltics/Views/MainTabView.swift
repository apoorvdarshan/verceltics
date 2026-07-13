import SwiftUI

struct MainTabView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        TabView {
            Tab("Projects", systemImage: "triangle.fill") {
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

    @ViewBuilder
    private func providerHome(startWithSearch: Bool = false) -> some View {
        if let credentials = authManager.cloudflareCredentials {
            CloudflareDashboardView(
                authenticationMode: credentials.mode,
                email: credentials.email,
                credential: credentials.credential,
                startWithSearch: startWithSearch
            )
        } else {
            ProjectsView(startWithSearch: startWithSearch)
        }
    }
}
