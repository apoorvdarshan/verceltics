import SwiftUI

@Observable
@MainActor
final class CloudflareKVNamespaceViewModel {
    private struct CacheEntry {
        let keys: [CloudflareKVKey]
        let updatedAt: Date
    }

    @ResettableMemoryCache private static var cache: [String: CacheEntry] = [:]
    private static let cacheLifetime: TimeInterval = 180

    let api: CloudflareAPI
    let accountID: String

    var namespace: CloudflareKVNamespace
    var keys: [CloudflareKVKey] = []
    var isLoading = true
    var isRefreshing = false
    var workingKey: String?
    var isDeletingNamespace = false
    var didDeleteNamespace = false
    var actionMessage: String?
    var actionFailed = false
    private var hasLoadedSnapshot = false
    private var loadGeneration = 0

    init(api: CloudflareAPI, accountID: String, namespace: CloudflareKVNamespace) {
        self.api = api
        self.accountID = accountID
        self.namespace = namespace
        let key = "\(api.cacheScope)|\(accountID)|kv|\(namespace.id)"
        if let cached = Self.cache[key] {
            keys = cached.keys
            hasLoadedSnapshot = true
            isLoading = false
        }
    }

    private var cacheKey: String {
        "\(api.cacheScope)|\(accountID)|kv|\(namespace.id)"
    }

    func load(forceRefresh: Bool = false) async {
        if let cached = Self.cache[cacheKey] {
            keys = cached.keys
            hasLoadedSnapshot = true
            isLoading = false
            isRefreshing = false
            if !forceRefresh,
               Date.now.timeIntervalSince(cached.updatedAt) < Self.cacheLifetime {
                return
            }
        }

        loadGeneration += 1
        let generation = loadGeneration
        isLoading = !hasLoadedSnapshot
        isRefreshing = hasLoadedSnapshot
        defer {
            if generation == loadGeneration {
                isLoading = false
                isRefreshing = false
            }
        }
        do {
            let loaded = try await api.fetchKVKeys(accountID: accountID, namespaceID: namespace.id)
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            guard generation == loadGeneration else { return }
            keys = loaded
            hasLoadedSnapshot = true
            Self.cache[cacheKey] = CacheEntry(keys: loaded, updatedAt: .now)
        } catch is CancellationError {
            return
        } catch {
            guard generation == loadGeneration else { return }
            report(error.localizedDescription, failed: true)
        }
    }

    func rename(to title: String) async throws {
        do {
            namespace = try await api.renameKVNamespace(
                accountID: accountID,
                namespaceID: namespace.id,
                title: title,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: namespace.id)
            )
            report("KV namespace renamed.")
        } catch {
            report(error.localizedDescription, failed: true)
            throw error
        }
    }

    func saveKey(key: String, data: Data, contentType: String, expirationTTL: Int?) async throws {
        workingKey = key
        defer { workingKey = nil }
        do {
            try await api.writeKVValue(
                accountID: accountID,
                namespaceID: namespace.id,
                key: key,
                data: data,
                contentType: contentType,
                expirationTTL: expirationTTL,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: key)
            )
            report("KV value saved.")
            await load(forceRefresh: true)
        } catch {
            report(error.localizedDescription, failed: true)
            throw error
        }
    }

    func deleteKey(_ key: CloudflareKVKey) async {
        workingKey = key.name
        defer { workingKey = nil }
        do {
            try await api.deleteKVValue(
                accountID: accountID,
                namespaceID: namespace.id,
                key: key.name,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: key.name)
            )
            keys.removeAll { $0.name == key.name }
            updateCache()
            report("KV key deleted.")
        } catch {
            report(error.localizedDescription, failed: true)
        }
    }

    func deleteNamespace() async {
        isDeletingNamespace = true
        defer { isDeletingNamespace = false }
        do {
            try await api.deleteKVNamespace(
                accountID: accountID,
                namespaceID: namespace.id,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: namespace.id)
            )
            Self.cache[cacheKey] = nil
            didDeleteNamespace = true
        } catch {
            report(error.localizedDescription, failed: true)
        }
    }

    private func report(_ message: String, failed: Bool = false) {
        actionMessage = message
        actionFailed = failed
    }

    private func updateCache() {
        hasLoadedSnapshot = true
        Self.cache[cacheKey] = CacheEntry(keys: keys, updatedAt: .now)
    }
}

struct CloudflareKVNamespaceView: View {
    let api: CloudflareAPI
    let accountID: String
    let onChange: (CloudflareKVNamespace?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModel: CloudflareKVNamespaceViewModel
    @State private var searchText = ""
    @State private var editorItem: CloudflareKVEditorItem?
    @State private var isRenaming = false
    @State private var deletingKey: CloudflareKVKey?
    @State private var isConfirmingNamespaceDelete = false

    init(
        api: CloudflareAPI,
        accountID: String,
        namespace: CloudflareKVNamespace,
        onChange: @escaping (CloudflareKVNamespace?) -> Void = { _ in }
    ) {
        self.api = api
        self.accountID = accountID
        self.onChange = onChange
        _viewModel = State(wrappedValue: CloudflareKVNamespaceViewModel(api: api, accountID: accountID, namespace: namespace))
    }

    private var filteredKeys: [CloudflareKVKey] {
        guard !searchText.isEmpty else { return viewModel.keys }
        return viewModel.keys.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            cloudflareStorageDisplayValue($0.metadata).localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    header
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    keySection
                    dangerZone
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(viewModel.namespace.title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: "Search KV keys")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await viewModel.load() }
        }
        .onChange(of: viewModel.namespace) { _, namespace in onChange(namespace) }
        .onChange(of: viewModel.didDeleteNamespace) { _, deleted in
            if deleted {
                onChange(nil)
                dismiss()
            }
        }
        .sheet(item: $editorItem) { item in
            NavigationStack {
                CloudflareKVValueEditor(
                    api: api,
                    accountID: accountID,
                    namespace: viewModel.namespace,
                    key: item.key,
                    onSave: { key, data, contentType, expirationTTL in
                        try await viewModel.saveKey(
                            key: key,
                            data: data,
                            contentType: contentType,
                            expirationTTL: expirationTTL
                        )
                    }
                )
            }
        }
        .sheet(isPresented: $isRenaming) {
            NavigationStack {
                CloudflareKVRenameView(namespace: viewModel.namespace) { title in
                    try await viewModel.rename(to: title)
                }
            }
        }
        .confirmationDialog(
            "Delete this KV key?",
            isPresented: Binding(
                get: { deletingKey != nil },
                set: { if !$0 { deletingKey = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let key = deletingKey {
                Button("Delete Key", role: .destructive) {
                    let key = key
                    self.deletingKey = nil
                    Task { await viewModel.deleteKey(key) }
                }
                Button("Cancel", role: .cancel) { self.deletingKey = nil }
            }
        } message: {
            Text(deletingKey.map { "\($0.name) will be permanently removed." } ?? "")
        }
        .confirmationDialog("Delete this KV namespace?", isPresented: $isConfirmingNamespaceDelete, titleVisibility: .visible) {
            Button("Delete Namespace", role: .destructive) { Task { await viewModel.deleteNamespace() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(viewModel.namespace.title) and every key in it will be permanently deleted.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                AppIconTile(icon: "key.fill", tint: CloudflareStyle.orange, size: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.namespace.title)
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)
                    Text("Workers KV namespace")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                }
                Spacer(minLength: 8)
                CloudflareStatusPill(text: "\(viewModel.keys.count) KEYS", color: CloudflareStyle.green)
            }

            CloudflareDetailRow(icon: "number", title: "Namespace ID", value: viewModel.namespace.id)

            HStack {
                CloudflareActionButton(title: "Rename", icon: "pencil", action: { isRenaming = true })
                Spacer()
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var keySection: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(
                title: "Keys",
                icon: "key.horizontal.fill",
                count: filteredKeys.count,
                actionTitle: "Write",
                action: { editorItem = CloudflareKVEditorItem(key: nil) }
            )
            Divider().overlay(AppTheme.divider)

            if viewModel.isLoading {
                ProgressView().tint(CloudflareStyle.orange).padding(30)
            } else if filteredKeys.isEmpty {
                CloudflareEmptySection(
                    icon: searchText.isEmpty ? "key.horizontal" : "magnifyingglass",
                    title: searchText.isEmpty ? "No keys" : "No matches",
                    message: searchText.isEmpty
                        ? "Write the first value to this namespace."
                        : "No KV keys match your search."
                )
            } else {
                ForEach(Array(filteredKeys.enumerated()), id: \.element.id) { index, key in
                    CloudflareKVKeyRow(
                        key: key,
                        subtitle: keySubtitle(key),
                        open: { editorItem = CloudflareKVEditorItem(key: key) },
                        delete: { deletingKey = key }
                    )
                    if index < filteredKeys.count - 1 {
                        Divider().overlay(AppTheme.divider).padding(.leading, 64)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private var dangerZone: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Danger Zone", icon: "exclamationmark.triangle.fill")
            Divider().overlay(AppTheme.divider)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Delete namespace")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Text("This permanently removes the namespace and every value.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(AppTheme.textTertiary)
                }
                Spacer(minLength: 8)
                CloudflareActionButton(
                    title: "Delete",
                    icon: "trash.fill",
                    role: .destructive,
                    isWorking: viewModel.isDeletingNamespace,
                    action: { isConfirmingNamespaceDelete = true }
                )
            }
            .padding(16)
        }
        .cloudflarePanel(accentOpacity: 0.06)
    }

    private func keySubtitle(_ key: CloudflareKVKey) -> String {
        var parts: [String] = []
        if let date = key.expirationDate {
            parts.append("Expires \(date.formatted(date: .abbreviated, time: .shortened))")
        } else {
            parts.append("No expiration")
        }
        if key.metadata != nil { parts.append("Metadata") }
        return parts.joined(separator: " · ")
    }
}

private struct CloudflareKVEditorItem: Identifiable {
    let id = UUID()
    let key: CloudflareKVKey?
}

private enum CloudflareKVEditorEncoding: String, CaseIterable, Identifiable {
    case text = "Text"
    case base64 = "Base64"

    var id: String { rawValue }
}

private struct CloudflareKVValueEditor: View {
    let api: CloudflareAPI
    let accountID: String
    let namespace: CloudflareKVNamespace
    let key: CloudflareKVKey?
    let onSave: (String, Data, String, Int?) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var keyName: String
    @State private var value = ""
    @State private var contentType = "text/plain; charset=utf-8"
    @State private var expirationTTL = ""
    @State private var encoding: CloudflareKVEditorEncoding = .text
    @State private var isLoading = false
    @State private var hasLoadedExistingValue = false
    @State private var isSaving = false
    @State private var isConfirming = false
    @State private var errorMessage: String?

    init(
        api: CloudflareAPI,
        accountID: String,
        namespace: CloudflareKVNamespace,
        key: CloudflareKVKey?,
        onSave: @escaping (String, Data, String, Int?) async throws -> Void
    ) {
        self.api = api
        self.accountID = accountID
        self.namespace = namespace
        self.key = key
        self.onSave = onSave
        _keyName = State(initialValue: key?.name ?? "")
        if let expiration = key?.expiration {
            _expirationTTL = State(initialValue: String(max(60, Int(expiration - Date().timeIntervalSince1970))))
        } else {
            _expirationTTL = State(initialValue: "")
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    CloudflareStorageFormPanel(title: "Value", icon: "key.horizontal.fill") {
                        CloudflareStorageTextFieldRow(label: "Key", placeholder: "settings/theme", text: $keyName)
                            .disabled(key != nil)
                        CloudflareStorageFormDivider()
                        CloudflareStorageMenuRow(label: "Encoding", value: encoding.rawValue) {
                            Picker("Encoding", selection: $encoding) {
                                ForEach(CloudflareKVEditorEncoding.allCases) { Text($0.rawValue).tag($0) }
                            }
                        }
                        CloudflareStorageFormDivider()
                        CloudflareStorageTextFieldRow(label: "Content type", placeholder: "text/plain", text: $contentType)
                        CloudflareStorageFormDivider()
                        CloudflareStorageTextFieldRow(label: "Expires in", placeholder: "Never (seconds)", text: $expirationTTL)
                            .keyboardType(.numberPad)
                        CloudflareStorageFormDivider()
                        VStack(alignment: .leading, spacing: 8) {
                            Text(encoding == .text ? "TEXT VALUE" : "BASE64 VALUE")
                                .font(.system(size: 9, weight: .semibold))
                                .tracking(0.8)
                                .foregroundStyle(AppTheme.textTertiary)
                            TextEditor(text: $value)
                                .font(.system(size: 12, weight: .medium, design: .monospaced))
                                .foregroundStyle(AppTheme.textPrimary)
                                .scrollContentBackground(.hidden)
                                .frame(minHeight: 170)
                                .padding(10)
                                .background(AppTheme.surfaceRaised)
                                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                                        .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                                )
                        }
                        .padding(16)
                    }

                    if isLoading {
                        ProgressView("Reading value…").tint(CloudflareStyle.orange)
                    }
                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }
                    CloudflareWriteNotice()
                }
                .padding()
            }
        }
        .navigationTitle(key == nil ? "Write KV Value" : keyName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }.foregroundStyle(AppTheme.textSecondary)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { validateAndConfirm() }
                    .fontWeight(.bold)
                    .foregroundStyle(CloudflareStyle.orange)
                    .disabled(
                        isLoading || isSaving || keyName.isEmpty ||
                        (key != nil && !hasLoadedExistingValue)
                    )
            }
        }
        .task { await readExistingValue() }
        .confirmationDialog("Save this KV value?", isPresented: $isConfirming, titleVisibility: .visible) {
            Button("Save Value") { Task { await save() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(key == nil ? "This creates \(keyName)." : "This overwrites the current value for \(keyName).")
        }
        .interactiveDismissDisabled(isSaving)
        .tint(CloudflareStyle.orange)
    }

    private func readExistingValue() async {
        guard let key else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let stored = try await api.readKVValue(accountID: accountID, namespaceID: namespace.id, key: key.name)
            contentType = stored.contentType ?? contentType
            if let text = stored.utf8Text {
                value = text
                encoding = .text
            } else {
                value = stored.base64Text
                encoding = .base64
            }
            hasLoadedExistingValue = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func validateAndConfirm() {
        errorMessage = nil
        let trimmedKey = keyName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else {
            errorMessage = "Enter a KV key."
            return
        }
        if encoding == .base64, Data(base64Encoded: value.filter { !$0.isWhitespace }) == nil {
            errorMessage = "The value is not valid Base64 data."
            return
        }
        let trimmedTTL = expirationTTL.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedTTL.isEmpty {
            guard let ttl = Int(trimmedTTL), ttl >= 60 else {
                errorMessage = "Expiration must be a whole number of at least 60 seconds."
                return
            }
        }
        isConfirming = true
    }

    private func save() async {
        let data: Data?
        switch encoding {
        case .text: data = value.data(using: .utf8)
        case .base64: data = Data(base64Encoded: value.filter { !$0.isWhitespace })
        }
        guard let data else {
            errorMessage = "The value could not be encoded."
            return
        }

        isSaving = true
        defer { isSaving = false }
        do {
            try await onSave(
                keyName.trimmingCharacters(in: .whitespacesAndNewlines),
                data,
                contentType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? "application/octet-stream"
                    : contentType.trimmingCharacters(in: .whitespacesAndNewlines),
                {
                    let trimmedTTL = expirationTTL.trimmingCharacters(in: .whitespacesAndNewlines)
                    return trimmedTTL.isEmpty ? nil : Int(trimmedTTL)
                }()
            )
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct CloudflareKVRenameView: View {
    let namespace: CloudflareKVNamespace
    let onRename: (String) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var isSaving = false
    @State private var isConfirming = false
    @State private var errorMessage: String?

    init(namespace: CloudflareKVNamespace, onRename: @escaping (String) async throws -> Void) {
        self.namespace = namespace
        self.onRename = onRename
        _title = State(initialValue: namespace.title)
    }

    var body: some View {
        CloudflareStorageCreateScaffold(
            title: "Rename Namespace",
            actionTitle: "Rename",
            isSaving: isSaving,
            canSave: !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && title != namespace.title,
            errorMessage: errorMessage,
            confirm: { isConfirming = true }
        ) {
            CloudflareStorageFormPanel(title: "Namespace", icon: "pencil") {
                CloudflareStorageTextFieldRow(label: "Title", placeholder: "APPLICATION_CACHE", text: $title)
            }
        }
        .confirmationDialog("Rename this namespace?", isPresented: $isConfirming, titleVisibility: .visible) {
            Button("Rename Namespace") { Task { await rename() } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func rename() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await onRename(title.trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct CloudflareKVKeyRow: View {
    let key: CloudflareKVKey
    let subtitle: String
    let open: () -> Void
    let delete: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: open) {
                HStack(spacing: 12) {
                    Image(systemName: "key.horizontal.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(key.expiration == nil ? CloudflareStyle.amber : CloudflareStyle.orange)
                        .frame(width: 36, height: 36)
                        .background((key.expiration == nil ? CloudflareStyle.amber : CloudflareStyle.orange).opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 3) {
                        Text(key.name)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(1)
                        Text(subtitle)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(AppTheme.textTertiary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    CloudflareChevron()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: delete) {
                Image(systemName: "trash")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(CloudflareStyle.red.opacity(0.82))
                    .frame(width: 44, height: 44)
                    .background(CloudflareStyle.red.opacity(0.08))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete \(key.name)")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}
