import SwiftUI

@Observable
@MainActor
final class CloudflareWorkerDetailViewModel {
    private struct WorkerCacheEntry {
        let worker: CloudflareWorkerScript
        let updatedAt: Date
    }

    private struct DeploymentCacheEntry {
        let deployments: [CloudflareWorkerDeployment]
        let updatedAt: Date
    }

    @ResettableMemoryCache private static var workerCache: [String: WorkerCacheEntry] = [:]
    @ResettableMemoryCache private static var deploymentCache: [String: DeploymentCacheEntry] = [:]
    private static let cacheLifetime: TimeInterval = 180

    let api: CloudflareAPI
    let accountID: String
    let workerID: String

    var worker: CloudflareWorkerScript
    var deployments: [CloudflareWorkerDeployment] = []
    var isLoading = true
    var isRefreshing = false
    var workingID: String?
    var workerError: String?
    var error: String?
    var actionMessage: String?
    var actionFailed = false
    var didDeleteWorker = false
    private var hasLoadedSnapshot = false
    private var workerLoadGeneration = 0
    private var loadGeneration = 0
    private var isWorkerRefreshing = false

    private var cacheKey: String { "\(api.cacheScope)|\(accountID)|\(workerID)" }

    init(api: CloudflareAPI, accountID: String, worker: CloudflareWorkerScript) {
        self.api = api
        self.accountID = accountID
        workerID = worker.id
        if let cachedWorker = Self.workerCache["\(api.cacheScope)|\(accountID)|\(worker.id)"],
           cachedWorker.worker == worker {
            self.worker = cachedWorker.worker
        } else {
            self.worker = worker
            Self.workerCache["\(api.cacheScope)|\(accountID)|\(worker.id)"] = WorkerCacheEntry(
                worker: worker,
                updatedAt: .now
            )
        }
        if let cached = Self.deploymentCache[cacheKey] {
            deployments = cached.deployments
            isLoading = false
            hasLoadedSnapshot = true
        }
    }

    func load(forceRefresh: Bool = false) async {
        async let workerLoad: Void = loadWorker(forceRefresh: forceRefresh)
        async let deploymentLoad: Void = loadDeployments(forceRefresh: forceRefresh)
        _ = await (workerLoad, deploymentLoad)
    }

    func refreshMetadata(forceRefresh: Bool = false) async {
        await loadWorker(forceRefresh: forceRefresh)
    }

    private func loadWorker(forceRefresh: Bool) async {
        if let cached = Self.workerCache[cacheKey] {
            worker = cached.worker
            workerError = nil
            if !forceRefresh,
               Date.now.timeIntervalSince(cached.updatedAt) < Self.cacheLifetime {
                return
            }
        }
        guard !isWorkerRefreshing else { return }

        workerLoadGeneration += 1
        let generation = workerLoadGeneration
        isWorkerRefreshing = true
        workerError = nil
        defer {
            if generation == workerLoadGeneration {
                isWorkerRefreshing = false
            }
        }
        do {
            let scripts = try await api.fetchWorkerScripts(accountID: accountID)
            guard let refreshed = scripts.first(where: { $0.id == workerID }) else {
                throw CloudflareAPIError.requestFailed(
                    statusCode: 404,
                    message: "Cloudflare did not return Worker \(workerID)."
                )
            }
            guard generation == workerLoadGeneration else { return }
            worker = refreshed
            Self.workerCache[cacheKey] = WorkerCacheEntry(worker: refreshed, updatedAt: .now)
        } catch is CancellationError {
            // Navigation can cancel an in-flight request.
        } catch let urlError as URLError where urlError.code == .cancelled {
            // Navigation can cancel an in-flight request.
        } catch {
            guard generation == workerLoadGeneration else { return }
            workerError = error.localizedDescription
        }
    }

    private func loadDeployments(forceRefresh: Bool) async {
        if let cached = Self.deploymentCache[cacheKey] {
            deployments = cached.deployments
            hasLoadedSnapshot = true
            isLoading = false
            if !forceRefresh,
               Date.now.timeIntervalSince(cached.updatedAt) < Self.cacheLifetime {
                return
            }
        }
        guard !isRefreshing else { return }

        loadGeneration += 1
        let generation = loadGeneration
        isLoading = !hasLoadedSnapshot
        isRefreshing = hasLoadedSnapshot
        error = nil
        defer {
            if generation == loadGeneration {
                isLoading = false
                isRefreshing = false
            }
        }
        do {
            let refreshed = try await api.fetchWorkerDeployments(accountID: accountID, scriptName: workerID)
            guard generation == loadGeneration else { return }
            deployments = refreshed
            hasLoadedSnapshot = true
            updateCache()
        } catch is CancellationError {
            // Navigation can cancel an in-flight request.
        } catch let urlError as URLError where urlError.code == .cancelled {
            // Navigation can cancel an in-flight request.
        } catch {
            guard generation == loadGeneration else { return }
            self.error = error.localizedDescription
        }
    }

    func deleteDeployment(_ deployment: CloudflareWorkerDeployment) async {
        cancelLoad()
        workingID = deployment.id
        actionMessage = nil
        do {
            try await api.deleteWorkerDeployment(
                accountID: accountID,
                scriptName: workerID,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
            actionMessage = "Worker deployment deleted."
            actionFailed = false
            deployments.removeAll { $0.id == deployment.id }
            updateCache()
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingID = nil
    }

    func deleteWorker() async {
        cancelLoad()
        workingID = workerID
        actionMessage = nil
        do {
            try await api.deleteWorker(
                accountID: accountID,
                scriptName: workerID,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: workerID)
            )
            actionMessage = "Worker deleted."
            actionFailed = false
            didDeleteWorker = true
            Self.workerCache[cacheKey] = nil
            Self.deploymentCache[cacheKey] = nil
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingID = nil
    }

    private func updateCache() {
        Self.deploymentCache[cacheKey] = DeploymentCacheEntry(deployments: deployments, updatedAt: .now)
    }

    private func cancelLoad() {
        guard isLoading || isRefreshing || isWorkerRefreshing else { return }
        workerLoadGeneration += 1
        loadGeneration += 1
        isWorkerRefreshing = false
        isLoading = false
        isRefreshing = false
    }
}

struct CloudflareWorkerDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let onWorkerChange: (CloudflareWorkerScript?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModel: CloudflareWorkerDetailViewModel
    @State private var pendingAction: PendingWorkerAction?

    init(
        api: CloudflareAPI,
        accountID: String,
        worker: CloudflareWorkerScript,
        onWorkerChange: @escaping (CloudflareWorkerScript?) -> Void = { _ in }
    ) {
        self.api = api
        self.accountID = accountID
        self.onWorkerChange = onWorkerChange
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
                    if let error = viewModel.workerError {
                        CloudflareActionResultBanner(message: error, isError: true)
                    }
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
        .navigationTitle(viewModel.worker.id)
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .onChange(of: viewModel.worker) { _, updatedWorker in
            onWorkerChange(updatedWorker)
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await viewModel.load() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cloudflareDataDidChange)) { notification in
            guard notification.object as? String == api.cacheScope,
                  let path = notification.userInfo?["path"] as? String,
                  path.hasPrefix("/accounts/\(accountID)/workers/scripts/\(viewModel.workerID)") else { return }
            Task { await viewModel.refreshMetadata(forceRefresh: true) }
        }
        .onChange(of: viewModel.didDeleteWorker) { _, deleted in
            if deleted {
                onWorkerChange(nil)
                dismiss()
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
                AppIconTile(icon: "shippingbox.fill", tint: CloudflareStyle.orange, size: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.worker.id)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text(viewModel.worker.hasModules == true ? "Modules Worker" : "Service Worker")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AppTheme.textSecondary)
                }

                Spacer(minLength: 8)
                CloudflareStatusPill(text: "DEPLOYED", color: CloudflareStyle.green)
            }

            HStack(spacing: 9) {
                if let route = viewModel.worker.routes.first,
                   let url = workerURL(from: route.pattern) {
                    openButton("Open route", icon: "arrow.up.right", url: url)
                }
                if let url = URL(string: "https://dash.cloudflare.com/\(accountID)/workers/services/view/\(viewModel.worker.id)/production") {
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
            Divider().overlay(AppTheme.divider)
            CloudflareDetailRow(
                icon: "calendar",
                title: "Compatibility date",
                value: viewModel.worker.compatibilityDate ?? "Default"
            )
            CloudflareDetailRow(
                icon: "shippingbox",
                title: "Format",
                value: viewModel.worker.hasModules == true ? "ES modules" : "Service Worker"
            )
            CloudflareDetailRow(
                icon: "photo.on.rectangle",
                title: "Static assets",
                value: viewModel.worker.hasAssets == true ? "Included" : "None detected"
            )
            CloudflareDetailRow(
                icon: "gauge.with.dots.needle.67percent",
                title: "Usage model",
                value: viewModel.worker.usageModel?.capitalized ?? "Account default"
            )
            if !viewModel.worker.compatibilityFlags.isEmpty {
                CloudflareDetailRow(
                    icon: "flag.fill",
                    title: "Compatibility flags",
                    value: viewModel.worker.compatibilityFlags.joined(separator: ", ")
                )
            }
            if !viewModel.worker.handlers.isEmpty {
                CloudflareDetailRow(
                    icon: "bolt.fill",
                    title: "Handlers",
                    value: viewModel.worker.handlers.joined(separator: ", ")
                )
            }
        }
        .cloudflarePanel()
    }

    private var operationsLink: some View {
        NavigationLink {
            CloudflareWorkerOperationsView(
                api: api,
                accountID: accountID,
                worker: viewModel.worker
            ) {
                await viewModel.load(forceRefresh: true)
            }
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
                count: viewModel.worker.routes.count
            )
            Divider().overlay(AppTheme.divider)
            if viewModel.worker.routes.isEmpty {
                CloudflareEmptySection(
                    icon: "point.3.connected.trianglepath.dotted",
                    title: "No routes returned",
                    message: "The Worker may use a workers.dev domain or custom domain instead."
                )
            } else {
                ForEach(viewModel.worker.routes) { route in
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
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(CloudflareStyle.orange)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if route.id != viewModel.worker.routes.last?.id {
                        Divider().overlay(AppTheme.divider).padding(.leading, 64)
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
                    message: "Cloudflare did not return deployment history for this Worker."
                )
            } else {
                ForEach(viewModel.deployments, id: \.id) { deployment in
                    workerDeploymentRow(deployment)
                    if deployment.id != viewModel.deployments.last?.id {
                        Divider().overlay(AppTheme.divider).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func workerDeploymentRow(_ deployment: CloudflareWorkerDeployment) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "square.stack.3d.up.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(CloudflareStyle.green)
                .frame(width: 36, height: 36)
                .background(CloudflareStyle.green.opacity(0.11))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(String(deployment.id.prefix(14)))
                    .font(.system(.subheadline, design: .monospaced, weight: .semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(deploymentSubtitle(deployment))
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
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
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                        .frame(width: 44, height: 44)
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
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
            }
            Text("Deleting the Worker removes the script from Cloudflare and stops traffic routed to it.")
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            CloudflareActionButton(
                title: "Delete Worker",
                icon: "trash.fill",
                role: .destructive,
                isWorking: viewModel.workingID == viewModel.worker.id
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
                Image(systemName: icon).font(.caption.weight(.semibold))
                Text(title).font(.subheadline.weight(.semibold))
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
