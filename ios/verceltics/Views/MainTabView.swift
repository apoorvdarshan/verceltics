import SwiftUI

struct MainTabView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker

    var body: some View {
        TabView {
            Tab("Projects", systemImage: "triangle.fill") {
                ProjectsView()
            }

            Tab(role: .search) {
                ProjectsView(startWithSearch: true)
            }

            Tab("Accounts", systemImage: "person.crop.circle") {
                AccountsView()
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
}
