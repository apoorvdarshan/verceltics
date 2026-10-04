import SwiftUI

struct DeveloperWelcomeView: View {
    @Environment(DeveloperWelcomeStore.self) private var welcome
    @Environment(\.openURL) private var openURL
    @AccessibilityFocusState private var headingIsFocused: Bool

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                // A blocking backdrop with no dismissal gesture. This is a
                // root overlay, so no sheet swipe or system dismissal can
                // accidentally acknowledge the introduction.
                Color.black.opacity(0.48)
                    .ignoresSafeArea()
                    .accessibilityHidden(true)

                card
                    .frame(maxWidth: 460)
                    .frame(maxHeight: max(0, min(680, geometry.size.height - 32)))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("developerWelcome")
        .onAppear { headingIsFocused = true }
    }

    private var card: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    introduction

                    VStack(spacing: 8) {
                        ForEach(DeveloperWelcomeLink.allCases) { link in
                            linkButton(link)
                        }
                    }
                }
                .padding(22)
            }
            .scrollBounceBehavior(.basedOnSize)

            Rectangle()
                .fill(AppTheme.divider)
                .frame(height: 0.5)

            Button {
                welcome.close()
            } label: {
                Text("Close")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .padding(.vertical, 4)
                    .foregroundStyle(AppTheme.canvas)
                    .background(AppTheme.textPrimary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityHint("Dismiss this welcome permanently on this device.")
            .accessibilityIdentifier("developerWelcome.close")
            .padding(.horizontal, 22)
            .padding(.vertical, 16)
        }
        .background(AppTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(AppTheme.strokeStrong, lineWidth: 0.5)
        }
        .shadow(color: Color.black.opacity(0.2), radius: 30, y: 12)
    }

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image("AppLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 46, height: 46)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)

                Text("WELCOME TO 2.1")
                    .font(.caption2.weight(.bold))
                    .tracking(1)
                    .foregroundStyle(AppTheme.signal)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Text("Meet the developer")
                .font(.title2.weight(.bold))
                .foregroundStyle(AppTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($headingIsFocused)

            Text("Hi, I’m Apoorv. I build Verceltics independently. Say hello, share feedback, or help more people discover the app.")
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func linkButton(_ link: DeveloperWelcomeLink) -> some View {
        Button {
            guard let url = URL(string: link.address) else { return }
            // External navigation leaves the welcome pending and visible
            // when the user returns, including after the app is terminated.
            openURL(url)
        } label: {
            HStack(spacing: 12) {
                AppIconTile(icon: link.icon, tint: link.tint, size: 38)

                VStack(alignment: .leading, spacing: 3) {
                    Text(link.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Text(link.subtitle)
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                }
                .fixedSize(horizontal: false, vertical: true)

                Spacer(minLength: 4)

                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
                    .accessibilityHidden(true)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityHint("Opens an external link. This welcome stays open.")
        .accessibilityIdentifier("developerWelcome.link.\(link.rawValue)")
    }
}

private enum DeveloperWelcomeLink: String, CaseIterable, Identifiable {
    case x
    case instagram
    case productHunt
    case github
    case discord

    var id: Self { self }

    var title: String {
        switch self {
        case .x: "Follow on X"
        case .instagram: "Follow on Instagram"
        case .productHunt: "Vote on Product Hunt"
        case .github: "Star on GitHub"
        case .discord: "Join Discord"
        }
    }

    var subtitle: String {
        switch self {
        case .x: "@apoorvdarshan"
        case .instagram: "@apoorvcodes"
        case .productHunt: "Support the Verceltics launch"
        case .github: "Explore the open-source project"
        case .discord: "Get help and share ideas"
        }
    }

    var icon: String {
        switch self {
        case .x: "at"
        case .instagram: "camera"
        case .productHunt: "arrow.up.circle.fill"
        case .github: "star.fill"
        case .discord: "bubble.left.and.bubble.right.fill"
        }
    }

    var tint: Color {
        switch self {
        case .x: AppTheme.textPrimary
        case .instagram: .pink
        case .productHunt: .orange
        case .github: AppTheme.warning
        case .discord: .indigo
        }
    }

    var address: String {
        switch self {
        case .x: "https://x.com/apoorvdarshan"
        case .instagram: "https://www.instagram.com/apoorvcodes/"
        case .productHunt: "https://www.producthunt.com/products/verceltics-2"
        case .github: "https://github.com/apoorvdarshan/verceltics"
        case .discord: "https://discord.gg/qv5nsTmkxA"
        }
    }
}
