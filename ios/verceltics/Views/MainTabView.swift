import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            Tab("Projects", systemImage: "triangle.fill") {
                ProjectsView()
            }

            Tab(role: .search) {
                ProjectsView(startWithSearch: true)
            }

            Tab("About", systemImage: "info.circle") {
                AboutView()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(.white)
    }
}
