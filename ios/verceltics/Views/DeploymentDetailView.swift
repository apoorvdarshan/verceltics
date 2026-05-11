import SwiftUI

@Observable
@MainActor
final class DeploymentDetailViewModel {
    var events: [DeploymentEvent] = []
    var isLoadingEvents = true
    var error: String?

    func load(token: String, deployment: RecentDeployment, teamId: String?) async {
        guard let idOrUrl = deployment.uid ?? deployment.url else {
            isLoadingEvents = false
            error = "This deployment does not include an event identifier."
            return
        }

        isLoadingEvents = true
        error = nil

        do {
            events = try await VercelAPI(token: token)
                .fetchDeploymentEvents(idOrUrl: idOrUrl, teamId: teamId)
        } catch {
            self.error = error.localizedDescription
        }

        isLoadingEvents = false
    }
}

struct DeploymentDetailView: View {
    let project: Project
    let deployment: RecentDeployment

    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var vm = DeploymentDetailViewModel()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    headerCard
                    metadataCard
                    eventsCard
                }
                .padding()
                .frame(maxWidth: hSize == .regular ? 820 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Deployment")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            await loadEvents()
        }
        .refreshable {
            await loadEvents()
        }
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 12) {
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                VStack(alignment: .leading, spacing: 5) {
                    Text(project.name)
                        .font(.system(size: 23, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)

                    Text(deployment.url ?? project.primaryDomain ?? "Deployment")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.42))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                statusPill(deployment.displayState)
            }

            HStack(spacing: 10) {
                if let url = deployment.url.flatMap({ URL(string: "https://\($0)") }) {
                    openButton("Open", icon: "arrow.up.right", url: url)
                }

                if let inspectorURL = deployment.inspectorUrl.flatMap(URL.init(string:)) {
                    openButton("Inspect", icon: "magnifyingglass", url: inspectorURL)
                }
            }
        }
        .padding(18)
        .deploymentPanel()
    }

    private var metadataCard: some View {
        infoPanel(title: "Details", icon: "shippingbox.fill") {
            VStack(spacing: 0) {
                detailRow(icon: "scope", title: "Target", value: deployment.displayTarget)

                detailRow(
                    icon: "clock.fill",
                    title: "Created",
                    value: deployment.date?.formatted(.relative(presentation: .named)) ?? "Unknown"
                )

                if let creator = deployment.creator?.username ?? deployment.creator?.email {
                    detailRow(icon: "person.fill", title: "Creator", value: creator)
                }

                if let repo = repositoryName {
                    detailRow(icon: "chevron.left.forwardslash.chevron.right", title: "Repository", value: repo)
                }

                if let branch = deployment.meta?.githubCommitRef {
                    detailRow(icon: "arrow.triangle.branch", title: "Branch", value: branch)
                }

                if let sha = deployment.meta?.githubCommitSha {
                    detailRow(icon: "number", title: "Commit", value: String(sha.prefix(12)))
                }

                if let message = deployment.meta?.githubCommitMessage {
                    detailRow(icon: "text.bubble.fill", title: "Message", value: message)
                }
            }
        }
    }

    private var eventsCard: some View {
        infoPanel(title: "Build Events", icon: "terminal.fill") {
            if vm.isLoadingEvents {
                VStack(spacing: 12) {
                    ProgressView()
                        .tint(.white.opacity(0.7))
                    Text("Loading events")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.35))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 32)
            } else if let error = vm.error {
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(.white.opacity(0.25))
                    Text(error)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.35))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 22)
                .padding(.vertical, 28)
            } else if vm.events.isEmpty {
                Text("No build events returned")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.3))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 30)
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(vm.events.prefix(80)) { event in
                        eventRow(event)
                    }
                }
            }
        }
    }

    private var repositoryName: String? {
        guard let org = deployment.meta?.githubOrg,
              let repo = deployment.meta?.githubRepo else { return nil }
        return "\(org)/\(repo)"
    }

    private func loadEvents() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token, deployment: deployment, teamId: project.teamId)
    }

    private func infoPanel<Content: View>(
        title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(.blue)
                    .frame(width: 22, height: 22)
                    .background(Color.blue.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

            Divider().overlay(Color.white.opacity(0.06))

            content()
        }
        .deploymentPanel()
    }

    private func detailRow(icon: String, title: String, value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.35))
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.35))
                    .textCase(.uppercase)
                    .tracking(0.7)

                Text(value)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.78))
                    .lineLimit(2)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 8)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }

    private func eventRow(_ event: DeploymentEvent) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(eventColor(event))
                .frame(width: 7, height: 7)
                .padding(.top, 7)
                .shadow(color: eventColor(event).opacity(0.45), radius: 3)

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 7) {
                    Text(event.type)
                        .font(.system(size: 10, weight: .heavy))
                        .foregroundStyle(eventColor(event))
                        .textCase(.uppercase)
                        .lineLimit(1)

                    Text(event.date.formatted(.dateTime.hour().minute().second()))
                        .font(.system(size: 10, weight: .semibold).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.32))
                }

                Text(event.message)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(4)
                    .textSelection(.enabled)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }

    private func openButton(_ title: String, icon: String, url: URL) -> some View {
        Button {
            UIApplication.shared.open(url)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .heavy))
                Text(title)
                    .font(.system(size: 13, weight: .bold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 13)
            .padding(.vertical, 9)
            .background(Color.white.opacity(0.08))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func statusPill(_ state: String) -> some View {
        Text(state.capitalized)
            .font(.system(size: 10, weight: .heavy))
            .foregroundStyle(statusColor(state))
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(statusColor(state).opacity(0.12))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(statusColor(state).opacity(0.20), lineWidth: 0.5))
    }

    private func eventColor(_ event: DeploymentEvent) -> Color {
        if let statusCode = event.statusCode, statusCode.hasPrefix("5") {
            return Color(red: 1.0, green: 0.35, blue: 0.35)
        }

        switch event.type.uppercased() {
        case "ERROR", "FATAL", "WARNING":
            return Color(red: 1.0, green: 0.35, blue: 0.35)
        case "READY", "DONE", "COMPLETE":
            return Color(red: 0.30, green: 0.85, blue: 0.55)
        case "BUILDING", "INITIALIZING", "COMMAND":
            return .blue
        default:
            return .white.opacity(0.45)
        }
    }

    private func statusColor(_ state: String) -> Color {
        switch state.uppercased() {
        case "READY": Color(red: 0.30, green: 0.85, blue: 0.55)
        case "ERROR", "FAILED": Color(red: 1.0, green: 0.35, blue: 0.35)
        case "BUILDING", "INITIALIZING": Color.blue
        case "QUEUED", "PENDING": Color(red: 1.0, green: 0.72, blue: 0.35)
        case "CANCELED", "CANCELLED": Color.white.opacity(0.35)
        default: Color.white.opacity(0.45)
        }
    }
}

private extension View {
    func deploymentPanel() -> some View {
        self
            .background(
                ZStack {
                    LinearGradient(
                        colors: [Color.white.opacity(0.06), Color.white.opacity(0.02)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    LinearGradient(
                        colors: [Color.white.opacity(0.04), .clear],
                        startPoint: .top,
                        endPoint: .center
                    )
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 0.5
                    )
            )
    }
}
