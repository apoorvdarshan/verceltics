import SwiftUI

struct FirstConnectionFlow: View {
    let experience: FirstLaunchExperienceStore
    let hasAnyConnection: Bool
    let hasActiveSubscription: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if experience.shouldPresentWelcome(
                hasAnyConnection: hasAnyConnection,
                hasActiveSubscription: hasActiveSubscription
            ) {
                FirstConnectionWelcomeView {
                    withAnimation(reduceMotion ? nil : .snappy(duration: 0.5)) {
                        experience.completeWelcome()
                    }
                }
                .transition(.asymmetric(
                    insertion: .opacity,
                    removal: .opacity.combined(with: .move(edge: .leading))
                ))
            } else {
                LoginView()
                    .transition(.opacity.combined(with: .move(edge: .trailing)))
            }
        }
        .task {
            experience.migrateIfNeeded(
                hasAnyConnection: hasAnyConnection,
                hasActiveSubscription: hasActiveSubscription
            )
        }
    }
}

struct FirstConnectionWelcomeView: View {
    let onContinue: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                Group {
                    if dynamicTypeSize.isAccessibilitySize {
                        compactLayout
                    } else {
                        ViewThatFits(in: .horizontal) {
                            regularLayout
                                .frame(minWidth: 860)
                            compactLayout
                        }
                    }
                }
                .padding(.horizontal, 22)
                .padding(.top, 22)
                .padding(.bottom, 28)
                .frame(maxWidth: 1080)
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
        }
        .safeAreaInset(edge: .bottom) {
            welcomeActionBar
        }
    }

    private var regularLayout: some View {
        HStack(alignment: .center, spacing: 56) {
            introduction
                .frame(width: 350, alignment: .leading)

            ProviderCatalogMarquee()
                .frame(maxWidth: .infinity)
        }
    }

    private var compactLayout: some View {
        VStack(alignment: .leading, spacing: 26) {
            introduction
            ProviderCatalogMarquee()
        }
        .frame(maxWidth: 680)
    }

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 24) {
            masthead

            VStack(alignment: .leading, spacing: 12) {
                Text("Check your whole stack. Close the laptop.")
                    .font(.largeTitle.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Hosting, domains, search, analytics, speed, and uptime—from one native iPhone and iPad workspace.")
                    .font(.body)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

            privacyNote

            if dynamicTypeSize.isAccessibilitySize {
                Text("No Verceltics account required.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
    }

    private var masthead: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 12) {
                    brandLockup
                    openSourceLabel
                }
            } else {
                HStack(spacing: 11) {
                    brandLockup
                    Spacer(minLength: 10)
                    openSourceLabel
                }
            }
        }
    }

    private var brandLockup: some View {
        HStack(spacing: 11) {
            Image("AppLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
                }
                .accessibilityHidden(true)

            Text("Verceltics")
                .font(.title3.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
        }
    }

    private var openSourceLabel: some View {
        Text("OPEN SOURCE")
            .font(.caption2.weight(.bold))
            .tracking(1.1)
            .foregroundStyle(AppTheme.textTertiary)
    }

    private var privacyNote: some View {
        Label {
            Text("Credentials stay in Keychain. Requests go directly to provider APIs.")
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "lock.shield.fill")
                .foregroundStyle(AppTheme.signal)
        }
        .font(.footnote)
        .foregroundStyle(AppTheme.textSecondary)
    }

    private var welcomeActionBar: some View {
        VStack(spacing: 7) {
            continueButton
            if !dynamicTypeSize.isAccessibilitySize {
                Text("No Verceltics account required.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .background(
            AppTheme.canvas
                .shadow(color: AppTheme.shadowSoft, radius: 12, y: -4)
                .ignoresSafeArea(edges: .bottom)
        )
        .overlay(alignment: .top) {
            Rectangle()
                .fill(AppTheme.divider)
                .frame(height: 0.5)
        }
    }

    private var continueButton: some View {
        Button(action: onContinue) {
            HStack(spacing: 12) {
                Text(dynamicTypeSize.isAccessibilitySize ? "Connect services" : "Choose what to connect")
                    .font(.headline)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                Image(systemName: "arrow.right")
                    .font(.callout.weight(.bold))
                    .frame(width: 30, height: 30)
                    .background(AppTheme.canvas.opacity(0.12), in: Circle())
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 10 : 0)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 58)
            .foregroundStyle(AppTheme.canvas)
            .background(AppTheme.textPrimary)
            .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 17, style: .continuous)
                    .strokeBorder(AppTheme.strokeStrong, lineWidth: 0.5)
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityHint("Shows the providers you can connect")
    }
}

private struct ProviderCatalogMarquee: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private let totalCount = AccountProvider.allCases.count
        + RegistrarProvider.allCases.count
        + SiteIntegrationProvider.allCases.count

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Group {
                if dynamicTypeSize >= .xxLarge {
                    VStack(alignment: .leading, spacing: 5) {
                        catalogTitle
                        integrationCount
                    }
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        catalogTitle
                        Spacer(minLength: 8)
                        integrationCount
                    }
                }
            }

            ProviderMarqueeLane(
                title: "HOSTING",
                items: AccountProvider.allCases,
                duration: 54,
                direction: .left,
                standardChipWidth: 150,
                name: \AccountProvider.displayName
            ) { provider in
                ProviderMark(provider: provider, size: 21)
            }

            ProviderMarqueeLane(
                title: "DOMAINS",
                items: RegistrarProvider.allCases,
                duration: 48,
                direction: .right,
                standardChipWidth: 156,
                name: \RegistrarProvider.displayName
            ) { provider in
                RegistrarMark(provider: provider, size: 32)
            }

            ProviderMarqueeLane(
                title: "SITE SERVICES",
                items: SiteIntegrationProvider.allCases,
                duration: 52,
                direction: .left,
                standardChipWidth: 208,
                name: \SiteIntegrationProvider.displayName
            ) { provider in
                SiteProviderMark(provider: provider, size: 21)
            }
        }
    }

    private var catalogTitle: some View {
        Text("WORKS WITH YOUR STACK")
            .font(.caption2.weight(.bold))
            .tracking(1.05)
            .foregroundStyle(AppTheme.textSecondary)
    }

    private var integrationCount: some View {
        Text("\(totalCount) INTEGRATIONS")
            .font(.caption2.weight(.bold).monospacedDigit())
            .tracking(0.6)
            .foregroundStyle(AppTheme.signal)
    }
}

private enum MarqueeDirection {
    case left
    case right
}

private struct ProviderMarqueeLane<Item: Identifiable, Mark: View>: View {
    let title: String
    let items: [Item]
    let duration: TimeInterval
    let direction: MarqueeDirection
    let standardChipWidth: CGFloat
    let name: KeyPath<Item, String>
    let mark: (Item) -> Mark

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase

    private let chipSpacing: CGFloat = 10

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.caption2.weight(.bold))
                    .tracking(0.9)
                Text("\(items.count)")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .foregroundStyle(AppTheme.textSecondary)

            Group {
                if shouldAutoMove {
                    automaticLane
                        .mask(edgeFadeMask)
                } else {
                    manualLane
                }
            }
            .frame(height: laneHeight)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title.capitalized), \(items.count) integrations: \(providerNames)")
    }

    private var automaticLane: some View {
        GeometryReader { proxy in
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
                let progress = context.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: duration) / duration
                let travel = progress * cycleWidth
                let offset: CGFloat = switch direction {
                case .left: -travel
                case .right: -cycleWidth + travel
                }

                HStack(spacing: 0) {
                    ForEach(0..<3, id: \.self) { _ in
                        laneCycle
                    }
                }
                .offset(x: offset)
                .frame(width: proxy.size.width, alignment: .leading)
            }
        }
        .clipped()
    }

    private var manualLane: some View {
        ScrollView(.horizontal) {
            HStack(spacing: chipSpacing) {
                ForEach(items) { item in
                    providerChip(item)
                }
            }
            .padding(.horizontal, 1)
        }
        .scrollIndicators(.hidden)
    }

    private var laneCycle: some View {
        HStack(spacing: chipSpacing) {
            ForEach(items) { item in
                providerChip(item)
            }
        }
        .padding(.trailing, chipSpacing)
    }

    private func providerChip(_ item: Item) -> some View {
        HStack(spacing: 9) {
            mark(item)
                .frame(width: 32, height: 32)

            Text(item[keyPath: name])
                .font(.footnote.weight(.medium))
                .foregroundStyle(AppTheme.textPrimary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                .minimumScaleFactor(0.84)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .frame(width: chipWidth, height: laneHeight, alignment: .leading)
        .background(AppTheme.surface.opacity(0.82), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(AppTheme.strokeSoft, lineWidth: 0.5)
        }
    }

    private var edgeFadeMask: some View {
        LinearGradient(
            stops: [
                .init(color: .clear, location: 0),
                .init(color: .black, location: 0.035),
                .init(color: .black, location: 0.965),
                .init(color: .clear, location: 1)
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
    }

    private var shouldAutoMove: Bool {
        !reduceMotion
            && !voiceOverEnabled
            && dynamicTypeSize < .xxLarge
            && scenePhase == .active
    }

    private var chipWidth: CGFloat {
        if dynamicTypeSize.isAccessibilitySize {
            max(210, standardChipWidth + 24)
        } else if dynamicTypeSize >= .xxLarge {
            standardChipWidth + 12
        } else {
            standardChipWidth
        }
    }

    private var laneHeight: CGFloat {
        if dynamicTypeSize.isAccessibilitySize {
            90
        } else if dynamicTypeSize >= .xxLarge {
            58
        } else {
            48
        }
    }

    private var cycleWidth: CGFloat {
        CGFloat(items.count) * (chipWidth + chipSpacing)
    }

    private var providerNames: String {
        items.map { $0[keyPath: name] }.joined(separator: ", ")
    }
}
