import SwiftUI
import RevenueCat

struct PaywallView: View {
    @Environment(PaywallManager.self) private var paywall
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPackage: Package?
    @State private var isPurchasing = false
    @State private var isRestoring = false
    @State private var isEligibleForTrial = false
    @State private var transactionAlert: TransactionAlert?

    private var subscribeButtonLabel: String {
        if paywall.isLoading { return "Loading plans…" }
        guard let package = selectedPackage else { return "Choose a plan" }
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
        if paywall.isLoading {
            return "Loading current prices and eligibility from the App Store."
        }
        guard let package = selectedPackage else {
            return "Choose a plan to open Pro details and tools across every workspace."
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
                    Group {
                        if dynamicTypeSize.isAccessibilitySize {
                            compactPaywallLayout
                        } else {
                            ViewThatFits(in: .horizontal) {
                                regularPaywallLayout
                                    .frame(minWidth: 800)
                                compactPaywallLayout
                            }
                        }
                    }
                    .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                    .padding(.top, 18)
                    .padding(.bottom, 28)
                    .frame(maxWidth: horizontalSizeClass == .regular ? 980 : 620)
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
                if paywall.isLoading || selectedPackage != nil {
                    purchaseBar
                }
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

    private var regularPaywallLayout: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(spacing: 18) {
                hero
                ProAccessScope()
                benefits
            }
            .frame(minWidth: 380, maxWidth: .infinity)

            VStack(spacing: 22) {
                plans
                purchaseFooter
            }
            .frame(minWidth: 360, maxWidth: .infinity)
        }
    }

    private var compactPaywallLayout: some View {
        VStack(spacing: 20) {
            hero
            ProAccessScope()
            benefits
            plans
            purchaseFooter
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image("AppLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 50, height: 50)
                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
                    }
                    .shadow(color: AppTheme.shadowSoft, radius: 10, y: 4)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text("VERCELTICS PRO")
                        .font(.caption2.weight(.bold))
                        .tracking(1.4)
                        .foregroundStyle(AppTheme.signal)
                    Text("ONE ENTITLEMENT · EVERY CONNECTION")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                }
            }

            Text("Open the whole stack.")
                .font(.largeTitle.weight(.bold))
                .fontDesign(.rounded)
                .foregroundStyle(AppTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            Text("Open projects, deployments, domains, site services, provider dashboards, and advanced tools across all \(IntegrationCatalogSummary.totalCount) integrations.")
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var benefits: some View {
        VStack(alignment: .leading, spacing: 0) {
            ProBenefitRow(
                icon: "point.3.connected.trianglepath.dotted",
                title: "Details and provider tools",
                subtitle: "Open dashboards, reports, API catalogs, and confirmed provider actions."
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
                Text("Every option unlocks the same Pro features.")
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
            if let package = selectedPackage, !dynamicTypeSize.isAccessibilitySize {
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
                    if isPurchasing || paywall.isLoading {
                        ProgressView()
                            .tint(selectedPackage != nil ? AppTheme.canvas : AppTheme.textSecondary)
                    }
                    Text(subscribeButtonLabel)
                        .font(.headline)
                    if !isPurchasing && !paywall.isLoading {
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
            .disabled(paywall.isLoading || selectedPackage == nil || isPurchasing || isRestoring)
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
        .background(
            AppTheme.surface
                .shadow(color: AppTheme.shadowSoft, radius: 12, y: -4)
                .ignoresSafeArea(edges: .bottom)
        )
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

private struct ProAccessScope: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var isRevealed = false
    private let lanes = IntegrationCatalogSummary.lanes

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 5) {
                    scopeTitle
                    connectionCount
                }
            } else {
                HStack(alignment: .firstTextBaseline) {
                    scopeTitle
                    Spacer(minLength: 10)
                    connectionCount
                }
            }

            VStack(spacing: 0) {
                ForEach(Array(lanes.enumerated()), id: \.element.id) { index, lane in
                    ProAccessScopeRow(lane: lane, isRevealed: isRevealed)
                    if index < lanes.count - 1 {
                        AppInsetDivider(leading: 46)
                    }
                }
            }
        }
        .padding(18)
        .appSurface()
        .overlay(alignment: .leading) {
            Capsule()
                .fill(AppTheme.signal)
                .frame(width: 2)
                .padding(.vertical, 18)
                .padding(.leading, 1)
                .scaleEffect(y: isRevealed ? 1 : 0, anchor: .top)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Pro includes \(IntegrationCatalogSummary.accessibilitySummary)")
        .task(id: reduceMotion) {
            isRevealed = reduceMotion
            guard !reduceMotion else { return }
            withAnimation(.easeOut(duration: 0.62)) {
                isRevealed = true
            }
        }
    }

    private var scopeTitle: some View {
        Text("PRO ACCESS SCOPE")
            .font(.caption2.weight(.bold))
            .tracking(1)
            .foregroundStyle(AppTheme.textSecondary)
    }

    private var connectionCount: some View {
        Text("\(IntegrationCatalogSummary.totalCount) CONNECTIONS")
            .font(.caption.weight(.semibold).monospacedDigit())
            .foregroundStyle(AppTheme.signal)
    }
}

private struct ProAccessScopeRow: View {
    let lane: IntegrationCatalogLane
    let isRevealed: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: lane.icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(AppTheme.signal)
                .frame(width: 34, height: 34)
                .background(AppTheme.signal.opacity(0.11))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(lane.label)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(lane.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Text("\(lane.count)")
                .font(.title3.weight(.bold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .contentTransition(.numericText())

            Circle()
                .fill(AppTheme.signal)
                .frame(width: 6, height: 6)
                .scaleEffect(isRevealed ? 1 : 0.01)
                .opacity(isRevealed ? 1 : 0)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 10)
    }
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
            .overlay(alignment: .leading) {
                Capsule()
                    .fill(AppTheme.signal)
                    .frame(width: 3)
                    .padding(.vertical, 13)
                    .padding(.leading, 1)
                    .opacity(isSelected ? 1 : 0)
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isSelected)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
        .accessibilityValue(
            "\(showTrial ? "Free trial, then " : "")\(price), \(detail)\(isSelected ? ", selected" : "")"
        )
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint("Select \(label.lowercased()) access")
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
            Text(dynamicTypeSize.isAccessibilitySize ? badge : badge.uppercased())
                .font(.caption2.weight(.bold))
                .tracking(dynamicTypeSize.isAccessibilitySize ? 0 : 0.35)
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
