import SwiftUI

@MainActor
private enum ProviderCatalogViewCache {
    struct Entry {
        let catalog: ProviderAPICatalog
        let updatedAt: Date
    }

    static var entries: [String: Entry] = [:]
    static let lifetime: TimeInterval = 300

    static func catalog(for key: String) -> ProviderAPICatalog? {
        entries[key]?.catalog
    }

    static func isFresh(_ key: String) -> Bool {
        guard let entry = entries[key] else { return false }
        return Date.now.timeIntervalSince(entry.updatedAt) < lifetime
    }

    static func store(_ catalog: ProviderAPICatalog, for key: String) {
        entries[key] = Entry(catalog: catalog, updatedAt: .now)
    }
}

struct ProviderFullAPICatalogView: View {
    private enum Context {
        case hosting(VercelAccount)
        case registrar(RegistrarAccount)
    }

    private enum AccessFilter: String, CaseIterable, Identifiable {
        case all = "All"
        case read = "Read"
        case write = "Write"
        var id: Self { self }
    }

    private let context: Context
    private let catalogID: String
    private let cacheKey: String
    private let accent: Color

    @State private var catalog: ProviderAPICatalog?
    @State private var query = ""
    @State private var selectedTag = "All"
    @State private var access: AccessFilter = .all
    @State private var error: String?
    @State private var isRefreshing = false
    @State private var loadGeneration = 0
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(account: VercelAccount) {
        context = .hosting(account)
        catalogID = account.provider.apiCatalogID
        cacheKey = account.provider == .railway
            ? "\(CredentialCacheScope.hostingAccount(account))|provider-api-catalog"
            : "bundled|\(account.provider.apiCatalogID)"
        accent = account.provider.accentColor
        _catalog = State(initialValue: ProviderCatalogViewCache.catalog(for: cacheKey))
    }

    init(account: RegistrarAccount) {
        context = .registrar(account)
        catalogID = account.provider.apiCatalogID
        cacheKey = "bundled|\(account.provider.apiCatalogID)"
        accent = account.provider.accentColor
        _catalog = State(initialValue: ProviderCatalogViewCache.catalog(for: cacheKey))
    }

    private var tags: [String] {
        guard let catalog else { return ["All"] }
        return ["All"] + Set(catalog.operations.flatMap(\.tags)).sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    private var operations: [ProviderAPIOperation] {
        guard let catalog else { return [] }
        return catalog.operations.filter { operation in
            operation.matches(query)
                && (selectedTag == "All" || operation.tags.contains(selectedTag))
                && (access == .all || (access == .write) == operation.isMutation)
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            if let catalog {
                catalogBody(catalog)
            } else if let error {
                AppEmptyState(
                    icon: "exclamationmark.triangle.fill",
                    title: "Catalog unavailable",
                    message: error,
                    actionTitle: "Try again"
                ) { Task { await loadCatalog(forceRefresh: true) } }
            } else {
                ProgressView("Loading operations").tint(accent).foregroundStyle(AppTheme.textSecondary)
            }
        }
        .navigationTitle("Complete API")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadCatalog() }
    }

    private func catalogBody(_ catalog: ProviderAPICatalog) -> some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                summary(catalog)
                manualExplorerLink

                TextField("Search operations, paths and tags", text: $query)
                    .textFieldStyle(.plain)
                    .padding(14)
                    .foregroundStyle(AppTheme.textPrimary)
                    .providerPanel(accent: accent)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Picker("Access", selection: $access) {
                    ForEach(AccessFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(tags, id: \.self) { tag in
                            Button {
                                selectedTag = tag
                            } label: {
                                Text(tag)
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(selectedTag == tag ? .white : AppTheme.textSecondary)
                                    .padding(.horizontal, 13)
                                    .frame(minHeight: 44)
                                    .background(selectedTag == tag ? AppTheme.signal : AppTheme.surfaceRaised)
                                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                AppSectionHeader(title: "Operations", count: operations.count, accent: accent)

                if operations.isEmpty {
                    AppEmptyState(
                        icon: "magnifyingglass",
                        title: "No matching operations",
                        message: "Change the search, access filter, or selected tag."
                    )
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    LazyVGrid(columns: operationColumns, spacing: 12) {
                        ForEach(operations) { operation in
                            NavigationLink {
                                ProviderAPIOperationView(operation: operation, context: requestContext, accent: accent)
                            } label: {
                                operationRow(operation)
                            }
                            .buttonStyle(PressScaleButtonStyle())
                        }
                    }
                }
            }
            .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
            .padding(.vertical, 16)
            .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await loadCatalog(forceRefresh: true) }
    }

    private var operationColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 420,
            regularMaximum: 530,
            spacing: 12
        )
    }

    private func summary(_ catalog: ProviderAPICatalog) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(catalog.title).font(.title3.weight(.semibold))
                    Text(catalog.apiVersion).font(.caption.weight(.semibold)).foregroundStyle(accent)
                }
                Spacer()
                Text(catalog.operations.count.formatted())
                    .font(.title2.weight(.semibold).monospacedDigit())
            }
            Text(catalog.sourceDescription)
                .font(.footnote).foregroundStyle(AppTheme.textSecondary)
            if let url = URL(string: catalog.sourceURL) {
                Link(destination: url) {
                    Label("Official API definition", systemImage: "arrow.up.right")
                        .font(.footnote.weight(.semibold)).foregroundStyle(accent)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .providerPanel(accent: accent)
    }

    @ViewBuilder
    private var manualExplorerLink: some View {
        NavigationLink {
            switch context {
            case .hosting(let account): HostingAPIExplorerView(account: account)
            case .registrar(let account): RegistrarAPIExplorerView(account: account)
            }
        } label: {
            HStack(spacing: 11) {
                Image(systemName: "terminal.fill").foregroundStyle(accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Manual raw request").font(.subheadline.weight(.semibold))
                    Text("For undocumented, beta, or newly released routes")
                        .font(.footnote).foregroundStyle(AppTheme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(AppTheme.textTertiary)
            }
            .foregroundStyle(AppTheme.textPrimary).padding(14).appSurface(raised: true)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func operationRow(_ operation: ProviderAPIOperation) -> some View {
        HStack(spacing: 12) {
            Text(operation.method)
                .font(.caption2.weight(.semibold).monospaced())
                .foregroundStyle(operation.isMutation ? .orange : .green)
                .frame(width: 48, height: 29)
                .background((operation.isMutation ? Color.orange : Color.green).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(operation.summary).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(operation.path).font(.caption.monospaced()).foregroundStyle(AppTheme.textSecondary).lineLimit(2)
                Text(operation.primaryTag.uppercased()).font(.caption2.weight(.semibold)).tracking(0.6).foregroundStyle(accent)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(AppTheme.textTertiary)
        }
        .foregroundStyle(AppTheme.textPrimary)
        .padding(14)
        .appSurface()
    }

    private var requestContext: ProviderAPIRequestContext {
        switch context {
        case .hosting(let account): .hosting(account)
        case .registrar(let account): .registrar(account)
        }
    }

    @MainActor
    private func loadCatalog(forceRefresh: Bool = false) async {
        if !forceRefresh, catalog != nil, ProviderCatalogViewCache.isFresh(cacheKey) {
            return
        }
        guard !isRefreshing else { return }

        loadGeneration += 1
        let generation = loadGeneration
        isRefreshing = true
        error = nil
        defer {
            if generation == loadGeneration { isRefreshing = false }
        }

        do {
            let loaded: ProviderAPICatalog
            if case .hosting(let account) = context, account.provider == .railway {
                loaded = try await ProviderAPICatalogStore.shared.railwayCatalog(
                    account: account,
                    forceRefresh: forceRefresh
                )
            } else {
                loaded = try await ProviderAPICatalogStore.shared.catalog(id: catalogID)
            }
            guard !Task.isCancelled, generation == loadGeneration else { return }
            catalog = loaded
            ProviderCatalogViewCache.store(loaded, for: cacheKey)
        } catch is CancellationError {
            return
        } catch {
            guard generation == loadGeneration else { return }
            if catalog == nil { self.error = error.localizedDescription }
        }
    }
}

enum ProviderAPIRequestContext {
    case hosting(VercelAccount)
    case registrar(RegistrarAccount)
}

private struct ProviderAPIOperationView: View {
    let operation: ProviderAPIOperation
    let context: ProviderAPIRequestContext
    let accent: Color

    @State private var values: [String: String]
    @State private var bodyText: String
    @State private var contentType: String
    @FocusState private var focusedParameterID: String?
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(operation: ProviderAPIOperation, context: ProviderAPIRequestContext, accent: Color) {
        self.operation = operation
        self.context = context
        self.accent = accent
        var initialValues: [String: String] = [:]
        for parameter in operation.parameters {
            let value = parameter.example.isEmpty ? (parameter.enumValues.first ?? "") : parameter.example
            initialValues[parameter.id] = value
        }
        _values = State(initialValue: initialValues)
        _bodyText = State(initialValue: operation.bodyTemplate)
        _contentType = State(initialValue: operation.contentTypes.first ?? "application/json")
    }

    private var preset: ProviderAPIRequestPreset {
        var path = operation.path
        var queryItems: [URLQueryItem] = []
        var headers: [String: String] = [:]
        for parameter in operation.parameters {
            let value = values[parameter.id, default: ""].trimmingCharacters(in: .whitespacesAndNewlines)
            switch parameter.location {
            case .path:
                path = path.replacingOccurrences(
                    of: "{+\(parameter.name)}",
                    with: ProviderAPIRequestEncoding.pathParameter(value, allowReserved: true)
                )
                path = path.replacingOccurrences(
                    of: "{\(parameter.name)}",
                    with: ProviderAPIRequestEncoding.pathParameter(value, allowReserved: false)
                )
            case .query:
                if !value.isEmpty { queryItems.append(URLQueryItem(name: parameter.name, value: value)) }
            case .header:
                if !value.isEmpty { headers[parameter.name] = value }
            }
        }
        if !queryItems.isEmpty, var components = URLComponents(string: path) {
            components.queryItems = (components.queryItems ?? []) + queryItems
            path = components.string ?? path
        }
        return ProviderAPIRequestPreset(
            title: operation.summary,
            method: operation.method,
            path: path,
            body: bodyText,
            headers: headers,
            contentType: operation.contentTypes.isEmpty ? nil : contentType,
            multipartFields: operation.multipartFields
        )
    }

    private var hasMissingRequiredParameters: Bool {
        operation.parameters.contains { parameter in
            parameter.required && values[parameter.id, default: ""]
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 15) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(operation.method).foregroundStyle(operation.isMutation ? .orange : .green)
                            Spacer()
                            Text(operation.primaryTag.uppercased()).foregroundStyle(accent)
                        }
                        .font(.caption.weight(.semibold))
                        .tracking(0.7)
                        Text(operation.path)
                            .font(.footnote.monospaced())
                            .foregroundStyle(AppTheme.textPrimary)
                            .textSelection(.enabled)
                        if !operation.description.isEmpty {
                            Text(operation.description)
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(15)
                    .providerSurface(accent: accent)

                    LazyVGrid(columns: parameterColumns, alignment: .leading, spacing: 12) {
                        ForEach(operation.parameters) { parameter in
                            parameterEditor(parameter)
                        }
                    }

                    if !operation.contentTypes.isEmpty {
                        Picker("Content type", selection: $contentType) {
                            ForEach(operation.contentTypes, id: \.self) { Text($0).tag($0) }
                        }
                        .pickerStyle(.menu).tint(accent)
                    }

                    if !operation.bodyTemplate.isEmpty || operation.requestBodyRequired {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(operation.requestBodyRequired ? "REQUEST BODY · REQUIRED" : "REQUEST BODY")
                                .font(.caption2.weight(.semibold))
                                .tracking(1)
                                .foregroundStyle(AppTheme.textSecondary)
                            TextEditor(text: $bodyText)
                                .font(.footnote.monospaced())
                                .scrollContentBackground(.hidden)
                                .frame(minHeight: 170)
                        }
                        .padding(14).providerPanel(accent: accent)
                    }

                    NavigationLink {
                        switch context {
                        case .hosting(let account): HostingAPIExplorerView(account: account, preset: preset)
                        case .registrar(let account): RegistrarAPIExplorerView(account: account, preset: preset)
                        }
                    } label: {
                        Label(
                            hasMissingRequiredParameters
                                ? "Fill required fields"
                                : (operation.isMutation ? "Review write request" : "Review request"),
                            systemImage: operation.isMutation ? "exclamationmark.shield.fill" : "arrow.right"
                        )
                            .font(.body.weight(.semibold)).frame(maxWidth: .infinity).frame(height: 54)
                            .background(AppTheme.signal).clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle()).foregroundStyle(.white)
                    .disabled(hasMissingRequiredParameters)
                    .opacity(hasMissingRequiredParameters ? 0.45 : 1)
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.vertical, 16)
                .padding(.bottom, 24)
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle(operation.summary)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var parameterColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 320,
            regularMaximum: 440,
            spacing: 12
        )
    }

    private func parameterEditor(_ parameter: ProviderAPIParameter) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text(parameter.name).font(.caption.weight(.semibold).monospaced())
                Text(parameter.location.rawValue.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(accent)
                if parameter.required { Text("REQUIRED").font(.caption2.weight(.semibold)).foregroundStyle(AppTheme.warning) }
            }
            if parameter.enumValues.isEmpty {
                TextField(parameter.type, text: Binding(
                    get: { values[parameter.id, default: ""] },
                    set: { values[parameter.id] = $0 }
                ))
                .font(.footnote.monospaced())
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .foregroundStyle(AppTheme.textPrimary)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                        .strokeBorder(
                            focusedParameterID == parameter.id ? accent.opacity(0.72) : AppTheme.strokeSoft,
                            lineWidth: focusedParameterID == parameter.id ? 1 : 0.5
                        )
                }
                .focused($focusedParameterID, equals: parameter.id)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            } else {
                Picker(parameter.name, selection: Binding(
                    get: { values[parameter.id, default: parameter.enumValues.first ?? ""] },
                    set: { values[parameter.id] = $0 }
                )) {
                    ForEach(parameter.enumValues, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu)
                .tint(AppTheme.textPrimary)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(.horizontal, 12)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            }
            if !parameter.description.isEmpty {
                Text(parameter.description).font(.footnote).foregroundStyle(AppTheme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14).appSurface()
    }
}
