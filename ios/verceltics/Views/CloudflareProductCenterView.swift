import SwiftUI

struct CloudflareProductCenterView: View {
    let api: CloudflareAPI
    let accountID: String
    let accountName: String
    let zones: [CloudflareZone]
    let authenticationMode: CloudflareAuthenticationMode

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var searchText = ""
    @State private var selectedZoneID: String?

    init(
        api: CloudflareAPI,
        accountID: String,
        accountName: String,
        zones: [CloudflareZone],
        authenticationMode: CloudflareAuthenticationMode
    ) {
        self.api = api
        self.accountID = accountID
        self.accountName = accountName
        self.zones = zones
        self.authenticationMode = authenticationMode
        _selectedZoneID = State(initialValue: zones.first?.id)
    }

    private var selectedZone: CloudflareZone? {
        zones.first { $0.id == selectedZoneID }
    }

    private var filteredProducts: [CloudflareProductDefinition] {
        guard !searchText.isEmpty else { return CloudflareProductCatalog.products }
        return CloudflareProductCatalog.products.compactMap { product in
            let productMatches = product.title.localizedCaseInsensitiveContains(searchText)
                || product.summary.localizedCaseInsensitiveContains(searchText)
            let operations = product.operations.filter {
                productMatches || $0.title.localizedCaseInsensitiveContains(searchText)
                    || $0.summary.localizedCaseInsensitiveContains(searchText)
            }
            guard !operations.isEmpty else { return nil }
            return .init(
                id: product.id,
                title: product.title,
                summary: product.summary,
                icon: product.icon,
                operations: operations
            )
        }
    }

    private var productColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 400,
            regularMaximum: 520,
            spacing: 16
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 16) {
                    commandHeader
                    contextPanel

                    if filteredProducts.isEmpty {
                        CloudflareEmptySection(
                            icon: "magnifyingglass",
                            title: "No operations found",
                            message: "Try a product name such as DNS, Workers, Images or Tunnel."
                        )
                        .cloudflarePanel()
                    } else {
                        LazyVGrid(columns: productColumns, alignment: .leading, spacing: 16) {
                            ForEach(filteredProducts) { product in
                                productPanel(product)
                            }
                        }
                    }
                }
                .padding(AppLayout.pagePadding(for: horizontalSizeClass))
                .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("Product Operations")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search products and operations")
        .tint(CloudflareStyle.orange)
    }

    private var commandHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                AppIconTile(icon: "cloud.bolt.rain.fill", tint: CloudflareStyle.orange, size: 48)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Cloudflare control plane")
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.white)
                    Text(accountName)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                CloudflareStatusPill(
                    text: authenticationMode == .apiToken ? "SCOPED" : "GLOBAL",
                    color: authenticationMode == .apiToken ? CloudflareStyle.green : CloudflareStyle.amber
                )
            }

            HStack(spacing: 9) {
                commandMetric("PRODUCTS", CloudflareProductCatalog.products.count)
                commandMetric(
                    "OPERATIONS",
                    CloudflareProductCatalog.products.reduce(0) { $0 + $1.operations.count }
                )
                commandMetric("ZONES", zones.count)
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func commandMetric(_ label: String, _ value: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 8, weight: .semibold))
                .tracking(0.7)
                .foregroundStyle(.white.opacity(0.3))
            Text(value.formatted())
                .font(.system(size: 19, weight: .semibold, design: .default).monospacedDigit())
                .foregroundStyle(.white)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(11)
        .background(Color.black.opacity(0.24), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private var contextPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("REQUEST CONTEXT")
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.9)
                .foregroundStyle(.white.opacity(0.34))

            contextRow(label: "Account", value: accountName, id: accountID)

            if zones.isEmpty {
                Text("No zone is available. Account-scoped operations still work; zone paths keep a ZONE_ID placeholder.")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(CloudflareStyle.amber.opacity(0.75))
            } else {
                Menu {
                    ForEach(zones.sorted { $0.name < $1.name }) { zone in
                        Button(zone.name) { selectedZoneID = zone.id }
                    }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "globe")
                            .foregroundStyle(CloudflareStyle.orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("ZONE")
                                .font(.system(size: 8, weight: .semibold))
                                .tracking(0.7)
                                .foregroundStyle(.white.opacity(0.3))
                            Text(selectedZone?.name ?? "Choose zone")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white.opacity(0.78))
                        }
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.3))
                    }
                    .padding(12)
                    .background(Color.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
            }

            Text("Every mutation opens an editable request and requires a final confirmation before it reaches Cloudflare.")
                .font(.system(size: 9, weight: .medium))
                .foregroundStyle(.white.opacity(0.26))
        }
        .padding(14)
        .cloudflarePanel()
    }

    private func contextRow(label: String, value: String, id: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "building.2")
                .foregroundStyle(CloudflareStyle.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(label.uppercased())
                    .font(.system(size: 8, weight: .semibold))
                    .tracking(0.7)
                    .foregroundStyle(.white.opacity(0.3))
                Text(value)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white.opacity(0.78))
            }
            Spacer()
            Text(String(id.prefix(8)))
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.25))
        }
        .padding(12)
        .background(Color.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private func productPanel(_ product: CloudflareProductDefinition) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                Image(systemName: product.icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .frame(width: 36, height: 36)
                    .background(CloudflareStyle.orange.opacity(0.11), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(product.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.86))
                    Text(product.summary)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.32))
                        .lineLimit(2)
                }
                Spacer()
                Text(product.operations.count.formatted())
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.36))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(Color.white.opacity(0.055), in: Capsule())
            }
            .padding(14)

            Divider().overlay(Color.white.opacity(0.06))

            ForEach(Array(product.operations.enumerated()), id: \.element.id) { index, operation in
                operationRow(operation)
                if index < product.operations.count - 1 {
                    Divider().overlay(Color.white.opacity(0.05)).padding(.leading, 57)
                }
            }
        }
        .cloudflarePanel(accentOpacity: 0.04)
    }

    @ViewBuilder
    private func operationRow(_ operation: CloudflareAPIOperationPreset) -> some View {
        let locked = operation.requiresAPIToken && authenticationMode != .apiToken
        let resolved = operation.resolved(accountID: accountID, zoneID: selectedZoneID)

        if locked {
            operationLabel(operation, locked: true)
                .opacity(0.48)
        } else {
            NavigationLink {
                CloudflareAPIExplorerView(api: api, accountID: accountID, preset: resolved)
            } label: {
                operationLabel(operation, locked: false)
            }
            .buttonStyle(.plain)
        }
    }

    private func operationLabel(_ operation: CloudflareAPIOperationPreset, locked: Bool) -> some View {
        HStack(spacing: 11) {
            Text(operation.method.rawValue)
                .font(.system(size: 8, weight: .semibold, design: .monospaced))
                .foregroundStyle(methodColor(operation.method))
                .frame(width: 36, height: 26)
                .background(methodColor(operation.method).opacity(0.1), in: RoundedRectangle(cornerRadius: 7, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(operation.title)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white.opacity(0.78))
                Text(locked ? "Scoped API token required" : operation.summary)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(locked ? CloudflareStyle.amber.opacity(0.7) : .white.opacity(0.29))
                    .lineLimit(2)
            }
            Spacer(minLength: 6)
            Image(systemName: locked ? "lock.fill" : "chevron.right")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.white.opacity(0.25))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }

    private func methodColor(_ method: CloudflareHTTPMethod) -> Color {
        switch method {
        case .get: CloudflareStyle.green
        case .post: CloudflareStyle.orange
        case .put, .patch: CloudflareStyle.amber
        case .delete: CloudflareStyle.red
        }
    }
}
