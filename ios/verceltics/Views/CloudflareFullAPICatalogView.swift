import SwiftUI

@Observable
@MainActor
final class CloudflareFullAPICatalogViewModel {
    var catalog: CloudflareOpenAPICatalog?
    var tags: [CloudflareAPITagSummary] = []
    var isLoading = true
    var error: String?

    func load() async {
        guard catalog == nil else { return }
        isLoading = true
        error = nil
        do {
            let catalog = try await CloudflareOpenAPICatalogStore.shared.load()
            self.catalog = catalog
            let grouped = Dictionary(grouping: catalog.operations, by: \.primaryTag)
            tags = grouped.map { tag, operations in
                CloudflareAPITagSummary(
                    name: tag,
                    operationCount: operations.count,
                    writeCount: operations.count(where: \.isMutation)
                )
            }
            .sorted {
                if $0.operationCount == $1.operationCount {
                    return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
                return $0.operationCount > $1.operationCount
            }
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

nonisolated struct CloudflareAPITagSummary: Identifiable, Sendable {
    let name: String
    let operationCount: Int
    let writeCount: Int
    var id: String { name }
}

private enum CloudflareOperationFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case read = "Read"
    case write = "Write"
    var id: Self { self }

    func includes(_ operation: CloudflareOpenAPIOperation) -> Bool {
        switch self {
        case .all: true
        case .read: !operation.isMutation
        case .write: operation.isMutation
        }
    }
}

struct CloudflareFullAPICatalogView: View {
    let api: CloudflareAPI
    let accountID: String
    let zones: [CloudflareZone]
    let authenticationMode: CloudflareAuthenticationMode

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel = CloudflareFullAPICatalogViewModel()
    @State private var searchText = ""
    @State private var filter: CloudflareOperationFilter = .all

    private var searchResults: [CloudflareOpenAPIOperation] {
        guard let catalog = viewModel.catalog, !searchText.isEmpty else { return [] }
        return catalog.operations.filter { filter.includes($0) && $0.matches(searchText) }
    }

    private var visibleTags: [CloudflareAPITagSummary] {
        guard searchText.isEmpty else { return [] }
        return switch filter {
        case .all: viewModel.tags
        case .read: viewModel.tags.filter { $0.operationCount > $0.writeCount }
        case .write: viewModel.tags.filter { $0.writeCount > 0 }
        }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            if viewModel.isLoading {
                CloudflareLoadingView()
            } else if let error = viewModel.error {
                CloudflareErrorView(message: error) { Task { await viewModel.load() } }
            } else {
                ScrollView {
                    LazyVStack(spacing: 14) {
                        catalogHeader
                        filterRail
                        if searchText.isEmpty {
                            tagDirectory
                        } else {
                            searchDirectory
                        }
                    }
                    .padding()
                    .frame(maxWidth: horizontalSizeClass == .regular ? 980 : .infinity)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle("Complete API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search official operations")
        .task { await viewModel.load() }
        .tint(CloudflareStyle.orange)
    }

    private var catalogHeader: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "point.3.filled.connected.trianglepath.dotted")
                    .font(.system(size: 20, weight: .semibold))
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
                    Text("Official API directory")
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.white)
                    Text("Generated from Cloudflare’s OpenAPI schema")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                }
                Spacer()
                CloudflareStatusPill(text: "COMPLETE", color: CloudflareStyle.green)
            }

            if let catalog = viewModel.catalog {
                HStack(spacing: 8) {
                    catalogMetric("OPERATIONS", catalog.operationCount)
                    catalogMetric("PRODUCT GROUPS", catalog.tagCount)
                    catalogMetric("PATHS", Set(catalog.operations.map(\.path)).count)
                }
                Text("Schema \(String(catalog.sourceCommit.prefix(8))) · OpenAPI \(catalog.openAPIVersion)")
                    .font(.system(size: 8, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.22))
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func catalogMetric(_ label: String, _ value: Int) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(value.formatted())
                .font(.system(size: 18, weight: .semibold, design: .default).monospacedDigit())
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 7, weight: .semibold))
                .tracking(0.5)
                .foregroundStyle(.white.opacity(0.28))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var filterRail: some View {
        HStack(spacing: 7) {
            ForEach(CloudflareOperationFilter.allCases) { item in
                Button {
                    filter = item
                } label: {
                    Text(item.rawValue.uppercased())
                        .font(.system(size: 9, weight: .semibold))
                        .tracking(0.6)
                        .foregroundStyle(filter == item ? .black : .white.opacity(0.45))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(filter == item ? CloudflareStyle.orange : Color.white.opacity(0.045))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(6)
        .background(Color.white.opacity(0.025), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
    }

    private var tagDirectory: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Product Directory", icon: "square.grid.3x3.fill", count: visibleTags.count)
            Divider().overlay(Color.white.opacity(0.06))
            ForEach(Array(visibleTags.enumerated()), id: \.element.id) { index, tag in
                NavigationLink {
                    if let catalog = viewModel.catalog {
                        CloudflareAPITagView(
                            api: api,
                            accountID: accountID,
                            zones: zones,
                            authenticationMode: authenticationMode,
                            tag: tag.name,
                            operations: catalog.operations.filter { $0.primaryTag == tag.name }
                        )
                    }
                } label: {
                    HStack(spacing: 12) {
                        Text(String(tag.name.prefix(2)).uppercased())
                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                            .foregroundStyle(CloudflareStyle.orange)
                            .frame(width: 37, height: 37)
                            .background(CloudflareStyle.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(tag.name)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white.opacity(0.78))
                            Text("\(tag.operationCount) operations · \(tag.writeCount) writes")
                                .font(.system(size: 9, weight: .medium))
                                .foregroundStyle(.white.opacity(0.3))
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.23))
                    }
                    .padding(13)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if index < visibleTags.count - 1 {
                    Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 62)
                }
            }
        }
        .cloudflarePanel()
    }

    @ViewBuilder
    private var searchDirectory: some View {
        if searchResults.isEmpty {
            CloudflareEmptySection(
                icon: "magnifyingglass",
                title: "No API operations found",
                message: "Search by product, endpoint, permission, method or path."
            )
            .cloudflarePanel()
        } else {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Matching Operations", icon: "terminal.fill", count: searchResults.count)
                Divider().overlay(Color.white.opacity(0.06))
                ForEach(Array(searchResults.enumerated()), id: \.element.id) { index, operation in
                    endpointLink(operation)
                    if index < searchResults.count - 1 {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 62)
                    }
                }
            }
            .cloudflarePanel()
        }
    }

    private func endpointLink(_ operation: CloudflareOpenAPIOperation) -> some View {
        NavigationLink {
            CloudflareGeneratedOperationView(
                api: api,
                accountID: accountID,
                zones: zones,
                authenticationMode: authenticationMode,
                operation: operation
            )
        } label: {
            CloudflareOpenAPIOperationRow(operation: operation)
        }
        .buttonStyle(.plain)
    }
}

private struct CloudflareAPITagView: View {
    let api: CloudflareAPI
    let accountID: String
    let zones: [CloudflareZone]
    let authenticationMode: CloudflareAuthenticationMode
    let tag: String
    let operations: [CloudflareOpenAPIOperation]

    @State private var searchText = ""
    @State private var filter: CloudflareOperationFilter = .all

    private var visibleOperations: [CloudflareOpenAPIOperation] {
        operations.filter { filter.includes($0) && $0.matches(searchText) }
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(visibleOperations.enumerated()), id: \.element.id) { index, operation in
                        NavigationLink {
                            CloudflareGeneratedOperationView(
                                api: api,
                                accountID: accountID,
                                zones: zones,
                                authenticationMode: authenticationMode,
                                operation: operation
                            )
                        } label: {
                            CloudflareOpenAPIOperationRow(operation: operation)
                        }
                        .buttonStyle(.plain)
                        if index < visibleOperations.count - 1 {
                            Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 62)
                        }
                    }
                }
                .cloudflarePanel()
                .padding()
            }
        }
        .navigationTitle(tag)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search \(operations.count) operations")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu(filter.rawValue) {
                    ForEach(CloudflareOperationFilter.allCases) { value in
                        Button(value.rawValue) { filter = value }
                    }
                }
                .font(.system(size: 11, weight: .bold))
            }
        }
        .tint(CloudflareStyle.orange)
    }
}

private struct CloudflareOpenAPIOperationRow: View {
    let operation: CloudflareOpenAPIOperation

    var body: some View {
        HStack(spacing: 11) {
            Text(operation.method.rawValue)
                .font(.system(size: 8, weight: .semibold, design: .monospaced))
                .foregroundStyle(methodColor)
                .frame(width: 39, height: 28)
                .background(methodColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 7))
            VStack(alignment: .leading, spacing: 3) {
                Text(operation.summary)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white.opacity(operation.deprecated ? 0.42 : 0.78))
                    .lineLimit(2)
                Text(operation.path)
                    .font(.system(size: 8, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.27))
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            if operation.deprecated {
                Text("OLD")
                    .font(.system(size: 7, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.amber)
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.white.opacity(0.22))
        }
        .padding(13)
        .contentShape(Rectangle())
    }

    private var methodColor: Color {
        switch operation.method {
        case .get: CloudflareStyle.green
        case .post: CloudflareStyle.orange
        case .put, .patch: CloudflareStyle.amber
        case .delete: CloudflareStyle.red
        }
    }
}

private struct CloudflareGeneratedOperationView: View {
    let api: CloudflareAPI
    let accountID: String
    let zones: [CloudflareZone]
    let authenticationMode: CloudflareAuthenticationMode
    let operation: CloudflareOpenAPIOperation

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var values: [String: String]
    @State private var bodyText: String
    @State private var contentType: String

    init(
        api: CloudflareAPI,
        accountID: String,
        zones: [CloudflareZone],
        authenticationMode: CloudflareAuthenticationMode,
        operation: CloudflareOpenAPIOperation
    ) {
        self.api = api
        self.accountID = accountID
        self.zones = zones
        self.authenticationMode = authenticationMode
        self.operation = operation
        var initialValues: [String: String] = [:]
        for parameter in operation.parameters {
            let lowercased = parameter.name.lowercased()
            if parameter.location == .path, lowercased.contains("account") {
                initialValues[parameter.id] = accountID
            } else if parameter.location == .path, lowercased.contains("zone"), let zoneID = zones.first?.id {
                initialValues[parameter.id] = zoneID
            } else {
                initialValues[parameter.id] = parameter.suggestedValue
            }
        }
        _values = State(initialValue: initialValues)
        _bodyText = State(initialValue: operation.bodyTemplate)
        _contentType = State(initialValue: operation.contentTypes.first ?? "application/json")
    }

    private var requestPreset: CloudflareAPIOperationPreset {
        let query = operation.parameters
            .filter { $0.location == .query && !(values[$0.id] ?? "").isEmpty }
            .map { "\($0.name)=\(values[$0.id] ?? "")" }
            .joined(separator: "\n")
        let headers = operation.parameters
            .filter { $0.location == .header && !(values[$0.id] ?? "").isEmpty }
            .map { "\($0.name): \(values[$0.id] ?? "")" }
            .joined(separator: "\n")
        var path = operation.path
        for parameter in operation.parameters where parameter.location == .path {
            let rawValue = values[parameter.id] ?? ""
            let value = rawValue.isEmpty ? parameter.name.uppercased() : pathEncoded(rawValue)
            path = path.replacingOccurrences(of: "{\(parameter.name)}", with: value)
        }
        return .init(
            id: operation.id,
            title: operation.summary,
            summary: operation.description.isEmpty ? operation.path : operation.description,
            method: operation.method,
            path: path,
            query: query,
            headers: headers,
            body: bodyText,
            contentType: contentType,
            multipartFields: operation.multipartFields
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    operationHeader
                    if !credentialIsSupported { credentialWarning }
                    if !operation.permissions.isEmpty { permissionPanel }
                    parameterPanel
                    if !operation.contentTypes.isEmpty { requestBodyPanel }
                    NavigationLink {
                        CloudflareAPIExplorerView(api: api, accountID: accountID, preset: requestPreset)
                    } label: {
                        Label("Review and execute request", systemImage: "arrow.right.circle.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.black.opacity(0.84))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(CloudflareStyle.orange)
                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(!credentialIsSupported)
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 860 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(operation.summary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .tint(CloudflareStyle.orange)
    }

    private var operationHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(operation.method.rawValue)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.black.opacity(0.82))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 7)
                    .background(CloudflareStyle.orange, in: RoundedRectangle(cornerRadius: 8))
                Text(operation.primaryTag.uppercased())
                    .font(.system(size: 9, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(.white.opacity(0.34))
                Spacer()
                if operation.deprecated {
                    CloudflareStatusPill(text: "DEPRECATED", color: CloudflareStyle.amber)
                }
            }
            Text(operation.summary)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
            Text(operation.path)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(CloudflareStyle.orange.opacity(0.72))
                .textSelection(.enabled)
            if !operation.description.isEmpty {
                Text(operation.description)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.4))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(17)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var permissionPanel: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("REQUIRED TOKEN PERMISSIONS")
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.34))
            ForEach(operation.permissions, id: \.self) { permission in
                Label(permission, systemImage: "key.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(CloudflareStyle.amber.opacity(0.82))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(15)
        .cloudflarePanel()
    }

    private var credentialIsSupported: Bool {
        switch authenticationMode {
        case .globalAPIKey: operation.supportsGlobalKey
        case .apiToken: operation.supportsAPIToken
        }
    }

    private var credentialWarning: some View {
        CloudflareActionResultBanner(
            message: authenticationMode == .globalAPIKey
                ? "Cloudflare’s schema marks this endpoint as API-token only. Add a scoped token with the permissions shown below."
                : "Cloudflare’s schema does not list API-token authentication for this endpoint. Switch to a Global API Key account.",
            isError: true
        )
    }

    @ViewBuilder
    private var parameterPanel: some View {
        if !operation.parameters.isEmpty {
            VStack(spacing: 0) {
                CloudflareSectionHeader(title: "Request Parameters", icon: "slider.horizontal.3", count: operation.parameters.count)
                Divider().overlay(Color.white.opacity(0.06))
                ForEach(Array(operation.parameters.enumerated()), id: \.element.id) { index, parameter in
                    parameterEditor(parameter)
                    if index < operation.parameters.count - 1 {
                        Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 16)
                    }
                }
            }
            .cloudflarePanel()
        }
    }

    private func parameterEditor(_ parameter: CloudflareOpenAPIParameter) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 7) {
                Text(parameter.location.rawValue.uppercased())
                    .font(.system(size: 7, weight: .semibold, design: .monospaced))
                    .foregroundStyle(CloudflareStyle.orange)
                Text(parameter.name)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.72))
                if parameter.required {
                    Text("REQUIRED")
                        .font(.system(size: 7, weight: .semibold))
                        .foregroundStyle(CloudflareStyle.red.opacity(0.8))
                }
                Spacer()
                Text(parameter.format ?? parameter.type ?? "value")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.25))
            }
            if let values = parameter.enumValues, !values.isEmpty {
                Menu {
                    ForEach(values.map(\.catalogText), id: \.self) { value in
                        Button(value) { self.values[parameter.id] = value }
                    }
                } label: {
                    parameterField(parameter)
                }
            } else {
                parameterField(parameter)
            }
            if !parameter.description.isEmpty {
                Text(parameter.description)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(.white.opacity(0.28))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
    }

    private func parameterField(_ parameter: CloudflareOpenAPIParameter) -> some View {
        TextField(parameter.required ? "Required value" : "Optional", text: valueBinding(parameter.id))
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(11)
            .background(Color.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 9))
            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5))
    }

    private var requestBodyPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Request Body", icon: "doc.text.fill")
            Divider().overlay(Color.white.opacity(0.06))
            VStack(alignment: .leading, spacing: 11) {
                Picker("Content type", selection: $contentType) {
                    ForEach(operation.contentTypes, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu)
                .font(.system(size: 10, weight: .bold))

                if operation.isMultipart {
                    Text("The API Explorer includes a multipart composer for these \(operation.multipartFields.count) schema fields, including files.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(CloudflareStyle.amber.opacity(0.72))
                    ForEach(operation.multipartFields) { field in
                        HStack {
                            Image(systemName: field.isFile ? "doc.badge.plus" : "text.cursor")
                                .foregroundStyle(CloudflareStyle.orange)
                            Text(field.name)
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.62))
                            Spacer()
                            Text(field.required ? "REQUIRED" : (field.format ?? field.type ?? "FIELD").uppercased())
                                .font(.system(size: 7, weight: .semibold))
                                .foregroundStyle(.white.opacity(0.25))
                        }
                    }
                } else {
                    TextEditor(text: $bodyText)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.76))
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 180)
                        .padding(8)
                        .background(Color.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5))
                }
            }
            .padding(14)
        }
        .cloudflarePanel()
    }

    private func valueBinding(_ id: String) -> Binding<String> {
        Binding(get: { values[id] ?? "" }, set: { values[id] = $0 })
    }

    private func pathEncoded(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}
