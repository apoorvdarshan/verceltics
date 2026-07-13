import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager = AuthManager()
    @State private var paywallManager = PaywallManager()
    @State private var appUpdateChecker = AppUpdateChecker()
    @State private var registrarStore = RegistrarStore()

    var body: some Scene {
        WindowGroup {
            Group {
                if !authManager.isAuthenticated && registrarStore.accounts.isEmpty {
                    LoginView()
                } else if !paywallManager.hasCheckedEntitlements {
                    // Brief flash-prevention while RevenueCat checks entitlements
                    // needed to know whether to gate the analytics drilldown.
                    AppTheme.canvas.ignoresSafeArea()
                } else {
                    // Soft paywall: everyone sees the projects list. Analytics
                    // taps gate via PaywallView sheet inside ProjectsView.
                    MainTabView()
                }
            }
            .environment(authManager)
            .environment(paywallManager)
            .environment(appUpdateChecker)
            .environment(registrarStore)
            .preferredColorScheme(.dark)
        }
    }
}
