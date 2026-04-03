import SwiftUI
import StoreKit

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(AuthManager.self) private var authManager
    @State private var selectedProduct: Product?
    @State private var isPurchasing = false
    @State private var isEligibleForTrial = true
    @State private var currentQuoteIndex = 0

    private let devQuotes: [(emoji: String, text: String)] = [
        ("🚀", "Ship faster. Track smarter."),
        ("☕", "console.log('why no analytics?')"),
        ("🔥", "Your deploy succeeded.\nBut did anyone visit?"),
        ("🤔", "404: Analytics not found.\nUntil now."),
        ("💻", "git commit -m \"finally tracking visitors\""),
        ("📊", "Numbers don't lie.\nYour bounce rate might."),
        ("🧑‍💻", "Monitoring prod from the couch? Yes."),
        ("⚡", "Vercel deploys in seconds.\nSo should your analytics."),
        ("🎯", "Know your users.\nNot just your console.log."),
        ("🌍", "Your site is global.\nYour analytics should be too."),
        ("😅", "\"It works on my machine\"\n— also tracks visitors now"),
        ("🍕", "Deploy. Eat pizza. Check analytics. Repeat."),
    ]

    private let quoteTimer = Timer.publish(every: 4, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 40)

                    // App icon
                    if let uiImage = UIImage(named: "AppIcon") {
                        Image(uiImage: uiImage)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 68, height: 68)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .shadow(color: .blue.opacity(0.2), radius: 16, y: 4)
                    }

                    Spacer().frame(height: 20)

                    Text("Verceltics Pro")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(.white)

                    Text("Your Vercel analytics, everywhere")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white.opacity(0.4))
                        .padding(.top, 4)

                    // Rotating dev quote
                    VStack(spacing: 8) {
                        Text(devQuotes[currentQuoteIndex].emoji)
                            .font(.system(size: 32))

                        Text(devQuotes[currentQuoteIndex].text)
                            .font(.system(size: 13, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.5))
                            .multilineTextAlignment(.center)
                            .lineSpacing(3)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 100)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                    .id(currentQuoteIndex)
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .move(edge: .trailing)),
                        removal: .opacity.combined(with: .move(edge: .leading))
                    ))
                    .onReceive(quoteTimer) { _ in
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentQuoteIndex = (currentQuoteIndex + 1) % devQuotes.count
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
                            Image(systemName: "gift")
                                .font(.system(size: 11, weight: .bold))
                            Text("3-day free trial")
                                .font(.system(size: 12, weight: .bold))
                        }
                        .foregroundStyle(.green)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.green.opacity(0.1))
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(Color.green.opacity(0.2), lineWidth: 0.5))
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

                            if let monthly = paywall.monthlyProduct {
                                PlanCard(
                                    product: monthly,
                                    label: "Monthly",
                                    price: monthly.displayPrice,
                                    detail: "per month",
                                    isSelected: selectedProduct?.id == monthly.id,
                                    badge: nil,
                                    showTrial: isEligibleForTrial
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
                                Text(isEligibleForTrial ? "Start Free Trial" : "Subscribe")
                                    .font(.system(size: 17, weight: .bold))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(selectedProduct != nil ? .white : .white.opacity(0.2))
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .disabled(selectedProduct == nil || isPurchasing)
                    .padding(.horizontal, 20)

                    if let error = paywall.error {
                        Text(error)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.red)
                            .padding(.top, 8)
                    }

                    // Footer
                    VStack(spacing: 10) {
                        Button("Restore Purchases") {
                            Task { await paywall.restorePurchases() }
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.white.opacity(0.35))

                        HStack(spacing: 16) {
                            Link("Privacy Policy", destination: URL(string: "https://verceltics.site/privacy")!)
                            Link("Terms of Use", destination: URL(string: "https://verceltics.site/terms")!)
                        }
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.25))

                        Text("Payment charged to Apple ID. Auto-renews unless cancelled 24h before period ends.")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundStyle(.white.opacity(0.15))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)

                        Button("Sign Out") { authManager.logout() }
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.red.opacity(0.5))
                            .padding(.top, 4)
                    }
                    .padding(.top, 20)

                    Spacer().frame(height: 30)
                }
            }
        }
        .task {
            await paywall.loadProducts()
            if selectedProduct == nil {
                selectedProduct = paywall.yearlyProduct ?? paywall.monthlyProduct
            }
            if let yearly = paywall.yearlyProduct {
                isEligibleForTrial = await yearly.subscription?.isEligibleForIntroOffer ?? false
            }
        }
    }
}

// MARK: - Feature Cell (compact grid)

struct FeatureCell: View {
    let icon: String
    let title: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(.blue)

            Text(title)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white.opacity(0.5))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 70)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.white.opacity(0.04), lineWidth: 0.5)
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

    var body: some View {
        Button(action: onTap) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text(label)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                        if let badge {
                            Text(badge)
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.green)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(Color.green.opacity(0.12))
                                .clipShape(Capsule())
                        }
                    }
                    Text(showTrial ? "\(price) \(detail) after trial" : "\(price) \(detail)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.35))
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 24))
                    .foregroundStyle(isSelected ? .blue : .white.opacity(0.15))
            }
            .padding(16)
            .background(.ultraThinMaterial.opacity(isSelected ? 0.6 : 0.3))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(isSelected ? Color.blue.opacity(0.4) : Color.white.opacity(0.06), lineWidth: isSelected ? 1.5 : 0.5)
            )
        }
        .buttonStyle(.plain)
    }
}
