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
                    ZStack {
                        AppTheme.canvas.ignoresSafeArea()
                        VStack(spacing: 14) {
                            Image("AppLogo")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 48, height: 48)
                            ProgressView()
                                .tint(AppTheme.textSecondary)
                            Text("Loading workspace")
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
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
