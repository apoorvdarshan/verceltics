import SwiftUI

struct CloudflareDNSRecordEditor: View {
    let zoneName: String
    let record: CloudflareDNSRecord?
    let onSave: (CloudflareDNSRecordInput) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var type: String
    @State private var name: String
    @State private var content: String
    @State private var ttl: Int
    @State private var proxied: Bool
    @State private var comment: String
    @State private var tags: String
    @State private var priority: String
    @State private var dataJSON: String
    @State private var settingsJSON: String
    @State private var privateRouting: Bool
    @State private var showingAdvanced = false
    @State private var showingSaveConfirmation = false
    @State private var isSaving = false
    @State private var errorMessage: String?

    private let recordTypes = [
        "A", "AAAA", "CNAME", "TXT", "MX", "SRV", "CAA", "NS", "PTR",
        "HTTPS", "SVCB", "DS", "DNSKEY", "TLSA", "LOC", "NAPTR", "SSHFP",
        "CERT", "SMIMEA", "URI"
    ]

    private let ttlOptions: [(String, Int)] = [
        ("Automatic", 1),
        ("30 seconds", 30),
        ("1 minute", 60),
        ("2 minutes", 120),
        ("5 minutes", 300),
        ("15 minutes", 900),
        ("30 minutes", 1_800),
        ("1 hour", 3_600),
        ("2 hours", 7_200),
        ("5 hours", 18_000),
        ("12 hours", 43_200),
        ("1 day", 86_400)
    ]

    init(
        zoneName: String,
        record: CloudflareDNSRecord?,
        onSave: @escaping (CloudflareDNSRecordInput) async throws -> Void
    ) {
        self.zoneName = zoneName
        self.record = record
        self.onSave = onSave
        _type = State(initialValue: record?.type ?? "A")
        _name = State(initialValue: record?.name ?? "")
        _content = State(initialValue: record?.content ?? "")
        _ttl = State(initialValue: record?.ttl ?? 1)
        _proxied = State(initialValue: record?.proxied ?? false)
        _comment = State(initialValue: record?.comment ?? "")
        _tags = State(initialValue: record?.tags.joined(separator: ", ") ?? "")
        _priority = State(initialValue: record?.priority.map(String.init) ?? "")
        _dataJSON = State(initialValue: Self.prettyJSON(record?.data))
        _settingsJSON = State(initialValue: Self.prettyJSON(record?.settings))
        _privateRouting = State(initialValue: record?.privateRouting ?? false)
        _showingAdvanced = State(initialValue: record?.data != nil || record?.settings != nil)
    }

    private var isLocked: Bool { record?.locked == true }
    private var canProxy: Bool {
        if let record { return record.proxiable == true }
        return ["A", "AAAA", "CNAME"].contains(type)
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    if isLocked {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "lock.fill")
                                .foregroundStyle(CloudflareStyle.amber)
                            Text("Cloudflare manages this record. Its fields are shown for reference and cannot be changed here.")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(AppTheme.textSecondary)
                            Spacer(minLength: 0)
                        }
                        .padding(14)
                        .cloudflarePanel()
                    }

                    basicFields
                    routingFields
                    metadataFields
                    returnedMetadataFields
                    advancedFields

                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }

                    CloudflareWriteNotice()
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.top, 16)
                .padding(.bottom, 24)
                .appContentWidth(AppLayout.formMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(record == nil ? "Add DNS Record" : "Edit DNS Record")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .foregroundStyle(AppTheme.textSecondary)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(record == nil ? "Create" : "Save") {
                    validateAndConfirm()
                }
                .fontWeight(.bold)
                .foregroundStyle(CloudflareStyle.orange)
                .disabled(isSaving || isLocked)
            }
        }
        .confirmationDialog(
            record == nil ? "Create this DNS record?" : "Save changes to this DNS record?",
            isPresented: $showingSaveConfirmation,
            titleVisibility: .visible
        ) {
            Button(record == nil ? "Create Record" : "Save Changes") {
                Task { await save() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This changes live DNS for \(zoneName).")
        }
        .interactiveDismissDisabled(isSaving)
        .tint(CloudflareStyle.orange)
    }

    private var basicFields: some View {
        editorPanel(title: "Record", icon: "server.rack") {
            editorField(label: "Type") {
                Menu {
                    ForEach(recordTypes, id: \.self) { value in
                        Button {
                            type = value
                            if !canProxy { proxied = false }
                        } label: {
                            Label(value, systemImage: type == value ? "checkmark" : "circle")
                        }
                    }
                } label: {
                    menuValue(type)
                }
                .disabled(record != nil)
            }

            editorDivider

            editorField(label: "Name") {
                TextField("@ or host.\(zoneName)", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(AppTheme.textPrimary)
            }

            editorDivider

            VStack(alignment: .leading, spacing: 8) {
                Text("CONTENT")
                    .font(.system(size: 9, weight: .semibold))
                    .tracking(0.8)
                    .foregroundStyle(AppTheme.textTertiary)
                TextEditor(text: $content)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(AppTheme.textPrimary)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 72)
                    .padding(9)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                    )
            }
            .padding(16)
        }
    }

    private var routingFields: some View {
        editorPanel(title: "Routing", icon: "arrow.triangle.branch") {
            editorField(label: "TTL") {
                Menu {
                    ForEach(ttlOptions, id: \.1) { option in
                        Button {
                            ttl = option.1
                        } label: {
                            Label(option.0, systemImage: ttl == option.1 ? "checkmark" : "circle")
                        }
                    }
                } label: {
                    menuValue(ttlOptions.first(where: { $0.1 == ttl })?.0 ?? "\(ttl) seconds")
                }
            }

            if canProxy {
                editorDivider
                Toggle(isOn: $proxied) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Proxy through Cloudflare")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                        Text(proxied ? "Traffic uses Cloudflare’s edge" : "DNS only")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                }
                .tint(CloudflareStyle.orange)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }

            if ["MX", "SRV", "URI"].contains(type) {
                editorDivider
                editorField(label: "Priority") {
                    TextField("0", text: $priority)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(AppTheme.textPrimary)
                }
            }
        }
    }

    private var metadataFields: some View {
        editorPanel(title: "Metadata", icon: "tag.fill") {
            editorField(label: "Comment") {
                TextField("Optional", text: $comment)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(AppTheme.textPrimary)
            }
            editorDivider
            editorField(label: "Tags") {
                TextField("tag:value, owner:team", text: $tags)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(AppTheme.textPrimary)
            }
        }
    }

    @ViewBuilder
    private var returnedMetadataFields: some View {
        if let record {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Returned metadata", icon: "list.bullet.rectangle.fill")
                Divider().overlay(AppTheme.divider)
                CloudflareDetailRow(icon: "number", title: "Record ID", value: record.id)
                CloudflareDetailRow(icon: "icloud.fill", title: "Can be proxied", value: booleanText(record.proxiable))
                CloudflareDetailRow(icon: "lock.fill", title: "Managed by Cloudflare", value: booleanText(record.locked))
                if let date = record.createdDate {
                    CloudflareDetailRow(icon: "calendar.badge.plus", title: "Created", value: date.formatted(date: .abbreviated, time: .shortened))
                }
                if let date = record.modifiedDate {
                    CloudflareDetailRow(icon: "calendar.badge.clock", title: "Modified", value: date.formatted(date: .abbreviated, time: .shortened))
                }
                if let date = record.commentModifiedDate {
                    CloudflareDetailRow(icon: "text.bubble.fill", title: "Comment modified", value: date.formatted(date: .abbreviated, time: .shortened))
                }
                if let date = record.tagsModifiedDate {
                    CloudflareDetailRow(icon: "tag.fill", title: "Tags modified", value: date.formatted(date: .abbreviated, time: .shortened))
                }
                if !record.meta.isEmpty {
                    CloudflareDetailRow(
                        icon: "curlybraces.square.fill",
                        title: "Metadata",
                        value: CloudflareJSONValue.object(record.meta).operationsDisplayText
                    )
                }
            }
            .cloudflarePanel()
        }
    }

    private func booleanText(_ value: Bool?) -> String {
        guard let value else { return "Not returned" }
        return value ? "Yes" : "No"
    }

    private var advancedFields: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { showingAdvanced.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "curlybraces.square.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(CloudflareStyle.orange)
                        .frame(width: 22, height: 22)
                        .background(CloudflareStyle.orange.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    Text("Advanced JSON")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                        .rotationEffect(.degrees(showingAdvanced ? 180 : 0))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }
            .buttonStyle(.plain)

            if showingAdvanced {
                Divider().overlay(AppTheme.divider)
                if ["A", "AAAA"].contains(type) {
                    editorField(label: "Private routing") {
                        Toggle("", isOn: $privateRouting)
                            .labelsHidden()
                            .tint(CloudflareStyle.orange)
                    }
                    Divider().overlay(AppTheme.divider).padding(.horizontal, 16)
                }
                jsonEditor(label: "STRUCTURED DATA", text: $dataJSON)
                Divider().overlay(AppTheme.divider).padding(.horizontal, 16)
                jsonEditor(label: "SETTINGS", text: $settingsJSON)
            }
        }
        .cloudflarePanel()
    }

    private func editorPanel<Content: View>(
        title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon)
            Divider().overlay(AppTheme.divider)
            content()
        }
        .cloudflarePanel()
    }

    private func editorField<Content: View>(
        label: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(spacing: 12) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.textTertiary)
            Spacer(minLength: 8)
            content()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var editorDivider: some View {
        Divider().overlay(AppTheme.divider).padding(.leading, 16)
    }

    private func menuValue(_ value: String) -> some View {
        HStack(spacing: 6) {
            Text(value)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AppTheme.textPrimary)
            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(AppTheme.textTertiary)
        }
    }

    private func jsonEditor(label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.textTertiary)
            TextEditor(text: text)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(AppTheme.textPrimary)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 105)
                .padding(9)
                .background(AppTheme.surfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                )
        }
        .padding(16)
    }

    private func validateAndConfirm() {
        do {
            _ = try makeInput()
            errorMessage = nil
            showingSaveConfirmation = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }
        do {
            let input = try makeInput()
            try await onSave(input)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func makeInput() throws -> CloudflareDNSRecordInput {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { throw DNSRecordEditorError.message("Enter a DNS record name.") }

        let trimmedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        let data = try parseJSONObject(dataJSON, label: "Structured data")
        let settings = try parseJSONObject(settingsJSON, label: "Settings")
        guard !trimmedContent.isEmpty || data?.isEmpty == false else {
            throw DNSRecordEditorError.message("Enter record content or structured data.")
        }

        let parsedPriority: Int?
        if priority.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parsedPriority = nil
        } else if let value = Int(priority), value >= 0 {
            parsedPriority = value
        } else {
            throw DNSRecordEditorError.message("Priority must be a non-negative whole number.")
        }

        let parsedTags = tags
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        return CloudflareDNSRecordInput(
            type: type,
            name: trimmedName,
            content: trimmedContent.isEmpty ? nil : trimmedContent,
            ttl: ttl,
            proxied: canProxy ? proxied : nil,
            comment: comment.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            tags: parsedTags.isEmpty ? nil : parsedTags,
            priority: parsedPriority,
            data: data,
            settings: settings,
            privateRouting: ["A", "AAAA"].contains(type) && (record?.privateRouting != nil || privateRouting)
                ? privateRouting
                : nil
        )
    }

    private func parseJSONObject(
        _ source: String,
        label: String
    ) throws -> [String: CloudflareJSONValue]? {
        let trimmed = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let data = trimmed.data(using: .utf8) else {
            throw DNSRecordEditorError.message("\(label) is not valid UTF-8 text.")
        }
        do {
            return try JSONDecoder().decode([String: CloudflareJSONValue].self, from: data)
        } catch {
            throw DNSRecordEditorError.message("\(label) must be a valid JSON object.")
        }
    }

    private static func prettyJSON(_ value: [String: CloudflareJSONValue]?) -> String {
        guard let value,
              let encoded = try? JSONEncoder().encode(value),
              let object = try? JSONSerialization.jsonObject(with: encoded),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let string = String(data: pretty, encoding: .utf8) else { return "" }
        return string
    }
}

struct CloudflareCachePurgeView: View {
    let zone: CloudflareZone
    let onPurge: (CloudflareCachePurge) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var kind: PurgeKind = .files
    @State private var values = ""
    @State private var confirmationText = ""
    @State private var showingConfirmation = false
    @State private var isPurging = false
    @State private var errorMessage: String?

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    warningCard
                    purgeTypePanel

                    if kind != .everything {
                        valuesPanel
                    } else {
                        fullPurgeConfirmation
                    }

                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.vertical, 16)
                .appContentWidth(AppLayout.formMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("Purge Cache")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .foregroundStyle(AppTheme.textSecondary)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Continue") { preparePurge() }
                    .fontWeight(.bold)
                    .foregroundStyle(CloudflareStyle.orange)
                    .disabled(isPurging || (kind == .everything && confirmationText != zone.name))
            }
        }
        .confirmationDialog(
            kind == .everything ? "Purge all cached content?" : "Purge selected cached content?",
            isPresented: $showingConfirmation,
            titleVisibility: .visible
        ) {
            Button("Purge Cache", role: kind == .everything ? .destructive : nil) {
                Task { await purge() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(kind.confirmationMessage(zone: zone.name))
        }
        .interactiveDismissDisabled(isPurging)
        .tint(CloudflareStyle.orange)
    }

    private var warningCard: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "bolt.shield.fill")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)
            VStack(alignment: .leading, spacing: 4) {
                Text("Cached traffic may briefly increase origin load")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Purging removes matching objects from Cloudflare’s edge. New requests refill the cache from your origin.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(AppTheme.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var purgeTypePanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Purge scope", icon: "scope")
            Divider().overlay(AppTheme.divider)
            ForEach(PurgeKind.allCases) { option in
                Button {
                    kind = option
                    errorMessage = nil
                } label: {
                    HStack(spacing: 11) {
                        Image(systemName: option.icon)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(kind == option ? CloudflareStyle.orange : AppTheme.textTertiary)
                            .frame(width: 28, height: 28)
                            .background(kind == option ? CloudflareStyle.orange.opacity(0.08) : AppTheme.stroke)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.title)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(AppTheme.textPrimary)
                            Text(option.subtitle)
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                        Spacer()
                        Image(systemName: kind == option ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(kind == option ? CloudflareStyle.orange : AppTheme.textTertiary)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                }
                .buttonStyle(.plain)
                if option != PurgeKind.allCases.last {
                    Divider().overlay(AppTheme.divider).padding(.leading, 54)
                }
            }
        }
        .cloudflarePanel()
    }

    private var valuesPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(kind.inputLabel)
                .font(.system(size: 10, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.textTertiary)
            TextEditor(text: $values)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(AppTheme.textPrimary)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 130)
                .padding(10)
                .background(AppTheme.surfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                )
            Text("Enter one value per line or separate values with commas.")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(AppTheme.textTertiary)
        }
        .padding(16)
        .cloudflarePanel()
    }

    private var fullPurgeConfirmation: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("TYPE THE ZONE NAME TO CONTINUE")
                .font(.system(size: 10, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(CloudflareStyle.red.opacity(0.85))
            Text(zone.name)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundStyle(AppTheme.textSecondary)
                .textSelection(.enabled)
            TextField(zone.name, text: $confirmationText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .padding(12)
                .background(AppTheme.surfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(
                            confirmationText == zone.name ? CloudflareStyle.green.opacity(0.4) : AppTheme.stroke,
                            lineWidth: 0.7
                        )
                )
        }
        .padding(16)
        .cloudflarePanel()
    }

    private func preparePurge() {
        do {
            _ = try purgeValue()
            errorMessage = nil
            showingConfirmation = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func purge() async {
        isPurging = true
        defer { isPurging = false }
        do {
            try await onPurge(try purgeValue())
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func purgeValue() throws -> CloudflareCachePurge {
        if kind == .everything {
            guard confirmationText == zone.name else {
                throw DNSRecordEditorError.message("Type the exact zone name to confirm a full cache purge.")
            }
            return .everything
        }

        let entries = values
            .components(separatedBy: CharacterSet(charactersIn: ",\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !entries.isEmpty else {
            throw DNSRecordEditorError.message("Enter at least one \(kind.inputLabel.lowercased()).")
        }

        switch kind {
        case .everything: return .everything
        case .files: return .files(entries)
        case .tags: return .tags(entries)
        case .hosts: return .hosts(entries)
        case .prefixes: return .prefixes(entries)
        }
    }
}

private enum PurgeKind: String, CaseIterable, Identifiable {
    case files
    case tags
    case hosts
    case prefixes
    case everything

    var id: String { rawValue }

    var title: String {
        switch self {
        case .files: "Specific URLs"
        case .tags: "Cache tags"
        case .hosts: "Hostnames"
        case .prefixes: "URL prefixes"
        case .everything: "Everything"
        }
    }

    var subtitle: String {
        switch self {
        case .files: "Remove exact cached files"
        case .tags: "Remove objects with matching cache tags"
        case .hosts: "Remove cached content for selected hosts"
        case .prefixes: "Remove every object under a URL prefix"
        case .everything: "Empty the entire zone cache"
        }
    }

    var icon: String {
        switch self {
        case .files: "doc.fill"
        case .tags: "tag.fill"
        case .hosts: "network"
        case .prefixes: "text.line.first.and.arrowtriangle.forward"
        case .everything: "trash.fill"
        }
    }

    var inputLabel: String {
        switch self {
        case .files: "URLs"
        case .tags: "Cache tags"
        case .hosts: "Hostnames"
        case .prefixes: "URL prefixes"
        case .everything: "Zone"
        }
    }

    func confirmationMessage(zone: String) -> String {
        switch self {
        case .everything: "Every cached object for \(zone) will be removed from Cloudflare’s edge."
        default: "Only the entered \(inputLabel.lowercased()) will be purged for \(zone)."
        }
    }
}

private enum DNSRecordEditorError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let message): message
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
