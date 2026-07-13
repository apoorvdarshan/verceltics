import SwiftUI

extension AccountProvider {
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
        Group {
            if provider == .cloudflare {
                Image("CloudflareMark")
                    .resizable()
                    .renderingMode(monochrome ? .template : .original)
                    .scaledToFit()
            } else {
                Image(systemName: provider.systemImage)
                    .resizable()
                    .scaledToFit()
            }
        }
        .foregroundStyle(monochrome ? Color.white : provider.accentColor)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
