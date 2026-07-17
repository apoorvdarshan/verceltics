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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var contentIsVisible = false

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
                .padding(.horizontal, 24)
                .padding(.top, 28)
                .padding(.bottom, 32)
                .frame(maxWidth: 1040)
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
        }
        .safeAreaInset(edge: .bottom) {
            welcomeActionBar
        }
        .task(id: reduceMotion) {
            if reduceMotion {
                contentIsVisible = true
            } else {
                contentIsVisible = false
                try? await Task.sleep(for: .milliseconds(320))
                guard !Task.isCancelled else { return }
                withAnimation(.easeOut(duration: 0.55)) {
                    contentIsVisible = true
                }
            }
        }
    }

    private var regularLayout: some View {
        HStack(alignment: .center, spacing: 64) {
            brandMoment(size: 300)
                .frame(maxWidth: .infinity)

            narrative
                .frame(width: 430)
        }
    }

    private var compactLayout: some View {
        VStack(spacing: 30) {
            brandMoment(size: dynamicTypeSize.isAccessibilitySize ? 156 : 196)
            narrative
                .frame(maxWidth: 560)
        }
    }

    private func brandMoment(size: CGFloat) -> some View {
        VStack(spacing: 16) {
            RouteActivationLogo(size: size)

            HStack(spacing: 8) {
                Circle()
                    .fill(AppTheme.success)
                    .frame(width: 7, height: 7)
                Text("STACK READY")
                    .font(.caption2.weight(.bold))
                    .tracking(1.2)
                    .foregroundStyle(AppTheme.textSecondary)
            }
            .opacity(contentIsVisible ? 1 : 0)
        }
        .accessibilityHidden(true)
    }

    private var narrative: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 12) {
                Text("VERCELTICS")
                    .font(.caption2.weight(.bold))
                    .tracking(1.6)
                    .foregroundStyle(AppTheme.signal)

                Text("Your whole web stack, in your pocket.")
                    .font(.largeTitle.weight(.bold))
                    .fontDesign(.rounded)
                    .foregroundStyle(AppTheme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Connect hosting, domains, analytics, search, speed, and uptime in one private workspace.")
                    .font(.body)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

            WelcomeIntegrationSummary()
            trustProof
        }
        .opacity(contentIsVisible ? 1 : 0)
        .offset(y: contentIsVisible ? 0 : 8)
    }

    private var trustProof: some View {
        VStack(spacing: 0) {
            WelcomeTrustRow(
                icon: "lock.shield.fill",
                title: "Private connections",
                detail: "Credentials stay on this device"
            )
            AppInsetDivider(leading: 52)
            WelcomeTrustRow(
                icon: "chevron.left.forwardslash.chevron.right",
                title: "Open source",
                detail: "MIT-licensed and built in the open"
            )
        }
        .appSurface()
    }

    private var welcomeActionBar: some View {
        VStack(spacing: 7) {
            continueButton
            Text("No Verceltics account required.")
                .font(.caption)
                .foregroundStyle(AppTheme.textTertiary)
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
        .opacity(contentIsVisible ? 1 : 0)
    }

    private var continueButton: some View {
        Button(action: onContinue) {
            HStack(spacing: 12) {
                Text("Connect your stack")
                    .font(.headline)
                Spacer()
                Image(systemName: "arrow.right")
                    .font(.callout.weight(.bold))
                    .frame(width: 30, height: 30)
                    .background(AppTheme.canvas.opacity(0.12), in: Circle())
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 18)
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

private struct RouteActivationLogo: View {
    private enum Lane {
        case top
        case middle
        case bottom
    }

    let size: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tileIsVisible = false
    @State private var topLaneIsVisible = false
    @State private var middleLaneIsVisible = false
    @State private var bottomLaneIsVisible = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.23, style: .continuous)
                .fill(.black)

            Image("AppLogo")
                .resizable()
                .scaledToFit()
                .mask {
                    VStack(spacing: 0) {
                        laneMask(isVisible: topLaneIsVisible)
                        laneMask(isVisible: middleLaneIsVisible)
                        laneMask(isVisible: bottomLaneIsVisible)
                    }
                }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.23, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: size * 0.23, style: .continuous)
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
        }
        .shadow(color: AppTheme.shadow, radius: 28, y: 16)
        .scaleEffect(tileIsVisible ? 1 : 0.94)
        .opacity(tileIsVisible ? 1 : 0)
        .task(id: reduceMotion) {
            resetAnimation()
            guard !reduceMotion else {
                showFinalState()
                return
            }

            withAnimation(.easeOut(duration: 0.42)) {
                tileIsVisible = true
            }
            await revealLane(.top, delay: 100)
            await revealLane(.middle, delay: 105)
            await revealLane(.bottom, delay: 105)
        }
    }

    private func laneMask(isVisible: Bool) -> some View {
        Rectangle()
            .scaleEffect(x: isVisible ? 1 : 0, anchor: .leading)
    }

    @MainActor
    private func revealLane(
        _ lane: Lane,
        delay: Int64
    ) async {
        try? await Task.sleep(for: .milliseconds(delay))
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.38)) {
            switch lane {
            case .top: topLaneIsVisible = true
            case .middle: middleLaneIsVisible = true
            case .bottom: bottomLaneIsVisible = true
            }
        }
    }

    private func resetAnimation() {
        tileIsVisible = false
        topLaneIsVisible = false
        middleLaneIsVisible = false
        bottomLaneIsVisible = false
    }

    private func showFinalState() {
        tileIsVisible = true
        topLaneIsVisible = true
        middleLaneIsVisible = true
        bottomLaneIsVisible = true
    }
}

private struct WelcomeIntegrationSummary: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private let lanes = IntegrationCatalogSummary.lanes

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 5) {
                    summaryTitle
                    summaryCount
                }
            } else {
                HStack(alignment: .firstTextBaseline) {
                    summaryTitle
                    Spacer(minLength: 12)
                    summaryCount
                }
            }

            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 12) {
                    ForEach(lanes) { lane in
                        WelcomeIntegrationLane(lane: lane, horizontal: true)
                    }
                }
            } else {
                HStack(spacing: 8) {
                    ForEach(lanes) { lane in
                        WelcomeIntegrationLane(lane: lane, horizontal: false)
                    }
                }
            }
        }
        .padding(17)
        .appSurface(raised: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(IntegrationCatalogSummary.accessibilitySummary)
    }

    private var summaryTitle: some View {
        Text("ONE CONNECTED WORKSPACE")
            .font(.caption2.weight(.bold))
            .tracking(0.9)
            .foregroundStyle(AppTheme.textSecondary)
    }

    private var summaryCount: some View {
        Text("\(IntegrationCatalogSummary.totalCount) integrations")
            .font(.caption.weight(.semibold).monospacedDigit())
            .foregroundStyle(AppTheme.signal)
    }
}

private struct WelcomeIntegrationLane: View {
    let lane: IntegrationCatalogLane
    let horizontal: Bool

    var body: some View {
        Group {
            if horizontal {
                HStack(spacing: 12) {
                    icon
                    Text("\(lane.count)")
                        .font(.title3.weight(.bold).monospacedDigit())
                    Text(lane.label)
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                }
            } else {
                VStack(spacing: 7) {
                    icon
                    Text("\(lane.count)")
                        .font(.title3.weight(.bold).monospacedDigit())
                    Text(lane.label)
                        .font(.caption2.weight(.semibold))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .foregroundStyle(AppTheme.textPrimary)
    }

    private var icon: some View {
        Image(systemName: lane.icon)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(AppTheme.signal)
            .frame(width: 32, height: 32)
            .background(AppTheme.signal.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct WelcomeTrustRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(AppTheme.signal)
                .frame(width: 34, height: 34)
                .background(AppTheme.signal.opacity(0.10))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(15)
    }
}
