import SwiftUI
import RevenueCat

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPackage: Package?
    @State private var isPurchasing = false
    @State private var isRestoring = false
    @State private var isEligibleForTrial = false
    @State private var transactionError: String?
    private var subscribeButtonLabel: String {
        guard let package = selectedPackage else { return "Subscribe" }
        if package.storeProduct.productIdentifier == PaywallManager.lifetimeProductID { return "Buy Lifetime" }
        if package.storeProduct.productIdentifier == PaywallManager.yearlyProductID, isEligibleForTrial { return "Start Free Trial" }
        return "Subscribe"
    }

    /// Reads the actual trial duration from RevenueCat's StoreKit product so
    /// the badge stays in sync with whatever App Store Connect is serving
    /// (3-day, 7-day, etc.) — no hardcoded copy.
    private var trialBadgeText: String {
        guard let intro = paywall.yearlyPackage?.storeProduct.introductoryDiscount,
              intro.paymentMode == .freeTrial else { return "Free trial" }
        let period = intro.subscriptionPeriod
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
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 40)

                    Text("Verceltics Pro")
                        .font(.system(size: 32, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)

                    Text("Detailed analytics on every project")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                        .padding(.top, 4)

                    VStack(alignment: .leading, spacing: 16) {
                        FeatureRow(icon: "chart.xyaxis.line", title: "Project analytics", subtitle: "Traffic, pages, referrers, devices, and locations")
                        FeatureRow(icon: "calendar", title: "Longer history", subtitle: "Use every time range available to your provider account")
                        FeatureRow(icon: "lock.shield", title: "Device-only credentials", subtitle: "Provider credentials remain in this iPhone's Keychain")
                    }
                    .padding(18)
                    .appSurface()
                    .padding(.horizontal, 20)
                    .padding(.top, 24)

                    Spacer().frame(height: 20)

                    // Trial badge
                    if isEligibleForTrial,
                       selectedPackage?.identifier == paywall.yearlyPackage?.identifier {
                        HStack(spacing: 6) {
                            Image(systemName: "gift.fill")
                                .font(.system(size: 11, weight: .semibold))
                            Text(trialBadgeText)
                                .font(.system(size: 12, weight: .semibold))
                                .tracking(0.2)
                        }
                        .foregroundStyle(AppTheme.success)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(AppTheme.success.opacity(0.12))
                        .clipShape(Capsule())
                    }

                    Spacer().frame(height: 20)

                    // Plans
                    if paywall.isLoading {
                        ProgressView()
                            .tint(.white)
                            .padding(.vertical, 20)
                    } else if paywall.yearlyPackage == nil,
                              paywall.monthlyPackage == nil,
                              paywall.lifetimePackage == nil {
                        AppFeedbackBanner(
                            title: "Plans unavailable",
                            message: paywall.error ?? "The App Store did not return any plans. Check your connection and try again.",
                            icon: "exclamationmark.triangle.fill",
                            tint: AppTheme.warning,
                            actionTitle: "Try again"
                        ) {
                            Task { await loadPlans() }
                        }
                        .padding(.horizontal, 20)
                    } else {
                        VStack(spacing: 10) {
                            if let yearly = paywall.yearlyPackage {
                                PlanCard(
                                    label: "Yearly",
                                    price: yearly.localizedPriceString,
                                    detail: "per year",
                                    isSelected: selectedPackage?.identifier == yearly.identifier,
                                    badge: "Annual",
                                    showTrial: isEligibleForTrial
                                ) { selectedPackage = yearly }
                            }

                            if let lifetime = paywall.lifetimePackage {
                                PlanCard(
                                    label: "Lifetime",
                                    price: lifetime.localizedPriceString,
                                    detail: "one-time, yours forever",
                                    isSelected: selectedPackage?.identifier == lifetime.identifier,
                                    badge: "One-time",
                                    showTrial: false
                                ) { selectedPackage = lifetime }
                            }

                            if let monthly = paywall.monthlyPackage {
                                PlanCard(
                                    label: "Monthly",
                                    price: monthly.localizedPriceString,
                                    detail: "per month",
                                    isSelected: selectedPackage?.identifier == monthly.identifier,
                                    badge: nil,
                                    showTrial: false
                                ) { selectedPackage = monthly }
                            }
                        }
                        .padding(.horizontal, 20)
                    }

                    Spacer().frame(height: 20)

                    // Subscribe
                    Button {
                        guard let package = selectedPackage else { return }
                        isPurchasing = true
                        transactionError = nil
                        Task {
                            let succeeded = await paywall.purchase(package)
                            if !succeeded { transactionError = paywall.error }
                            isPurchasing = false
                        }
                    } label: {
                        HStack(spacing: 10) {
                            if isPurchasing {
                                ProgressView().tint(.black)
                            } else {
                                Text(subscribeButtonLabel)
                                    .font(.system(size: 17, weight: .semibold))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 14, weight: .semibold))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(selectedPackage != nil ? AppTheme.signal : AppTheme.surfaceRaised)
                        .foregroundStyle(selectedPackage != nil ? .white : AppTheme.textTertiary)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(selectedPackage == nil || isPurchasing)
                    .padding(.horizontal, 20)

                    if let transactionError {
                        AppFeedbackBanner(
                            title: "Purchase couldn’t be completed",
                            message: transactionError,
                            icon: "exclamationmark.circle.fill",
                            tint: AppTheme.danger
                        )
                        .padding(.horizontal, 20)
                        .padding(.top, 10)
                    }

                    // Footer
                    VStack(spacing: 12) {
                        Button {
                            isRestoring = true
                            transactionError = nil
                            Task {
                                await paywall.restorePurchases()
                                if let error = paywall.error { transactionError = error }
                                isRestoring = false
                            }
                        } label: {
                            HStack(spacing: 6) {
                                if isRestoring {
                                    ProgressView().controlSize(.small)
                                } else {
                                    Image(systemName: "arrow.clockwise")
                                }
                                Text("Restore purchases")
                                    .font(.footnote.weight(.semibold))
                            }
                            .foregroundStyle(AppTheme.textSecondary)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .disabled(isRestoring)

                        HStack(spacing: 14) {
                            if let privacyURL = URL(string: "https://verceltics.com/privacy"),
                               let termsURL = URL(string: "https://verceltics.com/terms") {
                                Link("Privacy Policy", destination: privacyURL)
                                Text("·")
                                Link("Terms of Use", destination: termsURL)
                            }
                        }
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)

                        Text("Payment charged to Apple ID. Auto-renews unless cancelled 24h before period ends.")
                            .font(.caption)
                            .foregroundStyle(AppTheme.textTertiary)
                            .multilineTextAlignment(.center)
                            .lineSpacing(1.5)
                            .padding(.horizontal, 40)

                        Button("Sign Out") { authManager.logout() }
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42).opacity(0.6))
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                            .padding(.top, 4)
                    }
                    .padding(.top, 20)

                    Spacer().frame(height: 30)
                }
                .frame(maxWidth: hSize == .regular ? 520 : .infinity)
                .frame(maxWidth: .infinity)
            }

            VStack {
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                            .frame(width: 44, height: 44)
                            .background(AppTheme.surfaceRaised, in: Circle())
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .accessibilityLabel("Close")
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .task {
            await loadPlans()
        }
        .onChange(of: paywall.hasActiveSubscription) { _, isActive in
            // Auto-dismiss when shown as a sheet over Projects after a
            // successful purchase or restore.
            if isActive { dismiss() }
        }
    }

    @MainActor
    private func loadPlans() async {
        await paywall.loadProducts()
        if selectedPackage == nil {
            selectedPackage = paywall.yearlyPackage ?? paywall.monthlyPackage ?? paywall.lifetimePackage
        }
        isEligibleForTrial = await paywall.isEligibleForTrial(paywall.yearlyPackage)
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
                .foregroundStyle(AppTheme.signal)
                .frame(width: 36, height: 36)
                .background(AppTheme.signal.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Plan Card

struct PlanCard: View {
    let label: String
    let price: String
    let detail: String
    let isSelected: Bool
    let badge: String?
    var showTrial: Bool = true
    let onTap: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(label)
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white)
                        if let badge {
                            HStack(spacing: 3) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 8, weight: .semibold))
                                Text(badge)
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(0.3)
                            }
                            .foregroundStyle(AppTheme.success)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(AppTheme.success.opacity(0.12))
                            .clipShape(Capsule())
                        }
                    }
                    Text(showTrial ? "\(price) \(detail) after trial" : "\(price) \(detail)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.45))
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(isSelected ? AppTheme.signal : AppTheme.textTertiary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
            .background(isSelected ? AppTheme.signal.opacity(0.09) : AppTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(isSelected ? AppTheme.signal.opacity(0.65) : AppTheme.stroke, lineWidth: isSelected ? 1 : 0.5)
            )
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isSelected)
        }
        .buttonStyle(PressScaleButtonStyle())
        .sensoryFeedback(.selection, trigger: isSelected)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
