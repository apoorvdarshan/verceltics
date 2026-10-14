import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager: AuthManager
    @State private var paywallManager: PaywallManager
    @State private var appUpdateChecker: AppUpdateChecker
    @State private var appearanceStore: AppAppearanceStore
    @State private var registrarStore: RegistrarStore
    @State private var siteStore: SiteStore
    @State private var firstLaunchExperience: FirstLaunchExperienceStore
    @State private var developerWelcome: DeveloperWelcomeStore

    init() {
#if DEBUG
        // UI tests reset and seed UserDefaults before any store below reads them.
        UITestEnvironment.prepare()
        let isolatesServices = UITestEnvironment.isolatesExternalServices
        _paywallManager = State(initialValue: isolatesServices ? .withoutRevenueCatForTesting() : PaywallManager())
        let updateChecker = AppUpdateChecker()
        updateChecker.checksAppStore = !isolatesServices
        _appUpdateChecker = State(initialValue: updateChecker)
#else
        _paywallManager = State(initialValue: PaywallManager())
        _appUpdateChecker = State(initialValue: AppUpdateChecker())
#endif
        _appearanceStore = State(initialValue: AppAppearanceStore())
        _firstLaunchExperience = State(initialValue: FirstLaunchExperienceStore())
        _developerWelcome = State(initialValue: DeveloperWelcomeStore())

#if DEBUG
        if AppDebugFixtures.usesIsolatedAccountStores {
            _authManager = State(initialValue: AuthManager(ephemeralAccounts: []))
            _registrarStore = State(initialValue: RegistrarStore(ephemeralAccounts: []))
            _siteStore = State(initialValue: AppDebugFixtures.makeSiteStore())
        } else if isolatesServices {
            _authManager = State(initialValue: AuthManager(ephemeralAccounts: []))
            _registrarStore = State(initialValue: RegistrarStore(ephemeralAccounts: []))
            _siteStore = State(initialValue: UITestEnvironment.usesSampleSite
                ? AppDebugFixtures.sampleSiteStore()
                : SiteStore(ephemeralAccounts: []))
        } else {
            _authManager = State(initialValue: AuthManager())
            _registrarStore = State(initialValue: RegistrarStore())
            _siteStore = State(initialValue: SiteStore())
        }
#else
        _authManager = State(initialValue: AuthManager())
        _registrarStore = State(initialValue: RegistrarStore())
        _siteStore = State(initialValue: SiteStore())
#endif
    }

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
#if DEBUG
                if AppDebugFixtures.showsRegistrarDomain {
                    AppDebugFixtures.registrarDomainView
                } else if AppDebugFixtures.showsMainNavigation {
                    AppDebugFixtures.mainNavigationView
                } else {
                    appContent
                }
#else
                appContent
#endif
            }
            .environment(authManager)
            .environment(paywallManager)
            .environment(appUpdateChecker)
            .environment(appearanceStore)
            .environment(registrarStore)
            .environment(siteStore)
            .environment(developerWelcome)
            .preferredColorScheme(appearanceStore.selection.preferredColorScheme)
            .task(id: firstLaunchMigrationState) {
#if DEBUG
                guard !AppDebugFixtures.usesIsolatedAccountStores else { return }
#endif
                guard paywallManager.hasCheckedEntitlements else { return }
                firstLaunchExperience.migrateIfNeeded(
                    hasAnyConnection: hasAnyConnection,
                    hasActiveSubscription: paywallManager.hasActiveSubscription
                )
            }
        }
    }

    @ViewBuilder
    private var appContent: some View {
        if developerWelcome.isPresented {
            // Keep the workspace unmounted until Close is pressed, so its
            // automatic sheets or rating prompts cannot cover the welcome.
            DeveloperWelcomeView()
                .background(AppTheme.canvas)
        } else if !paywallManager.hasCheckedEntitlements {
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
}
