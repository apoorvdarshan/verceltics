import SwiftUI

struct AccountsView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var showingAddAccount = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Spacer().frame(height: 8)

                    accountSummary
                    accountList
                    accountActions
                }
                .frame(maxWidth: hSize == .regular ? 640 : .infinity)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 40)
            }
            .background(Color.black)
            .navigationTitle("Accounts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .sheet(isPresented: $showingAddAccount) {
                LoginView()
            }
        }
    }

    private var accountSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Active Account")
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.4))
                .tracking(1.4)

            HStack(spacing: 14) {
                Image(systemName: "person.crop.circle.fill")
                    .font(.system(size: 18, weight: .heavy))
                    .foregroundStyle(Color(red: 0.30, green: 0.60, blue: 1.0))
                    .frame(width: 42, height: 42)
                    .background(Color.white.opacity(0.07))
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(authManager.activeAccount?.name ?? "No active account")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                    Text(activeTokenPreview)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.35))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer()
            }
            .padding(16)
            .background(sectionBackground)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(sectionStroke(cornerRadius: 18))
        }
        .padding(.horizontal, 16)
    }

    private var accountList: some View {
        accountSection(title: "SWITCH ACCOUNT") {
            ForEach(authManager.accounts) { account in
                accountRow(account)

                if account.id != authManager.accounts.last?.id {
                    Divider()
                        .overlay(Color.white.opacity(0.06))
                        .padding(.leading, 62)
                }
            }

            if authManager.accounts.isEmpty {
                Text("No accounts connected.")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.4))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 16)
            }
        }
    }

    private var accountActions: some View {
        VStack(spacing: 12) {
            Button {
                showingAddAccount = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 14, weight: .heavy))
                    Text("Add Account")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(.white)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressScaleButtonStyle())

            if authManager.activeAccount != nil {
                Button {
                    authManager.logout()
                } label: {
                    accountActionLabel(icon: "rectangle.portrait.and.arrow.right", text: "Sign Out Current Account")
                }
                .buttonStyle(PressScaleButtonStyle())
            }

            if authManager.accounts.count > 1 {
                Button(role: .destructive) {
                    authManager.logoutAll()
                } label: {
                    accountActionLabel(icon: "trash.fill", text: "Remove All Accounts")
                }
                .buttonStyle(PressScaleButtonStyle())
            }
        }
        .padding(.horizontal, 16)
    }

    private func accountRow(_ account: VercelAccount) -> some View {
        let isActive = authManager.activeAccountId == account.id

        return HStack(spacing: 10) {
            Button {
                authManager.switchAccount(to: account.id)
            } label: {
                HStack(spacing: 14) {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 16, weight: .heavy))
                        .foregroundStyle(isActive ? Color(red: 0.30, green: 0.60, blue: 1.0) : .white.opacity(0.42))
                        .frame(width: 34, height: 34)
                        .background(Color.white.opacity(0.06))
                        .clipShape(Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.name)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                        Text(tokenPreview(account.token))
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.3))
                            .lineLimit(1)
                    }

                    Spacer()

                    if isActive {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Color(red: 0.30, green: 0.60, blue: 1.0))
                    }
                }
            }
            .buttonStyle(PressScaleButtonStyle())

            if !isActive {
                Button(role: .destructive) {
                    authManager.removeAccount(id: account.id)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42).opacity(0.75))
                        .frame(width: 32, height: 32)
                        .background(Color.red.opacity(0.08))
                        .clipShape(Circle())
                }
                .buttonStyle(PressScaleButtonStyle())
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    private func accountActionLabel(icon: String, text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .heavy))
            Text(text)
                .font(.system(size: 14, weight: .bold))
        }
        .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.42))
        .frame(maxWidth: .infinity)
        .frame(height: 50)
        .background(
            LinearGradient(
                colors: [Color.red.opacity(0.14), Color.red.opacity(0.06)],
                startPoint: .top, endPoint: .bottom
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.red.opacity(0.16), lineWidth: 0.5)
        )
    }

    private func accountSection(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.4))
                .tracking(1.4)
                .padding(.horizontal, 22)
                .padding(.bottom, 10)

            VStack(spacing: 0) {
                content()
            }
            .background(sectionBackground)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(sectionStroke(cornerRadius: 18))
            .padding(.horizontal, 16)
        }
    }

    private var sectionBackground: some View {
        ZStack {
            LinearGradient(
                colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            LinearGradient(
                colors: [Color.white.opacity(0.04), .clear],
                startPoint: .top,
                endPoint: .center
            )
        }
    }

    private func sectionStroke(cornerRadius: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .strokeBorder(
                LinearGradient(
                    colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                    startPoint: .top, endPoint: .bottom
                ),
                lineWidth: 0.5
            )
    }

    private var activeTokenPreview: String {
        guard let token = authManager.activeAccount?.token else { return "Add a Vercel token to continue" }
        return tokenPreview(token)
    }

    private func tokenPreview(_ token: String) -> String {
        "\(token.prefix(12))..."
    }
}
