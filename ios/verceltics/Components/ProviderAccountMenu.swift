import SwiftUI

struct ProviderAccountMenu: View {
    @Environment(AuthManager.self) private var authManager
    @State private var showingAddAccount = false

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
                        authManager.removeAccount(id: active.id)
                    } label: {
                        Label("Remove Current Account", systemImage: "rectangle.portrait.and.arrow.right")
                    }

                    if authManager.accounts.count > 1 {
                        Button(role: .destructive) {
                            authManager.logoutAll()
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
                    .font(.system(size: 8, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.45))
            }
            .frame(height: 30)
            .accessibilityLabel("Switch connected account")
        }
        .tint(.white)
        .sheet(isPresented: $showingAddAccount) {
            LoginView()
        }
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
                        Label(
                            account.name,
                            systemImage: authManager.activeAccountId == account.id
                                ? "checkmark.circle.fill"
                                : provider.systemImage
                        )
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
                        .font(.system(size: 11, weight: .heavy))
                }
            }
            .frame(width: 22, height: 22)
            .clipShape(Circle())
        } else if let provider = account?.provider {
            ProviderMark(provider: provider, size: 23, monochrome: true)
        } else {
            Image(systemName: "triangle.fill")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(.white)
        }
    }
}
