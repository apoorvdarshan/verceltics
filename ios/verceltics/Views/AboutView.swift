import SwiftUI
import StoreKit

struct AboutView: View {
    @Environment(AppUpdateChecker.self) private var appUpdateChecker
    @Environment(AppAppearanceStore.self) private var appearanceStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.openURL) private var openURL
    @Environment(\.requestReview) private var requestReview

    @State private var tipStore = TipStore()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 8)

                    VStack(spacing: 24) {
                        aboutSections
                        footer
                    }
                }
                .frame(maxWidth: hSize == .regular ? 960 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(AppTheme.canvas)
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .task {
                await appUpdateChecker.checkForUpdates()
            }
        }
    }

    @ViewBuilder
    private var aboutSections: some View {
        if hSize == .regular {
            HStack(alignment: .top, spacing: 0) {
                VStack(spacing: 24) {
                    appSection
                    appearanceSection
                    linksSection
                    waysToHelpSection
                }
                VStack(spacing: 24) {
                    helpSection
                    accountSection
                    legalSection
                    TipSectionView(store: tipStore)
                }
            }
        } else {
            VStack(spacing: 24) {
                appSection
                appearanceSection
                linksSection
                waysToHelpSection
                TipSectionView(store: tipStore)
                helpSection
                accountSection
                legalSection
            }
        }
    }

    private var appSection: some View {
        SectionCard(title: "App") { updateCheckRow }
    }

    private var appearanceSection: some View {
        SectionCard(title: "Appearance") {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    AppIconTile(
                        icon: appearanceStore.selection.systemImage,
                        tint: AppTheme.signal,
                        size: 36
                    )

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Color mode")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                        Text(appearanceStore.selection.explanation)
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 0)
                }

                appearancePicker
            }
            .padding(14)
        }
    }

    @ViewBuilder
    private var appearancePicker: some View {
        let selection = Binding(
            get: { appearanceStore.selection },
            set: { appearanceStore.select($0) }
        )

        if dynamicTypeSize.isAccessibilitySize {
            Picker("Appearance", selection: selection) {
                ForEach(AppAppearance.allCases) { appearance in
                    Label(appearance.title, systemImage: appearance.systemImage)
                        .tag(appearance)
                }
            }
            .pickerStyle(.menu)
            .tint(AppTheme.signal)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 12)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
        } else {
            Picker("Appearance", selection: selection) {
                ForEach(AppAppearance.allCases) { appearance in
                    Label(appearance.title, systemImage: appearance.systemImage)
                        .tag(appearance)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var linksSection: some View {
        SectionCard(title: "Links") {
            AboutRow(icon: "globe", title: "Website", subtitle: "verceltics.com", url: "https://verceltics.com")
            AppInsetDivider()
            AboutRow(icon: "chevron.left.forwardslash.chevron.right", title: "Source Code", subtitle: "github.com/apoorvdarshan/verceltics", url: "https://github.com/apoorvdarshan/verceltics")
            AppInsetDivider()
            AboutRow(icon: "building.2.fill", title: "Follow on LinkedIn", subtitle: "linkedin.com/company/verceltics", url: "https://www.linkedin.com/company/verceltics")
            AppInsetDivider()
            AboutRow(icon: "at", title: "Follow on X", subtitle: "@apoorvdarshan", url: "https://x.com/apoorvdarshan")
        }
    }

    private var helpSection: some View {
        SectionCard(title: "Help") {
            AboutRow(icon: "envelope.fill", title: "Contact", subtitle: "ad13dtu@gmail.com", url: "mailto:ad13dtu@gmail.com")
            AppInsetDivider()
            AboutRow(icon: "ant", title: "Report an issue", subtitle: "Open a GitHub issue", url: "https://github.com/apoorvdarshan/verceltics/issues")
        }
    }

    private var waysToHelpSection: some View {
        SectionCard(title: "Ways to help") {
            AboutRow(
                icon: "star.bubble.fill",
                title: "Rate Verceltics",
                subtitle: "Tap a star, no App Store needed",
                action: { requestReview() }
            )
            AppInsetDivider()
            ShareAppRow()
            AppInsetDivider()
            AboutRow(
                icon: "star.fill",
                title: "Star on GitHub",
                subtitle: "Open the GitHub repository",
                url: "https://github.com/apoorvdarshan/verceltics"
            )
            AppInsetDivider()
            AboutRow(
                icon: "arrow.up.circle.fill",
                title: "Upvote on Product Hunt",
                subtitle: "producthunt.com/products/verceltics",
                url: "https://www.producthunt.com/products/verceltics"
            )
        }
    }

    private var accountSection: some View {
        SectionCard(title: "Account") {
            AboutRow(icon: "creditcard.fill", title: "Manage subscription", subtitle: "Change plan or cancel", url: "https://apps.apple.com/account/subscriptions")
        }
    }

    private var legalSection: some View {
        SectionCard(title: "Legal") {
            AboutRow(icon: "hand.raised.fill", title: "Privacy policy", subtitle: "verceltics.com/privacy", url: "https://verceltics.com/privacy")
            AppInsetDivider()
            AboutRow(icon: "doc.text.fill", title: "Terms of service", subtitle: "verceltics.com/terms", url: "https://verceltics.com/terms")
            AppInsetDivider()
            AboutRow(icon: "checkmark.seal.fill", title: "License", subtitle: "MIT License", url: "https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE")
        }
    }

    private var footer: some View {
        VStack(spacing: 10) {
            Text("Made by Apoorv Darshan")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)

            Text("Verceltics is an independent app and is not affiliated with, endorsed by, or sponsored by any supported hosting platform, domain registrar, analytics provider, or webmaster service. All provider names and marks belong to their respective owners.")
                .font(.caption)
                .foregroundStyle(AppTheme.textTertiary)
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
        return AppTheme.textSecondary
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

    init(icon: String, iconColor: Color = AppTheme.textSecondary, title: String, subtitle: String, url: String? = nil, action: (() -> Void)? = nil) {
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
                .hoverEffect(.highlight)
        } else if let url, let link = URL(string: url) {
            Button { UIApplication.shared.open(link) } label: { content }
                .buttonStyle(PressScaleButtonStyle())
                .hoverEffect(.highlight)
        } else {
            content
        }
    }
}

struct AboutRowContent: View {
    let icon: String
    var iconColor: Color = AppTheme.textSecondary
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
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(2)
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
        .padding(.vertical, 12)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }
}
