import SwiftUI

struct RegistrarConnectionView: View {
    @Environment(RegistrarStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var selectedProvider: RegistrarProvider?
    private let onBack: (() -> Void)?
    private let onConnected: (() -> Void)?
    @State private var username = ""
    @State private var apiKey = ""
    @State private var apiSecret = ""
    @State private var clientIP = ""
    @State private var organization = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case username, apiKey, apiSecret, clientIP, organization }

    init(
        initialProvider: RegistrarProvider? = nil,
        onBack: (() -> Void)? = nil,
        onConnected: (() -> Void)? = nil
    ) {
        _selectedProvider = State(initialValue: initialProvider)
        self.onBack = onBack
        self.onConnected = onConnected
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            if let provider = selectedProvider {
                credentialView(provider)
            } else {
                providerList
            }
        }
    }

    private var providerList: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 12) {
                    VStack(spacing: 8) {
                        Image(systemName: "globe.americas.fill")
                            .font(.system(size: 38, weight: .bold))
                            .foregroundStyle(AppTheme.signal)
                        Text("Connect a registrar")
                            .font(.title2.weight(.semibold))
                        Text("Domains stay separate from hosting accounts")
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                    .padding(.vertical, 28)

                    ForEach(RegistrarProvider.allCases) { provider in
                        Button {
                            store.error = nil
                            withAnimation(.spring(duration: 0.35)) { selectedProvider = provider }
                        } label: {
                            HStack(spacing: 13) {
                                RegistrarMark(provider: provider, size: 42)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(provider.displayName)
                                        .font(.headline)
                                    Text(provider.apiDescription)
                                        .font(.footnote)
                                        .foregroundStyle(AppTheme.textSecondary)
                                        .lineLimit(2)
                                }
                                Spacer()
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(AppTheme.textTertiary)
                            }
                            .foregroundStyle(.white)
                            .padding(14)
                            .providerPanel(accent: provider.accentColor)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                    Spacer().frame(height: 30)
                }
                .padding(.horizontal, 16)
            }
            .navigationTitle("Registrars")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
    }

    private func credentialView(_ provider: RegistrarProvider) -> some View {
        ScrollViewReader { _ in
            ScrollView {
                VStack(spacing: 0) {
                    VStack(spacing: 20) {
                        HStack {
                            Button {
                                store.error = nil
                                if let onBack {
                                    onBack()
                                } else {
                                    withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                                }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 15, weight: .semibold))
                                    .frame(width: 44, height: 44)
                                    .background(Color.white.opacity(0.07))
                                    .clipShape(Circle())
                            }
                            Spacer()
                        }
                        RegistrarMark(provider: provider, size: 72)
                        VStack(spacing: 6) {
                            Text("Connect \(provider.displayName)")
                                .font(.title2.weight(.semibold))
                            Text(provider.apiDescription)
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 18)

                    if let error = store.error {
                        AppFeedbackBanner(
                            title: "Connection failed",
                            message: error,
                            icon: "exclamationmark.circle.fill",
                            tint: AppTheme.danger
                        )
                        .padding(.horizontal, 20)
                        .padding(.top, 18)
                    }

                    VStack(spacing: 14) {
                        securityCard(provider)

                        Button {
                            if let url = provider.credentialURL { UIApplication.shared.open(url) }
                        } label: {
                            Label("Open \(provider.displayName) API settings", systemImage: "arrow.up.right")
                                .font(.footnote.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .frame(minHeight: 46)
                                .foregroundStyle(AppTheme.textPrimary)
                                .appSurface(raised: true)
                        }
                        .buttonStyle(PressScaleButtonStyle())

                        fields(provider)

                        Button {
                            connect(provider)
                        } label: {
                            HStack(spacing: 9) {
                                if store.isConnecting { ProgressView().tint(.white) }
                                else {
                                    Text("Connect \(provider.displayName)").font(.system(size: 15, weight: .semibold))
                                    Image(systemName: "arrow.right").font(.system(size: 13, weight: .semibold))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(canConnect(provider) ? AppTheme.signal : AppTheme.surfaceRaised)
                            .foregroundStyle(canConnect(provider) ? .white : AppTheme.textTertiary)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .disabled(!canConnect(provider) || store.isConnecting)
                        .id("registrar-connect")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 26)
                    Spacer().frame(height: 40)
                }
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private func securityCard(_ provider: RegistrarProvider) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Device-only credentials", systemImage: "lock.shield.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(provider.accentColor)
            Text(connectionNote(provider))
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Text("Credentials are stored in this device’s Keychain and sent only to the registrar’s official API.")
                .font(.caption)
                .foregroundStyle(AppTheme.textTertiary)
        }
        .padding(17)
        .frame(maxWidth: .infinity, alignment: .leading)
        .providerPanel(accent: provider.accentColor)
    }

    @ViewBuilder
    private func fields(_ provider: RegistrarProvider) -> some View {
        switch provider {
        case .nameDotCom:
            field("API username", text: $username, field: .username)
            field("API token", text: $apiKey, field: .apiKey, secure: true)
        case .namecheap:
            field("Namecheap username / API user", text: $username, field: .username)
            field("API key", text: $apiKey, field: .apiKey, secure: true)
            field("Whitelisted public IPv4 address", text: $clientIP, field: .clientIP)
                .keyboardType(.numbersAndPunctuation)
        case .porkbun, .spaceship, .goDaddy:
            field("API key", text: $apiKey, field: .apiKey, secure: true)
            field("API secret", text: $apiSecret, field: .apiSecret, secure: true)
        case .dynadot, .nameSilo:
            field("API key", text: $apiKey, field: .apiKey, secure: true)
        case .gandi:
            field("Personal access token", text: $apiKey, field: .apiKey, secure: true)
            field("Organization label (optional)", text: $organization, field: .organization)
        }
    }

    private func field(_ placeholder: String, text: Binding<String>, field: Field, secure: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(placeholder)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
            Group {
                if secure { SecureField("Enter value", text: text) }
                else { TextField("Enter value", text: text) }
            }
            .font(.body.monospaced())
            .textFieldStyle(.plain)
            .padding(15)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous).strokeBorder(focusedField == field ? providerFocusColor : AppTheme.stroke, lineWidth: focusedField == field ? 1 : 0.5))
            .foregroundStyle(.white)
            .focused($focusedField, equals: field)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
    }

    private var providerFocusColor: Color {
        selectedProvider?.accentColor.opacity(0.72) ?? AppTheme.signal
    }

    private func connect(_ provider: RegistrarProvider) {
        Task {
            await store.connect(
                provider: provider,
                primaryCredential: apiKey,
                secondaryCredential: needsSecret(provider) ? apiSecret : nil,
                metadata: metadata(provider)
            )
            if store.error == nil {
                if let onConnected { onConnected() }
                else { dismiss() }
            }
        }
    }

    private func metadata(_ provider: RegistrarProvider) -> [String: String] {
        switch provider {
        case .nameDotCom: ["username": username]
        case .namecheap: ["username": username, "clientIP": clientIP]
        case .gandi: ["organization": organization]
        default: [:]
        }
    }

    private func needsSecret(_ provider: RegistrarProvider) -> Bool {
        [.porkbun, .spaceship, .goDaddy].contains(provider)
    }

    private func canConnect(_ provider: RegistrarProvider) -> Bool {
        guard !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch provider {
        case .nameDotCom: return !username.isEmpty
        case .namecheap: return !username.isEmpty && !clientIP.isEmpty
        case .porkbun, .spaceship, .goDaddy: return !apiSecret.isEmpty
        default: return true
        }
    }

    private func connectionNote(_ provider: RegistrarProvider) -> String {
        switch provider {
        case .nameDotCom: "Use a CORE API username and production token. If two-step verification is enabled, also turn Name.com API Access ON under Security Settings; that toggle authorizes API calls without a 2FA code."
        case .namecheap: "Enable API access and whitelist the same public IPv4 address you enter here. Namecheap signs every request with that address."
        case .goDaddy: "GoDaddy may require portfolio size or a paid Discount Domain Club plan before production Domains API access is enabled."
        case .gandi: "Create a scoped personal access token for the organization and domains you want available in the app."
        default: "Create a key with read access for domain lists and add write permissions only for operations you want to run."
        }
    }
}
