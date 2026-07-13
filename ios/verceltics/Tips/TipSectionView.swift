import SwiftUI
import RevenueCat

private let tipAccent = AppTheme.signal

private struct TipMeta {
    let emoji: String
    let title: String
    let blurb: String
    let popular: Bool

    static func of(_ id: String) -> TipMeta {
        switch id {
        case TipStore.coffeeID: return .init(emoji: "☕️", title: "Coffee", blurb: "A little caffeine", popular: false)
        case TipStore.lunchID:  return .init(emoji: "🍕", title: "Lunch", blurb: "Treat me to lunch", popular: true)
        case TipStore.bigID:    return .init(emoji: "🚀", title: "Big Tip", blurb: "Really generous", popular: false)
        case TipStore.hugeID:   return .init(emoji: "💎", title: "Huge Supporter", blurb: "You're amazing", popular: false)
        default:                return .init(emoji: "💜", title: "Tip", blurb: "Support development", popular: false)
        }
    }
}

/// Inline tip jar — renders the four tip tiers directly inside the About
/// screen's Support tab (no popup). Styled to match `aboutSection` cards.
struct TipSectionView: View {
    let store: TipStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Support the developer")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                content
            }
            .appSurface()
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private var content: some View {
        if store.didTip {
            thankYou
        } else {
            explainer
            if store.isLoading {
                loadingRow
            } else if store.loadFailed {
                retryRow
            } else {
                ForEach(Array(store.products.enumerated()), id: \.element.productIdentifier) { idx, product in
                    tierRow(product)
                    if idx < store.products.count - 1 { rowDivider }
                }
            }
            if let msg = store.errorMessage {
                Text(msg)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color(red: 1.0, green: 0.5, blue: 0.5))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 12)
            }
        }
    }

    private var explainer: some View {
        Text("A one-time tip — completely optional and unlocks nothing.")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.white.opacity(0.42))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.top, 13)
            .padding(.bottom, 6)
    }

    private var rowDivider: some View {
        Rectangle()
            .fill(Color.white.opacity(0.06))
            .frame(height: 0.5)
            .padding(.leading, 62)
    }

    private func tierRow(_ product: StoreProduct) -> some View {
        let meta = TipMeta.of(product.productIdentifier)
        let busy = store.purchasingID == product.productIdentifier
        return Button {
            Task { await store.purchase(product) }
        } label: {
            HStack(spacing: 14) {
                Text(meta.emoji)
                    .font(.system(size: 17))
                    .frame(width: 34, height: 34)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.06), lineWidth: 0.5)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(meta.title)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                        if meta.popular {
                            Text("POPULAR")
                                .font(.system(size: 7.5, weight: .semibold))
                                .tracking(0.6)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5).padding(.vertical, 2)
                                .background(tipAccent, in: Capsule())
                        }
                    }
                    Text(meta.blurb)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.4))
                }

                Spacer()

                ZStack {
                    if busy {
                        ProgressView().tint(.white).scaleEffect(0.8)
                    } else {
                        Text(product.localizedPriceString)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
                .frame(minWidth: 66)
                .padding(.vertical, 8)
                .background(tipAccent, in: Capsule())
                .opacity(busy ? 0.85 : 1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(store.purchasingID != nil)
    }

    private var loadingRow: some View {
        HStack(spacing: 10) {
            ProgressView().tint(.white.opacity(0.6))
            Text("Loading tip options…")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white.opacity(0.4))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }

    private var retryRow: some View {
        VStack(spacing: 12) {
            Text("Couldn't load tip options right now.")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white.opacity(0.5))
            Button { Task { await store.loadProducts() } } label: {
                Text("Try Again")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20).padding(.vertical, 9)
                    .background(tipAccent, in: Capsule())
            }
            .buttonStyle(PressScaleButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }

    private var thankYou: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(tipAccent)
            Text("Thank you")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
            Text("Your tip supports continued development of Verceltics.")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.55))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 18)
            Button { store.didTip = false } label: {
                Text("Done")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 28).padding(.vertical, 10)
                    .background(tipAccent, in: Capsule())
            }
            .buttonStyle(PressScaleButtonStyle())
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }
}
