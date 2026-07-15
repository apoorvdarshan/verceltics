import SwiftUI

struct RegistrarAccountMenu: View {
    @Environment(RegistrarStore.self) private var store
    @State private var showingAddAccount = false
    @State private var removalIntent: RemovalIntent?

    private enum RemovalIntent: Identifiable {
        case current(RegistrarAccount)
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
            ForEach(RegistrarProvider.allCases) { provider in
                let accounts = store.accounts.filter { $0.provider == provider }
                if !accounts.isEmpty {
                    Section(provider.displayName) {
                        ForEach(accounts) { account in
                            Button { store.switchAccount(to: account.id) } label: {
                                Label {
                                    Text(account.name)
                                } icon: {
                                    if store.activeAccountID == account.id {
                                        Image(systemName: "checkmark.circle.fill")
                                    } else {
                                        RegistrarMark(provider: provider, size: 20)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Section {
                Button { showingAddAccount = true } label: { Label("Add Registrar", systemImage: "plus.circle.fill") }
            }
            if let active = store.activeAccount {
                Section {
                    Button(role: .destructive) { removalIntent = .current(active) } label: {
                        Label("Remove Current Registrar", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    if store.accounts.count > 1 {
                        Button(role: .destructive) { removalIntent = .all } label: {
                            Label("Remove All Registrars", systemImage: "trash.fill")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                if let provider = store.activeAccount?.provider { RegistrarMark(provider: provider, size: 25, monochrome: true) }
                else { Image(systemName: "globe").font(.system(size: 16, weight: .bold)) }
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.45))
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityLabel("Switch connected registrar")
            .accessibilityValue(store.activeAccount?.name ?? "No active registrar")
        }
        .tint(.white)
        .sheet(isPresented: $showingAddAccount) {
            LoginView(initialCategory: .registrars)
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
        case .all: "Remove all registrar accounts?"
        case nil: "Remove registrar?"
        }
    }

    private var removalButtonTitle: String {
        switch removalIntent {
        case .all: "Remove All Registrars"
        case .current, nil: "Remove Registrar"
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
