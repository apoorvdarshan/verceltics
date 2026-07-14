import SwiftUI

/// The app's visual language: quiet infrastructure surfaces with provider color
/// reserved for identity and state, rather than decorative gradients.
enum AppTheme {
    static let canvas = Color(red: 0.018, green: 0.022, blue: 0.030)
    static let surface = Color(red: 0.060, green: 0.070, blue: 0.090)
    static let surfaceRaised = Color(red: 0.082, green: 0.092, blue: 0.115)
    static let textPrimary = Color(red: 0.94, green: 0.95, blue: 0.97)
    static let textSecondary = Color(red: 0.64, green: 0.67, blue: 0.73)
    static let textTertiary = Color(red: 0.44, green: 0.47, blue: 0.53)
    static let stroke = Color.white.opacity(0.10)
    static let strokeStrong = Color.white.opacity(0.14)
    static let signal = Color(red: 0.31, green: 0.63, blue: 1.0)
    static let success = Color(red: 0.30, green: 0.79, blue: 0.52)
    static let warning = Color(red: 0.96, green: 0.65, blue: 0.24)
    static let danger = Color(red: 0.96, green: 0.35, blue: 0.38)

    static let panelRadius: CGFloat = 14
    static let controlRadius: CGFloat = 12
    static let iconRadius: CGFloat = 9
}

struct AppSurfaceModifier: ViewModifier {
    var cornerRadius: CGFloat = AppTheme.panelRadius
    var raised = false

    func body(content: Content) -> some View {
        content
            .background(raised ? AppTheme.surfaceRaised : AppTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
            }
    }
}

struct ProviderSurfaceModifier: ViewModifier {
    let accent: Color
    var cornerRadius: CGFloat = AppTheme.panelRadius

    func body(content: Content) -> some View {
        content
            .background(AppTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
            }
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .fill(accent.opacity(0.72))
                    .frame(width: 2, height: 24)
                    .padding(.leading, 1)
            }
    }
}

struct NativeGlassSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .glassEffect(
                    .regular.tint(Color.black.opacity(0.58)).interactive(),
                    in: .rect(cornerRadius: cornerRadius)
                )
        } else {
            content
                .background(.ultraThinMaterial)
                .background(AppTheme.surface.opacity(0.82))
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(AppTheme.strokeStrong, lineWidth: 0.5)
                }
        }
    }
}

extension View {
    func appSurface(cornerRadius: CGFloat = AppTheme.panelRadius, raised: Bool = false) -> some View {
        modifier(AppSurfaceModifier(cornerRadius: cornerRadius, raised: raised))
    }

    func providerSurface(accent: Color, cornerRadius: CGFloat = AppTheme.panelRadius) -> some View {
        modifier(ProviderSurfaceModifier(accent: accent, cornerRadius: cornerRadius))
    }

    func nativeGlassSurface(cornerRadius: CGFloat) -> some View {
        modifier(NativeGlassSurfaceModifier(cornerRadius: cornerRadius))
    }
}

enum AppStatusTone {
    case success
    case warning
    case danger
    case progress
    case neutral

    var color: Color {
        switch self {
        case .success: AppTheme.success
        case .warning: AppTheme.warning
        case .danger: AppTheme.danger
        case .progress: AppTheme.signal
        case .neutral: AppTheme.textSecondary
        }
    }

    static func status(_ value: String) -> AppStatusTone {
        let value = value.lowercased()
        if value.contains("inactive") || value.contains("deactiv") || value.contains("expired")
            || value.contains("disabled") || value.contains("deleted") || value.contains("blocked")
            || value.contains("fail") || value.contains("error") || value.contains("cancel")
            || value.contains("suspend") || value.contains("fatal") || value.contains("stopped")
            || value.contains("offline") {
            return .danger
        }
        if value.contains("build") || value.contains("progress") || value.contains("initial") {
            return .progress
        }
        if value.contains("pending") || value.contains("queued") || value.contains("starting")
            || value.contains("warning") || value.contains("paused") || value.contains("not ready")
            || value.contains("incomplete") {
            return .warning
        }
        if value.contains("active") || value.contains("ready") || value.contains("success")
            || value.contains("live") || value.contains("running") || value.contains("published")
            || value.contains("succeed") || value.contains("complete") {
            return .success
        }
        return .neutral
    }
}

struct AppStatusBadge: View {
    let text: String
    var tone: AppStatusTone = .neutral

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tone.color)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(tone.color.opacity(0.12), in: Capsule())
            .overlay(Capsule().strokeBorder(tone.color.opacity(0.22), lineWidth: 0.5))
            .accessibilityLabel("Status: \(text)")
    }
}

struct AppIconTile: View {
    let icon: String
    var tint: Color = AppTheme.signal
    var size: CGFloat = 36

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: size * 0.38, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous))
            .accessibilityHidden(true)
    }
}

struct AppSectionHeader: View {
    let title: String
    var count: Int?
    var accent: Color = AppTheme.textSecondary

    var body: some View {
        HStack(spacing: 8) {
            Text(title.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(1.1)
                .foregroundStyle(AppTheme.textSecondary)
            Spacer(minLength: 8)
            if let count {
                Text(count.formatted())
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(accent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(AppTheme.surfaceRaised, in: Capsule())
            }
        }
    }
}

struct AppFeedbackBanner: View {
    let title: String
    let message: String
    var icon = "exclamationmark.triangle.fill"
    var tint = AppTheme.warning
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconTile(icon: icon, tint: tint, size: 34)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let actionTitle, let action {
                    Button(actionTitle, action: action)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(tint)
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                        .buttonStyle(.plain)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(15)
        .appSurface()
    }
}

struct AppEmptyState: View {
    let icon: String
    let title: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            AppIconTile(icon: icon, size: 46)
            VStack(spacing: 6) {
                Text(title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(AppTheme.signal, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    .buttonStyle(PressScaleButtonStyle())
            }
        }
        .frame(maxWidth: 380)
        .padding(28)
    }
}

extension AccountProvider {
    var logoAssetName: String {
        switch self {
        case .vercel: "VercelMark"
        case .cloudflare: "CloudflareMark"
        case .netlify: "NetlifyMark"
        case .railway: "RailwayMark"
        case .render: "RenderMark"
        case .digitalOcean: "DigitalOceanMark"
        case .heroku: "HerokuMark"
        case .fly: "FlyMark"
        case .firebase: "FirebaseMark"
        case .awsAmplify: "AWSAmplifyMark"
        }
    }

    var logoNeedsTint: Bool {
        switch self {
        case .vercel, .railway, .render, .heroku, .fly: true
        case .cloudflare, .netlify, .digitalOcean, .firebase, .awsAmplify: false
        }
    }

    var systemImage: String {
        switch self {
        case .vercel: "triangle.fill"
        case .cloudflare: "cloud.fill"
        case .netlify: "bolt.horizontal.fill"
        case .railway: "tram.fill"
        case .render: "square.3.layers.3d"
        case .digitalOcean: "drop.fill"
        case .heroku: "h.square.fill"
        case .fly: "airplane"
        case .firebase: "flame.fill"
        case .awsAmplify: "cloud.fill"
        }
    }

    var accentColor: Color {
        switch self {
        case .vercel: .white
        case .cloudflare: Color(red: 0.95, green: 0.42, blue: 0.08)
        case .netlify: Color(red: 0.18, green: 0.82, blue: 0.78)
        case .railway: Color(red: 0.67, green: 0.48, blue: 0.98)
        case .render: Color(red: 0.38, green: 0.47, blue: 1.0)
        case .digitalOcean: Color(red: 0.0, green: 0.46, blue: 0.95)
        case .heroku: Color(red: 0.55, green: 0.34, blue: 0.84)
        case .fly: Color(red: 0.53, green: 0.64, blue: 1.0)
        case .firebase: Color(red: 1.0, green: 0.68, blue: 0.12)
        case .awsAmplify: Color(red: 1.0, green: 0.60, blue: 0.12)
        }
    }

    var connectionSubtitle: String {
        switch self {
        case .vercel: "Projects, deployments and Web Analytics"
        case .cloudflare: "Zones, Pages, Workers, DNS and analytics"
        case .netlify: "Sites, deploys, domains and build controls"
        case .railway: "Projects, services, environments and logs"
        case .render: "Services, deploys, jobs and environments"
        case .digitalOcean: "Apps, deployments, logs and bandwidth"
        case .heroku: "Apps, releases, dynos, domains and logs"
        case .fly: "Apps, Machines, regions and volumes"
        case .firebase: "Hosting sites, channels, versions and releases"
        case .awsAmplify: "Apps, branches, jobs and domains"
        }
    }

    var credentialPageURL: URL? {
        let value: String
        switch self {
        case .vercel: value = "https://vercel.com/account/tokens"
        case .cloudflare: value = "https://dash.cloudflare.com/profile/api-tokens"
        case .netlify: value = "https://app.netlify.com/user/applications#personal-access-tokens"
        case .railway: value = "https://railway.com/account/tokens"
        case .render: value = "https://dashboard.render.com/u/settings#api-keys"
        case .digitalOcean: value = "https://cloud.digitalocean.com/account/api/tokens"
        case .heroku: value = "https://dashboard.heroku.com/account/applications"
        case .fly: value = "https://fly.io/user/personal_access_tokens"
        case .firebase: value = "https://developers.google.com/oauthplayground/"
        case .awsAmplify: value = "https://console.aws.amazon.com/iam/home#/security_credentials"
        }
        return URL(string: value)
    }

    var primaryActionLabel: String? {
        switch self {
        case .netlify, .render, .digitalOcean, .railway: "Redeploy"
        case .heroku, .fly: "Restart"
        case .awsAmplify: "Start release"
        case .vercel, .cloudflare, .firebase: nil
        }
    }
}

struct ProviderMark: View {
    let provider: AccountProvider
    var size: CGFloat = 22
    var monochrome = false

    var body: some View {
        Image(provider.logoAssetName)
            .resizable()
            .renderingMode(monochrome || provider.logoNeedsTint ? .template : .original)
            .scaledToFit()
            .foregroundStyle(monochrome ? Color.white : provider.accentColor)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
