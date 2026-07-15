import SwiftUI

enum ConnectionCategory: String, CaseIterable, Identifiable {
    case hosting
    case registrars
    case sites

    var id: Self { self }

    var title: String {
        switch self {
        case .hosting: "Hosting"
        case .registrars: "Registrars"
        case .sites: "Sites"
        }
    }

    var systemImage: String {
        switch self {
        case .hosting: "server.rack"
        case .registrars: "globe.americas.fill"
        case .sites: "chart.xyaxis.line"
        }
    }

    var sectionTitle: String {
        switch self {
        case .hosting: "CONNECT A HOSTING PLATFORM"
        case .registrars: "CONNECT A REGISTRAR"
        case .sites: "CONNECT A SITE SERVICE"
        }
    }
}

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @State private var connectionCategory: ConnectionCategory
    @State private var tokenInput = ""
    @State private var selectedProvider: AccountProvider?
    @State private var selectedRegistrarProvider: RegistrarProvider?
    @State private var selectedSiteProvider: SiteIntegrationProvider?
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
                if let siteProvider = selectedSiteProvider {
                    SiteServiceConnectionView(
                        initialProvider: siteProvider,
                        onBack: {
                            updateSelection { selectedSiteProvider = nil }
                        },
                        onConnected: {
                            selectedSiteProvider = nil
                            dismiss()
                        }
                    )
                } else if let registrar = selectedRegistrarProvider {
                    RegistrarConnectionView(
                        initialProvider: registrar,
                        onBack: {
                            updateSelection { selectedRegistrarProvider = nil }
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
                            updateSelection { selectedProvider = nil }
                        }
                    case nil:
                        welcomeView
                    }
                }
            }
            .appContentWidth(contentMaxWidth, horizontalSizeClass: hSize)
        }
    }

    private var contentMaxWidth: CGFloat {
        if selectedProvider == nil, selectedRegistrarProvider == nil, selectedSiteProvider == nil {
            return AppLayout.catalogMaxWidth
        }
        return AppLayout.formMaxWidth
    }

    private var providerColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: hSize,
            regularMinimum: 320,
            regularMaximum: 480,
            spacing: 14
        )
    }

    // MARK: - Platform selection

    private var welcomeView: some View {
        ScrollView {
            VStack(spacing: 0) {
                categorySelector
                    .padding(.horizontal, 20)
                    .padding(.top, 18)
                    .frame(maxWidth: AppLayout.formMaxWidth)
                    .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 12) {
                    Text(connectionCategory.sectionTitle)
                        .font(.caption2.weight(.semibold))
                        .tracking(1.5)
                        .foregroundStyle(AppTheme.textSecondary)
                        .padding(.horizontal, 4)

                    switch connectionCategory {
                    case .hosting:
                        LazyVGrid(columns: providerColumns, spacing: 14) {
                            ForEach(AccountProvider.allCases) { provider in
                                providerButton(provider)
                            }
                        }
                    case .registrars:
                        LazyVGrid(columns: providerColumns, spacing: 14) {
                            ForEach(RegistrarProvider.allCases) { provider in
                                registrarButton(provider)
                            }
                        }
                    case .sites:
                        LazyVGrid(columns: providerColumns, spacing: 14) {
                            ForEach(SiteIntegrationProvider.allCases) { provider in
                                siteProviderButton(provider)
                            }
                        }
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: hSize))
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
        .frame(minHeight: 48)
        // UISegmentedControl does not grow gracefully through the full
        // accessibility range. Keep this compact navigation control legible
        // while allowing the actual catalog content to scale without a cap.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityLabel("Connection type")
    }

    private func providerButton(_ provider: AccountProvider) -> some View {
        let accent = provider.accentColor
        return Button {
            authManager.error = nil
            updateSelection { selectedProvider = provider }
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
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
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
            .foregroundStyle(AppTheme.textPrimary)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
            .liquidGlassSurface(cornerRadius: AppTheme.panelRadius)
        }
        .buttonStyle(PressScaleButtonStyle())
        .hoverEffect(.highlight)
    }

    private func siteProviderButton(_ provider: SiteIntegrationProvider) -> some View {
        Button {
            updateSelection { selectedSiteProvider = provider }
        } label: {
            HStack(spacing: 13) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(provider.accentColor.opacity(0.14))
                    SiteProviderMark(provider: provider, size: 20)
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect \(provider.displayName)")
                        .font(.headline)
                    Text(provider.connectionSubtitle)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
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
            .foregroundStyle(AppTheme.textPrimary)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
            .liquidGlassSurface(cornerRadius: AppTheme.panelRadius)
        }
        .buttonStyle(PressScaleButtonStyle())
        .hoverEffect(.highlight)
    }

    private func registrarButton(_ provider: RegistrarProvider) -> some View {
        Button {
            updateSelection { selectedRegistrarProvider = provider }
        } label: {
            HStack(spacing: 13) {
                RegistrarMark(provider: provider, size: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect \(provider.displayName)")
                        .font(.headline)
                    Text(provider.apiDescription)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
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
            .foregroundStyle(AppTheme.textPrimary)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous))
            .liquidGlassSurface(cornerRadius: AppTheme.panelRadius)
        }
        .buttonStyle(PressScaleButtonStyle())
        .hoverEffect(.highlight)
    }

    // MARK: - Token Field (scrollable)

    private var tokenFieldView: some View {
        ScrollViewReader { _ in
        ScrollView {
            VStack(spacing: 0) {
                credentialHeader(provider: .vercel)

                if let error = authManager.error {
                    AppFeedbackBanner(title: "Vercel couldn’t connect", message: error, tint: AppTheme.danger)
                    .padding(.horizontal, 20)
                    .padding(.top, 24)
                }

                VStack(spacing: 14) {
                    // Steps
                    VStack(alignment: .leading, spacing: 14) {
                        Text("How to get your token")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)

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
                                .font(.caption.weight(.semibold))
                            Text("Open Vercel Tokens Page")
                                .font(.subheadline.weight(.semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 46)
                        .background(AppTheme.surfaceRaised)
                        .foregroundStyle(AppTheme.textPrimary)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
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
                            .foregroundStyle(AppTheme.textPrimary)
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
                                ProgressView().tint(.white)
                            } else {
                                Text("Connect")
                                    .font(.headline)
                                Image(systemName: "arrow.right")
                                    .font(.subheadline.weight(.semibold))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 54)
                        .background(tokenInput.isEmpty ? AppTheme.surfaceRaised : AppTheme.signal)
                        .foregroundStyle(tokenInput.isEmpty ? AppTheme.textTertiary : .white)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
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
        }
    }

    private var cloudflareCredentialsView: some View {
        ScrollViewReader { _ in
            ScrollView {
                VStack(spacing: 0) {
                    credentialHeader(provider: .cloudflare)

                    if let error = authManager.error {
                        AppFeedbackBanner(title: "Cloudflare couldn’t connect", message: error, tint: AppTheme.danger)
                        .padding(.horizontal, 20)
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
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppTheme.textPrimary)

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
                                        ? "Stored only in this device’s Keychain. The Global API Key has the same Cloudflare access as your user, including write access."
                                        : "Stored only in this device’s Keychain. The app can only use permissions and resources included in this token."
                                )
                                    .font(.footnote)
                                    .foregroundStyle(AppTheme.textSecondary)
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
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .frame(minHeight: 46)
                                .foregroundStyle(AppTheme.textPrimary)
                                .background(AppTheme.surfaceRaised)
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
                                        .font(.headline)
                                    Image(systemName: "arrow.right")
                                        .font(.subheadline.weight(.semibold))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 54)
                            .background(canConnectCloudflare ? AppTheme.signal : AppTheme.surfaceRaised)
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
                .foregroundStyle(AppTheme.textPrimary)
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
                    updateSelection { selectedProvider = nil }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .background(AppTheme.surfaceRaised)
                        .clipShape(Circle())
                }
                .foregroundStyle(AppTheme.textPrimary)
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

    private func updateSelection(_ changes: () -> Void) {
        if reduceMotion {
            changes()
        } else {
            withAnimation(.snappy(duration: 0.32)) { changes() }
        }
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
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .frame(width: 22, height: 22)
                .background(AppTheme.surfaceRaised)
                .clipShape(Circle())
                .overlay(Circle().strokeBorder(AppTheme.stroke, lineWidth: 0.5))

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
