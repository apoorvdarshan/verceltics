import SwiftUI

struct AboutView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 8)

                    VStack(spacing: 24) {
                        SectionCard(title: "App") {
                            updateCheckRow
                        }

                        SectionCard(title: "Links") {
                            AboutRow(icon: "globe", title: "Website", subtitle: "verceltics.com", url: "https://verceltics.com")
                            AboutRow(icon: "chevron.left.forwardslash.chevron.right", title: "Source Code", subtitle: "github.com/apoorvdarshan/verceltics", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "building.2.fill", title: "Follow on LinkedIn", subtitle: "linkedin.com/company/verceltics", url: "https://www.linkedin.com/company/verceltics")
                            AboutRow(icon: "at", title: "Follow on X", subtitle: "@apoorvdarshan", url: "https://x.com/apoorvdarshan")
                        }

                        SectionCard(title: "Help") {
                            AboutRow(icon: "envelope.fill", title: "Contact", subtitle: "ad13dtu@gmail.com", url: "mailto:ad13dtu@gmail.com")
                            AboutRow(icon: "ant", title: "Report an Issue", subtitle: "Open a GitHub issue", url: "https://github.com/apoorvdarshan/verceltics/issues")
                        }

                        SectionCard(title: "Account") {
                            AboutRow(icon: "creditcard.fill", title: "Manage Subscription", subtitle: "Change plan or cancel", url: "https://apps.apple.com/account/subscriptions")
                        }

                        SectionCard(title: "Legal") {
                            AboutRow(icon: "hand.raised.fill", title: "Privacy Policy", subtitle: "verceltics.com/privacy", url: "https://verceltics.com/privacy")
                            AboutRow(icon: "doc.text.fill", title: "Terms of Service", subtitle: "verceltics.com/terms", url: "https://verceltics.com/terms")
                            AboutRow(icon: "checkmark.seal.fill", title: "License", subtitle: "MIT License", url: "https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE")
                        }

                        footer
                    }
                }
                .frame(maxWidth: hSize == .regular ? 640 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(AppTheme.canvas)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .task {
                await appUpdateChecker.checkForUpdates()
            }
        }
    }

    private var footer: some View {
        VStack(spacing: 10) {
            Text("Made by Apoorv Darshan")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)

            Text("Verceltics is an independent app and is not affiliated with, endorsed by, or sponsored by any supported hosting platform or domain registrar. All provider names and marks belong to their respective owners.")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.white.opacity(0.22))
                .multilineTextAlignment(.center)
                .lineSpacing(2)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.top, 12)
        .padding(.bottom, 40)
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
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: 34, height: 34)
                .background(AppTheme.surfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(subtitle)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer()

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}
