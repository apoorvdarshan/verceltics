import SwiftUI

struct RegistrarDomainDetailView: View {
    let account: RegistrarAccount
    let domain: RegistrarDomain

    private var provider: RegistrarProvider { account.provider }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    hero
                    properties
                    nameservers
                    NavigationLink {
                        ProviderFullAPICatalogView(account: account)
                    } label: {
                        HStack(spacing: 12) {
                            AppIconTile(icon: "terminal.fill", tint: provider.accentColor, size: 38)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Complete registrar API")
                                    .font(.subheadline.weight(.semibold))
                                Text("Search every indexed read and write operation, then inspect the full raw response")
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.textSecondary)
                                    .lineLimit(2)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                        .foregroundStyle(AppTheme.textPrimary)
                        .padding(16)
                        .appSurface()
                    }
                    .buttonStyle(PressScaleButtonStyle())
                }
                .padding(16)
                .padding(.bottom, 72)
            }
        }
        .navigationTitle(domain.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(spacing: 13) {
                RegistrarMark(provider: provider, size: 54)
                VStack(alignment: .leading, spacing: 4) {
                    Text(domain.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(2)
                    AppStatusBadge(
                        text: domain.status?.uppercased() ?? provider.displayName.uppercased(),
                        tone: AppStatusTone.status(domain.status ?? "")
                    )
                }
                Spacer()
            }

            HStack(alignment: .lastTextBaseline) {
                Text(expiryValue)
                    .font(.largeTitle.weight(.semibold).monospacedDigit())
                    .foregroundStyle(expiryTone.color)
                Text(expiryLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textSecondary)
                Spacer()
                if let date = domain.expiresAt {
                    Text(date.formatted(date: .abbreviated, time: .omitted))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }

            HStack(spacing: 10) {
                if let url = URL(string: "https://\(domain.name)") {
                    Button { UIApplication.shared.open(url) } label: { action("Open domain", icon: "arrow.up.right") }
                }
                if let url = provider.dashboardURL {
                    Button { UIApplication.shared.open(url) } label: { action("Registrar", icon: "safari.fill") }
                }
            }
            .buttonStyle(PressScaleButtonStyle())
        }
        .padding(18)
        .providerSurface(accent: provider.accentColor)
    }

    private var properties: some View {
        VStack(spacing: 0) {
            property("Auto renewal", value: booleanText(domain.autoRenew), icon: "arrow.triangle.2.circlepath")
            Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 48)
            property("Transfer lock", value: booleanText(domain.locked), icon: "lock.fill")
            Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 48)
            property("WHOIS privacy", value: booleanText(domain.privacyEnabled), icon: "eye.slash.fill")
            if let date = domain.createdAt {
                Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 48)
                property("Registered", value: date.formatted(date: .abbreviated, time: .omitted), icon: "calendar.badge.checkmark")
            }
        }
        .appSurface()
    }

    private var nameservers: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Nameservers", systemImage: "server.rack")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(provider.accentColor)
            if domain.nameservers.isEmpty {
                Text("The list endpoint did not include nameservers. Open the API explorer for the domain detail or DNS route.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            } else {
                ForEach(domain.nameservers, id: \.self) { value in
                    Text(value)
                        .font(.caption.monospaced())
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .appSurface()
    }

    private func property(_ title: String, value: String, icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(provider.accentColor)
                .frame(width: 22)
            Text(title)
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)
            Spacer()
            Text(value)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
        }
        .padding(.horizontal, 15).padding(.vertical, 13)
    }

    private func action(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppTheme.textPrimary)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 44)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
    }

    private var expiryValue: String {
        guard let days = domain.daysUntilExpiry else { return "—" }
        return abs(days).formatted()
    }

    private var expiryLabel: String {
        guard let days = domain.daysUntilExpiry else { return "expiry unavailable" }
        return days < 0 ? "days expired" : "days left"
    }

    private var expiryTone: AppStatusTone {
        guard let days = domain.daysUntilExpiry else { return .neutral }
        if days < 0 { return .danger }
        if days <= 30 { return .warning }
        return .success
    }

    private func booleanText(_ value: Bool?) -> String {
        switch value { case true: "On"; case false: "Off"; case nil: "Not returned" }
    }
}
