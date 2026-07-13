import SwiftUI

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
    private let accent: Color

    @State private var catalog: ProviderAPICatalog?
    @State private var query = ""
    @State private var selectedTag = "All"
    @State private var access: AccessFilter = .all
    @State private var error: String?

    init(account: VercelAccount) {
        context = .hosting(account)
        catalogID = account.provider.apiCatalogID
        accent = account.provider.accentColor
    }

    init(account: RegistrarAccount) {
        context = .registrar(account)
        catalogID = account.provider.apiCatalogID
        accent = account.provider.accentColor
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
            Color.black.ignoresSafeArea()
            if let catalog {
                catalogBody(catalog)
            } else if let error {
                ContentUnavailableView("Catalog unavailable", systemImage: "exclamationmark.triangle.fill", description: Text(error))
                    .foregroundStyle(.white)
            } else {
                ProgressView("Loading every operation…").tint(accent).foregroundStyle(.white)
            }
        }
        .navigationTitle("Complete API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { loadCatalog() }
    }

    private func catalogBody(_ catalog: ProviderAPICatalog) -> some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                summary(catalog)
                manualExplorerLink

                TextField("Search operations, paths and tags", text: $query)
                    .textFieldStyle(.plain)
                    .padding(14)
                    .foregroundStyle(.white)
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
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(selectedTag == tag ? .black : .white.opacity(0.66))
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(selectedTag == tag ? accent : Color.white.opacity(0.07))
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                HStack {
                    Text("\(operations.count.formatted()) OPERATIONS")
                    Spacer()
                    Text("RAW RESPONSE PRESERVED")
                }
                .font(.system(size: 9, weight: .heavy)).tracking(1)
                .foregroundStyle(.white.opacity(0.34))

                ForEach(operations) { operation in
                    NavigationLink {
                        ProviderAPIOperationView(operation: operation, context: requestContext, accent: accent)
                    } label: {
                        operationRow(operation)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                }
                Spacer().frame(height: 80)
            }
            .padding(16)
        }
    }

    private func summary(_ catalog: ProviderAPICatalog) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(catalog.title).font(.system(size: 19, weight: .heavy))
                    Text(catalog.apiVersion).font(.system(size: 10, weight: .bold)).foregroundStyle(accent)
                }
                Spacer()
                Text(catalog.operations.count.formatted())
                    .font(.system(size: 26, weight: .black).monospacedDigit())
            }
            Text(catalog.sourceDescription)
                .font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.45))
            if let url = URL(string: catalog.sourceURL) {
                Link(destination: url) {
                    Label("Official API definition", systemImage: "arrow.up.right")
                        .font(.system(size: 10, weight: .bold)).foregroundStyle(accent)
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
                    Text("Manual raw request").font(.system(size: 12, weight: .bold))
                    Text("For undocumented, beta, or newly released routes")
                        .font(.system(size: 9, weight: .semibold)).foregroundStyle(.white.opacity(0.38))
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.white.opacity(0.22))
            }
            .foregroundStyle(.white).padding(14).providerPanel(accent: accent)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func operationRow(_ operation: ProviderAPIOperation) -> some View {
        HStack(spacing: 12) {
            Text(operation.method)
                .font(.system(size: 8, weight: .black, design: .monospaced))
                .foregroundStyle(operation.isMutation ? .orange : .green)
                .frame(width: 48, height: 29)
                .background((operation.isMutation ? Color.orange : Color.green).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(operation.summary).font(.system(size: 12, weight: .bold)).lineLimit(2)
                Text(operation.path).font(.system(size: 9, design: .monospaced)).foregroundStyle(.white.opacity(0.38)).lineLimit(1)
                Text(operation.primaryTag.uppercased()).font(.system(size: 7, weight: .heavy)).tracking(0.8).foregroundStyle(accent)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 10, weight: .heavy)).foregroundStyle(.white.opacity(0.22))
        }
        .foregroundStyle(.white)
        .padding(14)
        .providerPanel(accent: accent)
    }

    private var requestContext: ProviderAPIRequestContext {
        switch context {
        case .hosting(let account): .hosting(account)
        case .registrar(let account): .registrar(account)
        }
    }

    private func loadCatalog() {
        Task {
            do {
                if case .hosting(let account) = context, account.provider == .railway {
                    catalog = try await ProviderAPICatalogStore.shared.railwayCatalog(account: account)
                } else {
                    catalog = try await ProviderAPICatalogStore.shared.catalog(id: catalogID)
                }
            }
            catch { self.error = error.localizedDescription }
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
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 15) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(operation.method).foregroundStyle(operation.isMutation ? .orange : .green)
                            Spacer()
                            Text(operation.primaryTag.uppercased()).foregroundStyle(accent)
                        }
                        .font(.system(size: 9, weight: .heavy)).tracking(0.8)
                        Text(operation.path).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                        if !operation.description.isEmpty {
                            Text(operation.description).font(.system(size: 10, weight: .medium)).foregroundStyle(.white.opacity(0.48))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(15).providerPanel(accent: accent)

                    ForEach(operation.parameters) { parameter in
                        parameterEditor(parameter)
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
                                .font(.system(size: 9, weight: .heavy)).tracking(1).foregroundStyle(.white.opacity(0.36))
                            TextEditor(text: $bodyText)
                                .font(.system(size: 10, design: .monospaced))
                                .scrollContentBackground(.hidden).frame(minHeight: 170)
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
                            .font(.system(size: 14, weight: .heavy)).frame(maxWidth: .infinity).frame(height: 54)
                            .background(accent.opacity(0.82)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle()).foregroundStyle(.white)
                    .disabled(hasMissingRequiredParameters)
                    .opacity(hasMissingRequiredParameters ? 0.45 : 1)
                    Spacer().frame(height: 80)
                }
                .padding(16)
            }
        }
        .navigationTitle(operation.summary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private func parameterEditor(_ parameter: ProviderAPIParameter) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text(parameter.name).font(.system(size: 10, weight: .bold, design: .monospaced))
                Text(parameter.location.rawValue.uppercased()).font(.system(size: 7, weight: .heavy)).foregroundStyle(accent)
                if parameter.required { Text("REQUIRED").font(.system(size: 7, weight: .heavy)).foregroundStyle(.orange) }
            }
            if parameter.enumValues.isEmpty {
                TextField(parameter.type, text: Binding(
                    get: { values[parameter.id, default: ""] },
                    set: { values[parameter.id] = $0 }
                ))
                .font(.system(size: 12, design: .monospaced)).textFieldStyle(.plain)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            } else {
                Picker(parameter.name, selection: Binding(
                    get: { values[parameter.id, default: parameter.enumValues.first ?? ""] },
                    set: { values[parameter.id] = $0 }
                )) {
                    ForEach(parameter.enumValues, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu).tint(.white)
            }
            if !parameter.description.isEmpty {
                Text(parameter.description).font(.system(size: 9, weight: .medium)).foregroundStyle(.white.opacity(0.35))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14).providerPanel(accent: accent)
    }
}
