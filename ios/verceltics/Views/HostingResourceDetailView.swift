import SwiftUI

@Observable
@MainActor
final class HostingResourceDetailViewModel {
    private static var cachedDeployments: [String: [HostingDeployment]] = [:]

    let api: HostingProviderAPI
    private let cacheScope: String
    var deployments: [HostingDeployment] = []
    var isLoading = true
    var isActing = false
    var error: String?
    var successMessage: String?

    init(account: VercelAccount) {
        cacheScope = CredentialCacheScope.hostingAccount(account)
        api = HostingProviderAPI(account: account)
    }

    func load(resource: HostingResource, forceRefresh: Bool = false) async {
        let cacheKey = "\(cacheScope)|\(resource.id)"
        if !forceRefresh, let cached = Self.cachedDeployments[cacheKey] {
            deployments = cached
            isLoading = false
            error = nil
            return
        }

        isLoading = deployments.isEmpty
        error = nil
        do {
            deployments = try await api.fetchDeployments(for: resource)
            Self.cachedDeployments[cacheKey] = deployments
        }
        catch is CancellationError {
            // Going back can cancel a request; keep cached data intact.
        }
        catch { self.error = error.localizedDescription }
        isLoading = false
    }

    func act(resource: HostingResource, label: String) async {
        isActing = true
        error = nil
        do {
            try await api.performPrimaryAction(for: resource, latestDeployment: deployments.first)
            successMessage = "\(label) request accepted."
            try? await Task.sleep(for: .seconds(1))
            await load(resource: resource, forceRefresh: true)
        } catch { self.error = error.localizedDescription }
        isActing = false
    }
}

struct HostingResourceDetailView: View {
    let account: VercelAccount
    let resource: HostingResource

    @State private var viewModel: HostingResourceDetailViewModel
    @State private var showActionConfirmation = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(account: VercelAccount, resource: HostingResource) {
        self.account = account
        self.resource = resource
        _viewModel = State(initialValue: HostingResourceDetailViewModel(account: account))
    }

    private var provider: AccountProvider { account.provider }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 16) {
                    header
                    metadata

                    if let message = viewModel.successMessage {
                        AppFeedbackBanner(
                            title: "Request accepted",
                            message: message,
                            icon: "checkmark.circle.fill",
                            tint: AppTheme.success
                        )
                    }
                    if let error = viewModel.error {
                        AppFeedbackBanner(
                            title: "Request failed",
                            message: error,
                            tint: AppTheme.danger
                        )
                    }

                    AppSectionHeader(
                        title: historyTitle,
                        count: viewModel.deployments.count,
                        accent: provider.accentColor
                    )
                    if viewModel.isLoading {
                        ProgressView("Loading \(historyTitle.lowercased())")
                            .font(.footnote)
                            .tint(AppTheme.signal)
                            .foregroundStyle(AppTheme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 32)
                            .appSurface()
                    } else if viewModel.deployments.isEmpty {
                        AppEmptyState(
                            icon: provider.systemImage,
                            title: "No \(historyTitle.lowercased())",
                            message: "\(provider.displayName) did not return any \(historyTitle.lowercased()) for this resource."
                        )
                        .frame(maxWidth: .infinity)
                        .appSurface()
                    } else {
                        LazyVGrid(columns: deploymentColumns, spacing: 14) {
                            ForEach(viewModel.deployments) { deployment in
                                deploymentRow(deployment)
                            }
                        }
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.vertical, 16)
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(resource.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load(resource: resource) }
        .refreshable { await viewModel.load(resource: resource, forceRefresh: true) }
        .confirmationDialog(
            "\(provider.primaryActionLabel ?? "Run action") \(resource.name)?",
            isPresented: $showActionConfirmation,
            titleVisibility: .visible
        ) {
            Button(provider.primaryActionLabel ?? "Run action") {
                Task { await viewModel.act(resource: resource, label: provider.primaryActionLabel ?? "Action") }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This sends a real write request to \(provider.displayName).")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                ProviderMark(provider: provider, size: 26)
                    .frame(width: 52, height: 52)
                    .background(provider.accentColor.opacity(0.105))
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous)
                            .strokeBorder(provider.accentColor.opacity(0.12), lineWidth: 0.5)
                    }
                VStack(alignment: .leading, spacing: 5) {
                    Text(resource.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(2)
                    if !resourceSubtitle.isEmpty {
                        Text(resourceSubtitle)
                            .font(.subheadline)
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                }
                Spacer(minLength: 8)
                if let status = resource.status {
                    AppStatusBadge(text: status, tone: .status(status))
                }
            }

            LazyVGrid(columns: actionColumns, spacing: 10) {
                if let urlString = resource.url, let url = URL(string: urlString) {
                    Button { UIApplication.shared.open(url) } label: { actionLabel("Open", icon: "arrow.up.right") }
                }
                if let url = viewModel.api.dashboardURL(for: resource) {
                    Button { UIApplication.shared.open(url) } label: { actionLabel("Dashboard", icon: "safari.fill") }
                }
                if let label = provider.primaryActionLabel {
                    Button { showActionConfirmation = true } label: { actionLabel(label, icon: "arrow.clockwise") }
                        .disabled(viewModel.isActing)
                }
            }
            .buttonStyle(PressScaleButtonStyle())
        }
        .padding(18)
        .providerSurface(accent: provider.accentColor)
    }

    private var actionColumns: [GridItem] {
        if horizontalSizeClass != .regular {
            return [GridItem(.adaptive(minimum: 120), spacing: 10)]
        }
        return Array(
            repeating: GridItem(.flexible(), spacing: 10),
            count: max(1, availableActionCount)
        )
    }

    private var availableActionCount: Int {
        var count = 0
        if resource.url.flatMap(URL.init(string:)) != nil { count += 1 }
        if viewModel.api.dashboardURL(for: resource) != nil { count += 1 }
        if provider.primaryActionLabel != nil { count += 1 }
        return min(count, 3)
    }

    private var deploymentColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 340,
            regularMaximum: 440,
            spacing: 14
        )
    }

    private var metadata: some View {
        NavigationLink {
            ProviderFullAPICatalogView(account: account)
        } label: {
            HStack(spacing: 12) {
                AppIconTile(icon: "terminal.fill", tint: provider.accentColor, size: 36)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Complete API")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Text("Search official operations or send a manual raw request")
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .padding(16)
            .appSurface()
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func deploymentRow(_ deployment: HostingDeployment) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Text(deployment.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)
                Spacer(minLength: 8)
                AppStatusBadge(text: deployment.status, tone: .status(deployment.status))
            }
            if let message = deployment.commitMessage, !message.isEmpty {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(3)
            }
            HStack(spacing: 8) {
                if let branch = deployment.branch, !branch.isEmpty { Label(branch, systemImage: "arrow.triangle.branch").lineLimit(1) }
                if let date = deployment.createdAt { Text(date.formatted(date: .abbreviated, time: .shortened)) }
            }
            .font(.caption)
            .foregroundStyle(AppTheme.textTertiary)
        }
        .padding(15)
        .appSurface()
    }

    private func actionLabel(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppTheme.textPrimary)
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 44)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
    }

    private var resourceSubtitle: String {
        [resource.kind, resource.region]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    private var historyTitle: String {
        switch provider {
        case .heroku: "Releases"
        case .fly: "Machines"
        case .awsAmplify: "Build jobs"
        default: "Deployments"
        }
    }
}
