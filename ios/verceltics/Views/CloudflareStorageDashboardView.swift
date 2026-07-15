import SwiftUI

@Observable
@MainActor
final class CloudflareStorageDashboardViewModel {
    private struct CacheEntry {
        let databases: [CloudflareD1Database]
        let namespaces: [CloudflareKVNamespace]
        let buckets: [CloudflareR2Bucket]
        let warnings: [String]
        let updatedAt: Date
    }

    private struct LoadResult {
        let databases: Result<[CloudflareD1Database], Error>
        let namespaces: Result<[CloudflareKVNamespace], Error>
        let buckets: Result<[CloudflareR2Bucket], Error>
    }

    @ResettableMemoryCache private static var cache: [String: CacheEntry] = [:]
    private static let cacheLifetime: TimeInterval = 180

    let api: CloudflareAPI
    let accountID: String
    let allowsR2: Bool

    var databases: [CloudflareD1Database] = []
    var namespaces: [CloudflareKVNamespace] = []
    var buckets: [CloudflareR2Bucket] = []
    var isLoading = true
    var isRefreshing = false
    var warnings: [String] = []
    var actionMessage: String?
    var actionFailed = false

    private var hasLoadedSnapshot = false
    private var cacheUpdatedAt: Date?
    private var generation = 0
    private var loadTask: Task<LoadResult, Never>?

    private var cacheKey: String {
        "\(api.cacheScope)|\(accountID)|storage|r2:\(allowsR2)"
    }

    init(api: CloudflareAPI, accountID: String, allowsR2: Bool) {
        self.api = api
        self.accountID = accountID
        self.allowsR2 = allowsR2
        let key = "\(api.cacheScope)|\(accountID)|storage|r2:\(allowsR2)"
        if let cached = Self.cache[key] {
            databases = cached.databases
            namespaces = cached.namespaces
            buckets = cached.buckets
            warnings = cached.warnings
            cacheUpdatedAt = cached.updatedAt
            hasLoadedSnapshot = true
            isLoading = false
        }
    }

    func load(force: Bool = false) async {
        if let cached = Self.cache[cacheKey] {
            databases = cached.databases
            namespaces = cached.namespaces
            buckets = cached.buckets
            warnings = cached.warnings
            cacheUpdatedAt = cached.updatedAt
            hasLoadedSnapshot = true
            isLoading = false
            if !force, Self.isFresh(cached.updatedAt) { return }
        }

        if let task = loadTask {
            let currentGeneration = generation
            let result = await task.value
            apply(result, generation: currentGeneration)
            return
        }

        generation += 1
        let currentGeneration = generation
        isLoading = !hasLoadedSnapshot
        isRefreshing = hasLoadedSnapshot

        let task = Task { [api, accountID, allowsR2] in
            async let d1Result = Self.capture { try await api.fetchD1Databases(accountID: accountID) }
            async let kvResult = Self.capture { try await api.fetchKVNamespaces(accountID: accountID) }
            let (d1, kv) = await (d1Result, kvResult)
            let r2: Result<[CloudflareR2Bucket], Error>
            if allowsR2 {
                r2 = await Self.capture { try await api.fetchR2Buckets(accountID: accountID) }
            } else {
                r2 = .success([])
            }
            return LoadResult(databases: d1, namespaces: kv, buckets: r2)
        }
        loadTask = task
        let result = await task.value
        apply(result, generation: currentGeneration)
    }

    func createD1(_ input: CloudflareD1CreateInput) async throws {
        cancelLoad()
        do {
            let database = try await api.createD1Database(
                accountID: accountID,
                input: input,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: input.name)
            )
            databases.removeAll { $0.id == database.id }
            databases.append(database)
            sortResources()
            updateCachePreservingFreshness()
            report("D1 database created.")
        } catch {
            report(error.localizedDescription, failed: true)
            throw error
        }
    }

    func createKV(title: String) async throws {
        cancelLoad()
        do {
            let namespace = try await api.createKVNamespace(
                accountID: accountID,
                title: title,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: title.trimmingCharacters(in: .whitespacesAndNewlines))
            )
            namespaces.removeAll { $0.id == namespace.id }
            namespaces.append(namespace)
            sortResources()
            updateCachePreservingFreshness()
            report("KV namespace created.")
        } catch {
            report(error.localizedDescription, failed: true)
            throw error
        }
    }

    func createR2(_ input: CloudflareR2CreateInput) async throws {
        cancelLoad()
        do {
            let bucket = try await api.createR2Bucket(
                accountID: accountID,
                input: input,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: input.name)
            )
            buckets.removeAll { $0.id == bucket.id && $0.jurisdiction == bucket.jurisdiction }
            buckets.append(bucket)
            sortResources()
            updateCachePreservingFreshness()
            report("R2 bucket created.")
        } catch {
            report(error.localizedDescription, failed: true)
            throw error
        }
    }

    func reconcileD1(_ database: CloudflareD1Database) {
        cancelLoad()
        databases.removeAll { $0.id == database.id }
        databases.append(database)
        sortResources()
        updateCachePreservingFreshness()
    }

    func removeD1(id: String) {
        cancelLoad()
        databases.removeAll { $0.id == id }
        updateCachePreservingFreshness()
    }

    func reconcileKV(_ namespace: CloudflareKVNamespace) {
        cancelLoad()
        namespaces.removeAll { $0.id == namespace.id }
        namespaces.append(namespace)
        sortResources()
        updateCachePreservingFreshness()
    }

    func removeKV(id: String) {
        cancelLoad()
        namespaces.removeAll { $0.id == id }
        updateCachePreservingFreshness()
    }

    func reconcileR2(_ bucket: CloudflareR2Bucket) {
        cancelLoad()
        buckets.removeAll { $0.id == bucket.id && $0.jurisdiction == bucket.jurisdiction }
        buckets.append(bucket)
        sortResources()
        updateCachePreservingFreshness()
    }

    func removeR2(name: String, jurisdiction: String?) {
        cancelLoad()
        buckets.removeAll { $0.name == name && $0.jurisdiction == jurisdiction }
        updateCachePreservingFreshness()
    }

    private static func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }

    private func isCancellation<T>(_ result: Result<T, Error>) -> Bool {
        guard case .failure(let error) = result else { return false }
        return error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func apply(_ result: LoadResult, generation currentGeneration: Int) {
        guard generation == currentGeneration else { return }
        loadTask = nil
        isLoading = false
        isRefreshing = false
        if isCancellation(result.databases)
            || isCancellation(result.namespaces)
            || isCancellation(result.buckets) {
            return
        }

        warnings = []
        var allSucceeded = true
        switch result.databases {
        case .success(let values): databases = values
        case .failure(let error):
            warnings.append("D1: \(error.localizedDescription)")
            allSucceeded = false
        }
        switch result.namespaces {
        case .success(let values): namespaces = values
        case .failure(let error):
            warnings.append("KV: \(error.localizedDescription)")
            allSucceeded = false
        }
        switch result.buckets {
        case .success(let values): buckets = values
        case .failure(let error):
            warnings.append("R2: \(error.localizedDescription)")
            allSucceeded = false
        }
        sortResources()
        hasLoadedSnapshot = true
        cacheUpdatedAt = allSucceeded ? .now : .distantPast
        updateCache(updatedAt: cacheUpdatedAt ?? .distantPast)
    }

    private func sortResources() {
        databases.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        namespaces.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        buckets.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private func updateCache(updatedAt: Date = .now) {
        let resolvedUpdatedAt = warnings.isEmpty ? updatedAt : .distantPast
        cacheUpdatedAt = resolvedUpdatedAt
        hasLoadedSnapshot = true
        Self.cache[cacheKey] = CacheEntry(
            databases: databases,
            namespaces: namespaces,
            buckets: buckets,
            warnings: warnings,
            updatedAt: resolvedUpdatedAt
        )
    }

    private func updateCachePreservingFreshness() {
        updateCache(updatedAt: cacheUpdatedAt ?? .distantPast)
    }

    private func cancelLoad() {
        guard loadTask != nil else { return }
        loadTask?.cancel()
        loadTask = nil
        generation += 1
        isLoading = false
        isRefreshing = false
    }

    private static func isFresh(_ date: Date) -> Bool {
        Date.now.timeIntervalSince(date) < cacheLifetime
    }

    private func report(_ message: String, failed: Bool = false) {
        actionMessage = message
        actionFailed = failed
    }
}

struct CloudflareStorageDashboardView: View {
    let api: CloudflareAPI
    let accountID: String
    let accountName: String
    let allowsR2: Bool

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModel: CloudflareStorageDashboardViewModel
    @State private var searchText = ""
    @State private var creationSheet: CloudflareStorageCreationSheet?
    @State private var refreshSpin = 0.0

    init(api: CloudflareAPI, accountID: String, accountName: String, allowsR2: Bool) {
        self.api = api
        self.accountID = accountID
        self.accountName = accountName
        self.allowsR2 = allowsR2
        _viewModel = State(
            wrappedValue: CloudflareStorageDashboardViewModel(
                api: api,
                accountID: accountID,
                allowsR2: allowsR2
            )
        )
    }

    private var filteredDatabases: [CloudflareD1Database] {
        guard !searchText.isEmpty else { return viewModel.databases }
        return viewModel.databases.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.uuid.localizedCaseInsensitiveContains(searchText) ||
            ($0.jurisdiction?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    private var filteredNamespaces: [CloudflareKVNamespace] {
        guard !searchText.isEmpty else { return viewModel.namespaces }
        return viewModel.namespaces.filter {
            $0.title.localizedCaseInsensitiveContains(searchText) ||
            $0.id.localizedCaseInsensitiveContains(searchText)
        }
    }

    private var filteredBuckets: [CloudflareR2Bucket] {
        guard !searchText.isEmpty else { return viewModel.buckets }
        return viewModel.buckets.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            ($0.location?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            ($0.jurisdiction?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            ($0.storageClass?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    private var hasResults: Bool {
        !filteredDatabases.isEmpty || !filteredNamespaces.isEmpty || !filteredBuckets.isEmpty
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            if viewModel.isLoading {
                CloudflareLoadingView()
            } else {
                content
            }
        }
        .navigationTitle("Developer Storage")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: "Search storage")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    if !reduceMotion {
                        withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
                    }
                    Task { await viewModel.load(force: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                        .rotationEffect(.degrees(refreshSpin))
                }
                .disabled(viewModel.isRefreshing)
            }
        }
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(force: true) }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await viewModel.load() }
        }
        .sheet(item: $creationSheet) { sheet in
            NavigationStack {
                switch sheet {
                case .d1:
                    CloudflareD1CreateView { input in try await viewModel.createD1(input) }
                case .kv:
                    CloudflareKVNamespaceCreateView { title in try await viewModel.createKV(title: title) }
                case .r2:
                    CloudflareR2CreateView { input in try await viewModel.createR2(input) }
                }
            }
        }
        .tint(CloudflareStyle.orange)
    }

    private var content: some View {
        ScrollView {
            VStack(spacing: 16) {
                storageHeader
                CloudflareWriteNotice()

                if let message = viewModel.actionMessage {
                    CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                }

                if !viewModel.warnings.isEmpty {
                    warningsPanel
                }

                if !searchText.isEmpty && !hasResults {
                    CloudflareSearchEmptyView(searchText: searchText)
                } else {
                    storageResourceSections
                }
            }
            .padding(AppLayout.pagePadding(for: horizontalSizeClass))
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .overlay(alignment: .top) {
            if viewModel.isRefreshing {
                ProgressView().tint(CloudflareStyle.orange).padding(.top, 6)
            }
        }
    }

    private var storageResourceSections: some View {
        AppAdaptiveTwoPane(primaryMinimumWidth: 380, secondaryMinimumWidth: 380) {
            d1Section
        } secondary: {
            LazyVStack(spacing: 16) {
                kvSection
                r2Section
            }
        }
    }

    private var storageHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 13) {
                AppIconTile(icon: "externaldrive.connected.to.line.below.fill", tint: CloudflareStyle.orange, size: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Developer Storage")
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Text(accountName)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                CloudflareStatusPill(text: "LIVE", color: CloudflareStyle.green)
            }

            HStack(spacing: 9) {
                storageNode(title: "D1", value: viewModel.databases.count, icon: "cylinder.split.1x2.fill")
                storageNode(title: "KV", value: viewModel.namespaces.count, icon: "key.fill")
                if allowsR2 {
                    storageNode(title: "R2", value: viewModel.buckets.count, icon: "shippingbox.fill")
                } else {
                    storageAvailabilityNode(title: "R2", value: "TOKEN", icon: "shippingbox.fill")
                }
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func storageAvailabilityNode(title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.amber)
                Spacer()
                Text(title)
                    .font(.system(size: 9, weight: .semibold))
                    .tracking(0.7)
                    .foregroundStyle(AppTheme.textTertiary)
            }
            Text(value)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(CloudflareStyle.amber)
        }
        .frame(maxWidth: .infinity, minHeight: 63, alignment: .leading)
        .padding(12)
        .background(AppTheme.surfaceRaised)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func storageNode(title: String, value: Int, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                Spacer()
                Text(title)
                    .font(.system(size: 9, weight: .semibold))
                    .tracking(0.7)
                    .foregroundStyle(AppTheme.textTertiary)
            }
            Text(value.formatted())
                .font(.system(size: 21, weight: .semibold, design: .default).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(AppTheme.surfaceRaised)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var warningsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(viewModel.warnings, id: \.self) { warning in
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(CloudflareStyle.amber)
                    Text(warning)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(AppTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cloudflarePanel()
    }

    private var d1Section: some View {
        resourceSection(
            title: "D1 Databases",
            icon: "cylinder.split.1x2.fill",
            count: filteredDatabases.count,
            action: { creationSheet = .d1 },
            emptyTitle: "No D1 databases",
            emptyMessage: "Create a serverless SQL database for this account."
        ) {
            ForEach(Array(filteredDatabases.enumerated()), id: \.element.id) { index, database in
                NavigationLink {
                    CloudflareD1DatabaseView(
                        api: api,
                        accountID: accountID,
                        database: database
                    ) { updated in
                        if let updated {
                            viewModel.reconcileD1(updated)
                        } else {
                            viewModel.removeD1(id: database.id)
                        }
                    }
                } label: {
                    CloudflareResourceRow(
                        icon: "cylinder.split.1x2.fill",
                        title: database.name,
                        subtitle: d1Subtitle(database),
                        tint: CloudflareStyle.orange
                    )
                }
                .buttonStyle(.plain)
                if index < filteredDatabases.count - 1 { sectionDivider }
            }
        }
    }

    private var kvSection: some View {
        resourceSection(
            title: "Workers KV",
            icon: "key.fill",
            count: filteredNamespaces.count,
            action: { creationSheet = .kv },
            emptyTitle: "No KV namespaces",
            emptyMessage: "Create a namespace to store globally distributed key-value data."
        ) {
            ForEach(Array(filteredNamespaces.enumerated()), id: \.element.id) { index, namespace in
                NavigationLink {
                    CloudflareKVNamespaceView(
                        api: api,
                        accountID: accountID,
                        namespace: namespace
                    ) { updated in
                        if let updated {
                            viewModel.reconcileKV(updated)
                        } else {
                            viewModel.removeKV(id: namespace.id)
                        }
                    }
                } label: {
                    CloudflareResourceRow(
                        icon: "key.fill",
                        title: namespace.title,
                        subtitle: namespace.id,
                        tint: CloudflareStyle.amber
                    )
                }
                .buttonStyle(.plain)
                if index < filteredNamespaces.count - 1 { sectionDivider }
            }
        }
    }

    private var r2Section: some View {
        Group {
            if allowsR2 {
                resourceSection(
                    title: "R2 Buckets",
                    icon: "shippingbox.fill",
                    count: filteredBuckets.count,
                    action: { creationSheet = .r2 },
                    emptyTitle: "No R2 buckets",
                    emptyMessage: "Create an object-storage bucket for this account."
                ) {
                    ForEach(Array(filteredBuckets.enumerated()), id: \.element.id) { index, bucket in
                        NavigationLink {
                            CloudflareR2BucketView(
                                api: api,
                                accountID: accountID,
                                bucket: bucket
                            ) { updated in
                                if let updated {
                                    viewModel.reconcileR2(updated)
                                } else {
                                    viewModel.removeR2(name: bucket.name, jurisdiction: bucket.jurisdiction)
                                }
                            }
                        } label: {
                            CloudflareResourceRow(
                                icon: "shippingbox.fill",
                                title: bucket.name,
                                subtitle: r2Subtitle(bucket),
                                tint: CloudflareStyle.green
                            )
                        }
                        .buttonStyle(.plain)
                        if index < filteredBuckets.count - 1 { sectionDivider }
                    }
                }
            } else {
                VStack(spacing: 0) {
                    CloudflareSectionHeader(title: "R2 Buckets", icon: "shippingbox.fill")
                    Divider().overlay(AppTheme.divider)
                    CloudflareEmptySection(
                        icon: "key.horizontal.fill",
                        title: "Scoped API token required",
                        message: "Add a Cloudflare account using an API token with Workers R2 Storage permission to manage buckets and objects."
                    )
                }
                .cloudflarePanel()
            }
        }
    }

    private func resourceSection<Content: View>(
        title: String,
        icon: String,
        count: Int,
        action: @escaping () -> Void,
        emptyTitle: String,
        emptyMessage: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon, count: count, actionTitle: "Create", action: action)
            Divider().overlay(AppTheme.divider)
            if count == 0 {
                CloudflareEmptySection(icon: icon, title: emptyTitle, message: emptyMessage)
            } else {
                content()
            }
        }
        .cloudflarePanel()
    }

    private var sectionDivider: some View {
        Divider().overlay(AppTheme.divider).padding(.leading, 64)
    }

    private func d1Subtitle(_ database: CloudflareD1Database) -> String {
        [database.jurisdiction?.uppercased() ?? "GLOBAL", database.version]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private func r2Subtitle(_ bucket: CloudflareR2Bucket) -> String {
        [bucket.location?.uppercased() ?? bucket.jurisdiction?.uppercased() ?? "DEFAULT", bucket.storageClass]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}

private enum CloudflareStorageCreationSheet: String, Identifiable {
    case d1
    case kv
    case r2

    var id: String { rawValue }
}

private struct CloudflareD1CreateView: View {
    let onCreate: (CloudflareD1CreateInput) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var jurisdiction = "default"
    @State private var locationHint = "automatic"
    @State private var replication = "disabled"
    @State private var isConfirming = false
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        CloudflareStorageCreateScaffold(
            title: "Create D1 Database",
            actionTitle: "Create",
            isSaving: isSaving,
            canSave: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            errorMessage: errorMessage,
            confirm: { isConfirming = true }
        ) {
            CloudflareStorageFormPanel(title: "Database", icon: "cylinder.split.1x2.fill") {
                CloudflareStorageTextFieldRow(label: "Name", placeholder: "application-db", text: $name)
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(label: "Jurisdiction", value: jurisdiction == "default" ? "Automatic" : jurisdiction.uppercased()) {
                    Picker("Jurisdiction", selection: $jurisdiction) {
                        Text("Automatic").tag("default")
                        Text("European Union").tag("eu")
                        Text("FedRAMP").tag("fedramp")
                    }
                }
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(
                    label: "Primary location",
                    value: locationHint == "automatic" ? "Automatic" : locationHint.uppercased()
                ) {
                    Picker("Primary location", selection: $locationHint) {
                        Text("Automatic").tag("automatic")
                        ForEach(["wnam", "enam", "weur", "eeur", "apac", "oc"], id: \.self) {
                            Text($0.uppercased()).tag($0)
                        }
                    }
                }
                .disabled(jurisdiction != "default")
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(label: "Read replicas", value: replication == "auto" ? "Automatic" : "Disabled") {
                    Picker("Read replicas", selection: $replication) {
                        Text("Disabled").tag("disabled")
                        Text("Automatic").tag("auto")
                    }
                }
            }
        }
        .confirmationDialog("Create this D1 database?", isPresented: $isConfirming, titleVisibility: .visible) {
            Button("Create Database") { Task { await create() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Cloudflare will create \(name.trimmingCharacters(in: .whitespacesAndNewlines)) in this account.")
        }
    }

    private func create() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await onCreate(
                CloudflareD1CreateInput(
                    name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    jurisdiction: jurisdiction == "default" ? nil : jurisdiction,
                    primaryLocationHint: jurisdiction == "default" && locationHint != "automatic" ? locationHint : nil,
                    readReplicationMode: replication
                )
            )
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct CloudflareKVNamespaceCreateView: View {
    let onCreate: (String) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var isConfirming = false
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        CloudflareStorageCreateScaffold(
            title: "Create KV Namespace",
            actionTitle: "Create",
            isSaving: isSaving,
            canSave: !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            errorMessage: errorMessage,
            confirm: { isConfirming = true }
        ) {
            CloudflareStorageFormPanel(title: "Namespace", icon: "key.fill") {
                CloudflareStorageTextFieldRow(label: "Title", placeholder: "APPLICATION_CACHE", text: $title)
            }
        }
        .confirmationDialog("Create this KV namespace?", isPresented: $isConfirming, titleVisibility: .visible) {
            Button("Create Namespace") { Task { await create() } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func create() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await onCreate(title.trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct CloudflareR2CreateView: View {
    let onCreate: (CloudflareR2CreateInput) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var jurisdiction = "default"
    @State private var locationHint = "automatic"
    @State private var storageClass = "Standard"
    @State private var isConfirming = false
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        CloudflareStorageCreateScaffold(
            title: "Create R2 Bucket",
            actionTitle: "Create",
            isSaving: isSaving,
            canSave: !name.isEmpty,
            errorMessage: errorMessage,
            confirm: { isConfirming = true }
        ) {
            CloudflareStorageFormPanel(title: "Bucket", icon: "shippingbox.fill") {
                CloudflareStorageTextFieldRow(label: "Name", placeholder: "application-assets", text: $name)
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(label: "Jurisdiction", value: jurisdiction == "default" ? "Default" : jurisdiction.uppercased()) {
                    Picker("Jurisdiction", selection: $jurisdiction) {
                        Text("Default").tag("default")
                        Text("European Union").tag("eu")
                        Text("FedRAMP").tag("fedramp")
                    }
                }
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(
                    label: "Location hint",
                    value: locationHint == "automatic" ? "Automatic" : locationHint.uppercased()
                ) {
                    Picker("Location hint", selection: $locationHint) {
                        Text("Automatic").tag("automatic")
                        ForEach(["wnam", "enam", "weur", "eeur", "apac", "oc"], id: \.self) {
                            Text($0.uppercased()).tag($0)
                        }
                    }
                }
                .disabled(jurisdiction != "default")
                CloudflareStorageFormDivider()
                CloudflareStorageMenuRow(label: "Storage class", value: storageClass) {
                    Picker("Storage class", selection: $storageClass) {
                        Text("Standard").tag("Standard")
                        Text("Infrequent Access").tag("InfrequentAccess")
                    }
                }
            }
        }
        .confirmationDialog("Create this R2 bucket?", isPresented: $isConfirming, titleVisibility: .visible) {
            Button("Create Bucket") { Task { await create() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Bucket names cannot be changed after creation.")
        }
    }

    private func create() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await onCreate(
                CloudflareR2CreateInput(
                    name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    jurisdiction: jurisdiction == "default" ? nil : jurisdiction,
                    locationHint: jurisdiction == "default" && locationHint != "automatic" ? locationHint : nil,
                    storageClass: storageClass
                )
            )
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct CloudflareStorageCreateScaffold<Content: View>: View {
    let title: String
    let actionTitle: String
    let isSaving: Bool
    let canSave: Bool
    let errorMessage: String?
    let confirm: () -> Void
    @ViewBuilder let content: () -> Content

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    content()
                    if let errorMessage {
                        CloudflareActionResultBanner(message: errorMessage, isError: true)
                    }
                    CloudflareWriteNotice()
                }
                .padding()
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }.foregroundStyle(AppTheme.textSecondary)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(actionTitle, action: confirm)
                    .fontWeight(.bold)
                    .foregroundStyle(CloudflareStyle.orange)
                    .disabled(!canSave || isSaving)
            }
        }
        .interactiveDismissDisabled(isSaving)
        .tint(CloudflareStyle.orange)
    }
}

struct CloudflareStorageFormPanel<Content: View>: View {
    let title: String
    let icon: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: title, icon: icon)
            Divider().overlay(AppTheme.divider)
            content()
        }
        .cloudflarePanel()
    }
}

struct CloudflareStorageTextFieldRow: View {
    let label: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.textTertiary)
            Spacer(minLength: 12)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .multilineTextAlignment(.trailing)
                .foregroundStyle(AppTheme.textPrimary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }
}

struct CloudflareStorageMenuRow<MenuContent: View>: View {
    let label: String
    let value: String
    @ViewBuilder let menuContent: () -> MenuContent

    var body: some View {
        HStack(spacing: 12) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.textTertiary)
            Spacer(minLength: 12)
            Menu(content: menuContent) {
                HStack(spacing: 6) {
                    Text(value)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 8, weight: .semibold))
                        .foregroundStyle(AppTheme.textTertiary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }
}

struct CloudflareStorageFormDivider: View {
    var body: some View {
        Divider().overlay(AppTheme.divider).padding(.horizontal, 16)
    }
}
