import SwiftUI

enum ConnectionCategory: String, CaseIterable, Identifiable {
    case hosting
    case registrars

    var id: Self { self }

    var title: String {
        switch self {
        case .hosting: "Hosting"
        case .registrars: "Registrars"
        }
    }

    var systemImage: String {
        switch self {
        case .hosting: "server.rack"
        case .registrars: "globe.americas.fill"
        }
    }
}

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.dismiss) private var dismiss
    @State private var connectionCategory: ConnectionCategory
    @State private var tokenInput = ""
    @State private var selectedProvider: AccountProvider?
    @State private var selectedRegistrarProvider: RegistrarProvider?
    @State private var cloudflareEmail = ""
    @State private var cloudflareGlobalAPIKey = ""
    @State private var cloudflareAPIToken = ""
    @State private var cloudflareAuthenticationMode: CloudflareAuthenticationMode = .globalAPIKey
    @FocusState private var isTokenFocused: Bool
    @FocusState private var focusedCloudflareField: CloudflareField?

    private enum CloudflareField { case email, key }

    init(initialCategory: ConnectionCategory = .hosting) {
        _connectionCategory = State(initialValue: initialCategory)
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            Group {
                if let registrar = selectedRegistrarProvider {
                    RegistrarConnectionView(
                        initialProvider: registrar,
                        onBack: {
                            withAnimation(.spring(duration: 0.35)) {
                                selectedRegistrarProvider = nil
                            }
                        },
                        onConnected: {
                            selectedRegistrarProvider = nil
                            dismiss()
                        }
                    )
                } else {
                    switch selectedProvider {
                    case .vercel:
                        tokenFieldView
                    case .cloudflare:
                        cloudflareCredentialsView
                    case .some(let provider):
                        HostingProviderCredentialView(provider: provider) {
                            authManager.error = nil
                            withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                        }
                    case nil:
                        welcomeView
                    }
                }
            }
            .frame(maxWidth: hSize == .regular ? 480 : .infinity)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Platform selection

    private var welcomeView: some View {
        ScrollView {
            VStack(spacing: 0) {
                categorySelector
                    .padding(.horizontal, 20)
                    .padding(.top, 18)

                VStack(alignment: .leading, spacing: 12) {
                    Text(connectionCategory == .hosting ? "CONNECT A HOSTING PLATFORM" : "CONNECT A REGISTRAR")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.5)
                        .foregroundStyle(AppTheme.textSecondary)
                        .padding(.horizontal, 4)

                    if connectionCategory == .hosting {
                        ForEach(AccountProvider.allCases) { provider in
                            providerButton(provider)
                        }
                    } else {
                        ForEach(RegistrarProvider.allCases) { provider in
                            registrarButton(provider)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 24)

                Spacer().frame(height: 40)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var categorySelector: some View {
        Picker("Connection type", selection: $connectionCategory) {
            ForEach(ConnectionCategory.allCases) { category in
                Label(category.title, systemImage: category.systemImage)
                    .tag(category)
            }
        }
        .pickerStyle(.segmented)
        .controlSize(.large)
        .frame(height: 48)
        .accessibilityLabel("Connection type")
    }

    private func providerButton(_ provider: AccountProvider) -> some View {
        let accent = provider.accentColor
        return Button {
            authManager.error = nil
            withAnimation(.spring(duration: 0.4)) { selectedProvider = provider }
        } label: {
            HStack(spacing: 13) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(accent.opacity(0.14))
                    ProviderMark(provider: provider, size: 18)
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect \(provider.displayName)")
                        .font(.headline)
                    Text(provider.connectionSubtitle)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                }
                .layoutPriority(1)

                Spacer()
                Image(systemName: "arrow.right")
                    .font(.system(size: 12, weight: .semibold))
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 64)
            .padding(.vertical, 8)
            .foregroundStyle(.white)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
            .liquidGlassSurface(cornerRadius: AppTheme.panelRadius)
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .fill(accent.opacity(0.8))
                    .frame(width: 2, height: 24)
                    .padding(.leading, 1)
            }
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func registrarButton(_ provider: RegistrarProvider) -> some View {
        Button {
            withAnimation(.spring(duration: 0.4)) {
                selectedRegistrarProvider = provider
            }
        } label: {
            HStack(spacing: 13) {
                RegistrarMark(provider: provider, size: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect \(provider.displayName)")
                        .font(.headline)
                    Text(provider.apiDescription)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                }
                .layoutPriority(1)

                Spacer()
                Image(systemName: "arrow.right")
                    .font(.system(size: 12, weight: .semibold))
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 64)
            .padding(.vertical, 8)
            .foregroundStyle(.white)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
            .liquidGlassSurface(cornerRadius: AppTheme.panelRadius)
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .fill(provider.accentColor.opacity(0.8))
                    .frame(width: 2, height: 24)
                    .padding(.leading, 1)
            }
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    // MARK: - Token Field (scrollable)

    private var tokenFieldView: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 0) {
                credentialHeader(provider: .vercel)

                if let error = authManager.error {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 12))
                        Text(error)
                            .font(.system(size: 12, weight: .medium))
                    }
                    .foregroundStyle(.red)
                    .padding(.top, 24)
                }

                VStack(spacing: 14) {
                    // Steps
                    VStack(alignment: .leading, spacing: 14) {
                        Text("How to get your token")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)

                        StepRow(number: 1, text: "Go to vercel.com/account/tokens")
                        StepRow(number: 2, text: "Tap \"Create Token\"")
                        StepRow(number: 3, text: "Name it anything (e.g. Verceltics)")
                        StepRow(number: 4, text: "Set scope to your account")
                        StepRow(number: 5, text: "Copy and paste below")
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .providerSurface(accent: AppTheme.textSecondary)

                    // Open Vercel
                    Button {
                        if let url = URL(string: "https://vercel.com/account/tokens") {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 11, weight: .semibold))
                            Text("Open Vercel Tokens Page")
                                .font(.system(size: 13, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(AppTheme.surfaceRaised)
                        .foregroundStyle(.white.opacity(0.75))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())

                    // Token input
                    VStack(alignment: .leading, spacing: 7) {
                        Text("Vercel token")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppTheme.textSecondary)
                        SecureField("Paste token", text: $tokenInput)
                            .textFieldStyle(.plain)
                            .font(.body.monospaced())
                            .padding(15)
                            .background(AppTheme.surfaceRaised)
                            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                                    .strokeBorder(isTokenFocused ? AppTheme.signal.opacity(0.7) : AppTheme.stroke, lineWidth: isTokenFocused ? 1.0 : 0.5)
                            )
                            .foregroundStyle(.white)
                            .focused($isTokenFocused)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .animation(.easeOut(duration: 0.18), value: isTokenFocused)
                    }

                    // Connect
                    Button {
                        Task {
                            await authManager.login(token: tokenInput.trimmingCharacters(in: .whitespacesAndNewlines))
                            if authManager.error == nil {
                                dismiss()
                            }
                        }
                    } label: {
                        HStack(spacing: 10) {
                            if authManager.isLoading {
                                ProgressView().tint(.black)
                            } else {
                                Text("Connect")
                                    .font(.system(size: 16, weight: .semibold))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 14, weight: .semibold))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(tokenInput.isEmpty ? AppTheme.surfaceRaised : AppTheme.signal)
                        .foregroundStyle(tokenInput.isEmpty ? AppTheme.textTertiary : .white)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(tokenInput.isEmpty || authManager.isLoading)
                    .id("connect-button")
                }
                .padding(.horizontal, 20)
                .padding(.top, 36)

                Spacer().frame(height: 40)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: isTokenFocused) { _, focused in
            if focused {
                // Wait for the keyboard frame to settle, then scroll the
                // Connect button into view above the keyboard.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    withAnimation(.easeOut(duration: 0.25)) {
                        proxy.scrollTo("connect-button", anchor: .bottom)
                    }
                }
            }
        }
        }
    }

    private var cloudflareCredentialsView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    credentialHeader(provider: .cloudflare)

                    if let error = authManager.error {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                            Text(error)
                                .font(.system(size: 12, weight: .medium))
                        }
                        .foregroundStyle(.red)
                        .padding(.top, 20)
                    }

                    VStack(spacing: 14) {
                        Picker("Authentication", selection: $cloudflareAuthenticationMode) {
                            ForEach(CloudflareAuthenticationMode.allCases) { mode in
                                Text(mode.displayName).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        VStack(alignment: .leading, spacing: 14) {
                            Text(
                                cloudflareAuthenticationMode == .globalAPIKey
                                    ? "Connect with Global API Key"
                                    : "Connect with scoped API token"
                            )
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.white)

                            StepRow(number: 1, text: "Open Cloudflare My Profile → API Tokens")
                            if cloudflareAuthenticationMode == .globalAPIKey {
                                StepRow(number: 2, text: "In API Keys, tap View beside Global API Key")
                                StepRow(number: 3, text: "Complete identity verification")
                                StepRow(number: 4, text: "Paste your login email and key below")
                            } else {
                                StepRow(number: 2, text: "Create a custom token with the product permissions you need")
                                StepRow(number: 3, text: "Include Account Read so the app can discover your accounts")
                                StepRow(number: 4, text: "Paste the token below")
                            }

                            HStack(alignment: .top, spacing: 9) {
                                Image(systemName: "lock.shield.fill")
                                    .foregroundStyle(Color(red: 0.96, green: 0.45, blue: 0.10))
                                Text(
                                    cloudflareAuthenticationMode == .globalAPIKey
                                        ? "Stored only in this iPhone’s Keychain. The Global API Key has the same Cloudflare access as your user, including write access."
                                        : "Stored only in this iPhone’s Keychain. The app can only use permissions and resources included in this token."
                                )
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.48))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .providerSurface(accent: CloudflareStyle.orange)

                        Button {
                            if let tokenURL = URL(string: "https://dash.cloudflare.com/profile/api-tokens") {
                                UIApplication.shared.open(tokenURL)
                            }
                        } label: {
                            Label("Open Cloudflare API Tokens", systemImage: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .foregroundStyle(.white.opacity(0.8))
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())

                        if cloudflareAuthenticationMode == .globalAPIKey {
                            cloudflareTextField(
                                "Cloudflare login email",
                                text: $cloudflareEmail,
                                field: .email,
                                secure: false
                            )
                            .keyboardType(.emailAddress)

                            cloudflareTextField(
                                "Paste Global API Key",
                                text: $cloudflareGlobalAPIKey,
                                field: .key,
                                secure: true
                            )
                        } else {
                            cloudflareTextField(
                                "Paste scoped API token",
                                text: $cloudflareAPIToken,
                                field: .key,
                                secure: true
                            )
                        }

                        Button {
                            Task {
                                if cloudflareAuthenticationMode == .globalAPIKey {
                                    await authManager.loginCloudflare(
                                        email: cloudflareEmail.trimmingCharacters(in: .whitespacesAndNewlines),
                                        globalAPIKey: cloudflareGlobalAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
                                    )
                                } else {
                                    await authManager.loginCloudflare(
                                        apiToken: cloudflareAPIToken.trimmingCharacters(in: .whitespacesAndNewlines)
                                    )
                                }
                                if authManager.error == nil { dismiss() }
                            }
                        } label: {
                            HStack(spacing: 10) {
                                if authManager.isLoading {
                                    ProgressView().tint(.white)
                                } else {
                                    Text("Connect Cloudflare")
                                        .font(.system(size: 16, weight: .semibold))
                                    Image(systemName: "arrow.right")
                                        .font(.system(size: 14, weight: .semibold))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(canConnectCloudflare ? CloudflareStyle.orange : AppTheme.surfaceRaised)
                            .foregroundStyle(canConnectCloudflare ? .white : AppTheme.textTertiary)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .disabled(!canConnectCloudflare || authManager.isLoading)
                        .id("cloudflare-connect")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 28)

                    Spacer().frame(height: 40)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: focusedCloudflareField) { _, field in
                if field != nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        withAnimation(.easeOut(duration: 0.25)) {
                            proxy.scrollTo("cloudflare-connect", anchor: .bottom)
                        }
                    }
                }
            }
        }
    }

    private var canConnectCloudflare: Bool {
        switch cloudflareAuthenticationMode {
        case .globalAPIKey:
            cloudflareEmail.contains("@") && !cloudflareGlobalAPIKey.isEmpty
        case .apiToken:
            !cloudflareAPIToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    @ViewBuilder
    private func cloudflareTextField(
        _ placeholder: String,
        text: Binding<String>,
        field: CloudflareField,
        secure: Bool
    ) -> some View {
        let content = Group {
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
            }
        }
        VStack(alignment: .leading, spacing: 7) {
            Text(placeholder)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
            content
                .textFieldStyle(.plain)
                .font(.body.monospaced())
                .padding(15)
                .background(AppTheme.surfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                        .strokeBorder(
                            focusedCloudflareField == field
                                ? CloudflareStyle.orange.opacity(0.7)
                                : AppTheme.stroke,
                            lineWidth: focusedCloudflareField == field ? 1 : 0.5
                        )
                )
                .foregroundStyle(.white)
                .focused($focusedCloudflareField, equals: field)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
    }

    private func credentialHeader(provider: AccountProvider) -> some View {
        VStack(spacing: 22) {
            HStack {
                Button {
                    authManager.error = nil
                    withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 42, height: 42)
                        .background(Color.white.opacity(0.07))
                        .clipShape(Circle())
                }
                .foregroundStyle(.white)
                Spacer()
            }

            ProviderMark(provider: provider, size: 34)
                .frame(width: 72, height: 72)
                .background(provider.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            VStack(spacing: 6) {
                Text("Connect \(provider.displayName)")
                    .font(.title2.weight(.semibold))
                Text(provider == .cloudflare ? "Manage your Cloudflare edge" : "Analytics for your Vercel projects")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
    }

}

private struct LiquidGlassSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content.nativeGlassSurface(cornerRadius: cornerRadius)
    }
}

private extension View {
    func liquidGlassSurface(cornerRadius: CGFloat) -> some View {
        modifier(
            LiquidGlassSurfaceModifier(
                cornerRadius: cornerRadius
            )
        )
    }
}

// MARK: - Step Row

struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text("\(number)")
                .font(.system(size: 11, weight: .semibold).monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(AppTheme.surfaceRaised)
                .clipShape(Circle())
                .overlay(Circle().strokeBorder(Color.white.opacity(0.06), lineWidth: 0.5))

            Text(text)
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
        }
    }
}

struct PressScaleButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.985 : 1)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .animation(reduceMotion ? nil : .snappy(duration: 0.16), value: configuration.isPressed)
    }
}
