import SwiftUI
import StoreKit

struct SupportView: View {
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.requestReview) private var requestReview

    @State private var tipStore = TipStore()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 8)

                    supportSections
                    .padding(.bottom, 40)
                }
                .frame(maxWidth: hSize == .regular ? 960 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(AppTheme.canvas)
            .navigationTitle("Support")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }

    @ViewBuilder
    private var supportSections: some View {
        if hSize == .regular {
            HStack(alignment: .top, spacing: 0) {
                waysToHelp
                TipSectionView(store: tipStore)
            }
        } else {
            VStack(spacing: 24) {
                waysToHelp
                TipSectionView(store: tipStore)
            }
        }
    }

    private var waysToHelp: some View {
        SectionCard(title: "Ways to help") {
            AboutRow(icon: "star.bubble.fill", title: "Rate Verceltics", subtitle: "Tap a star, no App Store needed", action: { requestReview() })
            AppInsetDivider()
            ShareAppRow()
            AppInsetDivider()
            AboutRow(icon: "star.fill", title: "Star on GitHub", subtitle: "Open the GitHub repository", url: "https://github.com/apoorvdarshan/verceltics")
            AppInsetDivider()
            AboutRow(icon: "arrow.up.circle.fill", title: "Upvote on Product Hunt", subtitle: "producthunt.com/products/verceltics", url: "https://www.producthunt.com/products/verceltics")
        }
    }
}
