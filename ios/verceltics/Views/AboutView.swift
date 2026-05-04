import SwiftUI
import StoreKit

struct AboutView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.openURL) private var openURL
    @Environment(\.requestReview) private var requestReview
    
    @State private var showingAddAccount = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 8)

                    // Sections
                    VStack(spacing: 24) {
                        // Accounts
                        aboutSection(title: "ACCOUNTS") {
                            ForEach(authManager.accounts) { account in
                                Button {
                                    authManager.switchAccount(to: account.id)
                                } label: {
                                    HStack(spacing: 14) {
                                        Image(systemName: "person.crop.circle.fill")
                                            .font(.system(size: 16, weight: .heavy))
                                            .foregroundStyle(authManager.activeAccountId == account.id ? .blue : .white.opacity(0.4))
                                            .frame(width: 34, height: 34)
                                            .background(Color.white.opacity(0.06))
                                            .clipShape(Circle())

                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(account.name)
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundStyle(.white)
                                            Text(account.token.prefix(12) + "...")
                                                .font(.system(size: 10, design: .monospaced))
                                                .foregroundStyle(.white.opacity(0.3))
                                        }

                                        Spacer()

                                        if authManager.activeAccountId == account.id {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundStyle(.blue)
                                                .font(.system(size: 14))
                                        } else {
                                            Button {
                                                authManager.removeAccount(id: account.id)
                                            } label: {
                                                Image(systemName: "trash")
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(.red.opacity(0.6))
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 12)
                                }
                                .buttonStyle(PressScaleButtonStyle())
                                
                                if account.id != authManager.accounts.last?.id {
                                    Divider().overlay(Color.white.opacity(0.06)).padding(.leading, 60)
                                }
                            }
                            
                            Button {
                                showingAddAccount = true
                            } label: {
                                AboutRowContent(icon: "plus.circle.fill", title: "Add Another Account", subtitle: "Connect another Vercel token", showsChevron: true)
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                        
                        // Support — most important to surface first
                        aboutSection(title: "SUPPORT") {
                            AboutRow(icon: "star.bubble.fill", title: "Rate Verceltics", subtitle: "Tap a star, no App Store needed", action: { requestReview() })
                            shareAppRow
                            AboutRow(icon: "star.fill", title: "Star on GitHub", subtitle: "Help us reach more developers", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "arrow.up.circle.fill", title: "Upvote on Product Hunt", subtitle: "producthunt.com/products/verceltics", url: "https://www.producthunt.com/products/verceltics")
                        }

                        // App
                        aboutSection(title: "APP") {
                            updateCheckRow
                        }

                        // Links — outward connections
                        aboutSection(title: "LINKS") {
                            AboutRow(icon: "globe", title: "Website", subtitle: "verceltics.com", url: "https://verceltics.com")
                            AboutRow(icon: "chevron.left.forwardslash.chevron.right", title: "Source Code", subtitle: "github.com/apoorvdarshan/verceltics", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "at", title: "Follow on X", subtitle: "@apoorvdarshan", url: "https://x.com/apoorvdarshan")
                        }

                        // Help — actionable user assistance
                        aboutSection(title: "HELP") {
                            AboutRow(icon: "envelope.fill", title: "Contact", subtitle: "ad13dtu@gmail.com", url: "mailto:ad13dtu@gmail.com")
                            AboutRow(icon: "ant", title: "Report an Issue", subtitle: "Open a GitHub issue", url: "https://github.com/apoorvdarshan/verceltics/issues")
                        }

                        // Account
                        aboutSection(title: "ACCOUNT") {
                            AboutRow(icon: "creditcard.fill", title: "Manage Subscription", subtitle: "Change plan or cancel", url: "https://apps.apple.com/account/subscriptions")
                        }

                        // Legal
                        aboutSection(title: "LEGAL") {
                            AboutRow(icon: "hand.raised.fill", title: "Privacy Policy", subtitle: "verceltics.com/privacy", url: "https://verceltics.com/privacy")
                            AboutRow(icon: "doc.text.fill", title: "Terms of Service", subtitle: "verceltics.com/terms", url: "https://verceltics.com/terms")
                            AboutRow(icon: "checkmark.seal.fill", title: "License", subtitle: "MIT License", url: "https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE")
                        }

                        // Footer
                        VStack(spacing: 10) {
                            HStack(spacing: 5) {
                                Text("Built with")
                                    .font(.system(size: 11, weight: .bold))
                                Image(systemName: "heart.fill")
                                    .font(.system(size: 10, weight: .heavy))
                                    .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42))
                                Text("by Apoorv Darshan")
                                    .font(.system(size: 11, weight: .bold))
                            }
                            .foregroundStyle(.white.opacity(0.4))

                            Text("Verceltics is not affiliated with, endorsed by, or sponsored by Vercel Inc. Vercel and the Vercel logo are trademarks of Vercel Inc.")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(.white.opacity(0.22))
                                .multilineTextAlignment(.center)
                                .lineSpacing(2)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 28)
                        .padding(.top, 12)

                        // Sign out
                        Button {
                            authManager.logout()
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                    .font(.system(size: 13, weight: .heavy))
                                Text("Sign Out")
                                    .font(.system(size: 14, weight: .bold))
                            }
                            .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42))
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(
                                LinearGradient(
                                    colors: [Color.red.opacity(0.14), Color.red.opacity(0.06)],
                                    startPoint: .top, endPoint: .bottom
                                )
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .strokeBorder(
                                        LinearGradient(
                                            colors: [Color.red.opacity(0.22), Color.red.opacity(0.08)],
                                            startPoint: .top, endPoint: .bottom
                                        ),
                                        lineWidth: 0.5
                                    )
                            )
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 40)
                    }
                }
                .frame(maxWidth: hSize == .regular ? 640 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(Color.black)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .sheet(isPresented: $showingAddAccount) {
                LoginView()
            }
            .task {
                await appUpdateChecker.checkForUpdates()
            }
        }
    }

    private var updateCheckRow: some View {
        Button {
            if appUpdateChecker.isUpdateAvailable {
                openURL(appUpdateChecker.appStoreURL)
            } else {
                Task { await appUpdateChecker.checkForUpdates(force: true) }
            }
        } label: {
            AboutRowContent(
                icon: updateIcon,
                iconColor: updateIconColor,
                title: updateTitle,
                subtitle: updateSubtitle
            )
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(appUpdateChecker.isChecking)
    }

    private var updateIcon: String {
        if appUpdateChecker.isChecking { return "arrow.triangle.2.circlepath" }
        if appUpdateChecker.isUpdateAvailable { return "arrow.down.circle.fill" }
        if appUpdateChecker.errorMessage != nil { return "exclamationmark.triangle.fill" }
        return "checkmark.seal.fill"
    }

    private var updateIconColor: Color {
        if appUpdateChecker.isUpdateAvailable { return Color(red: 0.84, green: 1.0, blue: 0.36) }
        if appUpdateChecker.errorMessage != nil { return Color(red: 1.0, green: 0.72, blue: 0.35) }
        return .white.opacity(0.55)
    }

    private var updateTitle: String {
        if appUpdateChecker.isUpdateAvailable { return "Update Available" }
        return "Check for Updates"
    }

    private var updateSubtitle: String {
        if appUpdateChecker.isChecking {
            return "Checking App Store..."
        }
        if let errorMessage = appUpdateChecker.errorMessage {
            return errorMessage
        }
        if appUpdateChecker.isUpdateAvailable, let latestVersion = appUpdateChecker.latestVersion {
            return "Version \(latestVersion) is ready"
        }
        if appUpdateChecker.hasChecked {
            return "Version \(appUpdateChecker.currentVersion) is current"
        }
        return "Version \(appUpdateChecker.currentVersion)"
    }

    private var shareAppRow: some View {
        let message = """
        Verceltics — Vercel Web Analytics on your iPhone. Open source, no ads.

        App Store: https://apps.apple.com/us/app/verceltics/id6761645656
        Website: https://verceltics.com
        """
        return ShareLink(item: message) {
            AboutRowContent(
                icon: "square.and.arrow.up.fill",
                title: "Share Verceltics",
                subtitle: "Tell others about the app"
            )
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func aboutSection(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.4))
                .tracking(1.4)
                .padding(.horizontal, 22)
                .padding(.bottom, 10)

            VStack(spacing: 0) {
                content()
            }
            .background(
                ZStack {
                    LinearGradient(
                        colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    LinearGradient(
                        colors: [Color.white.opacity(0.04), .clear],
                        startPoint: .top,
                        endPoint: .center
                    )
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                            startPoint: .top, endPoint: .bottom
                        ),
                        lineWidth: 0.5
                    )
            )
            .padding(.horizontal, 16)
        }
    }
}

// MARK: - About Row

struct AboutRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let url: String?
    let action: (() -> Void)?

    init(icon: String, iconColor: Color = .white.opacity(0.5), title: String, subtitle: String, url: String? = nil, action: (() -> Void)? = nil) {
        self.icon = icon
        self.iconColor = iconColor
        self.title = title
        self.subtitle = subtitle
        self.url = url
        self.action = action
    }

    private var isInteractive: Bool { url != nil || action != nil }

    var body: some View {
        let content = AboutRowContent(
            icon: icon,
            iconColor: iconColor,
            title: title,
            subtitle: subtitle,
            showsChevron: isInteractive
        )

        if let action {
            Button(action: action) { content }
                .buttonStyle(PressScaleButtonStyle())
        } else if let url, let link = URL(string: url) {
            Button { UIApplication.shared.open(link) } label: { content }
                .buttonStyle(PressScaleButtonStyle())
        } else {
            content
        }
    }
}

struct AboutRowContent: View {
    let icon: String
    var iconColor: Color = .white.opacity(0.55)
    let title: String
    let subtitle: String
    var showsChevron: Bool = true

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(iconColor)
                .frame(width: 34, height: 34)
                .background(
                    LinearGradient(
                        colors: [Color.white.opacity(0.10), Color.white.opacity(0.04)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.06), lineWidth: 0.5)
                )

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.4))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer()

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.18))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}
