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
    @State private var showConnection = false

    var body: some View {
        Group {
            if let account = store.activeAccount {
                RegistrarDashboardView(account: account)
                    .id(account.id)
            } else {
                NavigationStack {
                    ZStack {
                        AppTheme.canvas.ignoresSafeArea()
                        AppEmptyState(
                            icon: "globe.americas.fill",
                            title: "No registrar account",
                            message: "Connect a registrar to track expiry, renewal, privacy, locks, and nameservers.",
                            actionTitle: "Connect registrar"
                        ) {
                            showConnection = true
                        }
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
        .sheet(isPresented: $showConnection) {
            LoginView(initialCategory: .registrars)
        }
    }
}

struct RegistrarDashboardView: View {
    let account: RegistrarAccount
    @State private var viewModel: RegistrarDashboardViewModel
    @State private var searchText = ""
    @State private var refreshSpin = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                        .foregroundStyle(AppTheme.textSecondary)
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
                        if !reduceMotion {
                            withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
                        }
                        Task { await viewModel.load(refresh: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.72))
                            .rotationEffect(.degrees(refreshSpin))
                    }
                    .disabled(viewModel.isRefreshing)
                    .accessibilityLabel(viewModel.isRefreshing ? "Refreshing domains" : "Refresh domains")
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
                    AppFeedbackBanner(
                        title: "Couldn’t refresh domains",
                        message: error,
                        actionTitle: "Try again"
                    ) {
                        Task { await viewModel.load(refresh: true) }
                    }
                }

                AppSectionHeader(title: "Domain portfolio", count: filteredDomains.count, accent: provider.accentColor)

                if filteredDomains.isEmpty {
                    AppEmptyState(
                        icon: searchText.isEmpty ? "globe" : "magnifyingglass",
                        title: searchText.isEmpty ? "No domains returned" : "No matching domains",
                        message: searchText.isEmpty
                            ? "This registrar did not return any domains for the connected account."
                            : "Nothing matches “\(searchText)”."
                    )
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    ForEach(filteredDomains) { domain in
                        NavigationLink {
                            RegistrarDomainDetailView(account: account, domain: domain)
                        } label: { domainRow(domain) }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)
            .padding(.bottom, 24)
        }
        .refreshable { await viewModel.load(refresh: true) }
    }

    private var portfolioHeader: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(spacing: 13) {
                RegistrarMark(provider: provider, size: 55)
                VStack(alignment: .leading, spacing: 4) {
                    Text(account.name)
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(provider.apiDescription)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                }
                Spacer()
                AppStatusBadge(text: "Connected", tone: .success)
            }

            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text("EXPIRY HEALTH")
                        .font(.caption2.weight(.semibold))
                        .tracking(1)
                        .foregroundStyle(AppTheme.textSecondary)
                    Spacer()
                    Text(expiringDomains.isEmpty ? "Clear" : "\(expiringDomains.count) need attention")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(expiringDomains.isEmpty ? AppTheme.success : AppTheme.warning)
                }
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.07))
                        Capsule()
                            .fill(expiringDomains.isEmpty ? AppTheme.success : AppTheme.warning)
                            .frame(width: geometry.size.width * healthFraction)
                    }
                }
                .frame(height: 5)
            }
        }
        .padding(18)
        .providerSurface(accent: provider.accentColor)
    }

    private var stats: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 10) {
            statCard("Domains", value: viewModel.domains.count.formatted(), icon: "globe")
            statCard("Attention", value: expiringDomains.count.formatted(), icon: "calendar.badge.exclamationmark")
            statCard("Auto renew", value: viewModel.domains.filter { $0.autoRenew == true }.count.formatted(), icon: "arrow.triangle.2.circlepath")
        }
    }

    private func statCard(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).font(.caption.weight(.semibold)).foregroundStyle(provider.accentColor)
            Text(value).font(.title3.weight(.semibold).monospacedDigit())
            Text(title.uppercased()).font(.caption2.weight(.semibold)).tracking(0.6).foregroundStyle(AppTheme.textSecondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(13)
        .appSurface()
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
            .appSurface(raised: true)
    }

    private func domainRow(_ domain: RegistrarDomain) -> some View {
        HStack(spacing: 13) {
            VStack(spacing: 1) {
                Text(expiryValue(domain))
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                Text(expiryUnit(domain)).font(.caption2.weight(.semibold)).tracking(0.5)
            }
            .foregroundStyle(expiryColor(domain))
            .frame(width: 42, height: 42)
            .background(expiryColor(domain).opacity(0.11))
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(domain.name).font(.subheadline.weight(.semibold)).foregroundStyle(AppTheme.textPrimary).lineLimit(2)
                HStack(spacing: 7) {
                    if domain.autoRenew == true { Label("Auto", systemImage: "arrow.triangle.2.circlepath") }
                    if domain.locked == true { Label("Locked", systemImage: "lock.fill") }
                    if let date = domain.expiresAt { Text("Expires \(date.formatted(date: .abbreviated, time: .omitted))") }
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(AppTheme.textTertiary)
        }
        .padding(14)
        .appSurface()
    }

    private var healthFraction: CGFloat {
        guard !viewModel.domains.isEmpty else { return 0 }
        return max(0, CGFloat(viewModel.domains.count - expiringDomains.count) / CGFloat(viewModel.domains.count))
    }

    private func expiryColor(_ domain: RegistrarDomain) -> Color {
        guard let days = domain.daysUntilExpiry else { return provider.accentColor }
        if days < 0 { return AppTheme.danger }
        if days <= 30 { return AppTheme.warning }
        return AppTheme.success
    }

    private func expiryValue(_ domain: RegistrarDomain) -> String {
        guard let days = domain.daysUntilExpiry else { return "—" }
        return abs(days).formatted()
    }

    private func expiryUnit(_ domain: RegistrarDomain) -> String {
        guard let days = domain.daysUntilExpiry else { return "UNKNOWN" }
        return days < 0 ? "EXPIRED" : "DAYS"
    }

    private func errorView(_ message: String) -> some View {
        AppEmptyState(
            icon: "exclamationmark.triangle.fill",
            title: "Could not load domains",
            message: message,
            actionTitle: "Try again"
        ) {
            Task { await viewModel.load(refresh: true) }
        }
    }
}
