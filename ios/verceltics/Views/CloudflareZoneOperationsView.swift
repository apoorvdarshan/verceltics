import SwiftUI

@Observable
@MainActor
final class CloudflareZoneOperationsViewModel {
    let api: CloudflareAPI
    var zone: CloudflareZone

    var dnssec: CloudflareDNSSECStatus?
    var settings: [CloudflareZoneSetting] = []
    var dnsSettings: CloudflareZoneDNSSettings?
    var dnsUsage: CloudflareDNSUsage?
    var dnsAnalytics: CloudflareDNSAnalyticsReport?
    var dnssecError: String?
    var settingsError: String?
    var dnsSettingsError: String?
    var dnsUsageError: String?
    var dnsAnalyticsError: String?
    var isLoading = true
    var workingAction: String?
    var actionMessage: String?
    var actionFailed = false
    private var loadGeneration = 0

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
    }

    func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        dnssecError = nil
        settingsError = nil
        dnsSettingsError = nil
        dnsUsageError = nil
        dnsAnalyticsError = nil

        let until = Date()
        let since = until.addingTimeInterval(-86_400)
        async let zoneResult = capture { try await api.fetchZone(id: zone.id) }
        async let dnssecResult = capture { try await api.fetchZoneDNSSEC(zoneID: zone.id) }
        async let settingsResult = capture { try await api.fetchZoneSettings(zoneID: zone.id) }
        async let dnsSettingsResult = capture { try await api.fetchZoneDNSSettings(zoneID: zone.id) }
        async let usageResult = capture { try await api.fetchZoneDNSUsage(zoneID: zone.id) }
        async let analyticsResult = capture { try await api.fetchZoneDNSAnalytics(zoneID: zone.id, since: since, until: until) }

        let results = await (zoneResult, dnssecResult, settingsResult, dnsSettingsResult, usageResult, analyticsResult)
        guard generation == loadGeneration else { return }

        if case .success(let value) = results.0 { zone = value }
        switch results.1 {
        case .success(let value): dnssec = value
        case .failure(let error): dnssecError = error.localizedDescription
        }
        switch results.2 {
        case .success(let value): settings = value.sorted(by: settingSort)
        case .failure(let error): settingsError = error.localizedDescription
        }
        switch results.3 {
        case .success(let value): dnsSettings = value
        case .failure(let error): dnsSettingsError = error.localizedDescription
        }
        switch results.4 {
        case .success(let value): dnsUsage = value
        case .failure(let error): dnsUsageError = error.localizedDescription
        }
        switch results.5 {
        case .success(let value): dnsAnalytics = value
        case .failure(let error): dnsAnalyticsError = error.localizedDescription
        }

        isLoading = false
    }

    func setDNSSEC(enabled: Bool) async {
        workingAction = "dnssec"
        defer { workingAction = nil }
        let path = "/zones/\(zone.id)/dnssec"

        do {
            if enabled {
                dnssec = try await api.enableZoneDNSSEC(
                    zoneID: zone.id,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
                )
                actionMessage = "DNSSEC enabled. Add the returned DS record at your registrar if Cloudflare requires it."
            } else {
                try await api.disableZoneDNSSEC(
                    zoneID: zone.id,
                    confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
                )
                dnssec = try? await api.fetchZoneDNSSEC(zoneID: zone.id)
                actionMessage = "DNSSEC disabled."
            }
            dnssecError = nil
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
    }

    func updateSetting(_ setting: CloudflareZoneSetting, value: CloudflareJSONValue) async throws {
        workingAction = "setting:\(setting.id)"
        defer { workingAction = nil }
        let path = "/zones/\(zone.id)/settings/\(setting.id)"

        do {
            let updated = try await api.updateZoneSetting(
                zoneID: zone.id,
                settingID: setting.id,
                value: value,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
            )
            if let index = settings.firstIndex(where: { $0.id == updated.id }) {
                settings[index] = updated
            }
            actionMessage = "\(settingDisplayName(setting.id)) updated."
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }
    }

    func updateDNSSettings(_ changes: [String: CloudflareJSONValue]) async throws {
        workingAction = "dns-settings"
        defer { workingAction = nil }
        let path = "/zones/\(zone.id)/dns_settings"

        do {
            dnsSettings = try await api.updateZoneDNSSettings(
                zoneID: zone.id,
                changes: changes,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
            )
            actionMessage = "DNS settings updated."
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
            throw error
        }
    }

    func requestActivationCheck() async {
        workingAction = "activation"
        defer { workingAction = nil }
        let path = "/zones/\(zone.id)/activation_check"

        do {
            _ = try await api.requestZoneActivationCheck(
                zoneID: zone.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
            )
            if let refreshed = try? await api.fetchZone(id: zone.id) {
                zone = refreshed
            }
            actionMessage = "Cloudflare accepted the activation check."
            actionFailed = false
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
    }

    func settingDisplayName(_ id: String) -> String {
        id.replacingOccurrences(of: "_", with: " ")
            .split(separator: " ")
            .map { $0.capitalized }
            .joined(separator: " ")
    }

    private func settingSort(_ lhs: CloudflareZoneSetting, _ rhs: CloudflareZoneSetting) -> Bool {
        let priority = Self.commonSettingIDs
        let left = priority.firstIndex(of: lhs.id)
        let right = priority.firstIndex(of: rhs.id)
        if let left, let right { return left < right }
        if left != nil { return true }
        if right != nil { return false }
        return lhs.id < rhs.id
    }

    private func capture<Value>(_ operation: () async throws -> Value) async -> Result<Value, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }

    nonisolated static let commonSettingIDs = [
        "ssl", "min_tls_version", "always_use_https", "automatic_https_rewrites",
        "http2", "http3", "tls_1_3", "brotli", "early_hints", "ipv6",
        "security_level", "browser_check", "development_mode", "always_online",
        "cache_level", "browser_cache_ttl", "rocket_loader", "websockets"
    ]
}

struct CloudflareZoneOperationsView: View {
    let api: CloudflareAPI

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareZoneOperationsViewModel
    @State private var editingSetting: CloudflareZoneSetting?
    @State private var showingDNSSettingsEditor = false
    @State private var pendingDNSSECState: Bool?
    @State private var showingActivationConfirmation = false

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        _viewModel = State(wrappedValue: CloudflareZoneOperationsViewModel(api: api, zone: zone))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    zoneHeader

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    CloudflareWriteNotice()
                    dnsOverview
                    dnssecPanel
                    dnsSettingsPanel
                    zoneSettingsPanel
                    identityPanel
                    lifecyclePanel
                    nameserverPanel
                    planPanel
                    dynamicMetadataPanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Zone operations")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .sheet(item: $editingSetting) { setting in
            NavigationStack {
                CloudflareZoneSettingEditor(setting: setting) { value in
                    try await viewModel.updateSetting(setting, value: value)
                }
            }
            .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $showingDNSSettingsEditor) {
            if let settings = viewModel.dnsSettings {
                NavigationStack {
                    CloudflareZoneDNSSettingsEditor(settings: settings) { changes in
                        try await viewModel.updateDNSSettings(changes)
                    }
                }
                .preferredColorScheme(.dark)
            }
        }
        .confirmationDialog(
            pendingDNSSECState == true ? "Enable DNSSEC?" : "Disable DNSSEC?",
            isPresented: Binding(
                get: { pendingDNSSECState != nil },
                set: { if !$0 { pendingDNSSECState = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let enabled = pendingDNSSECState {
                Button(enabled ? "Enable DNSSEC" : "Disable DNSSEC", role: enabled ? nil : .destructive) {
                    pendingDNSSECState = nil
                    Task { await viewModel.setDNSSEC(enabled: enabled) }
                }
                Button("Cancel", role: .cancel) { pendingDNSSECState = nil }
            }
        } message: {
            Text(pendingDNSSECState == true
                ? "Cloudflare will sign this zone. You may need to publish its DS record at your registrar."
                : "DNSSEC validation can fail if the existing DS record remains at your registrar.")
        }
        .confirmationDialog(
            "Request an activation check?",
            isPresented: $showingActivationConfirmation,
            titleVisibility: .visible
        ) {
            Button("Request Check") { Task { await viewModel.requestActivationCheck() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Cloudflare rate-limits activation checks. Use this after updating the domain’s nameservers.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var zoneHeader: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "globe.americas.fill")
                    .font(.system(size: 21, weight: .heavy))
                    .foregroundStyle(.black.opacity(0.82))
                    .frame(width: 48, height: 48)
                    .background(
                        LinearGradient(
                            colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                            startPoint: .bottomLeading,
                            endPoint: .topTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.zone.name)
                        .font(.system(size: 21, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(viewModel.zone.account?.name ?? "Cloudflare zone")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                    Text("DNS & EDGE CONTROL")
                        .font(.system(size: 9, weight: .heavy))
                        .tracking(1.0)
                        .foregroundStyle(CloudflareStyle.orange.opacity(0.82))
                }
                Spacer(minLength: 8)
                CloudflareStatusPill(
                    text: (viewModel.zone.paused == true ? "PAUSED" : viewModel.zone.status?.uppercased()) ?? "UNKNOWN",
                    color: viewModel.zone.isActive ? CloudflareStyle.green : CloudflareStyle.amber
                )
            }

            HStack(spacing: 9) {
                CloudflareActionButton(
                    title: "Activation check",
                    icon: "checkmark.arrow.trianglehead.counterclockwise",
                    isWorking: viewModel.workingAction == "activation"
                ) { showingActivationConfirmation = true }

                if let date = viewModel.zone.modifiedDate {
                    Text("Updated \(date.formatted(.relative(presentation: .named)))")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.34))
                        .lineLimit(1)
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private var dnsOverview: some View {
        VStack(spacing: 12) {
            HStack {
                Text("DNS · LAST 24 HOURS")
                    .font(.system(size: 10, weight: .heavy))
                    .tracking(1.0)
                    .foregroundStyle(.white.opacity(0.44))
                Spacer()
                if let analytics = viewModel.dnsAnalytics {
                    Text("lag \(Int(analytics.dataLag))s")
                        .font(.system(size: 9, weight: .heavy).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.28))
                }
            }
            .padding(.horizontal, 4)

            if let analytics = viewModel.dnsAnalytics {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    CloudflareMetricCard(
                        title: "Queries",
                        value: metricText(analytics.total(named: "queryCount")),
                        icon: "questionmark.bubble.fill"
                    )
                    CloudflareMetricCard(
                        title: "Uncached",
                        value: metricText(analytics.total(named: "uncachedCount")),
                        icon: "bolt.slash.fill",
                        accent: CloudflareStyle.amber
                    )
                    CloudflareMetricCard(
                        title: "Stale",
                        value: metricText(analytics.total(named: "staleCount")),
                        icon: "clock.badge.exclamationmark",
                        accent: CloudflareStyle.red
                    )
                    CloudflareMetricCard(
                        title: "Rows",
                        value: analytics.rows.formatted(),
                        icon: "tablecells.fill",
                        accent: CloudflareStyle.green
                    )
                }
            } else if viewModel.isLoading {
                loadingPanel
            } else if let error = viewModel.dnsAnalyticsError {
                unavailablePanel(title: "DNS analytics unavailable", message: error)
            }

            if let usage = viewModel.dnsUsage {
                HStack(spacing: 8) {
                    Label("\(usage.recordUsage.formatted()) records used", systemImage: "server.rack")
                    Spacer()
                    Text(usage.recordQuota.map { "\($0.formatted()) quota" } ?? "Account-level quota")
                }
                .font(.system(size: 10, weight: .bold).monospacedDigit())
                .foregroundStyle(.white.opacity(0.48))
                .padding(13)
                .cloudflarePanel()
            } else if let error = viewModel.dnsUsageError {
                unavailablePanel(title: "DNS quota unavailable", message: error)
            }
        }
    }

    private var dnssecPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "DNSSEC", icon: "checkmark.shield.fill")
            panelDivider
            if let dnssec = viewModel.dnssec {
                HStack(spacing: 11) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(dnssec.isActive ? "Zone signing is active" : "Zone signing is disabled")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white.opacity(0.86))
                        Text(dnssec.isActive ? "Verify the DS record remains published at your registrar." : "Enable DNSSEC to protect DNS responses from tampering.")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.white.opacity(0.36))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    CloudflareStatusPill(text: dnssec.status?.uppercased() ?? "UNKNOWN", color: dnssec.isActive ? CloudflareStyle.green : CloudflareStyle.amber)
                }
                .padding(16)

                panelDivider.padding(.horizontal, 16)
                dnssecDetails(dnssec)
                panelDivider.padding(.horizontal, 16)

                CloudflareActionButton(
                    title: dnssec.isActive ? "Disable DNSSEC" : "Enable DNSSEC",
                    icon: dnssec.isActive ? "lock.open.fill" : "lock.shield.fill",
                    role: dnssec.isActive ? .destructive : nil,
                    isWorking: viewModel.workingAction == "dnssec"
                ) { pendingDNSSECState = !dnssec.isActive }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            } else if viewModel.isLoading {
                sectionLoading
            } else {
                unavailableSection(title: "DNSSEC unavailable", message: viewModel.dnssecError ?? "No DNSSEC status was returned.")
            }
        }
        .cloudflarePanel()
    }

    private func dnssecDetails(_ value: CloudflareDNSSECStatus) -> some View {
        VStack(spacing: 0) {
            if let keyTag = value.keyTag { CloudflareDetailRow(icon: "number", title: "Key tag", value: keyTag.formatted()) }
            if let algorithm = value.algorithm { CloudflareDetailRow(icon: "function", title: "Algorithm", value: algorithm) }
            if let digestType = value.digestType { CloudflareDetailRow(icon: "number.square.fill", title: "Digest type", value: digestType) }
            if let ds = value.ds { CloudflareDetailRow(icon: "doc.text.fill", title: "DS record", value: ds) }
            if let digest = value.digest { CloudflareDetailRow(icon: "number", title: "Digest", value: digest) }
            if let publicKey = value.publicKey { CloudflareDetailRow(icon: "key.fill", title: "Public key", value: publicKey) }
            CloudflareDetailRow(icon: "person.2.badge.gearshape.fill", title: "Multi-signer", value: optionalBoolean(value.multiSigner))
            CloudflareDetailRow(icon: "signature", title: "Pre-signed", value: optionalBoolean(value.presigned))
            CloudflareDetailRow(icon: "arrow.trianglehead.branch", title: "NSEC3", value: optionalBoolean(value.useNSEC3))
            if let date = value.modifiedDate { CloudflareDetailRow(icon: "calendar", title: "Modified", value: date.formatted(date: .abbreviated, time: .shortened)) }
        }
    }

    private var dnsSettingsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "DNS configuration",
                icon: "slider.horizontal.3",
                actionTitle: viewModel.dnsSettings == nil ? nil : "Edit"
            ) { showingDNSSettingsEditor = true }
            panelDivider
            if let settings = viewModel.dnsSettings {
                CloudflareDetailRow(icon: "rectangle.compress.vertical", title: "Flatten all CNAMEs", value: optionalBoolean(settings.flattenAllCNAMEs))
                CloudflareDetailRow(icon: "shield.lefthalf.filled", title: "Foundation DNS", value: optionalBoolean(settings.foundationDNS))
                CloudflareDetailRow(icon: "point.3.filled.connected.trianglepath.dotted", title: "Multi-provider DNS", value: optionalBoolean(settings.multiProvider))
                CloudflareDetailRow(icon: "arrow.trianglehead.2.clockwise.rotate.90", title: "Secondary overrides", value: optionalBoolean(settings.secondaryOverrides))
                CloudflareDetailRow(icon: "square.stack.3d.up.fill", title: "Zone mode", value: settings.zoneMode?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Not returned")
                if let ttl = settings.nameServerTTL { CloudflareDetailRow(icon: "timer", title: "Nameserver TTL", value: durationText(ttl)) }
                CloudflareDetailRow(icon: "server.rack", title: "Nameserver type", value: settings.nameservers?.type ?? "Not returned")
                if let set = settings.nameservers?.set { CloudflareDetailRow(icon: "number", title: "Nameserver set", value: set.formatted()) }
                if let reference = settings.internalDNS?.referenceZoneID { CloudflareDetailRow(icon: "arrow.triangle.branch", title: "Internal fallback zone", value: reference) }
                if let soa = settings.soa {
                    CloudflareDetailRow(icon: "server.rack", title: "SOA primary", value: soa.primaryNameServer ?? "Cloudflare assigned")
                    CloudflareDetailRow(icon: "at", title: "SOA administrator", value: soa.responsibleName ?? "Not returned")
                    CloudflareDetailRow(icon: "arrow.clockwise", title: "SOA refresh / retry", value: "\(durationText(soa.refresh)) / \(durationText(soa.retry))")
                    CloudflareDetailRow(icon: "clock.badge.exclamationmark", title: "SOA expire / negative TTL", value: "\(durationText(soa.expire)) / \(durationText(soa.minimumTTL))")
                    CloudflareDetailRow(icon: "timer", title: "SOA TTL", value: durationText(soa.ttl))
                }
            } else if viewModel.isLoading {
                sectionLoading
            } else {
                unavailableSection(title: "DNS settings unavailable", message: viewModel.dnsSettingsError ?? "No DNS settings were returned.")
            }
        }
        .cloudflarePanel()
    }

    private var zoneSettingsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Zone settings", icon: "switch.2", count: viewModel.settings.count)
            panelDivider
            if viewModel.isLoading && viewModel.settings.isEmpty {
                sectionLoading
            } else if let error = viewModel.settingsError {
                unavailableSection(title: "Zone settings unavailable", message: error)
            } else if viewModel.settings.isEmpty {
                unavailableSection(title: "No settings returned", message: "Cloudflare returned an empty settings collection for this zone.")
            } else {
                ForEach(Array(viewModel.settings.enumerated()), id: \.element.id) { index, setting in
                    Button {
                        if setting.editable && isScalar(setting.value) { editingSetting = setting }
                    } label: {
                        HStack(spacing: 11) {
                            Image(systemName: settingIcon(setting.id))
                                .font(.system(size: 11, weight: .heavy))
                                .foregroundStyle(setting.editable ? CloudflareStyle.orange : .white.opacity(0.28))
                                .frame(width: 34, height: 34)
                                .background(Color.white.opacity(0.045))
                                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(viewModel.settingDisplayName(setting.id))
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(.white.opacity(0.84))
                                Text(setting.value.operationsDisplayText)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.38))
                                    .lineLimit(2)
                            }
                            Spacer(minLength: 8)
                            if viewModel.workingAction == "setting:\(setting.id)" {
                                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
                            } else if setting.editable && isScalar(setting.value) {
                                CloudflareChevron()
                            } else {
                                CloudflareStatusPill(text: setting.editable ? "ADVANCED" : "READ ONLY", color: .white.opacity(0.36))
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.workingAction != nil)
                    if index < viewModel.settings.count - 1 { insetDivider }
                }
            }
        }
        .cloudflarePanel()
    }

    private var identityPanel: some View {
        metadataPanel(title: "Identity & ownership", icon: "info.circle.fill", rows: [
            ("number", "Zone ID", viewModel.zone.id),
            ("building.2", "Account", viewModel.zone.account?.name),
            ("number", "Account ID", viewModel.zone.account?.id),
            ("person.crop.circle", "Owner", jsonObjectSummary(viewModel.zone.owner)),
            ("building.columns", "Tenant", jsonObjectSummary(viewModel.zone.tenant)),
            ("square.grid.3x3", "Tenant unit", jsonObjectSummary(viewModel.zone.tenantUnit)),
            ("checkmark.seal.fill", "Verification key", viewModel.zone.verificationKey),
            ("lock.shield", "Permissions", viewModel.zone.permissions.isEmpty ? nil : viewModel.zone.permissions.joined(separator: ", "))
        ])
    }

    private var lifecyclePanel: some View {
        metadataPanel(title: "Lifecycle", icon: "clock.fill", rows: [
            ("circle.fill", "Status", viewModel.zone.status),
            ("pause.circle.fill", "Paused", optionalBoolean(viewModel.zone.paused)),
            ("rectangle.3.group", "Type", viewModel.zone.type),
            ("hammer.fill", "Development mode", (viewModel.zone.developmentMode ?? 0) > 0 ? "Active" : "Off"),
            ("calendar.badge.plus", "Created", formattedDate(viewModel.zone.createdDate)),
            ("calendar.badge.clock", "Modified", formattedDate(viewModel.zone.modifiedDate)),
            ("calendar.badge.checkmark", "Activated", formattedDate(viewModel.zone.activatedDate)),
            ("building.columns", "Original registrar", viewModel.zone.originalRegistrar),
            ("server.rack", "Original DNS host", viewModel.zone.originalDNSHost),
            ("link", "CNAME suffix", viewModel.zone.cnameSuffix)
        ])
    }

    private var nameserverPanel: some View {
        metadataPanel(title: "Nameservers", icon: "server.rack", rows: [
            ("server.rack", "Assigned", viewModel.zone.nameServers.isEmpty ? nil : viewModel.zone.nameServers.joined(separator: ", ")),
            ("clock.arrow.circlepath", "Original", viewModel.zone.originalNameServers.isEmpty ? nil : viewModel.zone.originalNameServers.joined(separator: ", ")),
            ("sparkles", "Vanity", viewModel.zone.vanityNameServers.isEmpty ? nil : viewModel.zone.vanityNameServers.joined(separator: ", "))
        ])
    }

    @ViewBuilder
    private var planPanel: some View {
        if let plan = viewModel.zone.plan {
            metadataPanel(title: "Plan", icon: "creditcard.fill", rows: [
                ("number", "Plan ID", plan.id),
                ("tag.fill", "Name", plan.name),
                ("coloncurrencysign", "Currency", plan.currency),
                ("calendar", "Billing frequency", plan.frequency),
                ("banknote.fill", "Price", plan.price.map { planPrice($0, currency: plan.currency, frequency: plan.frequency) }),
                ("checkmark.seal.fill", "Subscribed", optionalBoolean(plan.isSubscribed)),
                ("cart.fill", "Can subscribe", optionalBoolean(plan.canSubscribe)),
                ("building.2.fill", "Externally managed", optionalBoolean(plan.externallyManaged)),
                ("tag.slash.fill", "Legacy discount", optionalBoolean(plan.legacyDiscount)),
                ("number", "Legacy plan ID", plan.legacyID)
            ])
        }
    }

    @ViewBuilder
    private var dynamicMetadataPanel: some View {
        if !viewModel.zone.meta.isEmpty {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Zone metadata", icon: "list.bullet.rectangle.fill", count: viewModel.zone.meta.count)
                panelDivider
                ForEach(Array(viewModel.zone.meta.sorted { $0.key < $1.key }.enumerated()), id: \.element.key) { _, item in
                    CloudflareDetailRow(
                        icon: "circle.fill",
                        title: item.key.replacingOccurrences(of: "_", with: " "),
                        value: item.value.operationsDisplayText
                    )
                }
            }
            .cloudflarePanel()
        }
    }

    private func metadataPanel(title: String, icon: String, rows: [(String, String, String?)]) -> some View {
        let populated = rows.filter { $0.2?.isEmpty == false }
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon)
            panelDivider
            ForEach(Array(populated.enumerated()), id: \.offset) { _, row in
                CloudflareDetailRow(icon: row.0, title: row.1, value: row.2 ?? "")
            }
        }
        .cloudflarePanel()
    }

    private var panelDivider: some View { Divider().overlay(Color.white.opacity(0.06)) }
    private var insetDivider: some View { Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 61) }
    private var sectionLoading: some View {
        ProgressView().tint(CloudflareStyle.orange).frame(maxWidth: .infinity).padding(.vertical, 28)
    }
    private var loadingPanel: some View {
        ProgressView().tint(CloudflareStyle.orange).frame(maxWidth: .infinity).padding(.vertical, 22).cloudflarePanel()
    }

    private func unavailableSection(title: String, message: String) -> some View {
        CloudflareEmptySection(icon: "exclamationmark.triangle.fill", title: title, message: message)
    }

    private func unavailablePanel(title: String, message: String) -> some View {
        unavailableSection(title: title, message: message).cloudflarePanel()
    }

    private func metricText(_ value: Double?) -> String {
        guard let value else { return "—" }
        return value.formatted(.number.notation(.compactName).precision(.fractionLength(0...1)))
    }

    private func optionalBoolean(_ value: Bool?, trueText: String = "On", falseText: String = "Off") -> String {
        guard let value else { return "Not returned" }
        return value ? trueText : falseText
    }

    private func durationText(_ value: Double?) -> String {
        guard let value else { return "Not returned" }
        return Duration.seconds(value).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated))
    }

    private func formattedDate(_ date: Date?) -> String? {
        date?.formatted(date: .abbreviated, time: .shortened)
    }

    private func jsonObjectSummary(_ object: [String: CloudflareJSONValue]?) -> String? {
        guard let object, !object.isEmpty else { return nil }
        return CloudflareJSONValue.object(object).operationsDisplayText
    }

    private func planPrice(_ price: Double, currency: String?, frequency: String?) -> String {
        let amount = price.formatted(.currency(code: currency ?? "USD"))
        return frequency.map { "\(amount) / \($0)" } ?? amount
    }

    private func isScalar(_ value: CloudflareJSONValue) -> Bool {
        switch value {
        case .string, .int, .double, .bool: true
        case .object, .array, .null: false
        }
    }

    private func settingIcon(_ id: String) -> String {
        switch id {
        case "ssl", "min_tls_version", "tls_1_3": "lock.shield.fill"
        case "always_use_https", "automatic_https_rewrites": "arrowshape.turn.up.right.fill"
        case "http2", "http3", "ipv6": "network"
        case "brotli", "cache_level", "browser_cache_ttl": "bolt.fill"
        case "security_level", "browser_check": "shield.fill"
        case "development_mode": "hammer.fill"
        default: "switch.2"
        }
    }
}

private struct CloudflareZoneSettingEditor: View {
    let setting: CloudflareZoneSetting
    let onSave: (CloudflareJSONValue) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var textValue: String
    @State private var toggleValue: Bool
    @State private var showingConfirmation = false
    @State private var isSaving = false
    @State private var error: String?

    init(setting: CloudflareZoneSetting, onSave: @escaping (CloudflareJSONValue) async throws -> Void) {
        self.setting = setting
        self.onSave = onSave
        switch setting.value {
        case .string(let value):
            _textValue = State(initialValue: value)
            _toggleValue = State(initialValue: value.lowercased() == "on")
        case .int(let value):
            _textValue = State(initialValue: String(value))
            _toggleValue = State(initialValue: value != 0)
        case .double(let value):
            _textValue = State(initialValue: String(value))
            _toggleValue = State(initialValue: value != 0)
        case .bool(let value):
            _textValue = State(initialValue: value ? "true" : "false")
            _toggleValue = State(initialValue: value)
        case .object, .array, .null:
            _textValue = State(initialValue: setting.value.operationsDisplayText)
            _toggleValue = State(initialValue: false)
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(displayName.uppercased())
                            .font(.system(size: 10, weight: .heavy))
                            .tracking(1.0)
                            .foregroundStyle(CloudflareStyle.orange)
                        Text("Current value: \(setting.value.operationsDisplayText)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.46))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .cloudflarePanel(accentOpacity: 0.06)

                    editorPanel

                    if let error { CloudflareActionResultBanner(message: error, isError: true) }

                    Button {
                        error = nil
                        showingConfirmation = true
                    } label: {
                        HStack(spacing: 8) {
                            if isSaving { ProgressView().controlSize(.small).tint(.black) }
                            Text(isSaving ? "Saving" : "Save setting")
                                .font(.system(size: 13, weight: .heavy))
                        }
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(CloudflareStyle.orange)
                        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(isSaving || proposedValue == nil)
                }
                .padding()
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
        .confirmationDialog("Save \(displayName)?", isPresented: $showingConfirmation, titleVisibility: .visible) {
            Button("Save setting") { Task { await save() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This changes the live Cloudflare setting from \(setting.value.operationsDisplayText) to \(proposedValue?.operationsDisplayText ?? "an invalid value").")
        }
        .tint(CloudflareStyle.orange)
    }

    @ViewBuilder
    private var editorPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            if isOnOffString {
                Toggle("Enabled", isOn: $toggleValue)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.82))
            } else if case .bool = setting.value {
                Toggle("Enabled", isOn: $toggleValue)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.82))
            } else if !options.isEmpty {
                Picker("Value", selection: $textValue) {
                    ForEach(options, id: \.self) { option in
                        Text(option.replacingOccurrences(of: "_", with: " ").capitalized).tag(option)
                    }
                }
                .pickerStyle(.menu)
                .font(.system(size: 13, weight: .bold))
            } else {
                TextField("Value", text: $textValue)
                    .font(.system(size: 13, weight: .semibold, design: isNumeric ? .monospaced : .default))
                    .foregroundStyle(.white.opacity(0.84))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(isNumeric ? .decimalPad : .default)
                    .padding(13)
                    .background(Color.black.opacity(0.3))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            Text("Cloudflare validates plan availability and allowed values when you save.")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.white.opacity(0.34))
        }
        .padding(16)
        .cloudflarePanel()
    }

    private var displayName: String {
        setting.id.replacingOccurrences(of: "_", with: " ").capitalized
    }

    private var isOnOffString: Bool {
        guard case .string(let value) = setting.value else { return false }
        return value == "on" || value == "off"
    }

    private var isNumeric: Bool {
        switch setting.value {
        case .int, .double: true
        default: false
        }
    }

    private var options: [String] {
        switch setting.id {
        case "ssl": ["off", "flexible", "full", "strict", "origin_pull"]
        case "min_tls_version": ["1.0", "1.1", "1.2", "1.3"]
        case "security_level": ["off", "essentially_off", "low", "medium", "high", "under_attack"]
        case "cache_level": ["basic", "simplified", "aggressive"]
        case "pseudo_ipv4": ["off", "add_header", "overwrite_header"]
        case "rocket_loader": ["off", "manual", "on"]
        default: []
        }
    }

    private var proposedValue: CloudflareJSONValue? {
        switch setting.value {
        case .string:
            return .string(isOnOffString ? (toggleValue ? "on" : "off") : textValue)
        case .int:
            guard let value = Int64(textValue) else { return nil }
            return .int(value)
        case .double:
            guard let value = Double(textValue) else { return nil }
            return .double(value)
        case .bool:
            return .bool(toggleValue)
        case .object, .array, .null:
            return nil
        }
    }

    private func save() async {
        guard let proposedValue else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            try await onSave(proposedValue)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct CloudflareZoneDNSSettingsEditor: View {
    let settings: CloudflareZoneDNSSettings
    let onSave: ([String: CloudflareJSONValue]) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var flattenAllCNAMEs: Bool
    @State private var foundationDNS: Bool
    @State private var multiProvider: Bool
    @State private var secondaryOverrides: Bool
    @State private var zoneMode: String
    @State private var nameServerTTL: String
    @State private var showingConfirmation = false
    @State private var isSaving = false
    @State private var error: String?

    init(settings: CloudflareZoneDNSSettings, onSave: @escaping ([String: CloudflareJSONValue]) async throws -> Void) {
        self.settings = settings
        self.onSave = onSave
        _flattenAllCNAMEs = State(initialValue: settings.flattenAllCNAMEs ?? false)
        _foundationDNS = State(initialValue: settings.foundationDNS ?? false)
        _multiProvider = State(initialValue: settings.multiProvider ?? false)
        _secondaryOverrides = State(initialValue: settings.secondaryOverrides ?? false)
        _zoneMode = State(initialValue: settings.zoneMode ?? "standard")
        _nameServerTTL = State(initialValue: settings.nameServerTTL.map { String(Int($0)) } ?? "")
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    CloudflareWriteNotice()
                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Resolution", icon: "server.rack")
                        Divider().overlay(Color.white.opacity(0.06))
                        toggleRow("Flatten all CNAMEs", detail: "Flatten CNAME records throughout this zone.", value: $flattenAllCNAMEs)
                        toggleRow("Multi-provider DNS", detail: "Respect other providers’ apex NS records.", value: $multiProvider)
                        toggleRow("Secondary overrides", detail: "Allow proxied overrides for Secondary DNS.", value: $secondaryOverrides)
                        toggleRow("Foundation DNS", detail: "Use Advanced Nameservers when the plan supports it.", value: $foundationDNS)
                    }
                    .cloudflarePanel()

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Zone mode & TTL", icon: "timer")
                        Divider().overlay(Color.white.opacity(0.06))
                        VStack(alignment: .leading, spacing: 9) {
                            Text("ZONE MODE")
                                .font(.system(size: 9, weight: .heavy))
                                .tracking(0.8)
                                .foregroundStyle(.white.opacity(0.34))
                            Picker("Zone mode", selection: $zoneMode) {
                                Text("Standard").tag("standard")
                                Text("CDN only").tag("cdn_only")
                                Text("DNS only").tag("dns_only")
                            }
                            .pickerStyle(.segmented)
                        }
                        .padding(16)
                        Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("NAMESERVER TTL · 30–86,400 SECONDS")
                                .font(.system(size: 9, weight: .heavy))
                                .tracking(0.8)
                                .foregroundStyle(.white.opacity(0.34))
                            TextField("86400", text: $nameServerTTL)
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                .keyboardType(.numberPad)
                                .foregroundStyle(.white.opacity(0.84))
                                .padding(12)
                                .background(Color.black.opacity(0.3))
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .padding(16)
                    }
                    .cloudflarePanel()

                    if let error { CloudflareActionResultBanner(message: error, isError: true) }

                    Button {
                        error = nil
                        if changes.isEmpty {
                            error = "No DNS settings have changed."
                        } else if !ttlIsValid {
                            error = "Nameserver TTL must be between 30 and 86,400 seconds."
                        } else {
                            showingConfirmation = true
                        }
                    } label: {
                        HStack(spacing: 8) {
                            if isSaving { ProgressView().controlSize(.small).tint(.black) }
                            Text(isSaving ? "Saving" : "Save DNS settings")
                                .font(.system(size: 13, weight: .heavy))
                        }
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(CloudflareStyle.orange)
                        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(isSaving)
                }
                .padding()
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("DNS settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .confirmationDialog("Save DNS settings?", isPresented: $showingConfirmation, titleVisibility: .visible) {
            Button("Save changes") { Task { await save() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This updates \(changes.count) live DNS configuration \(changes.count == 1 ? "field" : "fields") for the zone.")
        }
        .tint(CloudflareStyle.orange)
    }

    private func toggleRow(_ title: String, detail: String, value: Binding<Bool>) -> some View {
        Toggle(isOn: value) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.82))
                Text(detail)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.34))
            }
        }
        .tint(CloudflareStyle.orange)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var changes: [String: CloudflareJSONValue] {
        var value: [String: CloudflareJSONValue] = [:]
        if flattenAllCNAMEs != settings.flattenAllCNAMEs { value["flatten_all_cnames"] = .bool(flattenAllCNAMEs) }
        if foundationDNS != settings.foundationDNS { value["foundation_dns"] = .bool(foundationDNS) }
        if multiProvider != settings.multiProvider { value["multi_provider"] = .bool(multiProvider) }
        if secondaryOverrides != settings.secondaryOverrides { value["secondary_overrides"] = .bool(secondaryOverrides) }
        if zoneMode != settings.zoneMode { value["zone_mode"] = .string(zoneMode) }
        if let ttl = Int64(nameServerTTL), Double(ttl) != settings.nameServerTTL { value["ns_ttl"] = .int(ttl) }
        return value
    }

    private var ttlIsValid: Bool {
        guard let value = Int(nameServerTTL) else { return false }
        return (30...86_400).contains(value)
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await onSave(changes)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
