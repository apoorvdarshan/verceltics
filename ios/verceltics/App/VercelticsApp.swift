import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager = AuthManager()
    @State private var paywallManager = PaywallManager()
    @State private var appUpdateChecker = AppUpdateChecker()
    @State private var appearanceStore = AppAppearanceStore()
    @State private var registrarStore = RegistrarStore()
    @State private var siteStore = SiteStore()
    @State private var firstLaunchExperience = FirstLaunchExperienceStore()

    private var hasAnyConnection: Bool {
        !authManager.accounts.isEmpty
            || !registrarStore.accounts.isEmpty
            || !siteStore.accounts.isEmpty
    }

    private var firstLaunchMigrationState: Int {
        (paywallManager.hasCheckedEntitlements ? 1 : 0)
            | (hasAnyConnection ? 2 : 0)
            | (paywallManager.hasActiveSubscription ? 4 : 0)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if !paywallManager.hasCheckedEntitlements {
                    ZStack {
                        AppTheme.canvas.ignoresSafeArea()
                        VStack(spacing: 14) {
                            Image("AppLogo")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 64, height: 64)
                                .accessibilityHidden(true)
                            ProgressView()
                                .tint(AppTheme.textSecondary)
                            Text("Loading workspace")
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                } else if !hasAnyConnection {
                    FirstConnectionFlow(
                        experience: firstLaunchExperience,
                        hasAnyConnection: hasAnyConnection,
                        hasActiveSubscription: paywallManager.hasActiveSubscription
                    )
                } else {
                    // Soft paywall: connection and workspace browsing stay
                    // available; item details and provider actions gate inside
                    // their owning views.
                    MainTabView()
                }
            }
            .environment(authManager)
            .environment(paywallManager)
            .environment(appUpdateChecker)
            .environment(appearanceStore)
            .environment(registrarStore)
            .environment(siteStore)
            .preferredColorScheme(appearanceStore.selection.preferredColorScheme)
            .task(id: firstLaunchMigrationState) {
                guard paywallManager.hasCheckedEntitlements else { return }
                firstLaunchExperience.migrateIfNeeded(
                    hasAnyConnection: hasAnyConnection,
                    hasActiveSubscription: paywallManager.hasActiveSubscription
                )
            }
        }
    }
}
