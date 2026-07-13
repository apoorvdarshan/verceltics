import SwiftUI

@Observable
@MainActor
final class CloudflareWorkerOperationsViewModel {
    let api: CloudflareAPI
    let accountID: String
    let worker: CloudflareWorkerScript

    var versions: [CloudflareWorkerVersion] = []
    var secrets: [CloudflareWorkerSecretMetadata] = []
    var schedules: [CloudflareWorkerSchedule] = []
    var domains: [CloudflareWorkerDomain] = []
    var tails: [CloudflareWorkerTail] = []
    var settings: CloudflareWorkerScriptSettings?
    var scriptLevelSettings: CloudflareWorkerScriptLevelSettings?
    var subdomain: CloudflareWorkerSubdomain?
    var accountSubdomain: String?
    var isLoading = true
    var workingAction: String?
    var warnings: [String] = []
    var actionMessage: String?
    var actionFailed = false

    init(api: CloudflareAPI, accountID: String, worker: CloudflareWorkerScript) {
        self.api = api
        self.accountID = accountID
        self.worker = worker
    }

    func load() async {
        isLoading = true
        warnings = []
        actionMessage = nil

        await loadVersions()
        await loadSecrets()
        await loadSchedules()
        await loadDomains()
        await loadSettings()
        await loadSubdomains()
        await loadTails()

        isLoading = false
    }

    func deploy(_ version: CloudflareWorkerVersion) async {
        await perform("deploy-\(version.id)", success: "Version deployed to 100% of traffic.") {
            _ = try await api.deployWorkerVersion(
                accountID: accountID,
                scriptName: worker.id,
                versionID: version.id,
                message: "Deployed from Verceltics"
            )
            await loadVersions()
        }
    }

    func saveSecret(name: String, value: String) async {
        await perform("secret-save", success: "Secret saved. Its value is not stored or displayed.") {
            try await api.putWorkerSecret(
                accountID: accountID,
                scriptName: worker.id,
                name: name,
                value: value
            )
            await loadSecrets()
        }
    }

    func deleteSecret(_ secret: CloudflareWorkerSecretMetadata) async {
        await perform("secret-\(secret.id)", success: "Secret deleted.") {
            try await api.deleteWorkerSecret(accountID: accountID, scriptName: worker.id, name: secret.name)
            await loadSecrets()
        }
    }

    func addSchedule(_ cron: String) async {
        let expressions = Array(Set(schedules.map(\.cron) + [cron])).sorted()
        await saveSchedules(expressions, success: "Cron trigger added.")
    }

    func deleteSchedule(_ schedule: CloudflareWorkerSchedule) async {
        await saveSchedules(schedules.map(\.cron).filter { $0 != schedule.cron }, success: "Cron trigger deleted.")
    }

    func attachDomain(_ hostname: String) async {
        await perform("domain-add", success: "Custom domain attached.") {
            _ = try await api.attachWorkerDomain(accountID: accountID, hostname: hostname, scriptName: worker.id)
            await loadDomains()
        }
    }

    func detachDomain(_ domain: CloudflareWorkerDomain) async {
        await perform("domain-\(domain.id)", success: "Custom domain detached.") {
            try await api.detachWorkerDomain(accountID: accountID, domainID: domain.id)
            await loadDomains()
        }
    }

    func updateSubdomain(enabled: Bool, previewsEnabled: Bool) async {
        await perform("subdomain", success: "workers.dev settings updated.") {
            subdomain = try await api.updateWorkerSubdomain(
                accountID: accountID,
                scriptName: worker.id,
                enabled: enabled,
                previewsEnabled: previewsEnabled
            )
        }
    }

    func updateObservability(enabled: Bool, logsEnabled: Bool, tracesEnabled: Bool) async {
        await perform("observability", success: "Observability settings updated.") {
            scriptLevelSettings = try await api.updateWorkerObservability(
                accountID: accountID,
                scriptName: worker.id,
                enabled: enabled,
                logsEnabled: logsEnabled,
                tracesEnabled: tracesEnabled
            )
        }
    }

    private func saveSchedules(_ values: [String], success: String) async {
        await perform("schedules", success: success) {
            schedules = try await api.updateWorkerSchedules(
                accountID: accountID,
                scriptName: worker.id,
                cronExpressions: values
            )
        }
    }

    private func perform(
        _ action: String,
        success: String,
        operation: () async throws -> Void
    ) async {
        workingAction = action
        actionMessage = nil
        do {
            try await operation()
            actionMessage = success
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
        workingAction = nil
    }

    private func loadVersions() async {
        do { versions = try await api.fetchWorkerVersions(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Versions", error) }
    }

    private func loadSecrets() async {
        do { secrets = try await api.fetchWorkerSecrets(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Secrets", error) }
    }

    private func loadSchedules() async {
        do { schedules = try await api.fetchWorkerSchedules(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Cron triggers", error) }
    }

    private func loadDomains() async {
        do {
            domains = try await api.fetchWorkerDomains(accountID: accountID)
                .filter { $0.service.caseInsensitiveCompare(worker.id) == .orderedSame }
        } catch { addWarning("Domains", error) }
    }

    private func loadSettings() async {
        do { settings = try await api.fetchWorkerScriptSettings(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Settings", error) }
        do {
            scriptLevelSettings = try await api.fetchWorkerScriptLevelSettings(accountID: accountID, scriptName: worker.id)
        } catch {
            addWarning("Script settings", error)
        }
    }

    private func loadSubdomains() async {
        do { accountSubdomain = try await api.fetchWorkersAccountSubdomain(accountID: accountID).subdomain }
        catch { addWarning("Account workers.dev", error) }
        do { subdomain = try await api.fetchWorkerSubdomain(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Worker subdomain", error) }
    }

    private func loadTails() async {
        do { tails = try await api.fetchWorkerTails(accountID: accountID, scriptName: worker.id) }
        catch { addWarning("Live tails", error) }
    }

    private func addWarning(_ section: String, _ error: Error) {
        warnings.append("\(section): \(error.localizedDescription)")
    }
}

struct CloudflareWorkerOperationsView: View {
    let api: CloudflareAPI
    let accountID: String
    let worker: CloudflareWorkerScript

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareWorkerOperationsViewModel
    @State private var showingSecretEditor = false
    @State private var showingScheduleEditor = false
    @State private var showingDomainEditor = false
    @State private var showingObservabilityEditor = false
    @State private var showingSubdomainEditor = false
    @State private var pendingDelete: DeleteTarget?
    @State private var pendingDeployment: CloudflareWorkerVersion?

    init(api: CloudflareAPI, accountID: String, worker: CloudflareWorkerScript) {
        self.api = api
        self.accountID = accountID
        self.worker = worker
        _viewModel = State(
            wrappedValue: CloudflareWorkerOperationsViewModel(api: api, accountID: accountID, worker: worker)
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    capabilityRail
                    quickActions
                    metadataPanel
                    scriptSettingsPanel

                    if !viewModel.warnings.isEmpty {
                        warningPanel
                    }
                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    observabilityPanel
                    subdomainPanel
                    versionsPanel
                    secretsPanel
                    schedulesPanel
                    domainsPanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Worker operations")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .sheet(isPresented: $showingSecretEditor) {
            CloudflareWorkerSecretEditor { name, value in
                Task { await viewModel.saveSecret(name: name, value: value) }
            }
        }
        .sheet(isPresented: $showingScheduleEditor) {
            CloudflareWorkerScheduleEditor { cron in
                Task { await viewModel.addSchedule(cron) }
            }
        }
        .sheet(isPresented: $showingDomainEditor) {
            CloudflareWorkerDomainEditor { hostname in
                Task { await viewModel.attachDomain(hostname) }
            }
        }
        .sheet(isPresented: $showingObservabilityEditor) {
            CloudflareWorkerObservabilityEditor(settings: viewModel.scriptLevelSettings?.observability) {
                enabled, logsEnabled, tracesEnabled in
                Task {
                    await viewModel.updateObservability(
                        enabled: enabled,
                        logsEnabled: logsEnabled,
                        tracesEnabled: tracesEnabled
                    )
                }
            }
        }
        .sheet(isPresented: $showingSubdomainEditor) {
            CloudflareWorkerSubdomainEditor(settings: viewModel.subdomain) { enabled, previewsEnabled in
                Task {
                    await viewModel.updateSubdomain(enabled: enabled, previewsEnabled: previewsEnabled)
                }
            }
        }
        .confirmationDialog(
            deleteTitle,
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                guard let target = pendingDelete else { return }
                pendingDelete = nil
                Task { await delete(target) }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("This change takes effect immediately on Cloudflare.")
        }
        .confirmationDialog(
            "Deploy this version?",
            isPresented: Binding(
                get: { pendingDeployment != nil },
                set: { if !$0 { pendingDeployment = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Deploy to 100%") {
                guard let version = pendingDeployment else { return }
                pendingDeployment = nil
                Task { await viewModel.deploy(version) }
            }
            Button("Cancel", role: .cancel) { pendingDeployment = nil }
        } message: {
            Text("The selected version will receive all production traffic.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var capabilityRail: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(worker.id)
                        .font(.system(size: 20, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text("CONTROL PLANE")
                        .font(.system(size: 9, weight: .heavy, design: .monospaced))
                        .tracking(1.2)
                        .foregroundStyle(CloudflareStyle.orange)
                }
                Spacer()
                if viewModel.isLoading {
                    ProgressView().tint(CloudflareStyle.orange)
                } else {
                    CloudflareStatusPill(text: "SYNCED", color: CloudflareStyle.green)
                }
            }

            HStack(spacing: 8) {
                railValue("VERSIONS", viewModel.versions.count)
                railValue("SECRETS", viewModel.secrets.count)
                railValue("CRONS", viewModel.schedules.count)
                railValue("DOMAINS", viewModel.domains.count)
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func railValue(_ label: String, _ value: Int) -> some View {
        VStack(spacing: 5) {
            Text(value.formatted())
                .font(.system(size: 18, weight: .heavy, design: .rounded).monospacedDigit())
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 7, weight: .heavy, design: .monospaced))
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.32))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color.white.opacity(0.035))
        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private var quickActions: some View {
        HStack(spacing: 10) {
            NavigationLink {
                CloudflareWorkerContentView(api: api, accountID: accountID, scriptName: worker.id)
            } label: {
                actionTile("Source", icon: "chevron.left.forwardslash.chevron.right")
            }
            .buttonStyle(.plain)

            NavigationLink {
                CloudflareWorkerLiveTailView(api: api, accountID: accountID, scriptName: worker.id)
            } label: {
                actionTile("Live logs", icon: "waveform.path.ecg")
            }
            .buttonStyle(.plain)
        }
    }

    private func actionTile(_ title: String, icon: String) -> some View {
        HStack(spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(CloudflareStyle.orange)
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white.opacity(0.82))
            Spacer()
            CloudflareChevron()
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .cloudflarePanel()
    }

    private var metadataPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Returned metadata", icon: "list.bullet.rectangle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            detailRow("Created", worker.createdDate?.formatted(date: .abbreviated, time: .shortened))
            detailRow("Modified", worker.modifiedDate?.formatted(date: .abbreviated, time: .shortened))
            detailRow("Last deployed from", worker.lastDeployedFrom)
            detailRow("ETag", worker.etag, monospaced: true)
            detailRow("Migration tag", worker.migrationTag)
            detailRow("Script tag", worker.tag, monospaced: true)
            detailRow("Logpush", worker.logpush.map { $0 ? "Enabled" : "Disabled" })
            detailRow("Tags", worker.tags.isEmpty ? nil : worker.tags.joined(separator: ", "))
            detailRow("Placement mode", worker.placementMode)
            detailRow("Placement status", worker.placementStatus)
            detailRow("Named handlers", worker.namedHandlers.isEmpty ? nil : "\(worker.namedHandlers.count) returned")
            detailRow("Tail consumers", worker.tailConsumers.isEmpty ? nil : "\(worker.tailConsumers.count) returned")
        }
        .cloudflarePanel()
    }

    @ViewBuilder
    private var scriptSettingsPanel: some View {
        if let settings = viewModel.settings {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Script settings response", icon: "curlybraces.square.fill")
                Divider().overlay(Color.white.opacity(0.06))
                detailRow("Compatibility date", settings.compatibilityDate)
                detailRow("Compatibility flags", settings.compatibilityFlags.isEmpty ? nil : settings.compatibilityFlags.joined(separator: ", "))
                detailRow("Usage model", settings.usageModel)
                detailRow("Tags", settings.tags.isEmpty ? nil : settings.tags.joined(separator: ", "))
                detailRow("Annotations", settings.annotations.isEmpty ? nil : settings.annotations.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }.joined(separator: ", "))
                detailRow("Bindings", settings.bindings.isEmpty ? nil : CloudflareJSONValue.array(settings.bindings).operationsDisplayText)
                detailRow("Cache options", settings.cacheOptions.map { CloudflareJSONValue.object($0).operationsDisplayText })
                detailRow("Limits", settings.limits.map { CloudflareJSONValue.object($0).operationsDisplayText })
                detailRow("Migrations", settings.migrations?.operationsDisplayText)
                detailRow("Placement", settings.placement?.operationsDisplayText)
                detailRow("Tail consumers", settings.tailConsumers.isEmpty ? nil : CloudflareJSONValue.array(settings.tailConsumers).operationsDisplayText)
            }
            .cloudflarePanel()
        }
    }

    @ViewBuilder
    private func detailRow(_ title: String, _ value: String?, monospaced: Bool = false) -> some View {
        if let value, !value.isEmpty {
            HStack(alignment: .top, spacing: 12) {
                Text(title.uppercased())
                    .font(.system(size: 8, weight: .heavy, design: .monospaced))
                    .tracking(0.7)
                    .foregroundStyle(.white.opacity(0.3))
                    .frame(width: 105, alignment: .leading)
                Text(value)
                    .font(.system(size: 11, weight: .semibold, design: monospaced ? .monospaced : .default))
                    .foregroundStyle(.white.opacity(0.72))
                    .textSelection(.enabled)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 16)
        }
    }

    private var warningPanel: some View {
        VStack(alignment: .leading, spacing: 7) {
            Label("Some Worker capabilities are unavailable", systemImage: "exclamationmark.triangle.fill")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(CloudflareStyle.amber)
            ForEach(viewModel.warnings, id: \.self) { warning in
                Text(warning)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.38))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .cloudflarePanel()
    }

    private var deleteTitle: String {
        switch pendingDelete {
        case .secret(let value): "Delete secret \(value.name)?"
        case .schedule(let value): "Delete cron \(value.cron)?"
        case .domain(let value): "Detach \(value.hostname)?"
        case nil: "Delete resource?"
        }
    }

    private func delete(_ target: DeleteTarget) async {
        switch target {
        case .secret(let value): await viewModel.deleteSecret(value)
        case .schedule(let value): await viewModel.deleteSchedule(value)
        case .domain(let value): await viewModel.detachDomain(value)
        }
    }
}

private enum DeleteTarget {
    case secret(CloudflareWorkerSecretMetadata)
    case schedule(CloudflareWorkerSchedule)
    case domain(CloudflareWorkerDomain)
}

private extension CloudflareWorkerOperationsView {
    var observabilityPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Observability",
                icon: "waveform.path.ecg",
                actionTitle: viewModel.scriptLevelSettings == nil ? nil : "Configure"
            ) {
                if viewModel.scriptLevelSettings != nil { showingObservabilityEditor = true }
            }
            Divider().overlay(Color.white.opacity(0.06))

            if let observability = viewModel.scriptLevelSettings?.observability {
                statusRow(
                    "Event collection",
                    enabled: observability.enabled,
                    subtitle: observability.headSamplingRate.map { "Sampling \(($0 * 100).formatted(.number.precision(.fractionLength(0))))%" }
                )
                statusRow(
                    "Invocation logs",
                    enabled: observability.logs?.enabled == true,
                    subtitle: observability.logs?.persist == true ? "Persisted" : nil
                )
                statusRow(
                    "Traces",
                    enabled: observability.traces?.enabled == true,
                    subtitle: observability.traces?.propagationPolicy
                )
            } else {
                CloudflareEmptySection(
                    icon: "waveform.path.ecg",
                    title: "No observability settings",
                    message: "This Worker or account did not return script observability configuration."
                )
            }
        }
        .cloudflarePanel()
    }

    var subdomainPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "workers.dev",
                icon: "network",
                actionTitle: viewModel.subdomain == nil ? nil : "Configure"
            ) {
                if viewModel.subdomain != nil { showingSubdomainEditor = true }
            }
            Divider().overlay(Color.white.opacity(0.06))

            if let subdomain = viewModel.subdomain {
                statusRow(
                    "Production URL",
                    enabled: subdomain.enabled,
                    subtitle: workerDevURL
                )
                statusRow(
                    "Preview URLs",
                    enabled: subdomain.previewsEnabled,
                    subtitle: "Version preview hostnames"
                )
            } else {
                CloudflareEmptySection(
                    icon: "network",
                    title: "workers.dev unavailable",
                    message: "Cloudflare did not return subdomain settings for this Worker."
                )
            }
        }
        .cloudflarePanel()
    }

    var versionsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Deployable versions",
                icon: "clock.arrow.trianglehead.counterclockwise.rotate.90",
                count: viewModel.versions.count
            )
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.versions.isEmpty {
                CloudflareEmptySection(
                    icon: "clock.arrow.trianglehead.counterclockwise.rotate.90",
                    title: "No deployable versions",
                    message: "Version history may not be enabled for this Worker."
                )
            } else {
                ForEach(viewModel.versions) { version in
                    HStack(spacing: 12) {
                        Image(systemName: "shippingbox.and.arrow.backward.fill")
                            .font(.system(size: 12, weight: .heavy))
                            .foregroundStyle(CloudflareStyle.green)
                            .frame(width: 36, height: 36)
                            .background(CloudflareStyle.green.opacity(0.11))
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(version.number.map { "Version \($0)" } ?? String(version.id.prefix(14)))
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.white.opacity(0.86))
                            Text(versionSubtitle(version))
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(.white.opacity(0.36))
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)

                        NavigationLink {
                            CloudflareWorkerVersionDetailView(
                                api: api,
                                accountID: accountID,
                                scriptName: worker.id,
                                version: version
                            )
                        } label: {
                            Image(systemName: "info.circle")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(.white.opacity(0.4))
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)

                        if viewModel.workingAction == "deploy-\(version.id)" {
                            ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
                        } else {
                            Button {
                                pendingDeployment = version
                            } label: {
                                Image(systemName: "paperplane.fill")
                                    .font(.system(size: 12, weight: .heavy))
                                    .foregroundStyle(CloudflareStyle.orange)
                                    .frame(width: 34, height: 34)
                                    .background(CloudflareStyle.orange.opacity(0.11))
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)

                    if version.id != viewModel.versions.last?.id {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    var secretsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Secrets",
                icon: "key.fill",
                count: viewModel.secrets.count,
                actionTitle: "Add"
            ) {
                showingSecretEditor = true
            }
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.secrets.isEmpty {
                CloudflareEmptySection(
                    icon: "key",
                    title: "No secrets",
                    message: "Secret values are accepted once and never displayed or saved by the app."
                )
            } else {
                ForEach(viewModel.secrets) { secret in
                    resourceRow(
                        icon: "key.horizontal.fill",
                        title: secret.name,
                        subtitle: secret.type.replacingOccurrences(of: "_", with: " ").capitalized,
                        working: viewModel.workingAction == "secret-\(secret.id)"
                    ) {
                        pendingDelete = .secret(secret)
                    }
                    if secret.id != viewModel.secrets.last?.id {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    var schedulesPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Cron triggers",
                icon: "clock.badge.checkmark.fill",
                count: viewModel.schedules.count,
                actionTitle: "Add"
            ) {
                showingScheduleEditor = true
            }
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.schedules.isEmpty {
                CloudflareEmptySection(
                    icon: "clock.badge.checkmark",
                    title: "No cron triggers",
                    message: "Add a UTC cron expression to invoke the Worker's scheduled handler."
                )
            } else {
                ForEach(viewModel.schedules) { schedule in
                    resourceRow(
                        icon: "clock.fill",
                        title: schedule.cron,
                        subtitle: schedule.modifiedOn ?? schedule.createdOn ?? "UTC schedule",
                        working: viewModel.workingAction == "schedules"
                    ) {
                        pendingDelete = .schedule(schedule)
                    }
                    if schedule.id != viewModel.schedules.last?.id {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    var domainsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Custom domains",
                icon: "globe.americas.fill",
                count: viewModel.domains.count,
                actionTitle: "Attach"
            ) {
                showingDomainEditor = true
            }
            Divider().overlay(Color.white.opacity(0.06))

            if viewModel.domains.isEmpty {
                CloudflareEmptySection(
                    icon: "globe",
                    title: "No custom domains",
                    message: "Attach a hostname from a zone in this Cloudflare account."
                )
            } else {
                ForEach(viewModel.domains) { domain in
                    resourceRow(
                        icon: "globe",
                        title: domain.hostname,
                        subtitle: [domain.zoneName, domain.certificateID == nil ? nil : "TLS issued"]
                            .compactMap { $0 }
                            .joined(separator: " · "),
                        working: viewModel.workingAction == "domain-\(domain.id)"
                    ) {
                        pendingDelete = .domain(domain)
                    }
                    if domain.id != viewModel.domains.last?.id {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    func statusRow(_ title: String, enabled: Bool, subtitle: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: enabled ? "checkmark.circle.fill" : "minus.circle.fill")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(enabled ? CloudflareStyle.green : .white.opacity(0.25))
                .frame(width: 34, height: 34)
                .background((enabled ? CloudflareStyle.green : Color.white).opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.82))
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.35))
                        .lineLimit(1)
                }
            }
            Spacer()
            CloudflareStatusPill(
                text: enabled ? "ON" : "OFF",
                color: enabled ? CloudflareStyle.green : .white.opacity(0.35)
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }

    func resourceRow(
        icon: String,
        title: String,
        subtitle: String,
        working: Bool,
        delete: @escaping () -> Void
    ) -> some View {
        CloudflareResourceRow(icon: icon, title: title, subtitle: subtitle, tint: CloudflareStyle.amber) {
            if working {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Button(role: .destructive, action: delete) {
                    Image(systemName: "trash")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(CloudflareStyle.red.opacity(0.8))
                        .frame(width: 34, height: 34)
                }
                .buttonStyle(.plain)
            }
        }
    }

    var workerDevURL: String? {
        guard let accountSubdomain = viewModel.accountSubdomain, !accountSubdomain.isEmpty else { return nil }
        return "\(worker.id).\(accountSubdomain).workers.dev"
    }

    func versionSubtitle(_ version: CloudflareWorkerVersion) -> String {
        let values = [
            version.metadata?.source?.capitalized,
            version.metadata?.createdDate?.formatted(date: .abbreviated, time: .shortened),
            version.metadata?.authorEmail
        ].compactMap { $0 }.filter { !$0.isEmpty }
        return values.isEmpty ? String(version.id.prefix(18)) : values.joined(separator: " · ")
    }
}

// MARK: - Editors

private struct CloudflareWorkerSecretEditor: View {
    let save: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var value = ""

    var body: some View {
        workerEditorShell(
            title: "Add secret",
            subtitle: "The value is sent directly to Cloudflare and is never stored or shown again.",
            canSave: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !value.isEmpty
        ) {
            VStack(spacing: 12) {
                workerField("Binding name", text: $name, monospaced: true)
                SecureField("Secret value", text: $value)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(14)
                    .background(Color.white.opacity(0.055))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        } save: {
            save(name.trimmingCharacters(in: .whitespacesAndNewlines), value)
            value = ""
            dismiss()
        }
    }
}

private struct CloudflareWorkerScheduleEditor: View {
    let save: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var cron = ""

    var body: some View {
        workerEditorShell(
            title: "Add cron trigger",
            subtitle: "Cron expressions run in UTC. Example: */30 * * * * runs every 30 minutes.",
            canSave: cron.split(separator: " ").count == 5
        ) {
            workerField("Cron expression", text: $cron, monospaced: true)
        } save: {
            save(cron.trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
        }
    }
}

private struct CloudflareWorkerDomainEditor: View {
    let save: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var hostname = ""

    var body: some View {
        workerEditorShell(
            title: "Attach custom domain",
            subtitle: "Use a hostname from a zone in this Cloudflare account, such as api.example.com.",
            canSave: hostname.contains(".") && !hostname.contains(" ")
        ) {
            workerField("Hostname", text: $hostname, monospaced: true)
                .textContentType(.URL)
                .keyboardType(.URL)
        } save: {
            save(hostname.lowercased().trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
        }
    }
}

private struct CloudflareWorkerObservabilityEditor: View {
    let save: (Bool, Bool, Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var enabled: Bool
    @State private var logsEnabled: Bool
    @State private var tracesEnabled: Bool

    init(settings: CloudflareWorkerObservability?, save: @escaping (Bool, Bool, Bool) -> Void) {
        self.save = save
        _enabled = State(initialValue: settings?.enabled ?? false)
        _logsEnabled = State(initialValue: settings?.logs?.enabled ?? false)
        _tracesEnabled = State(initialValue: settings?.traces?.enabled ?? false)
    }

    var body: some View {
        workerEditorShell(
            title: "Configure observability",
            subtitle: "Control persisted events, invocation logs and traces for this Worker.",
            canSave: true
        ) {
            VStack(spacing: 0) {
                workerToggle("Collect events", isOn: $enabled)
                Divider().overlay(Color.white.opacity(0.06))
                workerToggle("Invocation logs", isOn: $logsEnabled)
                    .disabled(!enabled)
                Divider().overlay(Color.white.opacity(0.06))
                workerToggle("Traces", isOn: $tracesEnabled)
                    .disabled(!enabled)
            }
            .cloudflarePanel()
        } save: {
            save(enabled, enabled && logsEnabled, enabled && tracesEnabled)
            dismiss()
        }
    }
}

private struct CloudflareWorkerSubdomainEditor: View {
    let save: (Bool, Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var enabled: Bool
    @State private var previewsEnabled: Bool

    init(settings: CloudflareWorkerSubdomain?, save: @escaping (Bool, Bool) -> Void) {
        self.save = save
        _enabled = State(initialValue: settings?.enabled ?? false)
        _previewsEnabled = State(initialValue: settings?.previewsEnabled ?? false)
    }

    var body: some View {
        workerEditorShell(
            title: "Configure workers.dev",
            subtitle: "Production and preview URLs can be controlled independently.",
            canSave: true
        ) {
            VStack(spacing: 0) {
                workerToggle("Production URL", isOn: $enabled)
                Divider().overlay(Color.white.opacity(0.06))
                workerToggle("Version preview URLs", isOn: $previewsEnabled)
            }
            .cloudflarePanel()
        } save: {
            save(enabled, previewsEnabled)
            dismiss()
        }
    }
}

private func workerEditorShell<Content: View>(
    title: String,
    subtitle: String,
    canSave: Bool,
    @ViewBuilder content: () -> Content,
    save: @escaping () -> Void
) -> some View {
    NavigationStack {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(title)
                            .font(.system(size: 22, weight: .heavy))
                            .foregroundStyle(.white)
                        Text(subtitle)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.4))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    content()
                }
                .padding()
            }
        }
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save", action: save)
                    .fontWeight(.bold)
                    .disabled(!canSave)
            }
        }
    }
    .presentationDetents([.medium, .large])
    .tint(CloudflareStyle.orange)
}

private func workerField(_ placeholder: String, text: Binding<String>, monospaced: Bool) -> some View {
    TextField(placeholder, text: text)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .font(.system(size: 13, weight: .semibold, design: monospaced ? .monospaced : .default))
        .foregroundStyle(.white)
        .padding(14)
        .background(Color.white.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)
        )
}

private func workerToggle(_ title: String, isOn: Binding<Bool>) -> some View {
    Toggle(isOn: isOn) {
        Text(title)
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(.white.opacity(0.8))
    }
    .padding(16)
    .tint(CloudflareStyle.orange)
}

// MARK: - Version detail

@Observable
@MainActor
private final class CloudflareWorkerVersionDetailViewModel {
    let api: CloudflareAPI
    let accountID: String
    let scriptName: String
    let version: CloudflareWorkerVersion

    var detail: CloudflareWorkerVersionDetail?
    var isLoading = true
    var error: String?

    init(api: CloudflareAPI, accountID: String, scriptName: String, version: CloudflareWorkerVersion) {
        self.api = api
        self.accountID = accountID
        self.scriptName = scriptName
        self.version = version
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            detail = try await api.fetchWorkerVersion(
                accountID: accountID,
                scriptName: scriptName,
                versionID: version.id
            )
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

private struct CloudflareWorkerVersionDetailView: View {
    @State private var viewModel: CloudflareWorkerVersionDetailViewModel

    init(api: CloudflareAPI, accountID: String, scriptName: String, version: CloudflareWorkerVersion) {
        _viewModel = State(
            wrappedValue: CloudflareWorkerVersionDetailViewModel(
                api: api,
                accountID: accountID,
                scriptName: scriptName,
                version: version
            )
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if viewModel.isLoading {
                ProgressView().tint(CloudflareStyle.orange)
            } else if let error = viewModel.error {
                CloudflareErrorView(message: error) { Task { await viewModel.load() } }
            } else if let detail = viewModel.detail {
                ScrollView {
                    VStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(detail.number.map { "VERSION \($0)" } ?? "WORKER VERSION")
                                .font(.system(size: 9, weight: .heavy, design: .monospaced))
                                .tracking(1.1)
                                .foregroundStyle(CloudflareStyle.orange)
                            Text(detail.id)
                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                .foregroundStyle(.white)
                                .textSelection(.enabled)
                            Text(versionDetailSubtitle(detail))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(.white.opacity(0.4))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(18)
                        .cloudflarePanel(accentOpacity: 0.08)

                        bindingsPanel(detail.resources?.bindings ?? [])
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("Version detail")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
    }

    private func bindingsPanel(_ bindings: [CloudflareJSONValue]) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Bindings", icon: "link", count: bindings.count)
            Divider().overlay(Color.white.opacity(0.06))
            if bindings.isEmpty {
                CloudflareEmptySection(
                    icon: "link",
                    title: "No bindings returned",
                    message: "This version has no external resource bindings."
                )
            } else {
                ForEach(Array(bindings.enumerated()), id: \.offset) { _, binding in
                    let summary = bindingSummary(binding)
                    CloudflareResourceRow(
                        icon: "link",
                        title: summary.name,
                        subtitle: summary.type,
                        tint: CloudflareStyle.amber
                    )
                }
            }
        }
        .cloudflarePanel()
    }

    private func bindingSummary(_ value: CloudflareJSONValue) -> (name: String, type: String) {
        guard case .object(let object) = value else { return ("Binding", "Unknown type") }
        return (
            object["name"]?.stringValue ?? "Binding",
            object["type"]?.stringValue?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Unknown type"
        )
    }

    private func versionDetailSubtitle(_ detail: CloudflareWorkerVersionDetail) -> String {
        var values: [String] = []
        if let source = detail.metadata?.source { values.append(source.capitalized) }
        if let author = detail.metadata?.authorEmail { values.append(author) }
        if let startup = detail.startupTimeMilliseconds { values.append("Startup \(startup.formatted()) ms") }
        return values.isEmpty ? "Version metadata" : values.joined(separator: " · ")
    }
}

// MARK: - Script content

@Observable
@MainActor
private final class CloudflareWorkerContentViewModel {
    let api: CloudflareAPI
    let accountID: String
    let scriptName: String

    var content = ""
    var contentType: String?
    var byteCount = 0
    var isLoading = true
    var error: String?

    init(api: CloudflareAPI, accountID: String, scriptName: String) {
        self.api = api
        self.accountID = accountID
        self.scriptName = scriptName
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            let response = try await api.fetchWorkerContent(accountID: accountID, scriptName: scriptName)
            guard (200...299).contains(response.statusCode) else {
                throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: response.text)
            }
            byteCount = response.data.count
            contentType = response.headers.first { $0.key.caseInsensitiveCompare("Content-Type") == .orderedSame }?.value
            content = response.prettyPrintedBody.isEmpty
                ? "Binary or multipart Worker content (\(response.data.count.formatted()) bytes)."
                : response.prettyPrintedBody
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

private struct CloudflareWorkerContentView: View {
    @State private var viewModel: CloudflareWorkerContentViewModel

    init(api: CloudflareAPI, accountID: String, scriptName: String) {
        _viewModel = State(
            wrappedValue: CloudflareWorkerContentViewModel(api: api, accountID: accountID, scriptName: scriptName)
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if viewModel.isLoading {
                ProgressView().tint(CloudflareStyle.orange)
            } else if let error = viewModel.error {
                CloudflareErrorView(message: error) { Task { await viewModel.load() } }
            } else {
                ScrollView([.vertical, .horizontal]) {
                    Text(viewModel.content)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.76))
                        .textSelection(.enabled)
                        .padding()
                }
            }
        }
        .navigationTitle("Worker content")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
    }
}

// MARK: - Live tail

@Observable
@MainActor
private final class CloudflareWorkerLiveTailViewModel {
    let api: CloudflareAPI
    let accountID: String
    let scriptName: String

    var lines: [LiveLine] = []
    var status = "Preparing secure tail…"
    var isConnected = false
    var error: String?

    private var tail: CloudflareWorkerTail?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?

    struct LiveLine: Identifiable {
        let id = UUID()
        let timestamp = Date()
        let text: String
    }

    init(api: CloudflareAPI, accountID: String, scriptName: String) {
        self.api = api
        self.accountID = accountID
        self.scriptName = scriptName
    }

    func start() async {
        guard socket == nil else { return }
        error = nil
        status = "Creating live tail…"

        do {
            let tail = try await api.createWorkerTail(accountID: accountID, scriptName: scriptName)
            guard let url = URL(string: tail.url) else {
                throw CloudflareAPIError.decoding("Cloudflare returned an invalid live-tail URL.")
            }
            self.tail = tail
            let socket = URLSession.shared.webSocketTask(with: url)
            self.socket = socket
            socket.resume()
            isConnected = true
            status = "Listening for Worker events"
            receiveTask = Task { [weak self] in
                await self?.receiveLoop()
            }
        } catch {
            self.error = error.localizedDescription
            status = "Tail unavailable"
        }
    }

    func stop() async {
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        isConnected = false

        if let tail {
            try? await api.deleteWorkerTail(accountID: accountID, scriptName: scriptName, tailID: tail.id)
            self.tail = nil
        }
        status = "Tail stopped"
    }

    private func receiveLoop() async {
        guard let socket else { return }
        while !Task.isCancelled {
            do {
                let message = try await socket.receive()
                switch message {
                case .string(let value): append(value)
                case .data(let data): append(String(data: data, encoding: .utf8) ?? "<\(data.count) binary bytes>")
                @unknown default: break
                }
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
                status = "Live tail disconnected"
                isConnected = false
                return
            }
        }
    }

    private func append(_ raw: String) {
        let formatted: String
        if let data = raw.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data),
           let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
           let string = String(data: pretty, encoding: .utf8) {
            formatted = string
        } else {
            formatted = raw
        }
        lines.append(LiveLine(text: formatted))
        if lines.count > 500 {
            lines.removeFirst(lines.count - 500)
        }
    }
}

private struct CloudflareWorkerLiveTailView: View {
    @State private var viewModel: CloudflareWorkerLiveTailViewModel

    init(api: CloudflareAPI, accountID: String, scriptName: String) {
        _viewModel = State(
            wrappedValue: CloudflareWorkerLiveTailViewModel(api: api, accountID: accountID, scriptName: scriptName)
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 9) {
                    Circle()
                        .fill(viewModel.isConnected ? CloudflareStyle.green : CloudflareStyle.amber)
                        .frame(width: 8, height: 8)
                    Text(viewModel.status)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.58))
                    Spacer()
                    Text("\(viewModel.lines.count) EVENTS")
                        .font(.system(size: 8, weight: .heavy, design: .monospaced))
                        .tracking(0.7)
                        .foregroundStyle(.white.opacity(0.3))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(Color.white.opacity(0.035))

                if let error = viewModel.error, viewModel.lines.isEmpty {
                    CloudflareEmptySection(
                        icon: "exclamationmark.triangle.fill",
                        title: "Live tail unavailable",
                        message: error
                    )
                    Spacer()
                } else if viewModel.lines.isEmpty {
                    CloudflareEmptySection(
                        icon: "waveform.path.ecg",
                        title: "Waiting for requests",
                        message: "Events will appear here as the Worker receives traffic."
                    )
                    Spacer()
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 10) {
                                ForEach(viewModel.lines) { line in
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(line.timestamp.formatted(date: .omitted, time: .standard))
                                            .font(.system(size: 8, weight: .heavy, design: .monospaced))
                                            .foregroundStyle(CloudflareStyle.orange.opacity(0.7))
                                        Text(line.text)
                                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                                            .foregroundStyle(.white.opacity(0.72))
                                            .textSelection(.enabled)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(12)
                                    .background(Color.white.opacity(0.035))
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    .id(line.id)
                                }
                            }
                            .padding()
                        }
                        .onChange(of: viewModel.lines.count) { _, _ in
                            if let id = viewModel.lines.last?.id {
                                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(id, anchor: .bottom) }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Live Worker logs")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.start() }
        .onDisappear { Task { await viewModel.stop() } }
    }
}
