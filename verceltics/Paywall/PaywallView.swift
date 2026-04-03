import SwiftUI
import StoreKit

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(AuthManager.self) private var authManager
    @State private var selectedProduct: Product?
    @State private var isPurchasing = false
    @State private var isEligibleForTrial = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 32) {
                    Spacer().frame(height: 20)

                    // Header
                    VStack(spacing: 16) {
                        if let uiImage = UIImage(named: "AppIcon") {
                            Image(uiImage: uiImage)
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 72, height: 72)
                                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .shadow(color: .blue.opacity(0.3), radius: 20, y: 4)
                        }

                        Text("Verceltics Pro")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(.white)

                        Text("Your Vercel analytics, everywhere")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.white.opacity(0.5))
                    }

                    // Features
                    VStack(alignment: .leading, spacing: 14) {
                        FeatureRow(icon: "chart.line.uptrend.xyaxis", title: "Full Analytics Dashboard", subtitle: "Visitors, page views, bounce rate & more")
                        FeatureRow(icon: "globe", title: "All Your Projects", subtitle: "Browse every Vercel project in one place")
                        FeatureRow(icon: "clock.arrow.circlepath", title: "Real-Time Data", subtitle: "Pull to refresh with live Vercel API data")
                        FeatureRow(icon: "lock.shield", title: "Secure & Private", subtitle: "Token stored in Keychain, open source code")
                        FeatureRow(icon: "star", title: "All Breakdowns", subtitle: "Pages, referrers, countries, devices, OS & more")
                    }
                    .padding(.horizontal, 24)

                    // Trial badge
                    if isEligibleForTrial {
                        HStack(spacing: 6) {
                            Image(systemName: "gift")
                                .font(.system(size: 12, weight: .bold))
                            Text("3-day free trial included")
                                .font(.system(size: 13, weight: .bold))
                        }
                        .foregroundStyle(.green)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.green.opacity(0.12))
                        .clipShape(Capsule())
                    }

                    // Plan selection
                    if paywall.isLoading {
                        ProgressView()
                            .tint(.white)
                            .padding(.vertical, 20)
                    } else {
                        VStack(spacing: 12) {
                            if let monthly = paywall.monthlyProduct {
                                PlanCard(
                                    product: monthly,
                                    label: "Monthly",
                                    price: monthly.displayPrice,
                                    detail: "per month",
                                    isSelected: selectedProduct?.id == monthly.id,
                                    badge: nil,
                                    showTrial: isEligibleForTrial
                                ) {
                                    selectedProduct = monthly
                                }
                            }

                            if let yearly = paywall.yearlyProduct {
                                PlanCard(
                                    product: yearly,
                                    label: "Yearly",
                                    price: yearly.displayPrice,
                                    detail: "per year",
                                    isSelected: selectedProduct?.id == yearly.id,
                                    badge: "Save 37%",
                                    showTrial: isEligibleForTrial
                                ) {
                                    selectedProduct = yearly
                                }
                            }
                        }
                        .padding(.horizontal, 24)
                    }

                    // Subscribe button
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
                                ProgressView()
                                    .tint(.black)
                            } else {
                                Text(isEligibleForTrial ? "Start Free Trial" : "Subscribe")
                                    .font(.system(size: 17, weight: .bold))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(selectedProduct != nil ? Color.white : Color.white.opacity(0.3))
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .disabled(selectedProduct == nil || isPurchasing)
                    .padding(.horizontal, 24)

                    if let error = paywall.error {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    // Restore + legal + logout
                    VStack(spacing: 12) {
                        Button("Restore Purchases") {
                            Task { await paywall.restorePurchases() }
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.white.opacity(0.4))

                        HStack(spacing: 16) {
                            Link("Privacy Policy", destination: URL(string: "https://verceltics.site/privacy")!)
                            Link("Terms of Use", destination: URL(string: "https://verceltics.site/terms")!)
                        }
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.3))

                        Text("Payment will be charged to your Apple ID. Subscription auto-renews unless cancelled at least 24 hours before the end of the current period.")
                            .font(.system(size: 10))
                            .foregroundStyle(.white.opacity(0.2))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)

                        Button("Sign Out") {
                            authManager.logout()
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.red.opacity(0.6))
                        .padding(.top, 4)
                    }

                    Spacer().frame(height: 20)
                }
            }
        }
        .task {
            await paywall.loadProducts()
            // Default select yearly
            if selectedProduct == nil {
                selectedProduct = paywall.yearlyProduct ?? paywall.monthlyProduct
            }
            // Check trial eligibility
            if let yearly = paywall.yearlyProduct {
                isEligibleForTrial = await yearly.subscription?.isEligibleForIntroOffer ?? false
            }
        }
    }
}

// MARK: - Feature Row

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
                VStack(alignment: .leading, spacing: 4) {
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
                                .background(Color.green.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    Text(showTrial ? "\(price) \(detail) after free trial" : "\(price) \(detail)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.4))
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? .blue : .white.opacity(0.2))
            }
            .padding(16)
            .background(
                LinearGradient(
                    colors: [Color.white.opacity(isSelected ? 0.08 : 0.04), Color.white.opacity(0.02)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(isSelected ? Color.blue.opacity(0.5) : Color.white.opacity(0.08), lineWidth: isSelected ? 1.5 : 0.5)
            )
        }
        .buttonStyle(.plain)
    }
}
