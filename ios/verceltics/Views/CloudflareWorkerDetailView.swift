import SwiftUI

@Observable
@MainActor
final class CloudflareWorkerDetailViewModel {
    let api: CloudflareAPI
    let accountID: String
    let worker: CloudflareWorkerScript

    var deployments: [CloudflareWorkerDeployment] = []
    var isLoading = true
    var workingID: String?
    var error: String?
    var actionMessage: String?
    var actionFailed = false
    var didDeleteWorker = false

    init(api: CloudflareAPI, accountID: String, worker: CloudflareWorkerScript) {
        self.api = api
        self.accountID = accountID
        self.worker = worker
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            deployments = try await api.fetchWorkerDeployments(accountID: accountID, scriptName: worker.id)
        } catch is CancellationError {
            // Navigation can cancel an in-flight request.
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func deleteDeployment(_ deployment: CloudflareWorkerDeployment) async {
        workingID = deployment.id
        actionMessage = nil
        do {
            try await api.deleteWorkerDeployment(
                accountID: accountID,
                scriptName: worker.id,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
            actionMessage = "Worker deployment deleted."
            actionFailed = false
            deployments = try await api.fetchWorkerDeployments(accountID: accountID, scriptName: worker.id)
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingID = nil
    }

    func deleteWorker() async {
        workingID = worker.id
        actionMessage = nil
        do {
            try await api.deleteWorker(
                accountID: accountID,
                scriptName: worker.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: worker.id)
            )
            actionMessage = "Worker deleted."
            actionFailed = false
            didDeleteWorker = true
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingID = nil
    }
}

struct CloudflareWorkerDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let worker: CloudflareWorkerScript

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareWorkerDetailViewModel
    @State private var pendingAction: PendingWorkerAction?

    init(api: CloudflareAPI, accountID: String, worker: CloudflareWorkerScript) {
        self.api = api
        self.accountID = accountID
        self.worker = worker
        _viewModel = State(
            wrappedValue: CloudflareWorkerDetailViewModel(api: api, accountID: accountID, worker: worker)
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    workerHeader
                    metadataPanel
                    operationsLink
                    routesPanel
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    deploymentsPanel
                    dangerZone
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 850 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(worker.id)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .onChange(of: viewModel.didDeleteWorker) { _, deleted in
            if deleted { dismiss() }
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
                Button(pendingAction.confirmTitle, role: .destructive) {
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

    private var workerHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "shippingbox.fill")
                    .font(.system(size: 20, weight: .semibold))
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
                    Text(worker.id)
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(worker.hasModules == true ? "Modules Worker" : "Service Worker")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                }

                Spacer(minLength: 8)
                CloudflareStatusPill(text: "DEPLOYED", color: CloudflareStyle.green)
            }

            HStack(spacing: 9) {
                if let route = worker.routes.first,
                   let url = workerURL(from: route.pattern) {
                    openButton("Open route", icon: "arrow.up.right", url: url)
                }
                if let url = URL(string: "https://dash.cloudflare.com/\(accountID)/workers/services/view/\(worker.id)/production") {
                    openButton("Dashboard", icon: "safari", url: url)
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var metadataPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Runtime", icon: "cpu.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(
                icon: "calendar",
                title: "Compatibility date",
                value: worker.compatibilityDate ?? "Default"
            )
            CloudflareDetailRow(
                icon: "shippingbox",
                title: "Format",
                value: worker.hasModules == true ? "ES modules" : "Service Worker"
            )
            CloudflareDetailRow(
                icon: "photo.on.rectangle",
                title: "Static assets",
                value: worker.hasAssets == true ? "Included" : "None detected"
            )
            CloudflareDetailRow(
                icon: "gauge.with.dots.needle.67percent",
                title: "Usage model",
                value: worker.usageModel?.capitalized ?? "Account default"
            )
            if !worker.compatibilityFlags.isEmpty {
                CloudflareDetailRow(
                    icon: "flag.fill",
                    title: "Compatibility flags",
                    value: worker.compatibilityFlags.joined(separator: ", ")
                )
            }
            if !worker.handlers.isEmpty {
                CloudflareDetailRow(
                    icon: "bolt.fill",
                    title: "Handlers",
                    value: worker.handlers.joined(separator: ", ")
                )
            }
        }
        .cloudflarePanel()
    }

    private var operationsLink: some View {
        NavigationLink {
            CloudflareWorkerOperationsView(api: api, accountID: accountID, worker: worker)
        } label: {
            CloudflareResourceRow(
                icon: "switch.2",
                title: "Worker operations",
                subtitle: "Versions, live logs, secrets, cron, domains and settings",
                tint: CloudflareStyle.orange
            )
        }
        .buttonStyle(.plain)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var routesPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Routes",
                icon: "arrow.triangle.branch",
                count: worker.routes.count
            )
            Divider().overlay(Color.white.opacity(0.06))
            if worker.routes.isEmpty {
                CloudflareEmptySection(
                    icon: "point.3.connected.trianglepath.dotted",
                    title: "No routes returned",
                    message: "The Worker may use a workers.dev domain or custom domain instead."
                )
            } else {
                ForEach(worker.routes) { route in
                    CloudflareResourceRow(
                        icon: "point.3.filled.connected.trianglepath.dotted",
                        title: route.pattern,
                        subtitle: route.script ?? "Worker route",
                        tint: CloudflareStyle.amber
                    ) {
                        if let url = workerURL(from: route.pattern) {
                            Button {
                                UIApplication.shared.open(url)
                            } label: {
                                Image(systemName: "arrow.up.right")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(CloudflareStyle.orange)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if route.id != worker.routes.last?.id {
                        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
                    }
                }
            }
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
                    message: "Cloudflare did not return deployment history for this Worker."
                )
            } else {
                ForEach(viewModel.deployments, id: \.id) { deployment in
                    workerDeploymentRow(deployment)
                    if deployment.id != viewModel.deployments.last?.id {
                        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func workerDeploymentRow(_ deployment: CloudflareWorkerDeployment) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "square.stack.3d.up.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(CloudflareStyle.green)
                .frame(width: 36, height: 36)
                .background(CloudflareStyle.green.opacity(0.11))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(String(deployment.id.prefix(14)))
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.84))
                Text(deploymentSubtitle(deployment))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.34))
                    .lineLimit(1)
            }

            Spacer(minLength: 6)

            if viewModel.workingID == deployment.id {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Menu {
                    Button(role: .destructive) {
                        pendingAction = .deleteDeployment(deployment)
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

    private var dangerZone: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.shield.fill")
                    .foregroundStyle(CloudflareStyle.red)
                Text("Danger zone")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
            }
            Text("Deleting the Worker removes the script from Cloudflare and stops traffic routed to it.")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.35))
                .fixedSize(horizontal: false, vertical: true)
            CloudflareActionButton(
                title: "Delete Worker",
                icon: "trash.fill",
                role: .destructive,
                isWorking: viewModel.workingID == worker.id
            ) {
                pendingAction = .deleteWorker
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .cloudflarePanel()
    }

    private func deploymentSubtitle(_ deployment: CloudflareWorkerDeployment) -> String {
        var values: [String] = []
        if let source = deployment.source, !source.isEmpty { values.append(source.capitalized) }
        if let strategy = deployment.strategy, !strategy.isEmpty { values.append(strategy.capitalized) }
        if !deployment.versions.isEmpty { values.append("\(deployment.versions.count) versions") }
        if let author = deployment.authorEmail, !author.isEmpty { values.append(author) }
        return values.isEmpty ? "Worker deployment" : values.joined(separator: " · ")
    }

    private func workerURL(from route: String) -> URL? {
        let normalized = route
            .replacingOccurrences(of: "*", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !normalized.isEmpty else { return nil }
        return URL(string: normalized.hasPrefix("http") ? normalized : "https://\(normalized)")
    }

    private func openButton(_ title: String, icon: String, url: URL) -> some View {
        Button {
            UIApplication.shared.open(url)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: icon).font(.system(size: 9, weight: .semibold))
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

    private func perform(_ action: PendingWorkerAction) async {
        switch action {
        case .deleteWorker: await viewModel.deleteWorker()
        case .deleteDeployment(let deployment): await viewModel.deleteDeployment(deployment)
        }
    }
}

private enum PendingWorkerAction: Identifiable {
    case deleteWorker
    case deleteDeployment(CloudflareWorkerDeployment)

    var id: String {
        switch self {
        case .deleteWorker: "delete-worker"
        case .deleteDeployment(let deployment): "delete-deployment-\(deployment.id)"
        }
    }

    var title: String {
        switch self {
        case .deleteWorker: "Delete this Worker?"
        case .deleteDeployment: "Delete this deployment?"
        }
    }

    var message: String {
        switch self {
        case .deleteWorker:
            "This permanently removes the Worker script and can immediately interrupt routed traffic."
        case .deleteDeployment:
            "This permanently removes the selected Worker deployment from Cloudflare."
        }
    }

    var confirmTitle: String {
        switch self {
        case .deleteWorker: "Delete Worker"
        case .deleteDeployment: "Delete Deployment"
        }
    }
}
