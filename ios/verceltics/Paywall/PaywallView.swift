import SwiftUI
import RevenueCat

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPackage: Package?
    @State private var isPurchasing = false
    @State private var isRestoring = false
    @State private var isEligibleForTrial = false
    @State private var transactionAlert: TransactionAlert?

    private var subscribeButtonLabel: String {
        guard let package = selectedPackage else { return "Unlock Pro" }
        switch package.storeProduct.productIdentifier {
        case PaywallManager.lifetimeProductID:
            return "Buy lifetime access"
        case PaywallManager.yearlyProductID where isEligibleForTrial:
            return "Start \(trialBadgeText)"
        case PaywallManager.yearlyProductID:
            return "Subscribe yearly"
        case PaywallManager.monthlyProductID:
            return "Subscribe monthly"
        default:
            return "Unlock Pro"
        }
    }

    private var purchaseDisclosure: String {
        guard let package = selectedPackage else {
            return "Choose a plan to unlock every connected workspace."
        }
        let price = package.localizedPriceString
        switch package.storeProduct.productIdentifier {
        case PaywallManager.lifetimeProductID:
            return "\(price) one-time purchase. No renewal."
        case PaywallManager.yearlyProductID where isEligibleForTrial:
            return "\(trialBadgeText.capitalized), then \(price) per year. Renews annually until canceled."
        case PaywallManager.yearlyProductID:
            return "\(price) per year. Renews annually until canceled."
        case PaywallManager.monthlyProductID:
            return "\(price) per month. Renews monthly until canceled."
        default:
            return "Payment is charged to your Apple Account."
        }
    }

    private var selectedPlanName: String {
        guard let package = selectedPackage else { return "Select a plan" }
        switch package.storeProduct.productIdentifier {
        case PaywallManager.lifetimeProductID:
            return "Lifetime access"
        case PaywallManager.yearlyProductID where isEligibleForTrial:
            return "Yearly · \(trialBadgeText)"
        case PaywallManager.yearlyProductID:
            return "Yearly access"
        case PaywallManager.monthlyProductID:
            return "Monthly access"
        default:
            return "Verceltics Pro"
        }
    }

    private var selectedPricePeriod: String {
        guard let package = selectedPackage else { return "" }
        switch package.storeProduct.productIdentifier {
        case PaywallManager.lifetimeProductID:
            return "one-time"
        case PaywallManager.yearlyProductID:
            return "per year"
        case PaywallManager.monthlyProductID:
            return "per month"
        default:
            return ""
        }
    }

    /// Reads the actual trial duration from RevenueCat's StoreKit product so
    /// the paywall stays in sync with App Store Connect.
    private var trialBadgeText: String {
        guard let intro = paywall.yearlyPackage?.storeProduct.introductoryDiscount,
              intro.paymentMode == .freeTrial else { return "free trial" }
        let period = intro.subscriptionPeriod
        let duration: String
        switch period.unit {
        case .day:
            duration = "\(period.value)-day"
        case .week:
            duration = period.value == 1 ? "7-day" : "\(period.value)-week"
        case .month:
            duration = "\(period.value)-month"
        case .year:
            duration = "\(period.value)-year"
        @unknown default: return "free trial"
        }
        return "\(duration) free trial"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        hero
                        WholeStackRail()
                        benefits
                        plans

                        purchaseFooter
                    }
                    .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                    .padding(.top, 18)
                    .padding(.bottom, 28)
                    .frame(maxWidth: horizontalSizeClass == .regular ? 720 : 620)
                    .frame(maxWidth: .infinity)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.textPrimary)
                            .frame(width: 36, height: 36)
                            .background(AppTheme.surfaceRaised, in: Circle())
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(isPurchasing || isRestoring)
                    .accessibilityLabel("Close")
                    .accessibilityHint(
                        isPurchasing || isRestoring
                            ? "Available when the App Store transaction finishes"
                            : "Dismiss Verceltics Pro"
                    )
                }
            }
            .safeAreaInset(edge: .bottom) {
                purchaseBar
            }
            .alert(item: $transactionAlert) { alert in
                Alert(
                    title: Text(alert.title),
                    message: Text(alert.message),
                    dismissButton: .default(Text("OK"))
                )
            }
        }
        .task {
            await loadPlans()
        }
        .onChange(of: paywall.hasActiveSubscription) { _, isActive in
            if isActive { dismiss() }
        }
        .interactiveDismissDisabled(isPurchasing || isRestoring)
    }

    private var hero: some View {
        VStack(spacing: 10) {
            Image("AppLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: AppTheme.shadowSoft, radius: 12, y: 5)
                .accessibilityHidden(true)

            Text("VERCELTICS PRO")
                .font(.caption2.weight(.bold))
                .tracking(1.4)
                .foregroundStyle(AppTheme.signal)

            Text("Your whole web stack, unlocked")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
                .multilineTextAlignment(.center)

            Text("Open hosting, domains, deployments, analytics, search, speed, uptime, and provider tools from one private workspace.")
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .frame(maxWidth: 560)
        }
    }

    private var benefits: some View {
        VStack(alignment: .leading, spacing: 0) {
            ProBenefitRow(
                icon: "point.3.connected.trianglepath.dotted",
                title: "Full provider depth",
                subtitle: "Open dashboards, detail views, reports, and advanced provider operations."
            )
            AppInsetDivider(leading: 58)
            ProBenefitRow(
                icon: "lock.shield.fill",
                title: "Private connections",
                subtitle: "Credentials stay in this device’s Keychain; requests go directly to provider APIs."
            )
        }
        .appSurface()
    }

    @ViewBuilder
    private var plans: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Choose your access")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Every paid option unlocks the same Pro workspace.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            }

            if paywall.isLoading {
                HStack(spacing: 10) {
                    ProgressView().tint(AppTheme.signal)
                    Text("Loading App Store plans…")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 28)
                .appSurface()
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
            } else {
                VStack(spacing: 10) {
                    if let yearly = paywall.yearlyPackage {
                        PlanCard(
                            label: "Yearly",
                            price: yearly.localizedPriceString,
                            detail: "per year",
                            isSelected: selectedPackage?.identifier == yearly.identifier,
                            badge: isEligibleForTrial ? trialBadgeText : "Best value",
                            showTrial: isEligibleForTrial
                        ) { selectedPackage = yearly }
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

                    if let lifetime = paywall.lifetimePackage {
                        PlanCard(
                            label: "Lifetime",
                            price: lifetime.localizedPriceString,
                            detail: "one-time, no renewal",
                            isSelected: selectedPackage?.identifier == lifetime.identifier,
                            badge: "One-time",
                            showTrial: false
                        ) { selectedPackage = lifetime }
                    }
                }
            }
        }
    }

    private var purchaseFooter: some View {
        VStack(spacing: 12) {
            Button {
                isRestoring = true
                transactionAlert = nil
                Task {
                    await paywall.restorePurchases()
                    if let error = paywall.error {
                        transactionAlert = TransactionAlert(
                            title: "Restore couldn’t be completed",
                            message: error
                        )
                    } else if !paywall.hasActiveSubscription {
                        transactionAlert = TransactionAlert(
                            title: "No purchase found",
                            message: "No active Verceltics Pro purchase was found for this Apple Account."
                        )
                    }
                    isRestoring = false
                }
            } label: {
                HStack(spacing: 7) {
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
            .disabled(isRestoring || isPurchasing)

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

            Text("Purchases are handled by Apple. Subscriptions can be managed in your Apple Account settings.")
                .font(.caption)
                .foregroundStyle(AppTheme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(1.5)
                .frame(maxWidth: 460)
        }
    }

    private var purchaseBar: some View {
        VStack(spacing: 8) {
            if let package = selectedPackage {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        selectedPlanLabel
                        Spacer(minLength: 8)
                        selectedPrice(package)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        selectedPlanLabel
                        selectedPrice(package)
                    }
                }
                .accessibilityElement(children: .combine)
            }

            Button {
                guard let package = selectedPackage else { return }
                isPurchasing = true
                transactionAlert = nil
                Task {
                    let succeeded = await paywall.purchase(package)
                    if !succeeded, let error = paywall.error {
                        transactionAlert = TransactionAlert(
                            title: "Purchase couldn’t be completed",
                            message: error
                        )
                    }
                    isPurchasing = false
                }
            } label: {
                HStack(spacing: 10) {
                    if isPurchasing {
                        ProgressView().tint(AppTheme.canvas)
                    } else {
                        Text(subscribeButtonLabel)
                            .font(.headline)
                        Image(systemName: "arrow.right")
                            .font(.callout.weight(.semibold))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: 54)
                .background(selectedPackage != nil ? AppTheme.signal : AppTheme.surfaceRaised)
                .foregroundStyle(selectedPackage != nil ? AppTheme.canvas : AppTheme.textTertiary)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                }
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(selectedPackage == nil || isPurchasing || isRestoring)
            .accessibilityLabel(isPurchasing ? "Completing purchase" : subscribeButtonLabel)
            .accessibilityValue(isPurchasing ? "In progress" : purchaseDisclosure)

            Text(purchaseDisclosure)
                .font(.caption)
                .foregroundStyle(AppTheme.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: horizontalSizeClass == .regular ? 720 : 620)
        .frame(maxWidth: .infinity)
        .background(AppTheme.surface.shadow(color: AppTheme.shadowSoft, radius: 12, y: -4))
        .overlay(alignment: .top) {
            Rectangle().fill(AppTheme.divider).frame(height: 0.5)
        }
    }

    private var selectedPlanLabel: some View {
        Text(selectedPlanName)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppTheme.textPrimary)
    }

    private func selectedPrice(_ package: Package) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(package.localizedPriceString)
                .font(.title3.weight(.bold))
                .foregroundStyle(AppTheme.textPrimary)
            Text(selectedPricePeriod)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
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

private struct WholeStackRail: View {
    private var lanes: [ProWorkspaceLane] { [
        ProWorkspaceLane(icon: "server.rack", count: AccountProvider.allCases.count, label: "Hosting"),
        ProWorkspaceLane(icon: "globe.americas.fill", count: RegistrarProvider.allCases.count, label: "Registrars"),
        ProWorkspaceLane(icon: "chart.xyaxis.line", count: SiteIntegrationProvider.allCases.count, label: "Site services")
    ] }

    private var integrationCount: Int {
        lanes.reduce(0) { $0 + $1.count }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("ONE CONNECTED WORKSPACE")
                    .font(.caption2.weight(.bold))
                    .tracking(1)
                    .foregroundStyle(AppTheme.textSecondary)
                Spacer()
                Text("\(integrationCount) integrations")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.signal)
            }

            HStack(spacing: 0) {
                ForEach(Array(lanes.enumerated()), id: \.offset) { index, lane in
                    VStack(spacing: 7) {
                        Image(systemName: lane.icon)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(AppTheme.signal)
                            .frame(width: 36, height: 36)
                            .background(AppTheme.signal.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                        Text("\(lane.count)")
                            .font(.title2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(AppTheme.textPrimary)
                        Text(lane.label)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .combine)

                    if index < lanes.count - 1 {
                        VStack(spacing: 3) {
                            Circle().fill(AppTheme.signal).frame(width: 4, height: 4)
                            Rectangle().fill(AppTheme.signal.opacity(0.35)).frame(width: 1, height: 34)
                            Circle().fill(AppTheme.signal).frame(width: 4, height: 4)
                        }
                        .accessibilityHidden(true)
                    }
                }
            }
        }
        .padding(18)
        .appSurface()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(integrationCount) integrations: \(AccountProvider.allCases.count) hosting platforms, \(RegistrarProvider.allCases.count) registrars, and \(SiteIntegrationProvider.allCases.count) site services"
        )
    }
}

private struct ProWorkspaceLane {
    let icon: String
    let count: Int
    let label: String
}

private struct TransactionAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

private struct ProBenefitRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AppTheme.signal)
                .frame(width: 36, height: 36)
                .background(AppTheme.signal.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
    }
}

struct PlanCard: View {
    let label: String
    let price: String
    let detail: String
    let isSelected: Bool
    let badge: String?
    var showTrial = true
    let onTap: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: onTap) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 10) {
                        planTitle
                        priceDetail
                        selectionIcon
                    }
                } else {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 6) {
                            planTitle
                            priceDetail
                        }
                        Spacer()
                        selectionIcon
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
            .background(isSelected ? AppTheme.signal.opacity(0.09) : AppTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(
                        isSelected ? AppTheme.signal.opacity(0.7) : AppTheme.stroke,
                        lineWidth: isSelected ? 1 : 0.5
                    )
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isSelected)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint("Select this purchase option")
    }

    private var planTitle: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 6) {
                    planLabel
                    planBadge
                }
            } else {
                HStack(spacing: 8) {
                    planLabel
                    planBadge
                }
            }
        }
    }

    private var planLabel: some View {
        Text(label)
            .font(.headline)
            .foregroundStyle(AppTheme.textPrimary)
    }

    @ViewBuilder
    private var planBadge: some View {
        if let badge {
            Text(badge.uppercased())
                .font(.caption2.weight(.bold))
                .tracking(0.35)
                .foregroundStyle(AppTheme.success)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(AppTheme.success.opacity(0.12))
                .clipShape(Capsule())
        }
    }

    private var priceDetail: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                priceAmount
                billingDetail
            }
            VStack(alignment: .leading, spacing: 3) {
                priceAmount
                billingDetail
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var priceAmount: some View {
        Text(price)
            .font(.title3.weight(.bold))
            .foregroundStyle(AppTheme.textPrimary)
    }

    private var billingDetail: some View {
        Text(showTrial ? "\(detail) after trial" : detail)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(AppTheme.textSecondary)
    }

    private var selectionIcon: some View {
        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
            .font(.title2)
            .foregroundStyle(isSelected ? AppTheme.signal : AppTheme.textTertiary)
            .contentTransition(.symbolEffect(.replace))
            .accessibilityHidden(true)
    }
}
