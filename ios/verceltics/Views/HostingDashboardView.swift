import SwiftUI

@Observable
@MainActor
final class HostingDashboardViewModel {
    private static var cachedResources: [String: [HostingResource]] = [:]

    let account: VercelAccount
    let api: HostingProviderAPI
    var resources: [HostingResource] = []
    var isLoading = true
    var isRefreshing = false
    var error: String?
    private var hasLoaded = false

    init(account: VercelAccount) {
        self.account = account
        self.api = HostingProviderAPI(account: account)
        if let cached = Self.cachedResources[account.id.uuidString] {
            resources = cached
            isLoading = false
            hasLoaded = true
        }
    }

    func load(refresh: Bool = false) async {
        if hasLoaded && !refresh { return }
        if refresh { isRefreshing = true } else { isLoading = true }
        error = nil
        do {
            resources = try await api.fetchResources().sorted {
                $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
            hasLoaded = true
            Self.cachedResources[account.id.uuidString] = resources
        } catch is CancellationError {
            // Switching tabs can cancel a request; keep any cached content.
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
        isRefreshing = false
    }
}

struct HostingDashboardView: View {
    let account: VercelAccount
    var startWithSearch = false

    @State private var viewModel: HostingDashboardViewModel
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var refreshSpin = 0.0

    init(account: VercelAccount, startWithSearch: Bool = false) {
        self.account = account
        self.startWithSearch = startWithSearch
        _viewModel = State(initialValue: HostingDashboardViewModel(account: account))
    }

    private var provider: AccountProvider { account.provider }

    private var filteredResources: [HostingResource] {
        guard !searchText.isEmpty else { return viewModel.resources }
        return viewModel.resources.filter {
            $0.name.localizedCaseInsensitiveContains(searchText)
                || ($0.subtitle?.localizedCaseInsensitiveContains(searchText) ?? false)
                || ($0.status?.localizedCaseInsensitiveContains(searchText) ?? false)
                || ($0.kind?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                if viewModel.isLoading {
                    ProgressView("Loading \(provider.displayName)")
                        .tint(provider.accentColor)
                        .foregroundStyle(.white.opacity(0.6))
                } else if let error = viewModel.error, viewModel.resources.isEmpty {
                    errorView(error)
                } else {
                    content
                }
            }
            .navigationTitle(provider.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search \(provider.displayName)")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { ProviderAccountMenu() }
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
            .onAppear {
                if startWithSearch {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { isSearching = true }
                }
            }
        }
    }

    private var content: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                accountCard

                if let error = viewModel.error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .providerPanel(accent: .orange)
                }

                actionGrid

                HStack {
                    Text(resourceTitle.uppercased())
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(1.4)
                        .foregroundStyle(.white.opacity(0.38))
                    Spacer()
                    Text(filteredResources.count.formatted())
                        .font(.system(size: 10, weight: .semibold).monospacedDigit())
                        .foregroundStyle(provider.accentColor)
                }

                if filteredResources.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: provider.systemImage)
                            .font(.system(size: 26, weight: .bold))
                        Text(searchText.isEmpty ? "No resources returned" : "No matching resources")
                            .font(.system(size: 14, weight: .bold))
                    }
                    .foregroundStyle(.white.opacity(0.38))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 44)
                    .providerPanel(accent: provider.accentColor)
                } else {
                    ForEach(filteredResources) { resource in
                        NavigationLink {
                            HostingResourceDetailView(account: account, resource: resource)
                        } label: {
                            resourceRow(resource)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                }

                Spacer().frame(height: 96)
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)
        }
        .refreshable { await viewModel.load(refresh: true) }
    }

    private var accountCard: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(provider.accentColor.opacity(0.15))
                if let avatar = account.avatarURL, let url = URL(string: avatar) {
                    AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: {
                        ProviderMark(provider: provider, size: 28)
                    }
                } else {
                    ProviderMark(provider: provider, size: 28)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(account.name)
                    .font(.system(size: 18, weight: .semibold))
                    .lineLimit(1)
                Text(account.email ?? provider.connectionSubtitle)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.4))
                    .lineLimit(1)
            }
            Spacer()
            Text("CONNECTED")
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(.green)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(Color.green.opacity(0.12))
                .clipShape(Capsule())
        }
        .padding(18)
        .providerPanel(accent: provider.accentColor)
    }

    private var actionGrid: some View {
        HStack(spacing: 10) {
            Button {
                if let url = viewModel.api.dashboardURL() { UIApplication.shared.open(url) }
            } label: {
                dashboardAction(icon: "safari.fill", title: "Dashboard")
            }
            NavigationLink {
                ProviderFullAPICatalogView(account: account)
            } label: {
                dashboardAction(icon: "list.bullet.rectangle.fill", title: "Complete API")
            }
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func dashboardAction(icon: String, title: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .foregroundStyle(provider.accentColor)
            Text(title).font(.system(size: 12, weight: .bold))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .providerPanel(accent: provider.accentColor)
    }

    private func resourceRow(_ resource: HostingResource) -> some View {
        HStack(spacing: 13) {
            Image(systemName: resourceIcon(resource))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(provider.accentColor)
                .frame(width: 40, height: 40)
                .background(provider.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(resource.name)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white.opacity(0.9))
                    .lineLimit(1)
                Text([resource.kind, resource.region, resource.subtitle].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.36))
                    .lineLimit(1)
            }
            Spacer()
            if let status = resource.status {
                Text(status.uppercased())
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(statusColor(status))
                    .lineLimit(1)
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.white.opacity(0.2))
        }
        .padding(15)
        .providerPanel(accent: provider.accentColor)
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 28))
                .foregroundStyle(provider.accentColor)
            Text("Could not load \(provider.displayName)")
                .font(.system(size: 17, weight: .semibold))
            Text(message)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.45))
                .multilineTextAlignment(.center)
            Button("Try Again") { Task { await viewModel.load() } }
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(provider.accentColor)
        }
        .padding(28)
        .frame(maxWidth: 360)
    }

    private var resourceTitle: String {
        switch provider {
        case .netlify, .firebase: "Sites"
        case .railway: "Projects"
        case .render: "Services"
        case .digitalOcean, .heroku, .fly, .awsAmplify: "Apps"
        default: "Resources"
        }
    }

    private func resourceIcon(_ resource: HostingResource) -> String {
        switch provider {
        case .netlify, .firebase: "globe"
        case .railway: "shippingbox.fill"
        case .render: "server.rack"
        case .digitalOcean, .heroku, .fly, .awsAmplify: "app.fill"
        default: provider.systemImage
        }
    }
}

struct ProviderPanelModifier: ViewModifier {
    let accent: Color

    func body(content: Content) -> some View {
        content.providerSurface(accent: accent)
    }
}

extension View {
    func providerPanel(accent: Color) -> some View { modifier(ProviderPanelModifier(accent: accent)) }
}

func statusColor(_ status: String) -> Color {
    let value = status.lowercased()
    if value.contains("active") || value.contains("ready") || value.contains("success") || value.contains("live") || value.contains("running") || value.contains("published") || value.contains("succeed") { return AppTheme.success }
    if value.contains("fail") || value.contains("error") || value.contains("cancel") || value.contains("suspend") { return AppTheme.danger }
    if value.contains("build") || value.contains("progress") || value.contains("pending") || value.contains("starting") { return AppTheme.warning }
    return AppTheme.textSecondary
}
