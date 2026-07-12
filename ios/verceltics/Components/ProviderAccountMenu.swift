import SwiftUI

struct ProviderAccountMenu: View {
    @Environment(AuthManager.self) private var authManager
    @State private var showingAddAccount = false

    var body: some View {
        Menu {
            accountSection(provider: .vercel)
            accountSection(provider: .cloudflare)

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
                                : provider == .cloudflare ? "cloud.fill" : "triangle.fill"
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func providerBadge(for account: VercelAccount?) -> some View {
        if let account, account.provider == .cloudflare {
            Text("CF")
                .font(.system(size: 9, weight: .black, design: .rounded))
                .foregroundStyle(Color(red: 1.0, green: 0.48, blue: 0.10))
                .frame(width: 23, height: 23)
                .background(Color(red: 1.0, green: 0.48, blue: 0.10).opacity(0.14))
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        } else if let avatarURL = account?.avatarURL, let url = URL(string: avatarURL) {
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
        } else {
            Image(systemName: "triangle.fill")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(.white)
        }
    }
}
