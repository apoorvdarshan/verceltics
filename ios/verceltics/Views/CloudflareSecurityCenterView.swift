import SwiftUI

@Observable
@MainActor
final class CloudflareSecurityCenterViewModel {
    let api: CloudflareAPI
    let zone: CloudflareZone

    var snapshot = CloudflareSecuritySnapshot()
    var isLoading = true
    var workingAction: String?
    var actionMessage: String?
    var actionFailed = false

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
    }

    func load() async {
        isLoading = true
        snapshot = CloudflareSecuritySnapshot()

        do { snapshot.securityLevel = try await api.fetchZoneSecurityLevel(zoneID: zone.id) }
        catch { addWarning("Security level", error) }
        await load(.wafRulesets)
        await load(.accessRules)
        await load(.rateLimits)
        await load(.certificates)
        await load(.pageShield)
        await load(.botManagement)
        await load(.apiShield)

        isLoading = false
    }

    func updateSecurityLevel(_ level: String, confirmation: CloudflareMutationConfirmation) async {
        await perform("security-level", success: "Security level updated to \(level.replacingOccurrences(of: "_", with: " ")).") {
            snapshot.securityLevel = try await api.updateZoneSecurityLevel(
                zoneID: zone.id,
                level: level,
                confirmation: confirmation
            )
        }
    }

    func createAccessRule(
        target: String,
        value: String,
        mode: String,
        notes: String?,
        confirmation: CloudflareMutationConfirmation
    ) async {
        await perform("access-add", success: "IP access rule created.") {
            _ = try await api.createZoneAccessRule(
                zoneID: zone.id,
                target: target,
                value: value,
                mode: mode,
                notes: notes,
                confirmation: confirmation
            )
            await load(.accessRules)
        }
    }

    func deleteAccessRule(
        _ rule: CloudflareSecurityItem,
        confirmation: CloudflareMutationConfirmation
    ) async {
        await perform("access-\(rule.id)", success: "IP access rule deleted.") {
            try await api.deleteZoneAccessRule(
                zoneID: zone.id,
                ruleID: rule.id,
                confirmation: confirmation
            )
            snapshot.accessRules.removeAll { $0.id == rule.id }
        }
    }

    private func load(_ category: CloudflareSecurityCategory) async {
        do {
            let values = try await api.fetchZoneSecurityItems(zoneID: zone.id, category: category)
            switch category {
            case .wafRulesets: snapshot.rulesets = values
            case .accessRules: snapshot.accessRules = values
            case .rateLimits: snapshot.rateLimits = values
            case .certificates: snapshot.certificates = values
            case .pageShield: snapshot.pageShield = values
            case .botManagement: snapshot.botManagement = values
            case .apiShield: snapshot.apiShield = values
            }
        } catch {
            addWarning(category.rawValue, error)
        }
    }

    private func perform(_ id: String, success: String, operation: () async throws -> Void) async {
        workingAction = id
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

    private func addWarning(_ section: String, _ error: Error) {
        snapshot.warnings.append("\(section): \(error.localizedDescription)")
    }
}

struct CloudflareSecurityCenterView: View {
    let api: CloudflareAPI
    let zone: CloudflareZone

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareSecurityCenterViewModel
    @State private var showingAccessRuleEditor = false
    @State private var deletingAccessRule: CloudflareSecurityItem?
    @State private var pendingSecurityLevel: String?

    init(api: CloudflareAPI, zone: CloudflareZone) {
        self.api = api
        self.zone = zone
        _viewModel = State(wrappedValue: CloudflareSecurityCenterViewModel(api: api, zone: zone))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    postureRail
                    securityLevelPanel

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }
                    if !viewModel.snapshot.warnings.isEmpty {
                        warningPanel
                    }

                    securityPanel(
                        title: "WAF rulesets",
                        icon: "shield.checkered",
                        items: viewModel.snapshot.rulesets,
                        emptyMessage: "No WAF rulesets were returned for this zone."
                    ) { item in
                        CloudflareRulesetDetailView(api: api, zoneID: zone.id, ruleset: item)
                    }

                    accessRulesPanel

                    securityPanel(
                        title: "Rate limits",
                        icon: "speedometer",
                        items: viewModel.snapshot.rateLimits,
                        emptyMessage: "No legacy rate limits were returned. Ruleset-based rate limiting may appear under WAF."
                    ) { item in
                        CloudflareSecurityItemDetailView(title: item.title, item: item)
                    }

                    securityPanel(
                        title: "Certificates",
                        icon: "checkmark.seal.fill",
                        items: viewModel.snapshot.certificates,
                        emptyMessage: "No edge or custom certificate packs were returned."
                    ) { item in
                        CloudflareSecurityItemDetailView(title: item.title, item: item)
                    }

                    securityPanel(
                        title: "Page Shield",
                        icon: "doc.text.magnifyingglass",
                        items: viewModel.snapshot.pageShield,
                        emptyMessage: "Page Shield policies are unavailable or not configured."
                    ) { item in
                        CloudflareSecurityItemDetailView(title: item.title, item: item)
                    }

                    securityPanel(
                        title: "Bot management",
                        icon: "ant.fill",
                        items: viewModel.snapshot.botManagement,
                        emptyMessage: "Bot management configuration is unavailable on this plan."
                    ) { item in
                        CloudflareSecurityItemDetailView(title: item.title, item: item)
                    }

                    securityPanel(
                        title: "API Shield",
                        icon: "server.rack",
                        items: viewModel.snapshot.apiShield,
                        emptyMessage: "API Shield configuration is unavailable or not configured."
                    ) { item in
                        CloudflareSecurityItemDetailView(title: item.title, item: item)
                    }
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Security")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .sheet(isPresented: $showingAccessRuleEditor) {
            CloudflareAccessRuleEditor { target, value, mode, notes in
                let path = "/zones/\(zone.id)/firewall/access_rules/rules"
                Task {
                    await viewModel.createAccessRule(
                        target: target,
                        value: value,
                        mode: mode,
                        notes: notes,
                        confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
                    )
                }
            }
        }
        .confirmationDialog(
            "Change the zone security level?",
            isPresented: Binding(
                get: { pendingSecurityLevel != nil },
                set: { if !$0 { pendingSecurityLevel = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Apply Security Level") {
                guard let level = pendingSecurityLevel else { return }
                pendingSecurityLevel = nil
                let path = "/zones/\(zone.id)/settings/security_level"
                Task {
                    await viewModel.updateSecurityLevel(
                        level,
                        confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
                    )
                }
            }
            Button("Cancel", role: .cancel) { pendingSecurityLevel = nil }
        } message: {
            Text("This immediately changes how Cloudflare challenges or blocks requests for \(zone.name).")
        }
        .confirmationDialog(
            "Delete this IP access rule?",
            isPresented: Binding(
                get: { deletingAccessRule != nil },
                set: { if !$0 { deletingAccessRule = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete Rule", role: .destructive) {
                guard let rule = deletingAccessRule else { return }
                deletingAccessRule = nil
                let path = "/zones/\(zone.id)/firewall/access_rules/rules/\(rule.id)"
                Task {
                    await viewModel.deleteAccessRule(
                        rule,
                        confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
                    )
                }
            }
            Button("Cancel", role: .cancel) { deletingAccessRule = nil }
        } message: {
            Text("Traffic matching this rule will immediately stop using its current action.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var postureRail: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(zone.name)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                    Text("SECURITY CONTROL PLANE")
                        .font(.system(size: 8, weight: .semibold, design: .monospaced))
                        .tracking(1.1)
                        .foregroundStyle(CloudflareStyle.orange)
                }
                Spacer()
                if viewModel.isLoading {
                    ProgressView().tint(CloudflareStyle.orange)
                } else {
                    CloudflareStatusPill(text: "\(viewModel.snapshot.totalItems) ITEMS", color: CloudflareStyle.green)
                }
            }
            HStack(spacing: 8) {
                postureValue("WAF", viewModel.snapshot.rulesets.count)
                postureValue("ACCESS", viewModel.snapshot.accessRules.count)
                postureValue("RATE", viewModel.snapshot.rateLimits.count)
                postureValue("TLS", viewModel.snapshot.certificates.count)
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func postureValue(_ title: String, _ value: Int) -> some View {
        VStack(spacing: 4) {
            Text(value.formatted())
                .font(.system(size: 18, weight: .semibold, design: .default).monospacedDigit())
                .foregroundStyle(.white)
            Text(title)
                .font(.system(size: 7, weight: .semibold, design: .monospaced))
                .tracking(0.7)
                .foregroundStyle(.white.opacity(0.3))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 9)
        .background(Color.white.opacity(0.035))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var securityLevelPanel: some View {
        HStack(spacing: 12) {
            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(CloudflareStyle.orange)
                .frame(width: 40, height: 40)
                .background(CloudflareStyle.orange.opacity(0.11))
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text("Security level")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white.opacity(0.86))
                Text((viewModel.snapshot.securityLevel ?? "Not returned").replacingOccurrences(of: "_", with: " ").capitalized)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.38))
            }
            Spacer()
            if viewModel.workingAction == "security-level" {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                Menu {
                    ForEach(["essentially_off", "low", "medium", "high", "under_attack"], id: \.self) { level in
                        Button(level.replacingOccurrences(of: "_", with: " ").capitalized) {
                            pendingSecurityLevel = level
                        }
                    }
                } label: {
                    Text("Change")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(CloudflareStyle.orange)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 8)
                        .background(CloudflareStyle.orange.opacity(0.1))
                        .clipShape(Capsule())
                }
            }
        }
        .padding(16)
        .cloudflarePanel()
    }

    private var accessRulesPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "IP access rules",
                icon: "hand.raised.fill",
                count: viewModel.snapshot.accessRules.count,
                actionTitle: "Add"
            ) {
                showingAccessRuleEditor = true
            }
            Divider().overlay(Color.white.opacity(0.06))
            if viewModel.snapshot.accessRules.isEmpty {
                CloudflareEmptySection(
                    icon: "hand.raised",
                    title: "No IP access rules",
                    message: "Create a rule for an IP, network, ASN or country."
                )
            } else {
                ForEach(viewModel.snapshot.accessRules) { item in
                    CloudflareResourceRow(
                        icon: "hand.raised.fill",
                        title: item.title,
                        subtitle: [item.status, item.subtitle].compactMap { $0 }.joined(separator: " · "),
                        tint: securityTint(item.status)
                    ) {
                        if viewModel.workingAction == "access-\(item.id)" {
                            ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
                        } else {
                            Button(role: .destructive) { deletingAccessRule = item } label: {
                                Image(systemName: "trash")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(CloudflareStyle.red.opacity(0.82))
                                    .frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func securityPanel<Destination: View>(
        title: String,
        icon: String,
        items: [CloudflareSecurityItem],
        emptyMessage: String,
        @ViewBuilder destination: @escaping (CloudflareSecurityItem) -> Destination
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon, count: items.count)
            Divider().overlay(Color.white.opacity(0.06))
            if items.isEmpty {
                CloudflareEmptySection(icon: icon, title: "Nothing returned", message: emptyMessage)
            } else {
                ForEach(items) { item in
                    NavigationLink { destination(item) } label: {
                        CloudflareResourceRow(
                            icon: icon,
                            title: item.title,
                            subtitle: [item.status, item.subtitle].compactMap { $0 }.joined(separator: " · "),
                            tint: securityTint(item.status)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .cloudflarePanel()
    }

    private var warningPanel: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 7) {
                ForEach(viewModel.snapshot.warnings, id: \.self) { warning in
                    Text(warning)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.38))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 10)
        } label: {
            Label("Plan-limited security products", systemImage: "lock.trianglebadge.exclamationmark.fill")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(CloudflareStyle.amber)
        }
        .padding(16)
        .cloudflarePanel()
    }

    private func securityTint(_ status: String?) -> Color {
        switch status?.lowercased() {
        case "active", "enabled", "allow", "whitelist": CloudflareStyle.green
        case "block", "challenge", "js_challenge", "under_attack": CloudflareStyle.red
        default: CloudflareStyle.orange
        }
    }
}

@Observable
@MainActor
private final class CloudflareRulesetDetailViewModel {
    let api: CloudflareAPI
    let zoneID: String
    let ruleset: CloudflareSecurityItem

    var rules: [CloudflareSecurityItem] = []
    var isLoading = true
    var error: String?

    init(api: CloudflareAPI, zoneID: String, ruleset: CloudflareSecurityItem) {
        self.api = api
        self.zoneID = zoneID
        self.ruleset = ruleset
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            rules = try await api.fetchZoneRulesetRules(zoneID: zoneID, rulesetID: ruleset.id)
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

private struct CloudflareRulesetDetailView: View {
    @State private var viewModel: CloudflareRulesetDetailViewModel

    init(api: CloudflareAPI, zoneID: String, ruleset: CloudflareSecurityItem) {
        _viewModel = State(
            wrappedValue: CloudflareRulesetDetailViewModel(api: api, zoneID: zoneID, ruleset: ruleset)
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    CloudflareSecurityItemDetailCard(item: viewModel.ruleset)

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(
                            title: "Rules",
                            icon: "list.bullet.rectangle.fill",
                            count: viewModel.rules.count
                        )
                        Divider().overlay(Color.white.opacity(0.06))
                        if viewModel.isLoading {
                            ProgressView().tint(CloudflareStyle.orange).padding(.vertical, 34)
                        } else if let error = viewModel.error {
                            CloudflareEmptySection(
                                icon: "exclamationmark.triangle.fill",
                                title: "Rules unavailable",
                                message: error
                            )
                        } else if viewModel.rules.isEmpty {
                            CloudflareEmptySection(
                                icon: "list.bullet.rectangle",
                                title: "No rules",
                                message: "This ruleset did not return individual rules."
                            )
                        } else {
                            ForEach(viewModel.rules) { rule in
                                NavigationLink {
                                    CloudflareSecurityItemDetailView(title: rule.title, item: rule)
                                } label: {
                                    CloudflareResourceRow(
                                        icon: "shield.lefthalf.filled",
                                        title: rule.title,
                                        subtitle: [rule.status, rule.subtitle].compactMap { $0 }.joined(separator: " · "),
                                        tint: rule.status?.lowercased() == "block" ? CloudflareStyle.red : CloudflareStyle.orange
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .cloudflarePanel()
                }
                .padding()
            }
        }
        .navigationTitle("WAF ruleset")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
    }
}

private struct CloudflareSecurityItemDetailView: View {
    let title: String
    let item: CloudflareSecurityItem

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    CloudflareSecurityItemDetailCard(item: item)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("RAW RETURNED CONFIGURATION")
                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                            .tracking(0.9)
                            .foregroundStyle(CloudflareStyle.orange)
                        Text(prettySecurityJSON(item.raw))
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.68))
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .cloudflarePanel()
                }
                .padding()
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

private struct CloudflareSecurityItemDetailCard: View {
    let item: CloudflareSecurityItem

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Image(systemName: "shield.checkered")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .frame(width: 42, height: 42)
                    .background(CloudflareStyle.orange.opacity(0.11))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                    if let subtitle = item.subtitle {
                        Text(subtitle)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                }
                Spacer()
                if let status = item.status {
                    CloudflareStatusPill(text: status.uppercased(), color: CloudflareStyle.orange)
                }
            }
            Text(item.id)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.28))
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.07)
    }
}

private struct CloudflareAccessRuleEditor: View {
    let save: (String, String, String, String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var target = "ip"
    @State private var value = ""
    @State private var mode = "block"
    @State private var notes = ""
    @State private var isConfirming = false

    private let targets = ["ip", "ip_range", "asn", "country"]
    private let modes = ["block", "challenge", "js_challenge", "whitelist"]

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Add IP access rule")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(.white)
                            Text("The action applies immediately to matching requests for this zone.")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(.white.opacity(0.4))
                        }

                        pickerPanel("TARGET", values: targets, selection: $target)
                        securityTextField(valuePlaceholder, text: $value)
                            .keyboardType(target == "asn" ? .numberPad : .asciiCapable)
                        pickerPanel("ACTION", values: modes, selection: $mode)
                        securityTextField("Optional note", text: $notes)
                    }
                    .padding()
                }
            }
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        isConfirming = true
                    }
                    .fontWeight(.bold)
                    .disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .confirmationDialog(
                "Create this IP access rule?",
                isPresented: $isConfirming,
                titleVisibility: .visible
            ) {
                Button("Create Rule") {
                    save(
                        target,
                        value.trimmingCharacters(in: .whitespacesAndNewlines),
                        mode,
                        notes.isEmpty ? nil : notes
                    )
                    dismiss()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The \(mode.replacingOccurrences(of: "_", with: " ")) action applies immediately to matching traffic.")
            }
        }
        .presentationDetents([.large])
        .tint(CloudflareStyle.orange)
    }

    private var valuePlaceholder: String {
        switch target {
        case "ip": "IP address, e.g. 203.0.113.10"
        case "ip_range": "CIDR range, e.g. 203.0.113.0/24"
        case "asn": "ASN number"
        case "country": "Two-letter country code"
        default: "Value"
        }
    }

    private func pickerPanel(_ title: String, values: [String], selection: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 8, weight: .semibold, design: .monospaced))
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.35))
            Picker(title, selection: selection) {
                ForEach(values, id: \.self) { value in
                    Text(value.replacingOccurrences(of: "_", with: " ").capitalized).tag(value)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private func securityTextField(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 13, weight: .semibold, design: .monospaced))
            .foregroundStyle(.white)
            .padding(14)
            .background(Color.white.opacity(0.055))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private func prettySecurityJSON(_ value: CloudflareJSONValue) -> String {
    guard let data = try? JSONEncoder().encode(value),
          let object = try? JSONSerialization.jsonObject(with: data),
          let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
          let string = String(data: pretty, encoding: .utf8) else { return "Unable to format response." }
    return string
}
