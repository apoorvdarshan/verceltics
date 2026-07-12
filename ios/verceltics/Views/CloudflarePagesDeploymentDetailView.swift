import SwiftUI

@Observable
@MainActor
final class CloudflarePagesDeploymentDetailViewModel {
    let api: CloudflareAPI
    let accountID: String
    let projectName: String
    let initialDeployment: CloudflarePagesDeployment

    var deployment: CloudflarePagesDeployment
    var logs: [CloudflarePagesDeploymentLog] = []
    var isLoading = true
    var detailError: String?
    var logsError: String?

    init(api: CloudflareAPI, accountID: String, projectName: String, deployment: CloudflarePagesDeployment) {
        self.api = api
        self.accountID = accountID
        self.projectName = projectName
        initialDeployment = deployment
        self.deployment = deployment
    }

    func load() async {
        isLoading = true
        detailError = nil
        logsError = nil

        async let detailResult = capture {
            try await api.fetchPagesDeployment(
                accountID: accountID,
                projectName: projectName,
                deploymentID: initialDeployment.id
            )
        }
        async let logsResult = capture {
            try await api.fetchPagesDeploymentLogs(
                accountID: accountID,
                projectName: projectName,
                deploymentID: initialDeployment.id
            )
        }

        switch await detailResult {
        case .success(let value): deployment = value
        case .failure(let error): detailError = error.localizedDescription
        }
        switch await logsResult {
        case .success(let value): logs = value
        case .failure(let error): logsError = error.localizedDescription
        }
        isLoading = false
    }

    private func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }
}

struct CloudflarePagesDeploymentDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let projectName: String
    let deployment: CloudflarePagesDeployment

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflarePagesDeploymentDetailViewModel

    init(api: CloudflareAPI, accountID: String, projectName: String, deployment: CloudflarePagesDeployment) {
        self.api = api
        self.accountID = accountID
        self.projectName = projectName
        self.deployment = deployment
        _viewModel = State(
            wrappedValue: CloudflarePagesDeploymentDetailViewModel(
                api: api,
                accountID: accountID,
                projectName: projectName,
                deployment: deployment
            )
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    header
                    identityPanel
                    sourcePanel
                    stagesPanel
                    configurationPanel
                    logsPanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 850 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(viewModel.deployment.shortID ?? "Deployment")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .tint(CloudflareStyle.orange)
    }

    private var header: some View {
        let item = viewModel.deployment
        return VStack(alignment: .leading, spacing: 15) {
            HStack(spacing: 13) {
                Image(systemName: item.isSkipped == true ? "forward.fill" : "square.stack.3d.up.fill")
                    .font(.system(size: 19, weight: .heavy))
                    .foregroundStyle(.black.opacity(0.82))
                    .frame(width: 46, height: 46)
                    .background(
                        LinearGradient(
                            colors: [statusColor, CloudflareStyle.amber],
                            startPoint: .bottomLeading,
                            endPoint: .topTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.shortID ?? String(item.id.prefix(12)))
                        .font(.system(size: 18, weight: .heavy, design: .monospaced))
                        .foregroundStyle(.white)
                    Text(item.branch ?? item.url ?? projectName)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
                CloudflareStatusPill(text: item.displayStatus.uppercased(), color: statusColor)
            }

            if let rawURL = item.url,
               let url = URL(string: rawURL.hasPrefix("http") ? rawURL : "https://\(rawURL)") {
                Button { UIApplication.shared.open(url) } label: {
                    Label("Open deployment", systemImage: "arrow.up.right")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white.opacity(0.78))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(Color.white.opacity(0.07))
                        .clipShape(Capsule())
                }
                .buttonStyle(PressScaleButtonStyle())
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var identityPanel: some View {
        let item = viewModel.deployment
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Deployment", icon: "info.circle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "number", title: "Deployment ID", value: item.id)
            CloudflareDetailRow(icon: "folder", title: "Project", value: item.projectName ?? projectName)
            CloudflareDetailRow(icon: "shippingbox", title: "Environment", value: item.environment?.rawValue.capitalized ?? "Unknown")
            CloudflareDetailRow(icon: "link", title: "URL", value: item.url ?? "Not returned")
            CloudflareDetailRow(icon: "point.3.connected.trianglepath.dotted", title: "Aliases", value: item.aliases.isEmpty ? "None" : item.aliases.joined(separator: ", "))
            CloudflareDetailRow(icon: "function", title: "Functions", value: item.usesFunctions == true ? "Used" : "Not detected")
            CloudflareDetailRow(icon: "calendar", title: "Created", value: formatted(item.createdDate))
            CloudflareDetailRow(icon: "clock", title: "Modified", value: formatted(item.modifiedDate))
            if let detailError = viewModel.detailError {
                CloudflareDetailRow(icon: "exclamationmark.triangle", title: "Refresh warning", value: detailError, valueColor: CloudflareStyle.amber)
            }
        }
        .cloudflarePanel()
    }

    private var sourcePanel: some View {
        let item = viewModel.deployment
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Source", icon: "arrow.triangle.branch")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "bolt", title: "Trigger", value: item.deploymentTrigger?.type ?? "Unknown")
            CloudflareDetailRow(icon: "arrow.triangle.branch", title: "Branch", value: item.branch ?? "Not returned")
            CloudflareDetailRow(icon: "number", title: "Commit", value: item.commitHash ?? "Not returned")
            CloudflareDetailRow(icon: "text.bubble", title: "Message", value: item.commitMessage ?? "Not returned")
            CloudflareDetailRow(
                icon: "pencil.and.outline",
                title: "Dirty working tree",
                value: item.deploymentTrigger?.metadata?.commitDirty == true ? "Yes" : "No"
            )
        }
        .cloudflarePanel()
    }

    private var stagesPanel: some View {
        let stages = viewModel.deployment.stages.isEmpty
            ? [viewModel.deployment.latestStage].compactMap { $0 }
            : viewModel.deployment.stages
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Build stages", icon: "list.bullet.rectangle.fill", count: stages.count)
            Divider().overlay(Color.white.opacity(0.06))
            if stages.isEmpty {
                CloudflareEmptySection(icon: "list.bullet", title: "No stages returned", message: "Cloudflare did not include build-stage history for this deployment.")
            } else {
                ForEach(Array(stages.enumerated()), id: \.offset) { index, stage in
                    CloudflareResourceRow(
                        icon: stageIcon(stage.status),
                        title: stage.name?.capitalized ?? "Stage \(index + 1)",
                        subtitle: stageTime(stage),
                        tint: stageColor(stage.status)
                    ) {
                        CloudflareStatusPill(text: (stage.status ?? "unknown").uppercased(), color: stageColor(stage.status))
                    }
                    if index < stages.count - 1 {
                        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private var configurationPanel: some View {
        let item = viewModel.deployment
        let config = item.buildConfig
        let environmentKeys = item.environmentVariables.keys.sorted()
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Build configuration", icon: "hammer.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "terminal", title: "Build command", value: config?.buildCommand ?? "Not set")
            CloudflareDetailRow(icon: "folder", title: "Destination", value: config?.destinationDirectory ?? "Not set")
            CloudflareDetailRow(icon: "folder.badge.gearshape", title: "Root directory", value: config?.rootDirectory ?? "Not set")
            CloudflareDetailRow(icon: "externaldrive.fill", title: "Build cache", value: config?.buildCaching == true ? "Enabled" : "Off")
            CloudflareDetailRow(icon: "chart.bar.fill", title: "Web Analytics tag", value: config?.webAnalyticsTag ?? "Not set")
            CloudflareDetailRow(
                icon: "key.fill",
                title: "Environment keys",
                value: environmentKeys.isEmpty ? "None returned" : environmentKeys.joined(separator: ", ")
            )
        }
        .cloudflarePanel()
    }

    private var logsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Deployment logs", icon: "doc.text.fill", count: viewModel.logs.count)
            Divider().overlay(Color.white.opacity(0.06))
            if viewModel.isLoading {
                ProgressView().tint(CloudflareStyle.orange).padding(.vertical, 32)
                    .frame(maxWidth: .infinity)
            } else if let logsError = viewModel.logsError {
                CloudflareEmptySection(icon: "exclamationmark.triangle.fill", title: "Logs unavailable", message: logsError)
            } else if viewModel.logs.isEmpty {
                CloudflareEmptySection(icon: "doc.text", title: "No logs returned", message: "This deployment has no build-history logs available.")
            } else {
                ForEach(viewModel.logs) { log in
                    VStack(alignment: .leading, spacing: 5) {
                        if let date = log.date {
                            Text(date.formatted(date: .omitted, time: .standard))
                                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                .foregroundStyle(CloudflareStyle.orange.opacity(0.7))
                        }
                        Text(log.line)
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.62))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    Divider().overlay(Color.white.opacity(0.045)).padding(.leading, 16)
                }
            }
        }
        .cloudflarePanel()
    }

    private var statusColor: Color { stageColor(viewModel.deployment.displayStatus) }

    private func stageColor(_ status: String?) -> Color {
        switch status?.lowercased() {
        case "success": CloudflareStyle.green
        case "active", "queued", "idle": CloudflareStyle.amber
        case "failure", "canceled": CloudflareStyle.red
        default: CloudflareStyle.orange
        }
    }

    private func stageIcon(_ status: String?) -> String {
        switch status?.lowercased() {
        case "success": "checkmark.circle.fill"
        case "failure", "canceled": "xmark.circle.fill"
        default: "clock.fill"
        }
    }

    private func stageTime(_ stage: CloudflarePagesDeployment.Stage) -> String? {
        let start = CloudflareDateParser.date(from: stage.startedOn)
        let end = CloudflareDateParser.date(from: stage.endedOn)
        if let start, let end {
            return Duration.seconds(end.timeIntervalSince(start)).formatted(.units(allowed: [.minutes, .seconds], width: .abbreviated))
        }
        return start?.formatted(date: .omitted, time: .shortened)
    }

    private func formatted(_ date: Date?) -> String {
        date?.formatted(date: .abbreviated, time: .shortened) ?? "Not returned"
    }
}
