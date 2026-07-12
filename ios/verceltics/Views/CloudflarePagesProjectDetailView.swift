import SwiftUI

@Observable
@MainActor
final class CloudflarePagesProjectDetailViewModel {
    let api: CloudflareAPI
    let accountID: String
    let project: CloudflarePagesProject

    var deployments: [CloudflarePagesDeployment] = []
    var isLoading = true
    var workingDeploymentID: String?
    var error: String?
    var actionMessage: String?
    var actionFailed = false

    init(api: CloudflareAPI, accountID: String, project: CloudflarePagesProject) {
        self.api = api
        self.accountID = accountID
        self.project = project
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            deployments = try await api.fetchPagesDeployments(
                accountID: accountID,
                projectName: project.name,
                environment: nil
            )
        } catch is CancellationError {
            // Navigation can cancel a pending request.
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
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
            deployments = try await api.fetchPagesDeployments(
                accountID: accountID,
                projectName: project.name,
                environment: nil
            )
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingDeploymentID = nil
    }
}

struct CloudflarePagesProjectDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let project: CloudflarePagesProject

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflarePagesProjectDetailViewModel
    @State private var pendingAction: PendingPagesAction?

    init(api: CloudflareAPI, accountID: String, project: CloudflarePagesProject) {
        self.api = api
        self.accountID = accountID
        self.project = project
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
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    projectHeader
                    projectDetails
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
        .navigationTitle(project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
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

    private var projectHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "doc.badge.gearshape.fill")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(.black.opacity(0.82))
                    .frame(width: 46, height: 46)
                    .background(
                        LinearGradient(
                            colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                            startPoint: .bottomLeading,
                            endPoint: .topTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(project.name)
                        .font(.system(size: 21, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(project.domains.first ?? project.subdomain ?? "Cloudflare Pages")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                if let latest = project.latestDeployment,
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
                if let host = project.domains.first ?? project.subdomain,
                   let url = URL(string: host.hasPrefix("http") ? host : "https://\(host)") {
                    headerButton("Open site", icon: "arrow.up.right", url: url)
                }
                if let url = URL(string: "https://dash.cloudflare.com/\(accountID)/pages/view/\(project.name)") {
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
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "number", title: "Project ID", value: project.id)
            CloudflareDetailRow(
                icon: "arrow.triangle.branch",
                title: "Production branch",
                value: project.productionBranch ?? "Not set"
            )
            CloudflareDetailRow(
                icon: "function",
                title: "Functions",
                value: project.usesFunctions == true ? "Enabled" : "Not detected"
            )
            CloudflareDetailRow(
                icon: "globe",
                title: "Domains",
                value: project.domains.isEmpty ? "None" : project.domains.joined(separator: ", ")
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
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.isLoading {
                ProgressView()
                    .tint(CloudflareStyle.orange)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 34)
            } else if let error = viewModel.error {
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
                ForEach(viewModel.deployments, id: \.id) { deployment in
                    deploymentRow(deployment)
                    if deployment.id != viewModel.deployments.last?.id {
                        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
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
                    projectName: project.name,
                    deployment: deployment
                )
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: deployment.isSkipped == true ? "forward.fill" : "square.stack.3d.up.fill")
                        .font(.system(size: 12, weight: .heavy))
                        .foregroundStyle(deploymentColor(deployment))
                        .frame(width: 36, height: 36)
                        .background(deploymentColor(deployment).opacity(0.11))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(deployment.shortID ?? String(deployment.id.prefix(12)))
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.84))
                            .lineLimit(1)
                        Text(deployment.url ?? deployment.aliases.first ?? deployment.environment?.rawValue ?? "Deployment")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.white.opacity(0.34))
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
                        .foregroundStyle(.white.opacity(0.45))
                        .frame(width: 34, height: 34)
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
                Image(systemName: icon).font(.system(size: 9, weight: .heavy))
                Text(title).font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(.white.opacity(0.78))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Color.white.opacity(0.07))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func deploymentColor(_ deployment: CloudflarePagesDeployment) -> Color {
        if deployment.isSkipped == true { return .white.opacity(0.35) }
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
