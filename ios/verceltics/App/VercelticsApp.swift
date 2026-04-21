import SwiftUI

// monorepo root: ios/
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
                    // Show nothing while checking — prevents paywall flash
                    Color.black.ignoresSafeArea()
                } else if paywallManager.hasActiveSubscription {
                    MainTabView()
                } else {
                    PaywallView()
                }
            }
            .environment(authManager)
            .environment(paywallManager)
            .preferredColorScheme(.dark)
        }
    }
}
