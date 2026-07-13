import SwiftUI

struct RegistrarAccountMenu: View {
    @Environment(RegistrarStore.self) private var store
    @State private var showingAddAccount = false

    var body: some View {
        Menu {
            ForEach(RegistrarProvider.allCases) { provider in
                let accounts = store.accounts.filter { $0.provider == provider }
                if !accounts.isEmpty {
                    Section(provider.displayName) {
                        ForEach(accounts) { account in
                            Button { store.switchAccount(to: account.id) } label: {
                                Label(account.name, systemImage: store.activeAccountID == account.id ? "checkmark.circle.fill" : "globe")
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
                    Button(role: .destructive) { store.removeAccount(id: active.id) } label: {
                        Label("Remove Current Registrar", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    if store.accounts.count > 1 {
                        Button(role: .destructive) { store.removeAll() } label: {
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
                    .font(.system(size: 8, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.45))
            }
            .frame(height: 30)
            .accessibilityLabel("Switch connected registrar")
        }
        .tint(.white)
        .sheet(isPresented: $showingAddAccount) {
            LoginView(initialCategory: .registrars)
        }
    }
}
