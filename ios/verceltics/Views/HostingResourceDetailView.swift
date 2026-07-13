import SwiftUI

@Observable
@MainActor
final class HostingResourceDetailViewModel {
    let api: HostingProviderAPI
    var deployments: [HostingDeployment] = []
    var isLoading = true
    var isActing = false
    var error: String?
    var successMessage: String?

    init(account: VercelAccount) { api = HostingProviderAPI(account: account) }

    func load(resource: HostingResource) async {
        isLoading = true
        error = nil
        do { deployments = try await api.fetchDeployments(for: resource) }
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
            await load(resource: resource)
        } catch { self.error = error.localizedDescription }
        isActing = false
    }
}

struct HostingResourceDetailView: View {
    let account: VercelAccount
    let resource: HostingResource

    @State private var viewModel: HostingResourceDetailViewModel
    @State private var showActionConfirmation = false

    init(account: VercelAccount, resource: HostingResource) {
        self.account = account
        self.resource = resource
        _viewModel = State(initialValue: HostingResourceDetailViewModel(account: account))
    }

    private var provider: AccountProvider { account.provider }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 16) {
                    header
                    metadata

                    if let message = viewModel.successMessage {
                        Label(message, systemImage: "checkmark.circle.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.green)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(15)
                            .providerPanel(accent: .green)
                    }
                    if let error = viewModel.error {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(15)
                            .providerPanel(accent: .orange)
                    }

                    sectionHeader
                    if viewModel.isLoading {
                        ProgressView().tint(provider.accentColor).padding(36)
                    } else if viewModel.deployments.isEmpty {
                        Text("No deployments, releases, jobs, or Machines were returned.")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.4))
                            .padding(28)
                            .frame(maxWidth: .infinity)
                            .providerPanel(accent: provider.accentColor)
                    } else {
                        ForEach(viewModel.deployments) { deployment in deploymentRow(deployment) }
                    }
                    Spacer().frame(height: 80)
                }
                .padding(16)
            }
        }
        .navigationTitle(resource.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load(resource: resource) }
        .refreshable { await viewModel.load(resource: resource) }
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
                Image(systemName: provider.systemImage)
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundStyle(provider.accentColor)
                    .frame(width: 58, height: 58)
                    .background(provider.accentColor.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 5) {
                    Text(resource.name).font(.system(size: 20, weight: .heavy)).lineLimit(2)
                    Text([resource.kind, resource.region].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.42))
                }
                Spacer()
                if let status = resource.status {
                    Text(status.uppercased())
                        .font(.system(size: 8, weight: .heavy))
                        .foregroundStyle(statusColor(status))
                }
            }

            HStack(spacing: 10) {
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
        .providerPanel(accent: provider.accentColor)
    }

    private var metadata: some View {
        NavigationLink {
            HostingAPIExplorerView(account: account, suggestedResource: resource)
        } label: {
            HStack {
                Image(systemName: "terminal.fill").foregroundStyle(provider.accentColor)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Full API Explorer").font(.system(size: 13, weight: .bold))
                    Text("Read or write any provider API route").font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.38))
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.white.opacity(0.2))
            }
            .foregroundStyle(.white)
            .padding(16)
            .providerPanel(accent: provider.accentColor)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private var sectionHeader: some View {
        HStack {
            Text(historyTitle.uppercased())
                .font(.system(size: 10, weight: .heavy)).tracking(1.4).foregroundStyle(.white.opacity(0.38))
            Spacer()
            Text(viewModel.deployments.count.formatted()).font(.system(size: 10, weight: .heavy)).foregroundStyle(provider.accentColor)
        }
    }

    private func deploymentRow(_ deployment: HostingDeployment) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(deployment.title).font(.system(size: 13, weight: .bold)).lineLimit(2)
                Spacer()
                Text(deployment.status.uppercased())
                    .font(.system(size: 8, weight: .heavy))
                    .foregroundStyle(statusColor(deployment.status))
            }
            if let message = deployment.commitMessage, !message.isEmpty {
                Text(message).font(.system(size: 10, weight: .medium)).foregroundStyle(.white.opacity(0.4)).lineLimit(3)
            }
            HStack(spacing: 8) {
                if let branch = deployment.branch, !branch.isEmpty { Label(branch, systemImage: "arrow.triangle.branch").lineLimit(1) }
                if let date = deployment.createdAt { Text(date.formatted(date: .abbreviated, time: .shortened)) }
            }
            .font(.system(size: 9, weight: .semibold))
            .foregroundStyle(.white.opacity(0.28))
        }
        .padding(15)
        .providerPanel(accent: provider.accentColor)
    }

    private func actionLabel(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.system(size: 10, weight: .bold))
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(height: 42)
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
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
