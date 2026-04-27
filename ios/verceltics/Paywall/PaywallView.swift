import SwiftUI
import StoreKit
import Combine

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.dismiss) private var dismiss
    @State private var selectedProduct: Product?
    @State private var isPurchasing = false
    @State private var isEligibleForTrial = true
    @State private var currentMemeIndex = 0

    private let memes: [(gif: String, caption: String)] = [
        ("https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif", "Me checking analytics at 3am"),
        ("https://media.giphy.com/media/du3J3cXyzhj75IOgvA/giphy.gif", "When bounce rate finally drops"),
        ("https://media.giphy.com/media/13HgwGsXF0aiGY/giphy.gif", "Deploying to prod on Friday"),
        ("https://media.giphy.com/media/VbnUQpnihPSIgIXuZv/giphy.gif", "When visitors spike overnight"),
        ("https://media.giphy.com/media/26tn33aiTi1jkl6H6/giphy.gif", "git push origin main"),
        ("https://media.giphy.com/media/ule4vhcY1xEKQ/giphy.gif", "Watching real-time analytics"),
        ("https://media.giphy.com/media/LmNwrBhejkK9EFP504/giphy.gif", "When the site goes viral"),
        ("https://media.giphy.com/media/l3q2K5jinAlChoCLS/giphy.gif", "100% bounce rate vibes"),
        ("https://media.giphy.com/media/l41lFw057lAJQMwg0/giphy.gif", "\"Just one more feature\""),
        ("https://media.giphy.com/media/3o7aD2saalBwwftBIY/giphy.gif", "Reading server logs at midnight"),
    ]

    @State private var memeTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var subscribeButtonLabel: String {
        guard let product = selectedProduct else { return "Subscribe" }
        if product.id == PaywallManager.lifetimeProductID { return "Buy Lifetime" }
        if product.id == PaywallManager.yearlyProductID, isEligibleForTrial { return "Start Free Trial" }
        return "Subscribe"
    }

    /// Reads the actual trial duration from the live StoreKit product so
    /// the badge stays in sync with whatever App Store Connect is serving
    /// (3-day, 7-day, etc.) — no hardcoded copy.
    private var trialBadgeText: String {
        guard let intro = paywall.yearlyProduct?.subscription?.introductoryOffer,
              intro.paymentMode == .freeTrial else { return "Free trial" }
        let period = intro.period
        let count: Int
        switch period.unit {
        case .day:   count = period.value
        case .week:  count = period.value * 7
        case .month: count = period.value * 30
        case .year:  count = period.value * 365
        @unknown default: return "Free trial"
        }
        return "\(count)-day free trial"
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 40)

                    Text("Verceltics Pro")
                        .font(.system(size: 32, weight: .heavy))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.white, Color.white.opacity(0.75)],
                                startPoint: .top, endPoint: .bottom
                            )
                        )

                    Text("Your Vercel analytics, everywhere")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.45))
                        .padding(.top, 4)

                    // Rotating meme GIF
                    VStack(spacing: 10) {
                        GIFView(url: memes[currentMemeIndex].gif)
                            .frame(width: 180, height: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(
                                        LinearGradient(
                                            colors: [Color.white.opacity(0.16), Color.white.opacity(0.04)],
                                            startPoint: .top, endPoint: .bottom
                                        ),
                                        lineWidth: 0.5
                                    )
                            )
                            .id(currentMemeIndex)

                        Text("\u{201C}\(memes[currentMemeIndex].caption)\u{201D}")
                            .font(.system(size: 12, weight: .semibold))
                            .italic()
                            .foregroundStyle(.white.opacity(0.5))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .onReceive(memeTimer) { _ in
                        withAnimation(.easeInOut(duration: 0.3)) {
                            currentMemeIndex = (currentMemeIndex + 1) % memes.count
                        }
                    }

                    // Features grid
                    VStack(spacing: 0) {
                        HStack(spacing: 0) {
                            FeatureCell(icon: "chart.line.uptrend.xyaxis", title: "Analytics")
                            FeatureCell(icon: "globe", title: "Projects")
                            FeatureCell(icon: "clock.arrow.circlepath", title: "Real-Time")
                        }
                        HStack(spacing: 0) {
                            FeatureCell(icon: "lock.shield", title: "Secure")
                            FeatureCell(icon: "map", title: "Countries")
                            FeatureCell(icon: "desktopcomputer", title: "Devices")
                        }
                    }
                    .padding(.horizontal, 20)

                    Spacer().frame(height: 20)

                    // Trial badge
                    if isEligibleForTrial {
                        HStack(spacing: 6) {
                            Image(systemName: "gift.fill")
                                .font(.system(size: 11, weight: .heavy))
                            Text(trialBadgeText)
                                .font(.system(size: 12, weight: .heavy))
                                .tracking(0.2)
                        }
                        .foregroundStyle(Color(red: 0.30, green: 0.85, blue: 0.55))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(
                            LinearGradient(
                                colors: [
                                    Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.16),
                                    Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.06)
                                ],
                                startPoint: .top, endPoint: .bottom
                            )
                        )
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.28), lineWidth: 0.5))
                    }

                    Spacer().frame(height: 20)

                    // Plans
                    if paywall.isLoading {
                        ProgressView()
                            .tint(.white)
                            .padding(.vertical, 20)
                    } else {
                        VStack(spacing: 10) {
                            if let yearly = paywall.yearlyProduct {
                                PlanCard(
                                    product: yearly,
                                    label: "Yearly",
                                    price: yearly.displayPrice,
                                    detail: "per year",
                                    isSelected: selectedProduct?.id == yearly.id,
                                    badge: "Best Value",
                                    showTrial: isEligibleForTrial
                                ) { selectedProduct = yearly }
                            }

                            if let lifetime = paywall.lifetimeProduct {
                                PlanCard(
                                    product: lifetime,
                                    label: "Lifetime",
                                    price: lifetime.displayPrice,
                                    detail: "one-time, yours forever",
                                    isSelected: selectedProduct?.id == lifetime.id,
                                    badge: "Forever",
                                    showTrial: false
                                ) { selectedProduct = lifetime }
                            }

                            if let monthly = paywall.monthlyProduct {
                                PlanCard(
                                    product: monthly,
                                    label: "Monthly",
                                    price: monthly.displayPrice,
                                    detail: "per month",
                                    isSelected: selectedProduct?.id == monthly.id,
                                    badge: nil,
                                    showTrial: false
                                ) { selectedProduct = monthly }
                            }
                        }
                        .padding(.horizontal, 20)
                    }

                    Spacer().frame(height: 20)

                    // Subscribe
                    Button {
                        guard let product = selectedProduct else { return }
                        isPurchasing = true
                        Task {
                            _ = await paywall.purchase(product)
                            isPurchasing = false
                        }
                    } label: {
                        HStack(spacing: 10) {
                            if isPurchasing {
                                ProgressView().tint(.black)
                            } else {
                                Text(subscribeButtonLabel)
                                    .font(.system(size: 17, weight: .heavy))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 14, weight: .heavy))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(
                            LinearGradient(
                                colors: selectedProduct != nil
                                    ? [.white, Color.white.opacity(0.92)]
                                    : [Color.white.opacity(0.2), Color.white.opacity(0.12)],
                                startPoint: .top, endPoint: .bottom
                            )
                        )
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(selectedProduct == nil || isPurchasing)
                    .padding(.horizontal, 20)

                    if let error = paywall.error {
                        Text(error)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.red)
                            .padding(.top, 8)
                    }

                    // Footer
                    VStack(spacing: 12) {
                        Button {
                            Task { await paywall.restorePurchases() }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 10, weight: .heavy))
                                Text("Restore Purchases")
                                    .font(.system(size: 13, weight: .bold))
                            }
                            .foregroundStyle(.white.opacity(0.45))
                        }

                        HStack(spacing: 14) {
                            Link("Privacy Policy", destination: URL(string: "https://verceltics.com/privacy")!)
                            Text("·")
                            Link("Terms of Use", destination: URL(string: "https://verceltics.com/terms")!)
                        }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.3))

                        Text("Payment charged to Apple ID. Auto-renews unless cancelled 24h before period ends.")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundStyle(.white.opacity(0.2))
                            .multilineTextAlignment(.center)
                            .lineSpacing(1.5)
                            .padding(.horizontal, 40)

                        Button("Sign Out") { authManager.logout() }
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42).opacity(0.6))
                            .padding(.top, 4)
                    }
                    .padding(.top, 20)

                    Spacer().frame(height: 30)
                }
                .frame(maxWidth: hSize == .regular ? 520 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .task {
            await paywall.loadProducts()
            if selectedProduct == nil {
                selectedProduct = paywall.yearlyProduct ?? paywall.monthlyProduct ?? paywall.lifetimeProduct
            }
            if let yearly = paywall.yearlyProduct {
                isEligibleForTrial = await yearly.subscription?.isEligibleForIntroOffer ?? false
            }
        }
        .onChange(of: paywall.hasActiveSubscription) { _, isActive in
            // Auto-dismiss when shown as a sheet over Projects after a
            // successful purchase or restore.
            if isActive { dismiss() }
        }
    }
}

// MARK: - Feature Cell (compact grid)

struct FeatureCell: View {
    let icon: String
    let title: String

    var body: some View {
        VStack(spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color.blue, Color(red: 0.45, green: 0.65, blue: 1.0)],
                        startPoint: .top, endPoint: .bottom
                    )
                )

            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.white.opacity(0.6))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 76)
        .background(
            ZStack {
                LinearGradient(
                    colors: [Color.white.opacity(0.06), Color.white.opacity(0.02)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [Color.blue.opacity(0.06), .clear],
                    startPoint: .topLeading, endPoint: .center
                )
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0.10), Color.white.opacity(0.03)],
                        startPoint: .top, endPoint: .bottom
                    ),
                    lineWidth: 0.5
                )
        )
        .padding(3)
    }
}

// MARK: - Feature Row (kept for compatibility)

struct FeatureRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.blue)
                .frame(width: 36, height: 36)
                .background(Color.blue.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }
}

// MARK: - Plan Card

struct PlanCard: View {
    let product: Product
    let label: String
    let price: String
    let detail: String
    let isSelected: Bool
    let badge: String?
    var showTrial: Bool = true
    let onTap: () -> Void

    private var isPremium: Bool { badge != nil }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(label)
                            .font(.system(size: 17, weight: .heavy))
                            .foregroundStyle(.white)
                        if let badge {
                            HStack(spacing: 3) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 8, weight: .heavy))
                                Text(badge)
                                    .font(.system(size: 10, weight: .heavy))
                                    .tracking(0.3)
                            }
                            .foregroundStyle(Color(red: 0.30, green: 0.85, blue: 0.55))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.14))
                            .clipShape(Capsule())
                            .overlay(Capsule().strokeBorder(Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.22), lineWidth: 0.5))
                        }
                    }
                    Text(showTrial ? "\(price) \(detail) after trial" : "\(price) \(detail)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.45))
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(isSelected ? Color.blue : Color.white.opacity(0.18))
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
            .background(
                ZStack {
                    LinearGradient(
                        colors: isSelected
                            ? [Color.blue.opacity(0.14), Color.blue.opacity(0.04)]
                            : [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    LinearGradient(
                        colors: [Color.white.opacity(0.04), .clear],
                        startPoint: .top, endPoint: .center
                    )
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        isSelected
                            ? AnyShapeStyle(LinearGradient(
                                colors: [Color.blue.opacity(0.55), Color.blue.opacity(0.25)],
                                startPoint: .top, endPoint: .bottom
                            ))
                            : AnyShapeStyle(LinearGradient(
                                colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                                startPoint: .top, endPoint: .bottom
                            )),
                        lineWidth: isSelected ? 1.2 : 0.5
                    )
            )
            .shadow(
                color: isSelected ? Color.blue.opacity(0.25) : .clear,
                radius: isSelected ? 18 : 0,
                x: 0, y: isSelected ? 6 : 0
            )
            .scaleEffect(isPremium && isSelected ? 1.0 : 1.0)
            .animation(.spring(response: 0.35, dampingFraction: 0.85), value: isSelected)
        }
        .buttonStyle(PressScaleButtonStyle())
        .sensoryFeedback(.selection, trigger: isSelected)
    }
}

// MARK: - GIF View (animated via WKWebView)

import WebKit

struct GIFView: UIViewRepresentable {
    let url: String

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.isUserInteractionEnabled = false
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let html = """
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
        * { margin: 0; padding: 0; }
        body { background: transparent; display: flex; align-items: center; justify-content: center; height: 100vh; overflow: hidden; }
        img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; }
        </style></head>
        <body><img src="\(url)" /></body>
        </html>
        """
        webView.loadHTMLString(html, baseURL: nil)
    }
}
