import SwiftUI

/// The app's visual language: quiet infrastructure surfaces with provider color
/// reserved for identity and state, rather than decorative gradients.
enum AppTheme {
    static let canvas = Color(red: 0.018, green: 0.022, blue: 0.030)
    static let surface = Color(red: 0.060, green: 0.070, blue: 0.090)
    static let surfaceRaised = Color(red: 0.082, green: 0.092, blue: 0.115)
    static let textPrimary = Color(red: 0.94, green: 0.95, blue: 0.97)
    static let textSecondary = Color(red: 0.57, green: 0.60, blue: 0.66)
    static let textTertiary = Color(red: 0.36, green: 0.39, blue: 0.45)
    static let stroke = Color.white.opacity(0.085)
    static let strokeStrong = Color.white.opacity(0.14)
    static let signal = Color(red: 0.31, green: 0.63, blue: 1.0)
    static let success = Color(red: 0.30, green: 0.79, blue: 0.52)
    static let warning = Color(red: 0.96, green: 0.65, blue: 0.24)
    static let danger = Color(red: 0.96, green: 0.35, blue: 0.38)
}

struct AppSurfaceModifier: ViewModifier {
    var cornerRadius: CGFloat = 16
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
    var cornerRadius: CGFloat = 16

    func body(content: Content) -> some View {
        content
            .background(AppTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
            }
            .overlay(alignment: .leading) {
                Capsule()
                    .fill(accent.opacity(0.72))
                    .frame(width: 2, height: 30)
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
    func appSurface(cornerRadius: CGFloat = 16, raised: Bool = false) -> some View {
        modifier(AppSurfaceModifier(cornerRadius: cornerRadius, raised: raised))
    }

    func providerSurface(accent: Color, cornerRadius: CGFloat = 16) -> some View {
        modifier(ProviderSurfaceModifier(accent: accent, cornerRadius: cornerRadius))
    }

    func nativeGlassSurface(cornerRadius: CGFloat) -> some View {
        modifier(NativeGlassSurfaceModifier(cornerRadius: cornerRadius))
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
