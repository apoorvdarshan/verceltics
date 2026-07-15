import SwiftUI

struct SiteAccountMenu: View {
    @Environment(SiteStore.self) private var store
    @State private var showingAddService = false
    @State private var removalIntent: RemovalIntent?

    private enum RemovalIntent: Identifiable {
        case current(SiteIntegrationAccount)
        case all

        var id: String {
            switch self {
            case .current(let account): "current-\(account.id)"
            case .all: "all"
            }
        }
    }

    var body: some View {
        Menu {
            ForEach(SiteIntegrationProvider.allCases) { provider in
                let accounts = store.accounts.filter { $0.provider == provider }
                if !accounts.isEmpty {
                    Section(provider.displayName) {
                        ForEach(accounts) { account in
                            Button {
                                store.switchAccount(to: account.id)
                            } label: {
                                Label {
                                    Text(account.name)
                                } icon: {
                                    if store.activeAccountID == account.id {
                                        Image(systemName: "checkmark.circle.fill")
                                    } else {
                                        SiteProviderMark(provider: provider, size: 16, monochrome: true)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Section {
                Button { showingAddService = true } label: {
                    Label("Add Site Service", systemImage: "plus.circle.fill")
                }
            }

            if let active = store.activeAccount {
                Section {
                    Button(role: .destructive) {
                        removalIntent = .current(active)
                    } label: {
                        Label("Remove Current Service", systemImage: "rectangle.portrait.and.arrow.right")
                    }

                    if store.accounts.count > 1 {
                        Button(role: .destructive) {
                            removalIntent = .all
                        } label: {
                            Label("Remove All Site Services", systemImage: "trash.fill")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                if let provider = store.activeAccount?.provider {
                    SiteProviderMark(provider: provider, size: 21, monochrome: true)
                } else {
                    Image(systemName: "chart.xyaxis.line")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                }

                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityLabel("Switch connected site service")
            .accessibilityValue(store.activeAccount?.name ?? "No active site service")
        }
        .tint(AppTheme.textPrimary)
        .sheet(isPresented: $showingAddService) {
            LoginView(initialCategory: .sites)
                .presentationSizing(.page)
                .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            removalTitle,
            isPresented: Binding(
                get: { removalIntent != nil },
                set: { if !$0 { removalIntent = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(removalButtonTitle, role: .destructive) { confirmRemoval() }
            Button("Cancel", role: .cancel) { removalIntent = nil }
        } message: {
            Text("Credentials are removed from this device only.")
        }
    }

    private var removalTitle: String {
        switch removalIntent {
        case .current(let account): "Remove \(account.name)?"
        case .all: "Remove all site services?"
        case nil: "Remove site service?"
        }
    }

    private var removalButtonTitle: String {
        switch removalIntent {
        case .all: "Remove All Services"
        case .current, nil: "Remove Service"
        }
    }

    private func confirmRemoval() {
        switch removalIntent {
        case .current(let account): store.removeAccount(id: account.id)
        case .all: store.removeAll()
        case nil: break
        }
        removalIntent = nil
    }
}
