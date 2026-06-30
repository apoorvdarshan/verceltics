import SwiftUI
import StoreKit

private let tipLime = Color(red: 0.84, green: 1.0, blue: 0.36)

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

struct TipJarView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var store = TipStore()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    tiers
                        .padding(.top, 22)
                    footer
                        .padding(.top, 22)
                }
                .padding(.horizontal, 20)
                .padding(.top, 28)
                .padding(.bottom, 40)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }

            if store.didTip { thankYou }
        }
        .presentationDragIndicator(.visible)
        .overlay(alignment: .topTrailing) { closeButton }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 12) {
            Image(systemName: "heart.fill")
                .font(.system(size: 26, weight: .heavy))
                .foregroundStyle(tipLime)
                .frame(width: 64, height: 64)
                .background(
                    LinearGradient(colors: [Color.white.opacity(0.10), Color.white.opacity(0.03)],
                                   startPoint: .top, endPoint: .bottom)
                )
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)
                )

            Text("Support Verceltics")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(.white)

            Text("Verceltics is free and open source. Tips are completely optional and unlock nothing — they just help me keep building and shipping updates.")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.5))
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .padding(.horizontal, 8)
        }
    }

    // MARK: Tiers

    @ViewBuilder
    private var tiers: some View {
        if store.isLoading {
            VStack(spacing: 12) {
                ProgressView().tint(.white.opacity(0.6))
                Text("Loading tip options…")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.4))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 40)
        } else if store.loadFailed {
            VStack(spacing: 14) {
                Text("Couldn't load tip options.")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.55))
                Button { Task { await store.loadProducts() } } label: {
                    Text("Try Again")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 22).padding(.vertical, 10)
                        .background(tipLime, in: Capsule())
                }
                .buttonStyle(PressScaleButtonStyle())
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 36)
        } else {
            VStack(spacing: 10) {
                ForEach(store.products, id: \.id) { product in
                    tierRow(product)
                }
            }
            if let msg = store.errorMessage {
                Text(msg)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color(red: 1.0, green: 0.5, blue: 0.5))
                    .padding(.top, 10)
            }
        }
    }

    private func tierRow(_ product: Product) -> some View {
        let meta = TipMeta.of(product.id)
        let busy = store.purchasingID == product.id
        return Button {
            Task { await store.purchase(product) }
        } label: {
            HStack(spacing: 14) {
                Text(meta.emoji)
                    .font(.system(size: 24))
                    .frame(width: 48, height: 48)
                    .background(
                        LinearGradient(colors: [Color.white.opacity(0.10), Color.white.opacity(0.04)],
                                       startPoint: .top, endPoint: .bottom)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text(meta.title)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                        if meta.popular {
                            Text("POPULAR")
                                .font(.system(size: 8, weight: .heavy))
                                .tracking(0.6)
                                .foregroundStyle(.black)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(tipLime, in: Capsule())
                        }
                    }
                    Text(meta.blurb)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.4))
                }

                Spacer()

                ZStack {
                    if busy {
                        ProgressView().tint(.black)
                    } else {
                        Text(product.displayPrice)
                            .font(.system(size: 14, weight: .heavy))
                            .foregroundStyle(.black)
                    }
                }
                .frame(minWidth: 74)
                .padding(.vertical, 9)
                .background(tipLime, in: Capsule())
                .opacity(busy ? 0.85 : 1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(
                LinearGradient(colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(
                        meta.popular ? tipLime.opacity(0.45) : Color.white.opacity(0.08),
                        lineWidth: meta.popular ? 1 : 0.5
                    )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(store.purchasingID != nil)
    }

    // MARK: Footer

    private var footer: some View {
        Text("One-time tip · no subscription · nothing to manage")
            .font(.system(size: 10.5, weight: .semibold))
            .foregroundStyle(.white.opacity(0.28))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    // MARK: Close

    private var closeButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "xmark")
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(.white.opacity(0.6))
                .frame(width: 30, height: 30)
                .background(Color.white.opacity(0.08), in: Circle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .padding(.top, 16)
        .padding(.trailing, 16)
    }

    // MARK: Thank-you overlay

    private var thankYou: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 44, weight: .heavy))
                    .foregroundStyle(tipLime)
                Text("Thank you! 💜")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(.white)
                Text("Your support genuinely means a lot and helps keep Verceltics going.")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .multilineTextAlignment(.center)
                Button { dismiss() } label: {
                    Text("Done")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(tipLime, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(PressScaleButtonStyle())
                .padding(.top, 4)
            }
            .padding(26)
            .frame(maxWidth: 320)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color(red: 0.07, green: 0.07, blue: 0.08))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5)
            )
            .padding(.horizontal, 40)
            .transition(.scale(scale: 0.9).combined(with: .opacity))
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: store.didTip)
    }
}
