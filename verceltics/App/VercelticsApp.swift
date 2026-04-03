import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager = AuthManager()
    @State private var paywallManager = PaywallManager()
    @State private var isReady = false

    var body: some Scene {
        WindowGroup {
            Group {
                if !isReady {
                    SplashScreen()
                } else if !authManager.isAuthenticated {
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
                await paywallManager.checkEntitlements()
                // Small delay for splash feel
                try? await Task.sleep(for: .milliseconds(800))
                withAnimation(.easeOut(duration: 0.3)) {
                    isReady = true
                }
            }
        }
    }
}

// MARK: - Splash Screen

struct SplashScreen: View {
    @State private var logoScale: CGFloat = 0.8
    @State private var logoOpacity: Double = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 16) {
                if let uiImage = UIImage(named: "AppIcon") {
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 80, height: 80)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .shadow(color: .blue.opacity(0.3), radius: 20, y: 4)
                        .scaleEffect(logoScale)
                        .opacity(logoOpacity)
                }

                Text("Verceltics")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                    .opacity(logoOpacity)
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.5)) {
                logoScale = 1.0
                logoOpacity = 1.0
            }
        }
    }
}
