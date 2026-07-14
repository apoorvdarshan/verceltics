import SwiftUI

/// Titled, rounded card used by About and other settings-style screens.
struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title.uppercased())
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .tracking(1.1)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                content()
            }
            .appSurface()
            .padding(.horizontal, 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Share-the-app row used in About's support section.
struct ShareAppRow: View {
    var body: some View {
        let message = """
        Verceltics — Vercel Web Analytics on your iPhone and iPad. Open source, no ads.

        App Store: https://apps.apple.com/us/app/verceltics/id6761645656
        Website: https://verceltics.com
        """
        ShareLink(item: message) {
            AboutRowContent(
                icon: "square.and.arrow.up.fill",
                title: "Share Verceltics",
                subtitle: "Tell others about the app"
            )
        }
        .buttonStyle(PressScaleButtonStyle())
        .hoverEffect(.highlight)
    }
}
