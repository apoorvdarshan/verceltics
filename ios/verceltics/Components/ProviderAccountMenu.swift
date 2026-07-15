import SwiftUI

struct ProviderAccountMenu: View {
    @Environment(AuthManager.self) private var authManager
    @State private var showingAddAccount = false
    @State private var removalIntent: RemovalIntent?

    private enum RemovalIntent: Identifiable {
        case current(VercelAccount)
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
            ForEach(AccountProvider.allCases) { provider in
                accountSection(provider: provider)
            }

            Section {
                Button { showingAddAccount = true } label: {
                    Label("Add Account", systemImage: "plus.circle.fill")
                }
            }

            if let active = authManager.activeAccount {
                Section {
                    Button(role: .destructive) {
                        removalIntent = .current(active)
                    } label: {
                        Label("Remove Current Account", systemImage: "rectangle.portrait.and.arrow.right")
                    }

                    if authManager.accounts.count > 1 {
                        Button(role: .destructive) {
                            removalIntent = .all
                        } label: {
                            Label("Remove All Accounts", systemImage: "trash.fill")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                providerBadge(for: authManager.activeAccount)
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityLabel("Switch connected account")
            .accessibilityValue(authManager.activeAccount?.name ?? "No active account")
        }
        .tint(AppTheme.textPrimary)
        .sheet(isPresented: $showingAddAccount) {
            LoginView(initialCategory: .hosting)
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
        case .all: "Remove all hosting accounts?"
        case nil: "Remove account?"
        }
    }

    private var removalButtonTitle: String {
        switch removalIntent {
        case .all: "Remove All Accounts"
        case .current, nil: "Remove Account"
        }
    }

    private func confirmRemoval() {
        switch removalIntent {
        case .current(let account): authManager.removeAccount(id: account.id)
        case .all: authManager.logoutAll()
        case nil: break
        }
        removalIntent = nil
    }

    @ViewBuilder
    private func accountSection(provider: AccountProvider) -> some View {
        let accounts = authManager.accounts.filter { $0.provider == provider }
        if !accounts.isEmpty {
            Section(provider.displayName) {
                ForEach(accounts) { account in
                    Button {
                        authManager.switchAccount(to: account.id)
                    } label: {
                        Label {
                            Text(account.name)
                        } icon: {
                            if authManager.activeAccountId == account.id {
                                Image(systemName: "checkmark.circle.fill")
                            } else {
                                ProviderMark(provider: provider, size: 18)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func providerBadge(for account: VercelAccount?) -> some View {
        if let account, account.provider == .vercel,
           let avatarURL = account.avatarURL, let url = URL(string: avatarURL) {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 11, weight: .semibold))
                }
            }
            .frame(width: 22, height: 22)
            .clipShape(Circle())
        } else if let provider = account?.provider {
            ProviderMark(provider: provider, size: 23, monochrome: true)
        } else {
            Image(systemName: "server.rack")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppTheme.textPrimary)
        }
    }
}
