import SwiftUI

@Observable
@MainActor
final class CloudflarePagesProjectDetailViewModel {
    private struct CacheEntry {
        let project: CloudflarePagesProject
        let deployments: [CloudflarePagesDeployment]
        let projectError: String?
        let deploymentsError: String?
        let updatedAt: Date
    }

    private static var cache: [String: CacheEntry] = [:]
    private static let cacheLifetime: TimeInterval = 120

    let api: CloudflareAPI
    let accountID: String

    var project: CloudflarePagesProject
    var deployments: [CloudflarePagesDeployment] = []
    var isLoading = true
    var workingDeploymentID: String?
    var error: String?
    var projectError: String?
    var actionMessage: String?
    var actionFailed = false
    private var hasLoadedSnapshot = false
    private var loadGeneration = 0

    init(api: CloudflareAPI, accountID: String, project: CloudflarePagesProject) {
        self.api = api
        self.accountID = accountID
        self.project = project
        let key = "\(api.cacheScope)|\(accountID)|pages-project|\(project.name)"
        if let cached = Self.cache[key] {
            self.project = cached.project
            deployments = cached.deployments
            projectError = cached.projectError
            error = cached.deploymentsError
            hasLoadedSnapshot = true
            isLoading = false
        }
    }

    private var cacheKey: String {
        "\(api.cacheScope)|\(accountID)|pages-project|\(project.name)"
    }

    func load(forceRefresh: Bool = false) async {
        if let cached = Self.cache[cacheKey] {
            project = cached.project
            deployments = cached.deployments
            hasLoadedSnapshot = true
            isLoading = false
            projectError = cached.projectError
            error = cached.deploymentsError
            if !forceRefresh,
               Date.now.timeIntervalSince(cached.updatedAt) < Self.cacheLifetime {
                return
            }
        }

        loadGeneration += 1
        let generation = loadGeneration
        isLoading = !hasLoadedSnapshot
        projectError = nil
        error = nil

        async let projectResult = capture {
            try await api.fetchPagesProject(accountID: accountID, projectName: project.name)
        }
        async let deploymentsResult = capture {
            try await api.fetchPagesDeployments(
                accountID: accountID,
                projectName: project.name,
                environment: nil
            )
        }
        let results = await (projectResult, deploymentsResult)
        guard generation == loadGeneration else { return }
        if isCancellation(results.0) || isCancellation(results.1) {
            isLoading = false
            return
        }

        var allSucceeded = true
        switch results.0 {
        case .success(let loadedProject):
            project = loadedProject
        case .failure(let loadError):
            allSucceeded = false
            projectError = loadError.localizedDescription
        }
        switch results.1 {
        case .success(let loadedDeployments):
            deployments = loadedDeployments
            hasLoadedSnapshot = true
        case .failure(let loadError):
            allSucceeded = false
            error = loadError.localizedDescription
        }
        Self.cache[cacheKey] = CacheEntry(
            project: project,
            deployments: deployments,
            projectError: projectError,
            deploymentsError: error,
            updatedAt: allSucceeded ? .now : .distantPast
        )
        if generation == loadGeneration { isLoading = false }
    }

    func retry(_ deployment: CloudflarePagesDeployment) async {
        await runAction(id: deployment.id, success: "Deployment retry started.") {
            _ = try await api.retryPagesDeployment(
                accountID: accountID,
                projectName: project.name,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
        }
    }

    func rollback(_ deployment: CloudflarePagesDeployment) async {
        await runAction(id: deployment.id, success: "Production rollback started.") {
            _ = try await api.rollbackPagesDeployment(
                accountID: accountID,
                projectName: project.name,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
        }
    }

    func delete(_ deployment: CloudflarePagesDeployment) async {
        await runAction(id: deployment.id, success: "Deployment deleted.") {
            try await api.deletePagesDeployment(
                accountID: accountID,
                projectName: project.name,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
        }
    }

    private func runAction(
        id: String,
        success: String,
        operation: () async throws -> Void
    ) async {
        workingDeploymentID = id
        actionMessage = nil
        do {
            try await operation()
            actionMessage = success
            actionFailed = false
            await load(forceRefresh: true)
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingDeploymentID = nil
    }

    private func capture<Value>(_ operation: () async throws -> Value) async -> Result<Value, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }

    private func isCancellation<Value>(_ result: Result<Value, Error>) -> Bool {
        guard case .failure(let error) = result else { return false }
        return error is CancellationError || (error as? URLError)?.code == .cancelled
    }
}

struct CloudflarePagesProjectDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let onProjectChange: (CloudflarePagesProject?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflarePagesProjectDetailViewModel
    @State private var pendingAction: PendingPagesAction?

    @State private var didDeleteProject = false

    init(
        api: CloudflareAPI,
        accountID: String,
        project: CloudflarePagesProject,
        onProjectChange: @escaping (CloudflarePagesProject?) -> Void = { _ in }
    ) {
        self.api = api
        self.accountID = accountID
        self.onProjectChange = onProjectChange
        _viewModel = State(
            wrappedValue: CloudflarePagesProjectDetailViewModel(
                api: api,
                accountID: accountID,
                project: project
            )
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    projectHeader
                    if let error = viewModel.projectError {
                        AppFeedbackBanner(
                            title: "Project refresh failed",
                            message: "\(error) Showing the last successful project details.",
                            tint: AppTheme.warning,
                            actionTitle: "Retry"
                        ) {
                            Task { await viewModel.load(forceRefresh: true) }
                        }
                    }
                    projectDetails
                    operationsLink
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    deploymentsPanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 850 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(viewModel.project.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .onChange(of: viewModel.project) { _, updatedProject in
            onProjectChange(updatedProject)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cloudflareDataDidChange)) { notification in
            let projectPath = "/accounts/\(accountID)/pages/projects/\(viewModel.project.name)"
            guard notification.object as? String == api.cacheScope,
                  let path = notification.userInfo?["path"] as? String,
                  path == projectPath || path.hasPrefix(projectPath + "/") else { return }
            Task {
                guard !didDeleteProject else { return }
                await viewModel.load(forceRefresh: true)
            }
        }
        .confirmationDialog(
            pendingAction?.title ?? "Confirm action",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { if !$0 { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pendingAction {
                Button(pendingAction.confirmTitle, role: pendingAction.role) {
                    let action = pendingAction
                    self.pendingAction = nil
                    Task { await perform(action) }
                }
                Button("Cancel", role: .cancel) { self.pendingAction = nil }
            }
        } message: {
            Text(pendingAction?.message ?? "")
        }
        .tint(CloudflareStyle.orange)
    }

    private var operationsLink: some View {
        NavigationLink {
            CloudflarePagesOperationsView(
                api: api,
                accountID: accountID,
                project: viewModel.project
            ) {
                didDeleteProject = true
                onProjectChange(nil)
                dismiss()
            }
        } label: {
            CloudflareResourceRow(
                icon: "switch.2",
                title: "Pages operations",
                subtitle: "Domains, builds, bindings, deployments and project settings",
                tint: CloudflareStyle.orange
            )
        }
        .buttonStyle(.plain)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var projectHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                AppIconTile(icon: "doc.badge.gearshape.fill", tint: CloudflareStyle.orange, size: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.project.name)
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(viewModel.project.domains.first ?? viewModel.project.subdomain ?? "Cloudflare Pages")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                if let latest = viewModel.project.latestDeployment,
                   let environment = latest.environment {
                    CloudflareStatusPill(
                        text: environment.rawValue.uppercased(),
                        color: environment == .production
                            ? CloudflareStyle.green
                            : CloudflareStyle.amber
                    )
                }
            }

            HStack(spacing: 9) {
                if let host = viewModel.project.domains.first ?? viewModel.project.subdomain,
                   let url = URL(string: host.hasPrefix("http") ? host : "https://\(host)") {
                    headerButton("Open site", icon: "arrow.up.right", url: url)
                }
                if let url = URL(string: "https://dash.cloudflare.com/\(accountID)/pages/view/\(viewModel.project.name)") {
                    headerButton("Dashboard", icon: "safari", url: url)
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var projectDetails: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Project", icon: "info.circle.fill")
            Divider().overlay(AppTheme.divider)
            CloudflareDetailRow(icon: "number", title: "Project ID", value: viewModel.project.id)
            CloudflareDetailRow(
                icon: "arrow.triangle.branch",
                title: "Production branch",
                value: viewModel.project.productionBranch ?? "Not set"
            )
            CloudflareDetailRow(
                icon: "function",
                title: "Functions",
                value: viewModel.project.usesFunctions == true ? "Enabled" : "Not detected"
            )
            if let framework = viewModel.project.framework, !framework.isEmpty {
                CloudflareDetailRow(
                    icon: "shippingbox.fill",
                    title: "Framework",
                    value: [framework, viewModel.project.frameworkVersion].compactMap { $0 }.joined(separator: " ")
                )
            }
            CloudflareDetailRow(
                icon: "globe",
                title: "Domains",
                value: viewModel.project.domains.isEmpty
                    ? "None"
                    : viewModel.project.domains.joined(separator: ", ")
            )
        }
        .cloudflarePanel()
    }

    private var deploymentsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Deployments",
                icon: "square.stack.3d.up.fill",
                count: viewModel.deployments.count
            )
            Divider().overlay(AppTheme.divider)

            if viewModel.isLoading {
                ProgressView()
                    .tint(CloudflareStyle.orange)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 34)
            } else if let error = viewModel.error, viewModel.deployments.isEmpty {
                CloudflareEmptySection(
                    icon: "exclamationmark.triangle.fill",
                    title: "Deployments unavailable",
                    message: error
                )
            } else if viewModel.deployments.isEmpty {
                CloudflareEmptySection(
                    icon: "square.stack.3d.up",
                    title: "No deployments",
                    message: "This Pages project has no deployments."
                )
            } else {
                if let error = viewModel.error {
                    AppFeedbackBanner(
                        title: "Deployment refresh failed",
                        message: "\(error) Showing the last successful result.",
                        tint: AppTheme.warning,
                        actionTitle: "Retry"
                    ) {
                        Task { await viewModel.load(forceRefresh: true) }
                    }
                    .padding(12)
                }
                ForEach(viewModel.deployments, id: \.id) { deployment in
                    deploymentRow(deployment)
                    if deployment.id != viewModel.deployments.last?.id {
                        Divider().overlay(AppTheme.divider).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func deploymentRow(_ deployment: CloudflarePagesDeployment) -> some View {
        HStack(spacing: 12) {
            NavigationLink {
                CloudflarePagesDeploymentDetailView(
                    api: api,
                    accountID: accountID,
                    projectName: viewModel.project.name,
                    deployment: deployment
                )
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: deployment.isSkipped == true ? "forward.fill" : "square.stack.3d.up.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(deploymentColor(deployment))
                        .frame(width: 36, height: 36)
                        .background(deploymentColor(deployment).opacity(0.11))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(deployment.shortID ?? String(deployment.id.prefix(12)))
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(1)
                        Text(deployment.url ?? deployment.aliases.first ?? deployment.environment?.rawValue ?? "Deployment")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(AppTheme.textTertiary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 6)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if viewModel.workingDeploymentID == deployment.id {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Menu {
                    if let rawURL = deployment.url,
                       let url = URL(string: rawURL.hasPrefix("http") ? rawURL : "https://\(rawURL)") {
                        Button {
                            UIApplication.shared.open(url)
                        } label: {
                            Label("Open deployment", systemImage: "arrow.up.right")
                        }
                    }

                    Button {
                        pendingAction = .retry(deployment)
                    } label: {
                        Label("Retry deployment", systemImage: "arrow.clockwise")
                    }

                    Button {
                        pendingAction = .rollback(deployment)
                    } label: {
                        Label("Roll back production", systemImage: "arrow.uturn.backward")
                    }

                    Button(role: .destructive) {
                        pendingAction = .delete(deployment)
                    } label: {
                        Label("Delete deployment", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(width: 44, height: 44)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func headerButton(_ title: String, icon: String, url: URL) -> some View {
        Button {
            UIApplication.shared.open(url)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: icon).font(.system(size: 9, weight: .semibold))
                Text(title).font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(AppTheme.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(AppTheme.stroke)
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func deploymentColor(_ deployment: CloudflarePagesDeployment) -> Color {
        if deployment.isSkipped == true { return AppTheme.textTertiary }
        return switch deployment.environment {
        case .production: CloudflareStyle.green
        case .preview: CloudflareStyle.amber
        default: CloudflareStyle.orange
        }
    }

    private func perform(_ action: PendingPagesAction) async {
        switch action {
        case .retry(let deployment): await viewModel.retry(deployment)
        case .rollback(let deployment): await viewModel.rollback(deployment)
        case .delete(let deployment): await viewModel.delete(deployment)
        }
    }
}

private enum PendingPagesAction: Identifiable {
    case retry(CloudflarePagesDeployment)
    case rollback(CloudflarePagesDeployment)
    case delete(CloudflarePagesDeployment)

    var id: String {
        switch self {
        case .retry(let deployment): "retry-\(deployment.id)"
        case .rollback(let deployment): "rollback-\(deployment.id)"
        case .delete(let deployment): "delete-\(deployment.id)"
        }
    }

    var title: String {
        switch self {
        case .retry: "Retry this deployment?"
        case .rollback: "Roll production back?"
        case .delete: "Delete this deployment?"
        }
    }

    var message: String {
        switch self {
        case .retry:
            "Cloudflare will create a new deployment using this deployment’s configuration."
        case .rollback:
            "Cloudflare will make this deployment the active production version."
        case .delete:
            "This deployment will be permanently removed from Cloudflare Pages."
        }
    }

    var confirmTitle: String {
        switch self {
        case .retry: "Retry Deployment"
        case .rollback: "Roll Back"
        case .delete: "Delete Deployment"
        }
    }

    var role: ButtonRole? {
        switch self {
        case .delete, .rollback: .destructive
        case .retry: nil
        }
    }
}
