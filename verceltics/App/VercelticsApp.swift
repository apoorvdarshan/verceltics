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
                } else if paywallManager.hasActiveSubscription {
                    MainTabView()
                } else {
                    PaywallView()
                }
            }
            .environment(authManager)
            .environment(paywallManager)
            .preferredColorScheme(.dark)
            .task {
                await paywallManager.loadProducts()
            }
        }
    }
}
