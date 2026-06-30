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

                    VStack(spacing: 24) {
                        // Free ways to help
                        SectionCard(title: "WAYS TO HELP") {
                            AboutRow(icon: "star.bubble.fill", title: "Rate Verceltics", subtitle: "Tap a star, no App Store needed", action: { requestReview() })
                            ShareAppRow()
                            AboutRow(icon: "star.fill", title: "Star on GitHub", subtitle: "Help us reach more developers", url: "https://github.com/apoorvdarshan/verceltics")
                            AboutRow(icon: "arrow.up.circle.fill", title: "Upvote on Product Hunt", subtitle: "producthunt.com/products/verceltics", url: "https://www.producthunt.com/products/verceltics")
                        }

                        // Tip jar — four tiers shown inline
                        TipSectionView(store: tipStore)
                    }
                    .padding(.bottom, 40)
                }
                .frame(maxWidth: hSize == .regular ? 640 : .infinity)
                .frame(maxWidth: .infinity)
            }
            .background(Color.black)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}
