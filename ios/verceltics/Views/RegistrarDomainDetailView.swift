import SwiftUI

struct RegistrarDomainDetailView: View {
    let account: RegistrarAccount
    let domain: RegistrarDomain

    private var provider: RegistrarProvider { account.provider }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    hero
                    properties
                    nameservers
                    NavigationLink {
                        RegistrarAPIExplorerView(account: account, domain: domain)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "terminal.fill").foregroundStyle(provider.accentColor)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Full registrar API").font(.system(size: 13, weight: .bold))
                                Text("DNS, renewal, contacts, privacy, transfers and every available route")
                                    .font(.system(size: 9, weight: .semibold)).foregroundStyle(.white.opacity(0.38)).lineLimit(2)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.white.opacity(0.2))
                        }
                        .foregroundStyle(.white)
                        .padding(16)
                        .providerPanel(accent: provider.accentColor)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    Spacer().frame(height: 80)
                }
                .padding(16)
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
                    Text(domain.name).font(.system(size: 20, weight: .heavy)).lineLimit(2)
                    Text(domain.status?.uppercased() ?? provider.displayName.uppercased())
                        .font(.system(size: 8, weight: .heavy)).tracking(0.9).foregroundStyle(provider.accentColor)
                }
                Spacer()
            }

            HStack(alignment: .lastTextBaseline) {
                Text(domain.daysUntilExpiry.map { max(0, $0).formatted() } ?? "—")
                    .font(.system(size: 42, weight: .black).monospacedDigit())
                Text("days left").font(.system(size: 11, weight: .heavy)).foregroundStyle(.white.opacity(0.38))
                Spacer()
                if let date = domain.expiresAt {
                    Text(date.formatted(date: .abbreviated, time: .omitted))
                        .font(.system(size: 11, weight: .bold)).foregroundStyle(.white.opacity(0.6))
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
        .providerPanel(accent: provider.accentColor)
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
        .providerPanel(accent: provider.accentColor)
    }

    private var nameservers: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Nameservers", systemImage: "server.rack")
                .font(.system(size: 12, weight: .heavy)).foregroundStyle(provider.accentColor)
            if domain.nameservers.isEmpty {
                Text("The list endpoint did not include nameservers. Open the API explorer for the domain detail or DNS route.")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.38))
            } else {
                ForEach(domain.nameservers, id: \.self) { value in
                    Text(value).font(.system(size: 11, weight: .semibold, design: .monospaced)).foregroundStyle(.white.opacity(0.65))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .providerPanel(accent: provider.accentColor)
    }

    private func property(_ title: String, value: String, icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 11, weight: .heavy)).foregroundStyle(provider.accentColor).frame(width: 22)
            Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white.opacity(0.58))
            Spacer()
            Text(value).font(.system(size: 11, weight: .heavy)).foregroundStyle(.white.opacity(0.82))
        }
        .padding(.horizontal, 15).padding(.vertical, 13)
    }

    private func action(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.system(size: 10, weight: .bold))
            .frame(maxWidth: .infinity).frame(height: 42)
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func booleanText(_ value: Bool?) -> String {
        switch value { case true: "On"; case false: "Off"; case nil: "Not returned" }
    }
}
