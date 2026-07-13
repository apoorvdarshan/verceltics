import SwiftUI
import UIKit

@Observable
@MainActor
final class CloudflarePagesOperationsViewModel {
    let api: CloudflareAPI
    let accountID: String
    let initialProject: CloudflarePagesProject

    var project: CloudflarePagesOperationsProject?
    var domains: [CloudflarePagesCustomDomain] = []
    var isLoading = true
    var isWorking = false
    var workingResourceID: String?
    var loadError: String?
    var domainsError: String?
    var actionMessage: String?
    var actionFailed = false
    var didDeleteProject = false

    var projectName: String { project?.name ?? initialProject.name }

    init(api: CloudflareAPI, accountID: String, project: CloudflarePagesProject) {
        self.api = api
        self.accountID = accountID
        self.initialProject = project
    }

    func load() async {
        isLoading = project == nil
        loadError = nil
        domainsError = nil

        do {
            project = try await api.pagesOperationsFetchProject(
                accountID: accountID,
                projectName: projectName
            )
        } catch is CancellationError {
            isLoading = false
            return
        } catch {
            loadError = error.localizedDescription
        }

        await refreshDomains()
        isLoading = false
    }

    func refreshDomains() async {
        do {
            domains = try await api.pagesOperationsFetchDomains(
                accountID: accountID,
                projectName: projectName
            )
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            domainsError = nil
        } catch is CancellationError {
            return
        } catch {
            domainsError = error.localizedDescription
        }
    }

    func save(_ draft: CloudflarePagesProjectEditDraft) async -> Bool {
        guard !draft.productionBranch.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            showError("Production branch cannot be empty.")
            return false
        }

        return await runAction(resourceID: projectName, success: "Project settings saved.") {
            project = try await api.pagesOperationsUpdateProject(
                accountID: accountID,
                projectName: projectName,
                update: draft.request,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: projectPath)
            )
        }
    }

    func addDomain(_ rawName: String) async -> Bool {
        let name = rawName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !name.isEmpty, !name.contains(" "), name.contains(".") else {
            showError("Enter a valid hostname such as www.example.com.")
            return false
        }

        return await runAction(resourceID: name, success: "Domain added. Cloudflare is validating it.") {
            _ = try await api.pagesOperationsAddDomain(
                accountID: accountID,
                projectName: projectName,
                domainName: name,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: domainsPath)
            )
            await refreshDomains()
        }
    }

    func retryValidation(_ domain: CloudflarePagesCustomDomain) async {
        _ = await runAction(resourceID: domain.id, success: "Validation restarted for \(domain.name).") {
            _ = try await api.pagesOperationsRetryDomainValidation(
                accountID: accountID,
                projectName: projectName,
                domainName: domain.name,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: domainPath(domain.name))
            )
            await refreshDomains()
        }
    }

    func deleteDomain(_ domain: CloudflarePagesCustomDomain) async {
        _ = await runAction(resourceID: domain.id, success: "\(domain.name) removed from Pages.") {
            try await api.pagesOperationsDeleteDomain(
                accountID: accountID,
                projectName: projectName,
                domainName: domain.name,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: domainPath(domain.name))
            )
            await refreshDomains()
        }
    }

    func purgeBuildCache() async {
        _ = await runAction(resourceID: "build-cache", success: "Build cache purged.") {
            let path = projectPath + "/purge_build_cache"
            try await api.pagesOperationsPurgeBuildCache(
                accountID: accountID,
                projectName: projectName,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
            )
        }
    }

    func redeploy(_ deployment: CloudflarePagesDeployment) async {
        _ = await runAction(resourceID: deployment.id, success: "A new deployment was created from the latest build.") {
            _ = try await api.retryPagesDeployment(
                accountID: accountID,
                projectName: projectName,
                deploymentID: deployment.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: deployment.id)
            )
            project = try await api.pagesOperationsFetchProject(
                accountID: accountID,
                projectName: projectName
            )
        }
    }

    func deleteProject() async {
        _ = await runAction(resourceID: projectName, success: "Project deleted.") {
            try await api.pagesOperationsDeleteProject(
                accountID: accountID,
                projectName: projectName,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: projectPath)
            )
            didDeleteProject = true
        }
    }

    private func runAction(
        resourceID: String,
        success: String,
        operation: () async throws -> Void
    ) async -> Bool {
        isWorking = true
        workingResourceID = resourceID
        actionMessage = nil
        defer {
            isWorking = false
            workingResourceID = nil
        }

        do {
            try await operation()
            actionMessage = success
            actionFailed = false
            return true
        } catch is CancellationError {
            return false
        } catch {
            showError(error.localizedDescription)
            return false
        }
    }

    private func showError(_ message: String) {
        actionMessage = message
        actionFailed = true
    }

    private var projectPath: String {
        "/accounts/\(accountID)/pages/projects/\(projectName)"
    }

    private var domainsPath: String { projectPath + "/domains" }
    private func domainPath(_ name: String) -> String { domainsPath + "/\(name)" }
}

struct CloudflarePagesOperationsView: View {
    let api: CloudflareAPI
    let accountID: String
    let project: CloudflarePagesProject

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflarePagesOperationsViewModel
    @State private var selectedEnvironment: CloudflarePagesEnvironment = .production
    @State private var selectedDomain: CloudflarePagesCustomDomain?
    @State private var editDraft: CloudflarePagesProjectEditDraft?
    @State private var isAddingDomain = false
    @State private var pendingAction: CloudflarePagesOperationsPendingAction?

    init(api: CloudflareAPI, accountID: String, project: CloudflarePagesProject) {
        self.api = api
        self.accountID = accountID
        self.project = project
        _viewModel = State(
            initialValue: CloudflarePagesOperationsViewModel(
                api: api,
                accountID: accountID,
                project: project
            )
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            if viewModel.isLoading {
                CloudflareLoadingView()
            } else if let error = viewModel.loadError, viewModel.project == nil {
                CloudflareErrorView(message: error) {
                    Task { await viewModel.load() }
                }
            } else if let fullProject = viewModel.project {
                content(fullProject)
            }
        }
        .navigationTitle("Pages operations")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .sheet(item: $selectedDomain) { domain in
            CloudflarePagesDomainDetailSheet(
                api: api,
                accountID: accountID,
                projectName: viewModel.projectName,
                domain: domain
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $editDraft) { draft in
            CloudflarePagesProjectEditorSheet(draft: draft) { updated in
                let saved = await viewModel.save(updated)
                if saved { editDraft = nil }
                return saved ? nil : (viewModel.actionMessage ?? "Cloudflare could not save these settings.")
            }
        }
        .sheet(isPresented: $isAddingDomain) {
            CloudflarePagesAddDomainSheet { domain in
                let added = await viewModel.addDomain(domain)
                if added { isAddingDomain = false }
                return added ? nil : (viewModel.actionMessage ?? "Cloudflare could not add this domain.")
            }
            .presentationDetents([.height(330)])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            pendingAction?.title ?? "Confirm action",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { if !$0 { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let action = pendingAction {
                Button(action.confirmTitle, role: action.role) {
                    pendingAction = nil
                    Task { await perform(action) }
                }
                Button("Cancel", role: .cancel) { pendingAction = nil }
            }
        } message: {
            Text(pendingAction?.message ?? "")
        }
        .onChange(of: viewModel.didDeleteProject) { _, deleted in
            if deleted { dismiss() }
        }
        .tint(CloudflareStyle.orange)
    }

    private func content(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                projectHeader(fullProject)
                CloudflareWriteNotice()

                if let message = viewModel.actionMessage {
                    CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                }

                identityPanel(fullProject)
                buildPanel(fullProject)
                sourcePanel(fullProject)
                environmentPanel(fullProject)
                domainsPanel
                deploymentPanel(fullProject)
                maintenancePanel(fullProject)
            }
            .padding()
            .padding(.bottom, 28)
            .frame(maxWidth: horizontalSizeClass == .regular ? 880 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func projectHeader(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                ZStack {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                                startPoint: .bottomLeading,
                                endPoint: .topTrailing
                            )
                        )
                    Image(systemName: "point.3.filled.connected.trianglepath.dotted")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.black.opacity(0.82))
                }
                .frame(width: 48, height: 48)

                VStack(alignment: .leading, spacing: 5) {
                    Text(fullProject.name)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(fullProject.subdomain ?? "Cloudflare Pages")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.4))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                CloudflareStatusPill(
                    text: fullProject.latestDeployment?.displayStatus.uppercased() ?? "READY",
                    color: deploymentTint(fullProject.latestDeployment)
                )
            }

            HStack(spacing: 8) {
                if let framework = fullProject.framework, !framework.isEmpty {
                    projectBadge(
                        [framework, fullProject.frameworkVersion].compactMap { $0 }.joined(separator: " "),
                        icon: "shippingbox.fill"
                    )
                }
                projectBadge(fullProject.productionBranch ?? "No production branch", icon: "arrow.triangle.branch")
                if fullProject.usesFunctions == true {
                    projectBadge("Functions", icon: "function")
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func identityPanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Project identity", icon: "fingerprint")
            panelDivider
            CloudflareDetailRow(icon: "number", title: "Project ID", value: fullProject.id)
            CloudflareDetailRow(icon: "globe", title: "Pages subdomain", value: fullProject.subdomain ?? "Not assigned")
            CloudflareDetailRow(icon: "calendar", title: "Created", value: dateText(fullProject.createdDate))
            CloudflareDetailRow(
                icon: "square.stack.3d.up.fill",
                title: "Canonical deployment",
                value: deploymentLabel(fullProject.canonicalDeployment)
            )
            CloudflareDetailRow(
                icon: "bolt.horizontal.fill",
                title: "Production script",
                value: fullProject.productionScriptName ?? "Not provisioned"
            )
            CloudflareDetailRow(
                icon: "bolt.horizontal",
                title: "Preview script",
                value: fullProject.previewScriptName ?? "Not provisioned"
            )
        }
        .cloudflarePanel()
    }

    private func buildPanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Build pipeline", icon: "hammer.fill")
            panelDivider

            if let config = fullProject.buildConfig {
                CloudflareDetailRow(icon: "terminal.fill", title: "Build command", value: valueOrNotSet(config.buildCommand))
                CloudflareDetailRow(icon: "folder.fill", title: "Output directory", value: valueOrNotSet(config.destinationDirectory))
                CloudflareDetailRow(icon: "folder.badge.gearshape", title: "Root directory", value: valueOrNotSet(config.rootDirectory))
                CloudflareDetailRow(icon: "shippingbox.fill", title: "Build cache", value: yesNo(config.buildCaching))
                CloudflareDetailRow(icon: "chart.xyaxis.line", title: "Web Analytics tag", value: valueOrNotSet(config.webAnalyticsTag))
                CloudflareDetailRow(
                    icon: "key.fill",
                    title: "Analytics token",
                    value: config.webAnalyticsTokenConfigured ? "Configured · value hidden" : "Not configured",
                    valueColor: config.webAnalyticsTokenConfigured ? CloudflareStyle.green : .white.opacity(0.46)
                )
            } else {
                CloudflareEmptySection(icon: "hammer", title: "No build configuration", message: "This project may use direct uploads.")
            }
        }
        .cloudflarePanel()
    }

    private func sourcePanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Source control", icon: "arrow.triangle.branch")
            panelDivider

            if let source = fullProject.source, let config = source.config {
                CloudflareDetailRow(icon: "server.rack", title: "Provider", value: source.type?.uppercased() ?? "Unknown")
                CloudflareDetailRow(
                    icon: "chevron.left.forwardslash.chevron.right",
                    title: "Repository",
                    value: [config.owner, config.repositoryName].compactMap { $0 }.joined(separator: "/").nilIfEmpty ?? "Not set"
                )
                CloudflareDetailRow(icon: "person.crop.circle", title: "Owner ID", value: valueOrNotSet(config.ownerID))
                CloudflareDetailRow(icon: "number", title: "Repository ID", value: valueOrNotSet(config.repositoryID))
                CloudflareDetailRow(icon: "arrow.triangle.branch", title: "Source production branch", value: valueOrNotSet(config.productionBranch))
                CloudflareDetailRow(icon: "bolt.fill", title: "Legacy deployment switch", value: enabledText(config.deploymentsEnabled))
                CloudflareDetailRow(icon: "arrow.up.circle.fill", title: "Production deploys", value: enabledText(config.productionDeploymentsEnabled))
                CloudflareDetailRow(icon: "eye.fill", title: "Preview deploys", value: config.previewDeploymentSetting?.capitalized ?? "Not set")
                CloudflareDetailRow(icon: "bubble.left.fill", title: "Pull request comments", value: enabledText(config.pullRequestCommentsEnabled))
                CloudflareDetailRow(icon: "line.3.horizontal.decrease", title: "Preview branches", value: ruleText(includes: config.previewBranchIncludes, excludes: config.previewBranchExcludes))
                CloudflareDetailRow(icon: "point.topleft.down.to.point.bottomright.curvepath", title: "Path filters", value: ruleText(includes: config.pathIncludes, excludes: config.pathExcludes))
            } else {
                CloudflareEmptySection(icon: "link.badge.plus", title: "Direct upload project", message: "No Git repository is connected to this project.")
            }
        }
        .cloudflarePanel()
    }

    private func environmentPanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        let config = selectedEnvironment == .production
            ? fullProject.deploymentConfigs?.production
            : fullProject.deploymentConfigs?.preview

        return VStack(spacing: 0) {
            VStack(spacing: 12) {
                CloudflareSectionHeader(title: "Runtime switchboard", icon: "switch.2")

                Picker("Environment", selection: $selectedEnvironment) {
                    Label("Production", systemImage: "checkmark.seal.fill").tag(CloudflarePagesEnvironment.production)
                    Label("Preview", systemImage: "eye.fill").tag(CloudflarePagesEnvironment.preview)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 14)
                .padding(.bottom, 4)
            }
            panelDivider

            if let config {
                environmentCore(config)
                environmentVariables(config)
                environmentBindings(config)
            } else {
                CloudflareEmptySection(icon: "switch.2", title: "No environment configuration", message: "Cloudflare returned no \(selectedEnvironment.rawValue) runtime settings.")
            }
        }
        .cloudflarePanel(accentOpacity: selectedEnvironment == .production ? 0.045 : 0.075)
        .animation(.easeInOut(duration: 0.18), value: selectedEnvironment)
    }

    @ViewBuilder
    private func environmentCore(_ config: CloudflarePagesDeploymentConfiguration) -> some View {
        CloudflareDetailRow(icon: "calendar.badge.clock", title: "Compatibility date", value: valueOrNotSet(config.compatibilityDate))
        CloudflareDetailRow(icon: "flag.fill", title: "Compatibility flags", value: config.compatibilityFlags.isEmpty ? "None" : config.compatibilityFlags.joined(separator: ", "))
        CloudflareDetailRow(icon: "clock.arrow.circlepath", title: "Always latest date", value: yesNo(config.alwaysUseLatestCompatibilityDate))
        CloudflareDetailRow(icon: "shippingbox.fill", title: "Build image", value: config.buildImageMajorVersion.map { "Version \($0)" } ?? "Not set")
        CloudflareDetailRow(icon: "gauge.with.dots.needle.67percent", title: "Usage model", value: config.usageModel?.capitalized ?? "Standard")
        CloudflareDetailRow(icon: "exclamationmark.shield.fill", title: "Fail open", value: yesNo(config.failOpen))
        CloudflareDetailRow(icon: "gauge.with.dots.needle.33percent", title: "CPU limit", value: config.limits?.cpuMilliseconds.map { "\($0) ms" } ?? "Default")
        CloudflareDetailRow(icon: "location.fill", title: "Placement", value: config.placement?.mode?.capitalized ?? "Default")
        CloudflareDetailRow(icon: "doc.badge.gearshape.fill", title: "Wrangler config hash", value: valueOrNotSet(config.wranglerConfigHash))
    }

    @ViewBuilder
    private func environmentVariables(_ config: CloudflarePagesDeploymentConfiguration) -> some View {
        panelDivider
        CloudflareSectionHeader(title: "Environment variables", icon: "textformat.abc", count: config.environmentVariables.count)
        if config.environmentVariables.isEmpty {
            Text("No variables configured")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.32))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
        } else {
            ForEach(config.environmentVariables.keys.sorted(), id: \.self) { key in
                if let variable = config.environmentVariables[key] {
                    HStack(spacing: 11) {
                        Image(systemName: variable.isSecret ? "lock.fill" : "textformat")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(variable.isSecret ? CloudflareStyle.amber : CloudflareStyle.orange)
                            .frame(width: 30, height: 30)
                            .background((variable.isSecret ? CloudflareStyle.amber : CloudflareStyle.orange).opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(key)
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.82))
                            Text(variable.isSecret ? "Secret · value hidden" : "Plain text · value hidden")
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(.white.opacity(0.32))
                        }
                        Spacer()
                        CloudflareStatusPill(text: variable.valueConfigured ? "SET" : "EMPTY", color: variable.valueConfigured ? CloudflareStyle.green : .white.opacity(0.35))
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
        }
    }

    @ViewBuilder
    private func environmentBindings(_ config: CloudflarePagesDeploymentConfiguration) -> some View {
        panelDivider
        CloudflareSectionHeader(title: "Function bindings", icon: "link", count: config.bindingCount)
        if config.bindingGroups.isEmpty {
            Text("No resource bindings configured")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.32))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
        } else {
            ForEach(config.bindingGroups, id: \.name) { group in
                VStack(alignment: .leading, spacing: 7) {
                    Text(group.name.uppercased())
                        .font(.system(size: 9, weight: .semibold))
                        .tracking(0.7)
                        .foregroundStyle(CloudflareStyle.orange.opacity(0.8))
                    ForEach(group.values.keys.sorted(), id: \.self) { key in
                        if let reference = group.values[key] {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(key)
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.76))
                                Spacer(minLength: 8)
                                Text(reference.summary.nilIfEmpty ?? "Configured")
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.34))
                                    .lineLimit(2)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
        }
    }

    private var domainsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Custom domains",
                icon: "globe.americas.fill",
                count: viewModel.domains.count,
                actionTitle: "Add domain"
            ) {
                isAddingDomain = true
            }
            panelDivider

            if let error = viewModel.domainsError, viewModel.domains.isEmpty {
                CloudflareEmptySection(icon: "exclamationmark.triangle.fill", title: "Domains unavailable", message: error)
            } else if viewModel.domains.isEmpty {
                CloudflareEmptySection(icon: "globe.badge.chevron.backward", title: "No custom domains", message: "Add a hostname to start Cloudflare validation and certificate issuance.")
            } else {
                ForEach(viewModel.domains) { domain in
                    domainRow(domain)
                    if domain.id != viewModel.domains.last?.id { panelDivider.padding(.leading, 64) }
                }
            }
        }
        .cloudflarePanel()
    }

    private func domainRow(_ domain: CloudflarePagesCustomDomain) -> some View {
        HStack(spacing: 10) {
            Button {
                selectedDomain = domain
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: domain.isActive ? "checkmark.seal.fill" : "clock.badge.exclamationmark.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(domainTint(domain))
                        .frame(width: 36, height: 36)
                        .background(domainTint(domain).opacity(0.11))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(domain.name)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white.opacity(0.86))
                            .lineLimit(1)
                        Text(domain.validationData?.errorMessage ?? "Certificate: \(domain.certificateAuthority?.replacingOccurrences(of: "_", with: " ").capitalized ?? "pending")")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.white.opacity(0.34))
                            .lineLimit(1)
                    }
                    Spacer(minLength: 6)
                    CloudflareStatusPill(text: (domain.status ?? "unknown").uppercased(), color: domainTint(domain))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if viewModel.workingResourceID == domain.id {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Menu {
                    Button {
                        selectedDomain = domain
                    } label: {
                        Label("View details", systemImage: "info.circle")
                    }
                    Button {
                        Task { await viewModel.retryValidation(domain) }
                    } label: {
                        Label("Retry validation", systemImage: "arrow.clockwise")
                    }
                    Button(role: .destructive) {
                        pendingAction = .deleteDomain(domain)
                    } label: {
                        Label("Remove domain", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.44))
                        .frame(width: 34, height: 34)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }

    private func deploymentPanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        let preparation = api.pagesOperationsDirectUploadPreparation(
            accountID: accountID,
            projectName: fullProject.name
        )

        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Deploy a build", icon: "shippingbox.and.arrow.backward.fill")
            panelDivider

            if let latest = fullProject.latestDeployment {
                CloudflareDetailRow(icon: "clock.arrow.circlepath", title: "Latest deployment", value: deploymentLabel(latest))
                CloudflareDetailRow(icon: "globe", title: "Latest URL", value: valueOrNotSet(latest.url))
                CloudflareDetailRow(icon: "arrow.triangle.branch", title: "Latest source", value: [latest.branch, latest.commitHash.map { String($0.prefix(10)) }].compactMap { $0 }.joined(separator: " · ").nilIfEmpty ?? "Direct upload")
                CloudflareDetailRow(icon: "calendar", title: "Latest created", value: dateText(latest.createdDate))
                panelDivider
            }

            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "arrow.up.doc.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .frame(width: 40, height: 40)
                    .background(CloudflareStyle.orange.opacity(0.11))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    Text("Direct upload preparation")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white.opacity(0.84))
                    Text("Cloudflare requires a multipart manifest plus every hashed build file. The app prepares the exact endpoint; use Wrangler to package and upload the directory without corrupting binary assets.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.36))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(preparation.endpointPath)
                        .font(.system(size: 9, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.45))
                        .lineLimit(2)
                        .textSelection(.enabled)
                }
            }
            .padding(16)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 9) {
                    if let latestDeployment = fullProject.latestDeployment {
                        smallActionButton("Redeploy latest", icon: "arrow.clockwise") {
                            Task { await viewModel.redeploy(latestDeployment) }
                        }
                    }
                    smallActionButton("Copy endpoint", icon: "doc.on.doc") {
                        UIPasteboard.general.string = preparation.endpointPath
                    }
                    smallActionButton("Upload guide", icon: "arrow.up.right") {
                        if let url = URL(string: "https://developers.cloudflare.com/pages/get-started/direct-upload/") {
                            UIApplication.shared.open(url)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)

            panelDivider
            CloudflareDetailRow(icon: "doc.text.fill", title: "Required multipart parts", value: preparation.requiredParts.joined(separator: ", "))
            CloudflareDetailRow(icon: "plus.square.on.square", title: "Optional metadata", value: preparation.optionalParts.joined(separator: ", "))
        }
        .cloudflarePanel(accentOpacity: 0.055)
    }

    private func maintenancePanel(_ fullProject: CloudflarePagesOperationsProject) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Project controls", icon: "slider.horizontal.3")
            panelDivider

            VStack(spacing: 10) {
                operationRow(
                    icon: "slider.horizontal.3",
                    title: "Build and deploy settings",
                    message: canSafelyEdit(fullProject)
                        ? "Edit the production branch, build pipeline and Git automation."
                        : "Cloudflare did not return every editable value, so this form stays read-only to avoid overwriting defaults.",
                    isEnabled: canSafelyEdit(fullProject)
                ) {
                    editDraft = CloudflarePagesProjectEditDraft(project: fullProject)
                }

                operationRow(
                    icon: "trash.slash.fill",
                    title: "Purge build cache",
                    message: "Clear cached dependencies and build artifacts before the next deployment."
                ) {
                    pendingAction = .purgeCache
                }
            }
            .padding(14)

            panelDivider

            VStack(alignment: .leading, spacing: 12) {
                Label("Danger zone", systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.red)

                destructiveButton("Delete Pages project", icon: "trash.fill") {
                    pendingAction = .deleteProject(fullProject.name)
                }
            }
            .padding(16)
            .background(CloudflareStyle.red.opacity(0.035))
        }
        .cloudflarePanel()
    }

    private func operationRow(
        icon: String,
        title: String,
        message: String,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 11) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .frame(width: 36, height: 36)
                    .background(CloudflareStyle.orange.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white.opacity(0.82))
                    Text(message)
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(.white.opacity(0.32))
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.2))
            }
            .padding(11)
            .background(Color.white.opacity(0.035))
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(viewModel.isWorking || !isEnabled)
        .opacity(isEnabled ? 1 : 0.56)
    }

    private func canSafelyEdit(_ project: CloudflarePagesOperationsProject) -> Bool {
        guard project.productionBranch != nil,
              project.buildConfig != nil,
              project.buildConfig?.buildCaching != nil else { return false }
        guard let source = project.source else { return true }
        guard let config = source.config else { return false }
        return config.productionDeploymentsEnabled != nil
            && config.previewDeploymentSetting != nil
            && config.pullRequestCommentsEnabled != nil
    }

    private func projectBadge(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(.white.opacity(0.6))
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(Color.white.opacity(0.06))
            .clipShape(Capsule())
    }

    private func smallActionButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)
                .padding(.horizontal, 11)
                .padding(.vertical, 8)
                .background(CloudflareStyle.orange.opacity(0.09))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(CloudflareStyle.orange.opacity(0.14), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func destructiveButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(role: .destructive, action: action) {
            Label(title, systemImage: icon)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(CloudflareStyle.red)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(CloudflareStyle.red.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(CloudflareStyle.red.opacity(0.15), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(viewModel.isWorking)
    }

    private var panelDivider: some View {
        Divider().overlay(Color.white.opacity(0.06))
    }

    private func valueOrNotSet(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Not set"
    }

    private func dateText(_ date: Date?) -> String {
        date?.formatted(date: .abbreviated, time: .shortened) ?? "Not available"
    }

    private func yesNo(_ value: Bool?) -> String {
        switch value { case true: "Enabled"; case false: "Disabled"; case nil: "Not set" }
    }

    private func enabledText(_ value: Bool?) -> String { yesNo(value) }

    private func deploymentLabel(_ deployment: CloudflarePagesDeployment?) -> String {
        guard let deployment else { return "Not available" }
        return "\(deployment.shortID ?? String(deployment.id.prefix(12))) · \(deployment.displayStatus.capitalized)"
    }

    private func ruleText(includes: [String], excludes: [String]) -> String {
        let includeText = includes.isEmpty ? nil : "Include: \(includes.joined(separator: ", "))"
        let excludeText = excludes.isEmpty ? nil : "Exclude: \(excludes.joined(separator: ", "))"
        return [includeText, excludeText].compactMap { $0 }.joined(separator: " · ").nilIfEmpty ?? "No filters"
    }

    private func deploymentTint(_ deployment: CloudflarePagesDeployment?) -> Color {
        switch deployment?.displayStatus.lowercased() {
        case "success", "active": CloudflareStyle.green
        case "failure", "failed", "error": CloudflareStyle.red
        default: CloudflareStyle.amber
        }
    }

    private func domainTint(_ domain: CloudflarePagesCustomDomain) -> Color {
        switch domain.status?.lowercased() {
        case "active": CloudflareStyle.green
        case "error", "blocked", "deactivated": CloudflareStyle.red
        default: CloudflareStyle.amber
        }
    }

    private func perform(_ action: CloudflarePagesOperationsPendingAction) async {
        switch action {
        case .purgeCache:
            await viewModel.purgeBuildCache()
        case .deleteDomain(let domain):
            await viewModel.deleteDomain(domain)
        case .deleteProject:
            await viewModel.deleteProject()
        }
    }
}

// MARK: - Project editor

nonisolated struct CloudflarePagesProjectEditDraft: Identifiable, Sendable {
    let id = UUID()
    var productionBranch: String
    var buildCommand: String
    var destinationDirectory: String
    var rootDirectory: String
    var buildCaching: Bool
    var sourceType: String?
    var sourceOwner: String?
    var sourceOwnerID: String?
    var sourceRepositoryID: String?
    var sourceRepositoryName: String?
    var productionDeploymentsEnabled: Bool
    var previewDeploymentSetting: String
    var pullRequestCommentsEnabled: Bool
    var previewBranchIncludes: String
    var previewBranchExcludes: String
    var pathIncludes: String
    var pathExcludes: String

    init(project: CloudflarePagesOperationsProject) {
        let config = project.source?.config
        productionBranch = project.productionBranch ?? config?.productionBranch ?? ""
        buildCommand = project.buildConfig?.buildCommand ?? ""
        destinationDirectory = project.buildConfig?.destinationDirectory ?? ""
        rootDirectory = project.buildConfig?.rootDirectory ?? ""
        buildCaching = project.buildConfig?.buildCaching ?? false
        sourceType = project.source?.type
        sourceOwner = config?.owner
        sourceOwnerID = config?.ownerID
        sourceRepositoryID = config?.repositoryID
        sourceRepositoryName = config?.repositoryName
        productionDeploymentsEnabled = config?.productionDeploymentsEnabled ?? false
        previewDeploymentSetting = config?.previewDeploymentSetting ?? "none"
        pullRequestCommentsEnabled = config?.pullRequestCommentsEnabled ?? false
        previewBranchIncludes = config?.previewBranchIncludes.joined(separator: ", ") ?? ""
        previewBranchExcludes = config?.previewBranchExcludes.joined(separator: ", ") ?? ""
        pathIncludes = config?.pathIncludes.joined(separator: ", ") ?? ""
        pathExcludes = config?.pathExcludes.joined(separator: ", ") ?? ""
    }

    var request: CloudflarePagesProjectUpdateRequest {
        let source: CloudflarePagesProjectUpdateRequest.Source?
        if let sourceType {
            source = .init(
                type: sourceType,
                config: .init(
                    owner: sourceOwner,
                    ownerID: sourceOwnerID,
                    repositoryID: sourceRepositoryID,
                    repositoryName: sourceRepositoryName,
                    productionBranch: productionBranch.trimmed,
                    productionDeploymentsEnabled: productionDeploymentsEnabled,
                    previewDeploymentSetting: previewDeploymentSetting,
                    previewBranchIncludes: list(previewBranchIncludes),
                    previewBranchExcludes: list(previewBranchExcludes),
                    pathIncludes: list(pathIncludes),
                    pathExcludes: list(pathExcludes),
                    pullRequestCommentsEnabled: pullRequestCommentsEnabled
                )
            )
        } else {
            source = nil
        }

        return .init(
            productionBranch: productionBranch.trimmed,
            buildConfig: .init(
                buildCommand: buildCommand.trimmed,
                destinationDirectory: destinationDirectory.trimmed,
                rootDirectory: rootDirectory.trimmed,
                buildCaching: buildCaching
            ),
            source: source
        )
    }

    private func list(_ value: String) -> [String] {
        value
            .components(separatedBy: CharacterSet(charactersIn: ",\n"))
            .map(\.trimmed)
            .filter { !$0.isEmpty }
    }
}

private struct CloudflarePagesProjectEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var draft: CloudflarePagesProjectEditDraft
    @State private var isSaving = false
    @State private var errorMessage: String?
    let save: (CloudflarePagesProjectEditDraft) async -> String?

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        editorSection("Build pipeline", icon: "hammer.fill") {
                            editorField("Production branch", text: $draft.productionBranch, icon: "arrow.triangle.branch")
                            editorField("Build command", text: $draft.buildCommand, icon: "terminal.fill")
                            editorField("Output directory", text: $draft.destinationDirectory, icon: "folder.fill")
                            editorField("Root directory", text: $draft.rootDirectory, icon: "folder.badge.gearshape")
                            Toggle("Build caching", isOn: $draft.buildCaching)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white.opacity(0.78))
                                .tint(CloudflareStyle.orange)
                                .padding(14)
                        }

                        if draft.sourceType != nil {
                            editorSection("Git automation", icon: "arrow.triangle.branch") {
                                Toggle("Production deployments", isOn: $draft.productionDeploymentsEnabled)
                                    .editorToggleStyle()
                                Picker("Preview deployments", selection: $draft.previewDeploymentSetting) {
                                    Text("All branches").tag("all")
                                    Text("Disabled").tag("none")
                                    Text("Custom rules").tag("custom")
                                }
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white.opacity(0.78))
                                .padding(14)
                                Toggle("Pull request comments", isOn: $draft.pullRequestCommentsEnabled)
                                    .editorToggleStyle()

                                if draft.previewDeploymentSetting == "custom" {
                                    editorField("Preview branch includes", text: $draft.previewBranchIncludes, icon: "plus.circle")
                                    editorField("Preview branch excludes", text: $draft.previewBranchExcludes, icon: "minus.circle")
                                }
                                editorField("Path includes", text: $draft.pathIncludes, icon: "plus.forwardslash.minus")
                                editorField("Path excludes", text: $draft.pathExcludes, icon: "minus.forwardslash.plus")
                            }
                        }

                        Text("Separate multiple branches or paths with commas. Secret variables and Analytics credentials are never sent by this editor, so existing sensitive values remain untouched.")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.white.opacity(0.34))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)

                        if let errorMessage {
                            CloudflareActionResultBanner(message: errorMessage, isError: true)
                        }
                    }
                    .padding()
                }
            }
            .navigationTitle("Project settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        isSaving = true
                        Task {
                            errorMessage = await save(draft)
                            isSaving = false
                        }
                    } label: {
                        if isSaving { ProgressView().controlSize(.small) } else { Text("Save") }
                    }
                    .fontWeight(.bold)
                    .disabled(isSaving || draft.productionBranch.trimmed.isEmpty)
                }
            }
        }
        .interactiveDismissDisabled(isSaving)
    }

    private func editorSection<Content: View>(
        _ title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon)
            Divider().overlay(Color.white.opacity(0.06))
            content()
        }
        .cloudflarePanel()
    }

    private func editorField(_ title: String, text: Binding<String>, icon: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.white.opacity(0.32))
                .frame(width: 18)
            TextField(title, text: text, axis: .vertical)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white.opacity(0.82))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .lineLimit(1...3)
        }
        .padding(14)
    }
}

private extension View {
    func editorToggleStyle() -> some View {
        font(.system(size: 12, weight: .bold))
            .foregroundStyle(.white.opacity(0.78))
            .tint(CloudflareStyle.orange)
            .padding(14)
    }
}

// MARK: - Domain sheets

private struct CloudflarePagesAddDomainSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var domain = ""
    @State private var isAdding = false
    @State private var errorMessage: String?
    let add: (String) async -> String?

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 16) {
                    Label("Connect a hostname", systemImage: "globe.badge.chevron.backward")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                    Text("Cloudflare will check DNS ownership, validate the hostname and provision its certificate.")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.4))
                        .fixedSize(horizontal: false, vertical: true)

                    TextField("www.example.com", text: $domain)
                        .font(.system(size: 14, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .padding(14)
                        .background(Color.white.opacity(0.06))
                        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))

                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }

                    Button {
                        isAdding = true
                        Task {
                            errorMessage = await add(domain)
                            isAdding = false
                        }
                    } label: {
                        HStack {
                            if isAdding { ProgressView().tint(.black) }
                            Text(isAdding ? "Adding domain…" : "Add domain")
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(CloudflareStyle.orange)
                        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(isAdding || domain.trimmed.isEmpty)

                    Spacer()
                }
                .padding(20)
            }
            .navigationTitle("Custom domain")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(isAdding)
                }
            }
        }
        .interactiveDismissDisabled(isAdding)
    }
}

private struct CloudflarePagesDomainDetailSheet: View {
    let api: CloudflareAPI
    let accountID: String
    let projectName: String
    let domain: CloudflarePagesCustomDomain

    @State private var detail: CloudflarePagesCustomDomain?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                ScrollView {
                    let current = detail ?? domain
                    VStack(spacing: 16) {
                        domainHero(current)
                        if let error {
                            CloudflareActionResultBanner(message: error, isError: true)
                        }
                        domainMetadata(current)
                        validationPanel(current)
                    }
                    .padding()
                }
            }
            .navigationTitle(domain.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .task { await load() }
        }
    }

    private func load() async {
        do {
            detail = try await api.pagesOperationsFetchDomain(
                accountID: accountID,
                projectName: projectName,
                domainName: domain.name
            )
            error = nil
        } catch is CancellationError {
            return
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func domainHero(_ current: CloudflarePagesCustomDomain) -> some View {
        HStack(spacing: 13) {
            Image(systemName: current.isActive ? "checkmark.seal.fill" : "globe.badge.chevron.backward")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(current.isActive ? CloudflareStyle.green : CloudflareStyle.amber)
                .frame(width: 46, height: 46)
                .background((current.isActive ? CloudflareStyle.green : CloudflareStyle.amber).opacity(0.11))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(current.name)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                Text((current.status ?? "unknown").capitalized)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white.opacity(0.4))
            }
            Spacer()
        }
        .padding(16)
        .cloudflarePanel(accentOpacity: 0.06)
    }

    private func domainMetadata(_ current: CloudflarePagesCustomDomain) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Domain", icon: "info.circle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "number", title: "Domain ID", value: current.domainID ?? current.id)
            CloudflareDetailRow(icon: "number", title: "Zone tag", value: current.zoneTag ?? "Not linked")
            CloudflareDetailRow(icon: "checkmark.shield.fill", title: "Certificate authority", value: current.certificateAuthority?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Pending")
            CloudflareDetailRow(icon: "calendar", title: "Created", value: current.createdDate?.formatted(date: .abbreviated, time: .shortened) ?? "Not available")
        }
        .cloudflarePanel()
    }

    private func validationPanel(_ current: CloudflarePagesCustomDomain) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Validation", icon: "checkmark.shield.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "network", title: "Method", value: current.validationData?.method?.uppercased() ?? "Not assigned")
            CloudflareDetailRow(icon: "checkmark.circle", title: "Validation status", value: current.validationData?.status?.capitalized ?? "Pending")
            CloudflareDetailRow(icon: "checkmark.seal", title: "Verification status", value: current.verificationData?.status?.capitalized ?? "Pending")
            if let txtName = current.validationData?.txtName {
                CloudflareDetailRow(icon: "text.quote", title: "TXT name", value: txtName)
            }
            if let txtValue = current.validationData?.txtValue {
                CloudflareDetailRow(icon: "textformat.abc", title: "TXT value", value: txtValue)
            }
            if let message = current.validationData?.errorMessage ?? current.verificationData?.errorMessage {
                CloudflareDetailRow(icon: "exclamationmark.triangle.fill", title: "Cloudflare message", value: message, valueColor: CloudflareStyle.red)
            }
        }
        .cloudflarePanel()
    }
}

private enum CloudflarePagesOperationsPendingAction: Identifiable {
    case purgeCache
    case deleteDomain(CloudflarePagesCustomDomain)
    case deleteProject(String)

    var id: String {
        switch self {
        case .purgeCache: "purge-cache"
        case .deleteDomain(let domain): "delete-domain-\(domain.id)"
        case .deleteProject(let name): "delete-project-\(name)"
        }
    }

    var title: String {
        switch self {
        case .purgeCache: "Purge the build cache?"
        case .deleteDomain(let domain): "Remove \(domain.name)?"
        case .deleteProject: "Delete this Pages project?"
        }
    }

    var message: String {
        switch self {
        case .purgeCache:
            "The next build will recreate every cached dependency and artifact. Existing deployments stay online."
        case .deleteDomain(let domain):
            "Cloudflare will detach \(domain.name) and stop serving it from this project."
        case .deleteProject(let name):
            "\(name), its deployments and Pages configuration will be permanently removed. This cannot be undone."
        }
    }

    var confirmTitle: String {
        switch self {
        case .purgeCache: "Purge Cache"
        case .deleteDomain: "Remove Domain"
        case .deleteProject: "Delete Project"
        }
    }

    var role: ButtonRole? {
        switch self {
        case .purgeCache: nil
        case .deleteDomain, .deleteProject: .destructive
        }
    }
}

private extension String {
    nonisolated var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    nonisolated var nilIfEmpty: String? { isEmpty ? nil : self }
}
