import SwiftUI
import StoreKit

struct AboutView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.requestReview) private var requestReview

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 8)

                    // Sections
                    VStack(spacing: 24) {
                        // Links
                        aboutSection(title: "LINKS") {
                            AboutRow(icon: "chevron.left.forwardslash.chevron.right", title: "Source Code", subtitle: "github.com/apoorvdarshan/verceltics", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "ant", title: "Report an Issue", subtitle: "Open a GitHub issue", url: "https://github.com/apoorvdarshan/verceltics/issues")
                            AboutRow(icon: "envelope.fill", title: "Contact", subtitle: "ad13dtu@gmail.com", url: "mailto:ad13dtu@gmail.com")
                            AboutRow(icon: "globe", title: "Website", subtitle: "verceltics.com", url: "https://verceltics.com")
                        }

                        // Support
                        aboutSection(title: "SUPPORT US") {
                            AboutRow(icon: "star.bubble.fill", title: "Rate Verceltics", subtitle: "Tap a star, no App Store needed", action: { requestReview() })
                            shareAppRow
                            AboutRow(icon: "star.fill", title: "Star on GitHub", subtitle: "Help us reach more developers", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "arrow.up.circle.fill", title: "Upvote on Product Hunt", subtitle: "producthunt.com/products/verceltics", url: "https://www.producthunt.com/products/verceltics")
                            AboutRow(icon: "heart.fill", title: "Support via PayPal", subtitle: "paypal.me/apoorvdarshan", url: "https://paypal.me/apoorvdarshan")
                        }

                        // Developer
                        aboutSection(title: "DEVELOPER") {
                            AboutRow(icon: "at", title: "Follow on X", subtitle: "@apoorvdarshan", url: "https://x.com/apoorvdarshan")
                        }

                        // Legal
                        aboutSection(title: "LEGAL") {
                            AboutRow(icon: "hand.raised.fill", title: "Privacy Policy", subtitle: "verceltics.com/privacy", url: "https://verceltics.com/privacy")
                            AboutRow(icon: "doc.text.fill", title: "Terms of Service", subtitle: "verceltics.com/terms", url: "https://verceltics.com/terms")
                            AboutRow(icon: "checkmark.seal.fill", title: "License", subtitle: "MIT License", url: "https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE")
                        }

                        // Subscription
                        aboutSection(title: "SUBSCRIPTION") {
                            AboutRow(icon: "creditcard.fill", title: "Manage Subscription", subtitle: "Change plan or cancel", url: "https://apps.apple.com/account/subscriptions")
                        }

                        // About text
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Built with SwiftUI and Swift Charts.\nNo third-party dependencies. Open source.")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.white.opacity(0.3))
                                .lineSpacing(3)

                            Text("Verceltics is not affiliated with, endorsed by, or sponsored by Vercel Inc. Vercel and the Vercel logo are trademarks of Vercel Inc.")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(.white.opacity(0.15))
                                .lineSpacing(2)
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 4)

                        // Sign out
                        Button {
                            authManager.logout()
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                    .font(.system(size: 13, weight: .semibold))
                                Text("Sign Out")
                                    .font(.system(size: 14, weight: .semibold))
                            }
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(Color.red.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(Color.red.opacity(0.12), lineWidth: 0.5)
                            )
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 40)
                    }
                }
                .frame(maxWidth: hSize == .regular ? 640 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(Color.black)
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }

    private var shareAppRow: some View {
        ShareLink(
            item: URL(string: "https://apps.apple.com/us/app/verceltics/id6761645656")!,
            subject: Text("Verceltics"),
            message: Text("Check out Verceltics — Vercel Web Analytics on your iPhone.\n\nApp Store: https://apps.apple.com/us/app/verceltics/id6761645656\nWebsite: https://verceltics.com")
        ) {
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
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.white.opacity(0.25))
                .tracking(1.2)
                .padding(.horizontal, 20)
                .padding(.bottom, 10)

            VStack(spacing: 0) {
                content()
            }
            .background(.ultraThinMaterial.opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
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
    var iconColor: Color = .white.opacity(0.5)
    let title: String
    let subtitle: String
    var showsChevron: Bool = true

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(Color.white.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.35))
            }

            Spacer()

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.15))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }
}
