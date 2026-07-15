import SwiftUI

@Observable
@MainActor
final class CloudflareD1DatabaseViewModel {
    private static var databaseCache: [String: CloudflareD1Database] = [:]

    let api: CloudflareAPI
    let accountID: String
    let databaseID: String

    var database: CloudflareD1Database
    var queryResults: [CloudflareD1QueryResult] = []
    var isLoading = true
    var isRunningQuery = false
    var isDeleting = false
    var didDelete = false
    var actionMessage: String?
    var actionFailed = false
    private var hasLoaded = false

    private var cacheKey: String { "\(api.cacheScope)|\(accountID)|\(databaseID)" }

    init(api: CloudflareAPI, accountID: String, database: CloudflareD1Database) {
        self.api = api
        self.accountID = accountID
        databaseID = database.id
        let key = "\(api.cacheScope)|\(accountID)|\(database.id)"
        self.database = Self.databaseCache[key] ?? database
        hasLoaded = Self.databaseCache[key] != nil
        isLoading = !hasLoaded
    }

    func load(forceRefresh: Bool = false) async {
        if hasLoaded && !forceRefresh { return }
        isLoading = true
        defer { isLoading = false }
        do {
            database = try await api.fetchD1Database(accountID: accountID, databaseID: databaseID)
            Self.databaseCache[cacheKey] = database
            hasLoaded = true
        } catch is CancellationError {
            // Navigation can cancel an in-flight request; retain prior state.
        } catch let urlError as URLError where urlError.code == .cancelled {
            // Navigation can cancel an in-flight request; retain prior state.
        } catch {
            report(error.localizedDescription, failed: true)
        }
    }

    func run(sql: String) async {
        isRunningQuery = true
        defer { isRunningQuery = false }
        do {
            queryResults = try await api.queryD1Database(
                accountID: accountID,
                databaseID: databaseID,
                sql: sql,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: databaseID)
            )
            let rows = queryResults.reduce(0) { $0 + $1.rows.count }
            report(rows == 1 ? "Query returned 1 row." : "Query returned \(rows) rows.")
            if queryResults.contains(where: { $0.meta?.changedDatabase == true }) {
                database = try await api.fetchD1Database(accountID: accountID, databaseID: databaseID)
                Self.databaseCache[cacheKey] = database
            }
        } catch {
            queryResults = []
            report(error.localizedDescription, failed: true)
        }
    }

    func delete() async {
        isDeleting = true
        defer { isDeleting = false }
        do {
            try await api.deleteD1Database(
                accountID: accountID,
                databaseID: databaseID,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: databaseID)
            )
            didDelete = true
            Self.databaseCache[cacheKey] = nil
        } catch {
            report(error.localizedDescription, failed: true)
        }
    }

    private func report(_ message: String, failed: Bool = false) {
        actionMessage = message
        actionFailed = failed
    }
}

struct CloudflareD1DatabaseView: View {
    let api: CloudflareAPI
    let accountID: String
    let initialDatabase: CloudflareD1Database

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareD1DatabaseViewModel
    @State private var sql = "SELECT name, type FROM sqlite_schema WHERE type IN ('table', 'view') ORDER BY name;"
    @State private var isConfirmingQuery = false
    @State private var isConfirmingDelete = false

    init(api: CloudflareAPI, accountID: String, database: CloudflareD1Database) {
        self.api = api
        self.accountID = accountID
        initialDatabase = database
        _viewModel = State(wrappedValue: CloudflareD1DatabaseViewModel(api: api, accountID: accountID, database: database))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    header
                    metadata
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    queryPanel
                    queryResults
                    dangerZone
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(viewModel.database.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load(forceRefresh: true) }
        .onChange(of: viewModel.didDelete) { _, deleted in if deleted { dismiss() } }
        .confirmationDialog("Run this SQL statement?", isPresented: $isConfirmingQuery, titleVisibility: .visible) {
            Button("Run SQL") { Task { await viewModel.run(sql: sql) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("D1 accepts both read and write SQL here. Review the statement before running it.")
        }
        .confirmationDialog("Delete this D1 database?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete Database", role: .destructive) { Task { await viewModel.delete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(viewModel.database.name) and all of its data will be permanently deleted.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 13) {
            AppIconTile(icon: "cylinder.split.1x2.fill", tint: CloudflareStyle.orange, size: 46)

            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.database.name)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text("Cloudflare D1 · SQLite")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.38))
            }
            Spacer(minLength: 8)
            CloudflareStatusPill(text: "READY", color: CloudflareStyle.green)
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var metadata: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Database", icon: "info.circle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "number", title: "Database ID", value: viewModel.database.uuid)
            CloudflareDetailRow(icon: "tablecells.fill", title: "Tables", value: viewModel.database.numberOfTables?.formatted() ?? "Not returned")
            CloudflareDetailRow(icon: "internaldrive.fill", title: "File size", value: storageByteCount(viewModel.database.fileSize))
            CloudflareDetailRow(icon: "globe", title: "Jurisdiction", value: viewModel.database.jurisdiction?.uppercased() ?? "Automatic")
            CloudflareDetailRow(icon: "point.3.connected.trianglepath.dotted", title: "Read replication", value: viewModel.database.readReplication?.mode.capitalized ?? "Not returned")
            if let version = viewModel.database.version {
                CloudflareDetailRow(icon: "number.square.fill", title: "Version", value: version)
            }
            if let date = viewModel.database.createdDate {
                CloudflareDetailRow(icon: "calendar", title: "Created", value: date.formatted(date: .abbreviated, time: .shortened))
            }
        }
        .cloudflarePanel()
    }

    private var queryPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "SQL Console", icon: "terminal.fill")
            Divider().overlay(Color.white.opacity(0.06))

            TextEditor(text: $sql)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(.white.opacity(0.82))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 128)
                .padding(12)
                .background(Color.black.opacity(0.28))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
                )
                .padding(14)

            Divider().overlay(Color.white.opacity(0.06))
            HStack {
                Text("Queries run directly against this live database.")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.32))
                Spacer()
                CloudflareActionButton(
                    title: "Run SQL",
                    icon: "play.fill",
                    isWorking: viewModel.isRunningQuery,
                    action: { isConfirmingQuery = true }
                )
                .disabled(sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(14)
        }
        .cloudflarePanel()
    }

    @ViewBuilder
    private var queryResults: some View {
        if !viewModel.queryResults.isEmpty {
            ForEach(Array(viewModel.queryResults.enumerated()), id: \.offset) { index, result in
                VStack(spacing: 0) {
                    CloudflareSectionHeader(
                        title: viewModel.queryResults.count == 1 ? "Query Result" : "Result \(index + 1)",
                        icon: "tablecells.fill",
                        count: result.rows.count
                    )
                    Divider().overlay(Color.white.opacity(0.06))
                    if result.rows.isEmpty {
                        CloudflareEmptySection(
                            icon: result.success ? "checkmark.circle.fill" : "xmark.circle.fill",
                            title: result.success ? "Statement completed" : "Statement failed",
                            message: queryMetaSummary(result.meta)
                        )
                    } else {
                        CloudflareD1ResultTable(rows: result.rows)
                    }
                    if result.meta != nil {
                        Divider().overlay(Color.white.opacity(0.06))
                        Text(queryMetaSummary(result.meta))
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.38))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                    }
                }
                .cloudflarePanel()
            }
        }
    }

    private var dangerZone: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Danger Zone", icon: "exclamationmark.triangle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Delete database")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white.opacity(0.76))
                    Text("This permanently removes its schema and every row.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.34))
                }
                Spacer(minLength: 8)
                CloudflareActionButton(
                    title: "Delete",
                    icon: "trash.fill",
                    role: .destructive,
                    isWorking: viewModel.isDeleting,
                    action: { isConfirmingDelete = true }
                )
            }
            .padding(16)
        }
        .cloudflarePanel(accentOpacity: 0.06)
    }

    private func queryMetaSummary(_ meta: CloudflareD1QueryResult.Meta?) -> String {
        guard let meta else { return "No execution metadata returned." }
        var parts: [String] = []
        if let duration = meta.timings?.sqlDurationMilliseconds ?? meta.duration {
            parts.append(String(format: "%.2f ms", duration))
        }
        if let read = meta.rowsRead { parts.append("\(read) rows read") }
        if let written = meta.rowsWritten { parts.append("\(written) rows written") }
        if let region = meta.servedByRegion { parts.append(region) }
        if let colo = meta.servedByColo { parts.append(colo) }
        return parts.isEmpty ? "Statement completed." : parts.joined(separator: " · ")
    }
}

private struct CloudflareD1ResultTable: View {
    let rows: [[String: CloudflareJSONValue]]

    private var columns: [String] {
        Array(Set(rows.flatMap(\.keys))).sorted()
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 0) {
                GridRow {
                    ForEach(columns, id: \.self) { column in
                        Text(column.uppercased())
                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                            .foregroundStyle(CloudflareStyle.orange)
                            .frame(minWidth: 100, alignment: .leading)
                            .padding(.vertical, 11)
                    }
                }

                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    Divider().overlay(Color.white.opacity(0.055)).gridCellColumns(max(columns.count, 1))
                    GridRow {
                        ForEach(columns, id: \.self) { column in
                            Text(cloudflareStorageDisplayValue(row[column]))
                                .font(.system(size: 11, weight: .medium, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.7))
                                .lineLimit(4)
                                .textSelection(.enabled)
                                .frame(minWidth: 100, maxWidth: 260, alignment: .leading)
                                .padding(.vertical, 11)
                        }
                    }
                }
            }
            .padding(.horizontal, 14)
        }
    }
}

func cloudflareStorageDisplayValue(_ value: CloudflareJSONValue?) -> String {
    guard let value else { return "NULL" }
    switch value {
    case .string(let string): return string
    case .int(let integer): return integer.formatted()
    case .double(let double): return double.formatted()
    case .bool(let boolean): return boolean ? "true" : "false"
    case .null: return "NULL"
    case .object, .array:
        guard let data = try? JSONEncoder().encode(value),
              let string = String(data: data, encoding: .utf8) else { return "JSON" }
        return string
    }
}

func storageByteCount(_ value: Int64?) -> String {
    guard let value else { return "Not returned" }
    return ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
}
