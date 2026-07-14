import SwiftUI

@Observable
@MainActor
final class CloudflareAccountOperationsViewModel {
    let api: CloudflareAPI
    var account: CloudflareAccountSummary

    var members: [CloudflareAccountMember] = []
    var roles: [CloudflareAccountRole] = []
    var auditEvents: [CloudflareAccountAuditEvent] = []
    var membersError: String?
    var rolesError: String?
    var auditError: String?
    var accountError: String?
    var isLoading = true
    private var loadGeneration = 0

    init(api: CloudflareAPI, account: CloudflareAccountSummary) {
        self.api = api
        self.account = account
    }

    func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        membersError = nil
        rolesError = nil
        auditError = nil
        accountError = nil

        let before = Date()
        let since = Calendar.current.date(byAdding: .day, value: -7, to: before) ?? before.addingTimeInterval(-604_800)
        async let accountResult = capture { try await api.fetchAccountOperationsDetail(accountID: account.id) }
        async let memberResult = capture { try await api.fetchAccountMembers(accountID: account.id) }
        async let roleResult = capture { try await api.fetchAccountRoles(accountID: account.id) }
        async let auditResult = capture {
            try await api.fetchAccountAuditEvents(accountID: account.id, since: since, before: before)
        }

        let results = await (accountResult, memberResult, roleResult, auditResult)
        guard generation == loadGeneration else { return }

        switch results.0 {
        case .success(let refreshed): account = refreshed
        case .failure(let error): accountError = error.localizedDescription
        }
        switch results.1 {
        case .success(let value): members = value.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        case .failure(let error): membersError = error.localizedDescription
        }
        switch results.2 {
        case .success(let value): roles = value.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        case .failure(let error): rolesError = error.localizedDescription
        }
        switch results.3 {
        case .success(let value): auditEvents = value
        case .failure(let error): auditError = error.localizedDescription
        }

        isLoading = false
    }

    private func capture<Value>(_ operation: () async throws -> Value) async -> Result<Value, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }
}

struct CloudflareAccountOperationsView: View {
    let api: CloudflareAPI
    let email: String

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareAccountOperationsViewModel
    @State private var selectedAuditEvent: CloudflareAccountAuditEvent?
    @State private var selectedMember: CloudflareAccountMember?
    @State private var selectedRole: CloudflareAccountRole?

    init(api: CloudflareAPI, account: CloudflareAccountSummary, email: String) {
        self.api = api
        self.email = email
        _viewModel = State(wrappedValue: CloudflareAccountOperationsViewModel(api: api, account: account))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    accountHeader

                    if let error = viewModel.accountError {
                        CloudflareActionResultBanner(message: "Account refresh: \(error)", isError: true)
                    }

                    identityPanel
                    managementPanel
                    membersPanel
                    rolesPanel
                    auditPanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 820 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Account operations")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .sheet(item: $selectedAuditEvent) { event in
            NavigationStack {
                CloudflareAuditEventDetailView(event: event)
            }
            .preferredColorScheme(.dark)
        }
        .sheet(item: $selectedMember) { member in
            NavigationStack {
                CloudflareAccountMemberDetailView(member: member)
            }
            .preferredColorScheme(.dark)
        }
        .sheet(item: $selectedRole) { role in
            NavigationStack {
                CloudflareAccountRoleDetailView(role: role)
            }
            .preferredColorScheme(.dark)
        }
        .tint(CloudflareStyle.orange)
    }

    private var accountHeader: some View {
        HStack(alignment: .top, spacing: 13) {
            AppIconTile(icon: "building.2.crop.circle.fill", tint: CloudflareStyle.orange, size: 50)

            VStack(alignment: .leading, spacing: 5) {
                Text(viewModel.account.name)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                Text(email)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.38))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("ACCESS & AUDIT")
                    .font(.system(size: 9, weight: .semibold))
                    .tracking(1.0)
                    .foregroundStyle(CloudflareStyle.orange.opacity(0.8))
            }

            Spacer(minLength: 8)
            if viewModel.isLoading {
                ProgressView().controlSize(.small).tint(CloudflareStyle.orange)
            } else {
                CloudflareStatusPill(text: "LIVE", color: CloudflareStyle.green)
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private var identityPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Account metadata", icon: "info.circle.fill")
            panelDivider
            CloudflareDetailRow(icon: "number", title: "Account ID", value: viewModel.account.id)
            CloudflareDetailRow(icon: "building.2", title: "Name", value: viewModel.account.name)
            CloudflareDetailRow(icon: "person.text.rectangle", title: "Type", value: viewModel.account.type?.capitalized ?? "Not returned")
            CloudflareDetailRow(icon: "envelope.fill", title: "API user", value: email)
            if let date = viewModel.account.createdDate {
                CloudflareDetailRow(icon: "calendar", title: "Created", value: date.formatted(date: .abbreviated, time: .shortened))
            }
            CloudflareDetailRow(
                icon: "lock.shield.fill",
                title: "Two-factor requirement",
                value: booleanText(viewModel.account.settings?.enforceTwoFactor, trueText: "Required", falseText: "Not required"),
                valueColor: viewModel.account.settings?.enforceTwoFactor == true ? CloudflareStyle.green : .white.opacity(0.76)
            )
            CloudflareDetailRow(
                icon: "exclamationmark.bubble.fill",
                title: "Abuse contact",
                value: viewModel.account.settings?.abuseContactEmail ?? "Not returned"
            )
        }
        .cloudflarePanel()
    }

    @ViewBuilder
    private var managementPanel: some View {
        if viewModel.account.managedBy != nil {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Managed by", icon: "point.3.connected.trianglepath.dotted")
                panelDivider
                CloudflareDetailRow(
                    icon: "building.columns.fill",
                    title: "Parent organization",
                    value: viewModel.account.managedBy?.parentOrganizationName ?? "Name not returned"
                )
                CloudflareDetailRow(
                    icon: "number",
                    title: "Parent organization ID",
                    value: viewModel.account.managedBy?.parentOrganizationID ?? "Not returned"
                )
            }
            .cloudflarePanel()
        }
    }

    private var membersPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Members", icon: "person.2.fill", count: viewModel.members.count)
            panelDivider
            if viewModel.isLoading && viewModel.members.isEmpty {
                sectionLoading
            } else if let error = viewModel.membersError {
                sectionError(title: "Members unavailable", message: error)
            } else if viewModel.members.isEmpty {
                CloudflareEmptySection(icon: "person.2.slash", title: "No members returned", message: "Cloudflare did not return any memberships for this account.")
            } else {
                ForEach(Array(viewModel.members.enumerated()), id: \.element.id) { index, member in
                    Button { selectedMember = member } label: {
                        VStack(alignment: .leading, spacing: 9) {
                            HStack(alignment: .top, spacing: 11) {
                                Image(systemName: member.user?.twoFactorAuthenticationEnabled == true ? "person.badge.shield.checkmark.fill" : "person.crop.circle.fill")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(member.user?.twoFactorAuthenticationEnabled == true ? CloudflareStyle.green : CloudflareStyle.orange)
                                    .frame(width: 36, height: 36)
                                    .background(Color.white.opacity(0.045))
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                                VStack(alignment: .leading, spacing: 3) {
                                    Text(member.displayName)
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(.white.opacity(0.88))
                                    Text(member.resolvedEmail)
                                        .font(.system(size: 11, weight: .medium))
                                        .foregroundStyle(.white.opacity(0.38))
                                    Text(member.roles.isEmpty ? "Policy-based access" : member.roles.map(\.name).joined(separator: ", "))
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundStyle(.white.opacity(0.46))
                                        .lineLimit(2)
                                }
                                Spacer(minLength: 6)
                                CloudflareStatusPill(
                                    text: member.status?.uppercased() ?? "UNKNOWN",
                                    color: member.status == "accepted" ? CloudflareStyle.green : CloudflareStyle.amber
                                )
                            }

                            HStack(spacing: 8) {
                                accessPill("\(member.roles.count) roles", icon: "person.badge.key.fill")
                                accessPill("\(member.policies.count) policies", icon: "checklist.checked")
                                accessPill(member.user?.twoFactorAuthenticationEnabled == true ? "2FA on" : "2FA off", icon: "lock.shield")
                                Spacer(minLength: 0)
                                CloudflareChevron()
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 13)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if index < viewModel.members.count - 1 { insetDivider }
                }
            }
        }
        .cloudflarePanel()
    }

    private var rolesPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Available roles", icon: "person.badge.key.fill", count: viewModel.roles.count)
            panelDivider
            if viewModel.isLoading && viewModel.roles.isEmpty {
                sectionLoading
            } else if let error = viewModel.rolesError {
                sectionError(title: "Roles unavailable", message: error)
            } else if viewModel.roles.isEmpty {
                CloudflareEmptySection(icon: "key.slash", title: "No roles returned", message: "No account roles were available to this API user.")
            } else {
                ForEach(Array(viewModel.roles.enumerated()), id: \.element.id) { index, role in
                    Button { selectedRole = role } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(role.name)
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(.white.opacity(0.86))
                                Spacer()
                                Text("\(role.permissions.count) grants")
                                    .font(.system(size: 9, weight: .semibold).monospacedDigit())
                                    .foregroundStyle(CloudflareStyle.orange.opacity(0.76))
                                CloudflareChevron()
                            }
                            if let description = role.description, !description.isEmpty {
                                Text(description)
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.36))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            if !role.permissions.isEmpty {
                                Text(permissionSummary(role))
                                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.42))
                                    .lineLimit(3)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if index < viewModel.roles.count - 1 { insetDivider }
                }
            }
        }
        .cloudflarePanel()
    }

    private var auditPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Audit log · 7 days", icon: "clock.arrow.circlepath", count: viewModel.auditEvents.count)
            panelDivider
            if viewModel.isLoading && viewModel.auditEvents.isEmpty {
                sectionLoading
            } else if let error = viewModel.auditError {
                sectionError(title: "Audit log unavailable", message: error)
            } else if viewModel.auditEvents.isEmpty {
                CloudflareEmptySection(icon: "checkmark.shield.fill", title: "No recent activity", message: "No account audit events were returned for the last seven days.")
            } else {
                ForEach(Array(viewModel.auditEvents.enumerated()), id: \.element.id) { index, event in
                    Button { selectedAuditEvent = event } label: {
                        HStack(spacing: 11) {
                            Image(systemName: auditIcon(event.action?.type))
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(auditColor(event.action?.result))
                                .frame(width: 34, height: 34)
                                .background(auditColor(event.action?.result).opacity(0.1))
                                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(event.action?.description ?? event.action?.type?.capitalized ?? "Cloudflare activity")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(.white.opacity(0.84))
                                    .lineLimit(2)
                                Text(auditSubtitle(event))
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.36))
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 7)
                            CloudflareChevron()
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if index < viewModel.auditEvents.count - 1 { insetDivider }
                }
            }
        }
        .cloudflarePanel()
    }

    private var panelDivider: some View {
        Divider().overlay(Color.white.opacity(0.06))
    }

    private var insetDivider: some View {
        Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 63)
    }

    private var sectionLoading: some View {
        ProgressView()
            .tint(CloudflareStyle.orange)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 26)
    }

    private func sectionError(title: String, message: String) -> some View {
        CloudflareEmptySection(icon: "exclamationmark.triangle.fill", title: title, message: message)
    }

    private func accessPill(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(.white.opacity(0.42))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.045), in: Capsule())
    }

    private func booleanText(_ value: Bool?, trueText: String = "On", falseText: String = "Off") -> String {
        guard let value else { return "Not returned" }
        return value ? trueText : falseText
    }

    private func permissionSummary(_ role: CloudflareAccountRole) -> String {
        role.permissions
            .sorted { $0.key < $1.key }
            .map { key, grant in
                let mode = grant.write == true ? "read/write" : grant.read == true ? "read" : "none"
                return "\(key.replacingOccurrences(of: "_", with: " ")): \(mode)"
            }
            .joined(separator: "  ·  ")
    }

    private func auditIcon(_ type: String?) -> String {
        switch type?.lowercased() {
        case "create": "plus.circle.fill"
        case "delete": "trash.fill"
        case "update": "pencil.circle.fill"
        default: "eye.fill"
        }
    }

    private func auditColor(_ result: String?) -> Color {
        result?.lowercased() == "failure" ? CloudflareStyle.red : CloudflareStyle.green
    }

    private func auditSubtitle(_ event: CloudflareAccountAuditEvent) -> String {
        let actor = event.actor?.email ?? event.actor?.type ?? "Unknown actor"
        let date = event.date?.formatted(date: .abbreviated, time: .shortened) ?? "Unknown time"
        return "\(actor) · \(date)"
    }
}

private struct CloudflareAccountMemberDetailView: View {
    let member: CloudflareAccountMember

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Member", icon: "person.crop.circle.fill")
                        Divider().overlay(Color.white.opacity(0.06))
                        CloudflareDetailRow(icon: "person.fill", title: "Name", value: member.displayName)
                        CloudflareDetailRow(icon: "envelope.fill", title: "Email", value: member.resolvedEmail)
                        CloudflareDetailRow(icon: "number", title: "Membership ID", value: member.id)
                        if let userID = member.user?.id {
                            CloudflareDetailRow(icon: "number", title: "User ID", value: userID)
                        }
                        CloudflareDetailRow(icon: "checkmark.circle.fill", title: "Status", value: member.status?.capitalized ?? "Not returned")
                        CloudflareDetailRow(
                            icon: "lock.shield.fill",
                            title: "Two-factor authentication",
                            value: member.user?.twoFactorAuthenticationEnabled == true ? "Enabled" : "Not enabled",
                            valueColor: member.user?.twoFactorAuthenticationEnabled == true ? CloudflareStyle.green : CloudflareStyle.amber
                        )
                    }
                    .cloudflarePanel()

                    collectionPanel(
                        title: "Assigned roles",
                        icon: "person.badge.key.fill",
                        values: member.roles.map { role in
                            (role.name, role.description ?? role.id)
                        },
                        emptyMessage: "This membership has no legacy roles. Access may be policy based."
                    )

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Policies", icon: "checklist.checked", count: member.policies.count)
                        Divider().overlay(Color.white.opacity(0.06))
                        if member.policies.isEmpty {
                            CloudflareEmptySection(icon: "checklist", title: "No policies returned", message: "This membership may use legacy account roles instead.")
                        } else {
                            ForEach(Array(member.policies.enumerated()), id: \.offset) { index, policy in
                                VStack(alignment: .leading, spacing: 7) {
                                    HStack {
                                        Text(policy.access?.uppercased() ?? "POLICY")
                                            .font(.system(size: 10, weight: .semibold))
                                            .foregroundStyle(policy.access == "deny" ? CloudflareStyle.red : CloudflareStyle.green)
                                        Spacer()
                                        Text(policy.id)
                                            .font(.system(size: 8, weight: .semibold, design: .monospaced))
                                            .foregroundStyle(.white.opacity(0.28))
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                    if !policy.permissionGroups.isEmpty {
                                        policyValue("Permission groups", value: .array(policy.permissionGroups))
                                    }
                                    if !policy.resourceGroups.isEmpty {
                                        policyValue("Resource groups", value: .array(policy.resourceGroups))
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                                if index < member.policies.count - 1 {
                                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 16)
                                }
                            }
                        }
                    }
                    .cloudflarePanel()
                }
                .padding()
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Member access")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        .tint(CloudflareStyle.orange)
    }

    private func collectionPanel(
        title: String,
        icon: String,
        values: [(String, String)],
        emptyMessage: String
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon, count: values.count)
            Divider().overlay(Color.white.opacity(0.06))
            if values.isEmpty {
                CloudflareEmptySection(icon: icon, title: "None returned", message: emptyMessage)
            } else {
                ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                    CloudflareDetailRow(icon: "key.fill", title: value.0, value: value.1)
                }
            }
        }
        .cloudflarePanel()
    }

    private func policyValue(_ title: String, value: CloudflareJSONValue) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title.uppercased())
                .font(.system(size: 8, weight: .semibold))
                .tracking(0.7)
                .foregroundStyle(.white.opacity(0.3))
            Text(value.operationsDisplayText)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.white.opacity(0.52))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
    }
}

private struct CloudflareAccountRoleDetailView: View {
    let role: CloudflareAccountRole

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Role", icon: "person.badge.key.fill")
                        Divider().overlay(Color.white.opacity(0.06))
                        CloudflareDetailRow(icon: "tag.fill", title: "Name", value: role.name)
                        CloudflareDetailRow(icon: "number", title: "Role ID", value: role.id)
                        if let description = role.description {
                            CloudflareDetailRow(icon: "text.alignleft", title: "Description", value: description)
                        }
                    }
                    .cloudflarePanel()

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Permission grants", icon: "checkmark.shield.fill", count: role.permissions.count)
                        Divider().overlay(Color.white.opacity(0.06))
                        if role.permissions.isEmpty {
                            CloudflareEmptySection(icon: "lock.slash", title: "No grants returned", message: "Cloudflare did not include permission grants for this role.")
                        } else {
                            ForEach(Array(role.permissions.sorted { $0.key < $1.key }.enumerated()), id: \.element.key) { index, entry in
                                HStack(spacing: 11) {
                                    Image(systemName: entry.value.write == true ? "pencil.and.list.clipboard" : "eye.fill")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(entry.value.write == true ? CloudflareStyle.orange : CloudflareStyle.green)
                                        .frame(width: 34, height: 34)
                                        .background(Color.white.opacity(0.045))
                                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                                    Text(entry.key.replacingOccurrences(of: "_", with: " ").capitalized)
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundStyle(.white.opacity(0.82))
                                    Spacer()
                                    CloudflareStatusPill(
                                        text: entry.value.write == true ? "READ / WRITE" : entry.value.read == true ? "READ" : "NONE",
                                        color: entry.value.write == true ? CloudflareStyle.orange : entry.value.read == true ? CloudflareStyle.green : .white.opacity(0.32)
                                    )
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                if index < role.permissions.count - 1 {
                                    Divider().overlay(Color.white.opacity(0.055)).padding(.leading, 61)
                                }
                            }
                        }
                    }
                    .cloudflarePanel()
                }
                .padding()
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Role details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        .tint(CloudflareStyle.orange)
    }
}

private struct CloudflareAuditEventDetailView: View {
    let event: CloudflareAccountAuditEvent

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    detailPanel(title: "Action", icon: "bolt.fill", rows: [
                        ("Description", event.action?.description),
                        ("Type", event.action?.type),
                        ("Result", event.action?.result),
                        ("Time", event.date?.formatted(date: .complete, time: .standard)),
                        ("Event ID", event.eventID)
                    ])
                    detailPanel(title: "Actor", icon: "person.fill", rows: [
                        ("Email", event.actor?.email),
                        ("Actor ID", event.actor?.id),
                        ("Type", event.actor?.type),
                        ("Context", event.actor?.context),
                        ("IP address", event.actor?.ipAddress),
                        ("Token name", event.actor?.tokenName),
                        ("Token ID", event.actor?.tokenID)
                    ])
                    detailPanel(title: "Request", icon: "network", rows: [
                        ("Method", event.raw?.method),
                        ("Status code", event.raw?.statusCode.map(String.init)),
                        ("URI", event.raw?.uri),
                        ("Ray ID", event.raw?.cfRayID),
                        ("User agent", event.raw?.userAgent)
                    ])
                    detailPanel(title: "Resource", icon: "cube.fill", rows: [
                        ("Product", event.resource?.product),
                        ("Type", event.resource?.type),
                        ("Resource ID", event.resource?.id),
                        ("Scope", event.resource?.scope?.operationsDisplayText),
                        ("Zone", event.zone?.name),
                        ("Zone ID", event.zone?.id)
                    ])
                }
                .padding()
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Audit event")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }
                    .font(.system(size: 12, weight: .bold))
            }
        }
        .tint(CloudflareStyle.orange)
    }

    private func detailPanel(title: String, icon: String, rows: [(String, String?)]) -> some View {
        let populated = rows.filter { $0.1?.isEmpty == false }
        return VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon)
            Divider().overlay(Color.white.opacity(0.06))
            ForEach(Array(populated.enumerated()), id: \.offset) { _, row in
                CloudflareDetailRow(icon: "circle.fill", title: row.0, value: row.1 ?? "")
            }
        }
        .cloudflarePanel()
    }
}
