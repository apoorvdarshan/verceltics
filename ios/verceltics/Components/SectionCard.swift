import SwiftUI

/// Titled, rounded card used across the About and Support tabs.
struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .tracking(0.7)
                .textCase(nil)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                content()
            }
            .appSurface()
            .padding(.horizontal, 16)
        }
    }
}

/// Share-the-app row (used in the Support tab).
struct ShareAppRow: View {
    var body: some View {
        let message = """
        Verceltics — Vercel Web Analytics on your iPhone. Open source, no ads.

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
    }
}
