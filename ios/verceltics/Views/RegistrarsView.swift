import SwiftUI

@Observable
@MainActor
final class RegistrarDashboardViewModel {
    private static var cachedDomains: [String: [RegistrarDomain]] = [:]

    let account: RegistrarAccount
    let api: RegistrarAPI
    var domains: [RegistrarDomain] = []
    var isLoading = true
    var isRefreshing = false
    var error: String?
    private var hasLoaded = false

    init(account: RegistrarAccount) {
        self.account = account
        api = RegistrarAPI(account: account)
        if let cached = Self.cachedDomains[account.id.uuidString] {
            domains = cached
            isLoading = false
            hasLoaded = true
        }
    }

    func load(refresh: Bool = false) async {
        if hasLoaded && !refresh { return }
        if refresh { isRefreshing = true } else { isLoading = true }
        error = nil
        do {
            domains = try await api.fetchDomains().sorted {
                $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
            hasLoaded = true
            Self.cachedDomains[account.id.uuidString] = domains
        } catch is CancellationError {
            // Switching tabs can cancel a request; keep any cached content.
        } catch { self.error = error.localizedDescription }
        isLoading = false
        isRefreshing = false
    }
}

struct RegistrarsView: View {
    @Environment(RegistrarStore.self) private var store

    var body: some View {
        Group {
            if let account = store.activeAccount {
                RegistrarDashboardView(account: account)
                    .id(account.id)
            } else {
                NavigationStack {
                    ZStack {
                        AppTheme.canvas.ignoresSafeArea()
                        VStack(spacing: 18) {
                            Image(systemName: "globe.americas.fill")
                                .font(.system(size: 44, weight: .bold))
                                .foregroundStyle(AppTheme.signal)
                            VStack(spacing: 7) {
                                Text("No registrar account")
                                    .font(.system(size: 22, weight: .semibold))
                                Text("Use the account menu to connect a registrar. Domain expiry, renewal, privacy, locks, and nameservers will appear here.")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.42))
                                    .multilineTextAlignment(.center)
                            }
                        }
                        .padding(34)
                    }
                    .navigationTitle("Registrars")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbarColorScheme(.dark, for: .navigationBar)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            RegistrarAccountMenu()
                        }
                    }
                }
            }
        }
    }
}

struct RegistrarDashboardView: View {
    let account: RegistrarAccount
    @State private var viewModel: RegistrarDashboardViewModel
    @State private var searchText = ""
    @State private var refreshSpin = 0.0

    init(account: RegistrarAccount) {
        self.account = account
        _viewModel = State(initialValue: RegistrarDashboardViewModel(account: account))
    }

    private var provider: RegistrarProvider { account.provider }
    private var filteredDomains: [RegistrarDomain] {
        guard !searchText.isEmpty else { return viewModel.domains }
        return viewModel.domains.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) || ($0.status?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }
    private var expiringDomains: [RegistrarDomain] {
        viewModel.domains.filter { guard let days = $0.daysUntilExpiry else { return false }; return days <= 30 }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                if viewModel.isLoading {
                    ProgressView("Loading domains")
                        .tint(provider.accentColor)
                        .foregroundStyle(.white.opacity(0.55))
                } else if let error = viewModel.error, viewModel.domains.isEmpty {
                    errorView(error)
                } else {
                    dashboard
                }
            }
            .navigationTitle("Registrars")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .searchable(text: $searchText, prompt: "Search domains")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { RegistrarAccountMenu() }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        withAnimation(.easeInOut(duration: 0.55)) { refreshSpin += 360 }
                        Task { await viewModel.load(refresh: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.72))
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(viewModel.isRefreshing)
                }
            }
            .task(id: account.id) { await viewModel.load() }
        }
    }

    private var dashboard: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                portfolioHeader
                stats
                actions

                if let error = viewModel.error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(15)
                        .providerPanel(accent: .orange)
                }

                HStack {
                    Text("DOMAIN PORTFOLIO")
                        .font(.system(size: 10, weight: .semibold)).tracking(1.4).foregroundStyle(.white.opacity(0.38))
                    Spacer()
                    Text(filteredDomains.count.formatted())
                        .font(.system(size: 10, weight: .semibold).monospacedDigit()).foregroundStyle(provider.accentColor)
                }

                if filteredDomains.isEmpty {
                    Text(searchText.isEmpty ? "The API returned no domains for this account." : "No domains match this search.")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.4))
                        .padding(34)
                        .frame(maxWidth: .infinity)
                        .providerPanel(accent: provider.accentColor)
                } else {
                    ForEach(filteredDomains) { domain in
                        NavigationLink {
                            RegistrarDomainDetailView(account: account, domain: domain)
                        } label: { domainRow(domain) }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                }
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)
        }
        .refreshable { await viewModel.load(refresh: true) }
    }

    private var portfolioHeader: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(spacing: 13) {
                RegistrarMark(provider: provider, size: 55)
                VStack(alignment: .leading, spacing: 4) {
                    Text(account.name).font(.system(size: 18, weight: .semibold)).lineLimit(1)
                    Text(provider.apiDescription).font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.4)).lineLimit(1)
                }
                Spacer()
                Text("CONNECTED")
                    .font(.system(size: 8, weight: .semibold)).foregroundStyle(.green)
            }

            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text("EXPIRY HEALTH").font(.system(size: 8, weight: .semibold)).tracking(1.1).foregroundStyle(.white.opacity(0.34))
                    Spacer()
                    Text(expiringDomains.isEmpty ? "Clear" : "\(expiringDomains.count) due soon")
                        .font(.system(size: 9, weight: .semibold)).foregroundStyle(expiringDomains.isEmpty ? .green : .orange)
                }
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.07))
                        Capsule()
                            .fill(LinearGradient(colors: [.green, expiringDomains.isEmpty ? .green : .orange], startPoint: .leading, endPoint: .trailing))
                            .frame(width: geometry.size.width * healthFraction)
                    }
                }
                .frame(height: 5)
            }
        }
        .padding(18)
        .providerPanel(accent: provider.accentColor)
    }

    private var stats: some View {
        HStack(spacing: 10) {
            statCard("Domains", value: viewModel.domains.count.formatted(), icon: "globe")
            statCard("≤ 30 days", value: expiringDomains.count.formatted(), icon: "calendar.badge.exclamationmark")
            statCard("Auto renew", value: viewModel.domains.filter { $0.autoRenew == true }.count.formatted(), icon: "arrow.triangle.2.circlepath")
        }
    }

    private func statCard(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).font(.system(size: 11, weight: .semibold)).foregroundStyle(provider.accentColor)
            Text(value).font(.system(size: 20, weight: .semibold).monospacedDigit())
            Text(title.uppercased()).font(.system(size: 7, weight: .semibold)).tracking(0.8).foregroundStyle(.white.opacity(0.34)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(13)
        .providerPanel(accent: provider.accentColor)
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                if let url = provider.dashboardURL { UIApplication.shared.open(url) }
            } label: { actionLabel("Dashboard", icon: "safari.fill") }
            NavigationLink { ProviderFullAPICatalogView(account: account) } label: { actionLabel("Complete API", icon: "list.bullet.rectangle.fill") }
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func actionLabel(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity).frame(height: 47)
            .providerPanel(accent: provider.accentColor)
    }

    private func domainRow(_ domain: RegistrarDomain) -> some View {
        HStack(spacing: 13) {
            VStack(spacing: 1) {
                Text(domain.daysUntilExpiry.map { max($0, 0).formatted() } ?? "—")
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                Text("DAYS").font(.system(size: 6, weight: .semibold)).tracking(0.7)
            }
            .foregroundStyle(expiryColor(domain))
            .frame(width: 42, height: 42)
            .background(expiryColor(domain).opacity(0.11))
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(domain.name).font(.system(size: 14, weight: .bold)).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
                HStack(spacing: 7) {
                    if domain.autoRenew == true { Label("Auto", systemImage: "arrow.triangle.2.circlepath") }
                    if domain.locked == true { Label("Locked", systemImage: "lock.fill") }
                    if let date = domain.expiresAt { Text("Expires \(date.formatted(date: .abbreviated, time: .omitted))") }
                }
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(.white.opacity(0.34))
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.2))
        }
        .padding(14)
        .providerPanel(accent: provider.accentColor)
    }

    private var healthFraction: CGFloat {
        guard !viewModel.domains.isEmpty else { return 0 }
        return max(0.08, CGFloat(viewModel.domains.count - expiringDomains.count) / CGFloat(viewModel.domains.count))
    }

    private func expiryColor(_ domain: RegistrarDomain) -> Color {
        guard let days = domain.daysUntilExpiry else { return provider.accentColor }
        if days < 0 { return .red }
        if days <= 30 { return .orange }
        return .green
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 28)).foregroundStyle(provider.accentColor)
            Text("Could not load domains").font(.system(size: 17, weight: .semibold))
            Text(message).font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.45)).multilineTextAlignment(.center)
            Button("Try Again") { Task { await viewModel.load() } }.font(.system(size: 13, weight: .bold)).foregroundStyle(provider.accentColor)
        }
        .padding(28)
    }
}
