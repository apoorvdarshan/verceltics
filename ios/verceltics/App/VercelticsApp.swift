import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager = AuthManager()
    @State private var paywallManager = PaywallManager()

    var body: some Scene {
        WindowGroup {
            Group {
                if !authManager.isAuthenticated {
                    LoginView()
                } else if !paywallManager.hasCheckedEntitlements {
                    // Brief flash-prevention while StoreKit boots — entitlements
                    // are needed to know whether to gate the analytics drilldown.
                    Color.black.ignoresSafeArea()
                } else {
                    // Soft paywall: everyone sees the projects list. Analytics
                    // taps gate via PaywallView sheet inside ProjectsView.
                    MainTabView()
                }
            }
            .environment(authManager)
            .environment(paywallManager)
            .preferredColorScheme(.dark)
        }
    }
}
