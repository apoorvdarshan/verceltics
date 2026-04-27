import SwiftUI
import StoreKit

struct MainTabView: View {
    @Environment(\.requestReview) private var requestReview
    @AppStorage("hasShownOnboardingRatePrompt") private var hasShownOnboardingRatePrompt = false

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
        .task {
            guard !hasShownOnboardingRatePrompt else { return }
            // Let the user reach the projects list and settle before asking.
            try? await Task.sleep(for: .seconds(4))
            requestReview()
            hasShownOnboardingRatePrompt = true
        }
    }
}
