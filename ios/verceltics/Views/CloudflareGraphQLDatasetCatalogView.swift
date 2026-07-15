import SwiftUI

nonisolated enum CloudflareGraphQLScope: String, CaseIterable, Identifiable, Sendable {
    case zone = "Zone"
    case account = "Account"
    var id: Self { self }
}

nonisolated struct CloudflareGraphQLDataset: Identifiable, Sendable, Equatable {
    let name: String
    let description: String
    let enabled: Bool?
    let availableFields: [String]
    let maxDuration: Int?
    let notOlderThan: Int?
    let maxPageSize: Int?
    let maxNumberOfFields: Int?

    var id: String { name }
}

@Observable
@MainActor
final class CloudflareGraphQLDatasetCatalogViewModel {
    private struct CacheEntry {
        let datasets: [CloudflareGraphQLDataset]
        let updatedAt: Date
    }

    @ResettableMemoryCache private static var datasetCache: [String: CacheEntry] = [:]
    private static let cacheLifetime: TimeInterval = 300

    let api: CloudflareAPI
    let accountID: String
    let zones: [CloudflareZone]

    var scope: CloudflareGraphQLScope = .zone
    var selectedZoneID: String?
    var datasets: [CloudflareGraphQLDataset] = []
    var isLoading = false
    var error: String?
    private var loadGeneration = 0

    init(api: CloudflareAPI, accountID: String, zones: [CloudflareZone]) {
        self.api = api
        self.accountID = accountID
        self.zones = zones
        selectedZoneID = zones.first?.id
        if zones.isEmpty { scope = .account }
        let key = "\(api.cacheScope)|\(accountID)|graphql|\(scope.rawValue)|\(selectedZoneID ?? accountID)"
        if let cached = Self.datasetCache[key] {
            datasets = cached.datasets
            isLoading = false
        }
    }

    func load(forceRefresh: Bool = false) async {
        loadGeneration += 1
        let generation = loadGeneration
        let key = cacheKey
        var hydratedCache = false
        if let cached = Self.datasetCache[key] {
            datasets = cached.datasets
            hydratedCache = true
            isLoading = false
            error = nil
            if !forceRefresh,
               Date.now.timeIntervalSince(cached.updatedAt) < Self.cacheLifetime {
                return
            }
        }
        isLoading = !hydratedCache
        error = nil

        do {
            let rootType = scope.rawValue
            let rootFields = try await introspect(type: rootType)
            guard let settingsType = rootFields.first(where: { $0.name == "settings" })?.typeName else {
                throw CloudflareAPIError.decoding("Cloudflare did not expose a settings type for \(rootType.lowercased()) analytics.")
            }
            let settingsFields = try await introspect(type: settingsType)
                .filter { $0.name != "__typename" }

            var settings: [String: CloudflareJSONValue] = [:]
            for chunk in settingsFields.chunked(into: 20) {
                let response = try await api.rawGraphQLQuery(
                    query: settingsQuery(datasetNames: chunk.map(\.name)),
                    variables: settingsVariables
                )
                settings.merge(parseSettings(response.data)) { _, new in new }
            }

            guard generation == loadGeneration else { return }
            datasets = settingsFields.map { field in
                dataset(field: field, settings: settings[field.name])
            }
            .sorted {
                if $0.enabled != $1.enabled { return $0.enabled == true }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
            Self.datasetCache[key] = CacheEntry(datasets: datasets, updatedAt: .now)
        } catch is CancellationError {
            return
        } catch {
            guard generation == loadGeneration else { return }
            self.error = error.localizedDescription
        }
        guard generation == loadGeneration else { return }
        isLoading = false
    }

    func selectScope(_ scope: CloudflareGraphQLScope) async {
        self.scope = scope
        await load()
    }

    func selectZone(_ id: String) async {
        selectedZoneID = id
        await load()
    }

    private var settingsVariables: [String: CloudflareJSONValue] {
        switch scope {
        case .zone: ["tag": .string(selectedZoneID ?? "")]
        case .account: ["tag": .string(accountID)]
        }
    }

    private var cacheKey: String {
        "\(api.cacheScope)|\(accountID)|graphql|\(scope.rawValue)|\(selectedZoneID ?? accountID)"
    }

    private func introspect(type: String) async throws -> [IntrospectionField] {
        let query = """
        query CatalogType($name: String!) {
          __type(name: $name) {
            fields {
              name
              description
              type { kind name ofType { kind name ofType { kind name } } }
            }
          }
        }
        """
        let response = try await api.rawGraphQLQuery(query: query, variables: ["name": .string(type)])
        let root = try JSONDecoder().decode(CloudflareJSONValue.self, from: response.data)
        guard let fields = root.value(at: ["data", "__type", "fields"])?.arrayValue else {
            throw CloudflareAPIError.decoding("Cloudflare did not return fields for GraphQL type \(type).")
        }
        return fields.compactMap { value in
            guard let object = value.objectValue,
                  let name = object["name"]?.stringValue else { return nil }
            return IntrospectionField(
                name: name,
                description: object["description"]?.stringValue ?? "",
                typeName: object["type"]?.deepTypeName
            )
        }
    }

    private func settingsQuery(datasetNames: [String]) -> String {
        let selections = datasetNames.map {
            "\($0) { enabled availableFields maxDuration notOlderThan maxPageSize maxNumberOfFields }"
        }
        .joined(separator: "\n")
        let scopeSelection = scope == .zone
            ? "zones(filter: { zoneTag: $tag })"
            : "accounts(filter: { accountTag: $tag })"
        return """
        query DatasetSettings($tag: string) {
          viewer {
            \(scopeSelection) {
              settings {
                \(selections)
              }
            }
          }
        }
        """
    }

    private func parseSettings(_ data: Data) -> [String: CloudflareJSONValue] {
        guard let root = try? JSONDecoder().decode(CloudflareJSONValue.self, from: data) else { return [:] }
        let scopeKey = scope == .zone ? "zones" : "accounts"
        return root.value(at: ["data", "viewer", scopeKey])?
            .arrayValue?.first?
            .value(at: ["settings"])?.objectValue ?? [:]
    }

    private func dataset(field: IntrospectionField, settings: CloudflareJSONValue?) -> CloudflareGraphQLDataset {
        let object = settings?.objectValue ?? [:]
        return .init(
            name: field.name,
            description: field.description,
            enabled: object["enabled"]?.boolValue,
            availableFields: object["availableFields"]?.arrayValue?.compactMap(\.stringValue) ?? [],
            maxDuration: object["maxDuration"]?.intValue,
            notOlderThan: object["notOlderThan"]?.intValue,
            maxPageSize: object["maxPageSize"]?.intValue,
            maxNumberOfFields: object["maxNumberOfFields"]?.intValue
        )
    }

    private struct IntrospectionField {
        let name: String
        let description: String
        let typeName: String?
    }
}

struct CloudflareGraphQLDatasetCatalogView: View {
    let api: CloudflareAPI
    let accountID: String
    let zones: [CloudflareZone]

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareGraphQLDatasetCatalogViewModel
    @State private var searchText = ""

    init(api: CloudflareAPI, accountID: String, zones: [CloudflareZone]) {
        self.api = api
        self.accountID = accountID
        self.zones = zones
        _viewModel = State(wrappedValue: .init(api: api, accountID: accountID, zones: zones))
    }

    private var filteredDatasets: [CloudflareGraphQLDataset] {
        guard !searchText.isEmpty else { return viewModel.datasets }
        return viewModel.datasets.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.description.localizedCaseInsensitiveContains(searchText) ||
                $0.availableFields.contains { $0.localizedCaseInsensitiveContains(searchText) }
        }
    }

    private var datasetColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 400,
            regularMaximum: 520,
            spacing: 10
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 15) {
                    scopePanel
                    if viewModel.isLoading && viewModel.datasets.isEmpty {
                        CloudflareLoadingView()
                            .frame(minHeight: 220)
                    } else if let error = viewModel.error, viewModel.datasets.isEmpty {
                        CloudflareErrorView(message: error) { Task { await viewModel.load(forceRefresh: true) } }
                            .frame(minHeight: 220)
                    } else {
                        if let error = viewModel.error {
                            AppFeedbackBanner(
                                title: "Dataset refresh failed",
                                message: error,
                                tint: AppTheme.warning,
                                actionTitle: "Retry"
                            ) {
                                Task { await viewModel.load(forceRefresh: true) }
                            }
                        }
                        datasetDirectory
                    }
                }
                .padding(AppLayout.pagePadding(for: horizontalSizeClass))
                .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("GraphQL Datasets")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: "Search datasets and fields")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .tint(CloudflareStyle.orange)
    }

    private var scopePanel: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("LIVE DATASET DISCOVERY")
                        .font(.system(size: 9, weight: .semibold))
                        .tracking(0.8)
                        .foregroundStyle(CloudflareStyle.orange)
                    Text("Availability and limits come from your current Cloudflare plan.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(AppTheme.textTertiary)
                }
                Spacer()
                CloudflareStatusPill(text: "\(viewModel.datasets.count) DATASETS", color: CloudflareStyle.green)
            }

            HStack(spacing: 7) {
                ForEach(CloudflareGraphQLScope.allCases) { scope in
                    Button {
                        Task { await viewModel.selectScope(scope) }
                    } label: {
                        Text(scope.rawValue.uppercased())
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(viewModel.scope == scope ? .black : AppTheme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(viewModel.scope == scope ? CloudflareStyle.orange : AppTheme.strokeSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .disabled(scope == .zone && zones.isEmpty)
                }
            }

            if viewModel.scope == .zone, !zones.isEmpty {
                Menu {
                    ForEach(zones.sorted { $0.name < $1.name }) { zone in
                        Button(zone.name) { Task { await viewModel.selectZone(zone.id) } }
                    }
                } label: {
                    HStack {
                        Image(systemName: "globe")
                        Text(zones.first(where: { $0.id == viewModel.selectedZoneID })?.name ?? "Choose zone")
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(AppTheme.textSecondary)
                    .padding(12)
                    .background(AppTheme.strokeSoft, in: RoundedRectangle(cornerRadius: 10))
                }
            }
        }
        .padding(16)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var datasetDirectory: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Available Datasets", icon: "chart.xyaxis.line", count: filteredDatasets.count)
            Divider().overlay(AppTheme.divider)
            if horizontalSizeClass == .regular {
                LazyVGrid(columns: datasetColumns, alignment: .leading, spacing: 10) {
                    ForEach(filteredDatasets) { dataset in
                        datasetLink(dataset)
                            .background(AppTheme.surfaceRaised)
                            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                                    .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
                            }
                    }
                }
                .padding(12)
            } else {
                ForEach(Array(filteredDatasets.enumerated()), id: \.element.id) { index, dataset in
                    datasetLink(dataset)
                    if index < filteredDatasets.count - 1 {
                        Divider().overlay(AppTheme.strokeSoft).padding(.leading, 61)
                    }
                }
            }
        }
        .cloudflarePanel()
    }

    private func datasetLink(_ dataset: CloudflareGraphQLDataset) -> some View {
        NavigationLink {
            CloudflareGraphQLDatasetDetailView(
                api: api,
                accountID: accountID,
                zoneID: viewModel.selectedZoneID,
                scope: viewModel.scope,
                dataset: dataset
            )
        } label: {
            HStack(spacing: 11) {
                Image(systemName: dataset.enabled == false ? "lock.fill" : "waveform.path.ecg")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(dataset.enabled == false ? AppTheme.textTertiary : CloudflareStyle.orange)
                    .frame(width: 36, height: 36)
                    .background(AppTheme.strokeSoft, in: RoundedRectangle(cornerRadius: 9))
                VStack(alignment: .leading, spacing: 3) {
                    Text(dataset.name)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(dataset.enabled == false ? AppTheme.textTertiary : AppTheme.textPrimary)
                        .lineLimit(2)
                    Text(dataset.enabled == false ? "Not enabled on this plan" : "\(dataset.availableFields.count) fields · \(durationLabel(dataset.maxDuration)) max window")
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(AppTheme.textTertiary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .padding(13)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct CloudflareGraphQLDatasetDetailView: View {
    let api: CloudflareAPI
    let accountID: String
    let zoneID: String?
    let scope: CloudflareGraphQLScope
    let dataset: CloudflareGraphQLDataset

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var preset: CloudflareAPIOperationPreset {
        let scopeName = scope == .zone ? "zones" : "accounts"
        let tagName = scope == .zone ? "zoneTag" : "accountTag"
        let tag = scope == .zone ? (zoneID ?? "ZONE_ID") : accountID
        let selection = graphqlSelection(dataset.availableFields)
        let query = "query { viewer { \(scopeName)(filter: { \(tagName): \"\(tag)\" }) { \(dataset.name)(limit: 10) { \(selection) } } } }"
        let body = "{\n  \"query\": \(jsonString(query))\n}"
        return .init(
            id: "graphql-\(dataset.name)",
            title: dataset.name,
            summary: "Edit filters or fields, then query this live Cloudflare GraphQL dataset.",
            method: .post,
            path: "/graphql",
            body: body,
            readOnlyGraphQL: true
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 15) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Image(systemName: "chart.xyaxis.line")
                                .foregroundStyle(CloudflareStyle.orange)
                            Text(dataset.name)
                                .font(.system(size: 16, weight: .semibold, design: .monospaced))
                                .foregroundStyle(AppTheme.textPrimary)
                            Spacer()
                            CloudflareStatusPill(
                                text: dataset.enabled == false ? "LOCKED" : "ENABLED",
                                color: dataset.enabled == false ? CloudflareStyle.amber : CloudflareStyle.green
                            )
                        }
                        if !dataset.description.isEmpty {
                            Text(dataset.description)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                    }
                    .padding(16)
                    .cloudflarePanel(accentOpacity: 0.07)

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Plan Limits", icon: "gauge.with.dots.needle.50percent")
                        Divider().overlay(AppTheme.divider)
                        CloudflareDetailRow(icon: "calendar", title: "Maximum window", value: durationLabel(dataset.maxDuration))
                        CloudflareDetailRow(icon: "clock.arrow.circlepath", title: "Retention", value: durationLabel(dataset.notOlderThan))
                        CloudflareDetailRow(icon: "list.number", title: "Maximum records", value: dataset.maxPageSize?.formatted() ?? "Not returned")
                        CloudflareDetailRow(icon: "square.grid.3x3", title: "Fields per query", value: dataset.maxNumberOfFields?.formatted() ?? "Not returned")
                    }
                    .cloudflarePanel()

                    VStack(spacing: 0) {
                        CloudflareSectionHeader(title: "Available Fields", icon: "list.bullet.rectangle", count: dataset.availableFields.count)
                        Divider().overlay(AppTheme.divider)
                        Text(dataset.availableFields.joined(separator: "\n"))
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(AppTheme.textSecondary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(15)
                    }
                    .cloudflarePanel()

                    NavigationLink {
                        CloudflareAPIExplorerView(api: api, accountID: accountID, preset: preset)
                    } label: {
                        Label("Open generated query", systemImage: "terminal.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.black.opacity(0.84))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(CloudflareStyle.orange, in: RoundedRectangle(cornerRadius: 13))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(dataset.enabled == false)
                }
                .padding(AppLayout.pagePadding(for: horizontalSizeClass))
                .appContentWidth(AppLayout.detailMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("Dataset")
        .navigationBarTitleDisplayMode(.inline)
        .tint(CloudflareStyle.orange)
    }

    private func graphqlSelection(_ fields: [String]) -> String {
        let candidates = Array(fields.prefix(12))
        guard !candidates.isEmpty else { return "__typename" }
        let paths = candidates.map { $0.split(separator: "_").map(String.init) }

        func render(_ paths: [[String]]) -> String {
            let groups = Dictionary(grouping: paths.filter { !$0.isEmpty }, by: { $0[0] })
            return groups.keys.sorted().map { key in
                let remainders = groups[key, default: []].map { Array($0.dropFirst()) }
                let hasDirectField = remainders.contains(where: \.isEmpty)
                let nestedPaths = remainders.filter { !$0.isEmpty }
                if nestedPaths.isEmpty || hasDirectField { return key }
                return "\(key) { \(render(nestedPaths)) }"
            }
            .joined(separator: " ")
        }

        return render(paths)
    }

    private func jsonString(_ value: String) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let string = String(data: data, encoding: .utf8) else { return "\"\"" }
        return string
    }
}

private func durationLabel(_ seconds: Int?) -> String {
    guard let seconds, seconds > 0 else { return "Not returned" }
    let formatter = DateComponentsFormatter()
    formatter.allowedUnits = seconds >= 86_400 ? [.day, .hour] : [.hour, .minute]
    formatter.unitsStyle = .abbreviated
    formatter.maximumUnitCount = 2
    return formatter.string(from: TimeInterval(seconds)) ?? "\(seconds)s"
}

private nonisolated extension CloudflareJSONValue {
    var objectValue: [String: CloudflareJSONValue]? {
        guard case .object(let value) = self else { return nil }
        return value
    }
    var arrayValue: [CloudflareJSONValue]? {
        guard case .array(let value) = self else { return nil }
        return value
    }
    var boolValue: Bool? {
        guard case .bool(let value) = self else { return nil }
        return value
    }
    var intValue: Int? {
        switch self {
        case .int(let value): Int(exactly: value)
        case .double(let value): Int(exactly: value)
        default: nil
        }
    }
    var deepTypeName: String? {
        if let name = objectValue?["name"]?.stringValue { return name }
        return objectValue?["ofType"]?.deepTypeName
    }
    func value(at path: [String]) -> CloudflareJSONValue? {
        path.reduce(Optional(self)) { value, key in value?.objectValue?[key] }
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0 else { return [] }
        return stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}
