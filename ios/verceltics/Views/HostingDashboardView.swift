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
    private let cacheKey: String

    init(account: VercelAccount) {
        self.account = account
        self.api = HostingProviderAPI(account: account)
        cacheKey = CredentialCacheScope.hostingAccount(account)
        if let cached = Self.cachedResources[cacheKey] {
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
            Self.cachedResources[cacheKey] = resources
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(AuthManager.self) private var authManager

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
                    AppDashboardLoadingView(accent: provider.accentColor, showsMetrics: false)
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
                    .accessibilityLabel(viewModel.isRefreshing ? "Refreshing \(provider.displayName)" : "Refresh \(provider.displayName)")
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

                if let error = authManager.error {
                    AppFeedbackBanner(
                        title: "Saved account change failed",
                        message: error,
                        icon: "lock.trianglebadge.exclamationmark.fill",
                        tint: AppTheme.danger
                    )
                }

                if let error = viewModel.error {
                    AppFeedbackBanner(
                        title: "Couldn’t refresh \(provider.displayName)",
                        message: error,
                        actionTitle: "Try again"
                    ) {
                        Task { await viewModel.load(refresh: true) }
                    }
                }

                actionGrid

                AppSectionHeader(title: resourceTitle, count: filteredResources.count, accent: provider.accentColor)

                if filteredResources.isEmpty {
                    AppEmptyState(
                        icon: searchText.isEmpty ? provider.systemImage : "magnifyingglass",
                        title: searchText.isEmpty ? "No resources returned" : "No matching resources",
                        message: searchText.isEmpty
                            ? "This provider did not return any \(resourceTitle.lowercased()) for the connected account."
                            : "Nothing matches “\(searchText)”."
                    )
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    LazyVGrid(columns: resourceColumns, spacing: 14) {
                        ForEach(filteredResources) { resource in
                            NavigationLink {
                                HostingResourceDetailView(account: account, resource: resource)
                            } label: {
                                resourceRow(resource)
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                    }
                }

            }
            .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
            .padding(.top, 18)
            .padding(.bottom, 24)
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await viewModel.load(refresh: true) }
    }

    private var resourceColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 340,
            regularMaximum: 540,
            spacing: 14
        )
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
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(1)
                Text(account.email ?? provider.connectionSubtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            AppStatusBadge(text: "Connected", tone: .success)
        }
        .padding(18)
        .providerSurface(accent: provider.accentColor)
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
        .frame(maxWidth: horizontalSizeClass == .regular ? 470 : .infinity, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func dashboardAction(icon: String, title: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .foregroundStyle(provider.accentColor)
            Text(title).font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .appSurface(raised: true)
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
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)
                Text([resource.kind, resource.region, resource.subtitle].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(2)
            }
            .layoutPriority(1)
            Spacer()
            if let status = resource.status {
                AppStatusBadge(text: status.capitalized, tone: .status(status))
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(AppTheme.textTertiary)
        }
        .padding(15)
        .appSurface()
        .accessibilityElement(children: .combine)
        .accessibilityHint("Open \(resource.name) details")
    }

    private func errorView(_ message: String) -> some View {
        AppEmptyState(
            icon: "exclamationmark.triangle.fill",
            title: "Could not load \(provider.displayName)",
            message: message,
            actionTitle: "Try again"
        ) {
            Task { await viewModel.load(refresh: true) }
        }
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
        content.appSurface()
    }
}

extension View {
    func providerPanel(accent: Color) -> some View { modifier(ProviderPanelModifier(accent: accent)) }
}

func statusColor(_ status: String) -> Color {
    AppStatusTone.status(status).color
}
