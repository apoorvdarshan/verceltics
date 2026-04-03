import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            Tab("Projects", systemImage: "triangle.fill") {
                ProjectsView()
            }

            Tab("About", systemImage: "info.circle") {
                AboutView()
            }
        }
        .tint(.white)
    }
}
