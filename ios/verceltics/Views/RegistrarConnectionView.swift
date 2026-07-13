import SwiftUI

struct RegistrarConnectionView: View {
    @Environment(RegistrarStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var selectedProvider: RegistrarProvider?
    @State private var username = ""
    @State private var apiKey = ""
    @State private var apiSecret = ""
    @State private var clientIP = ""
    @State private var organization = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case username, apiKey, apiSecret, clientIP, organization }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
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
                            .font(.system(size: 38, weight: .black))
                            .foregroundStyle(Color(red: 0.30, green: 0.67, blue: 1.0))
                        Text("Connect a registrar")
                            .font(.system(size: 25, weight: .heavy))
                        Text("Domains stay separate from hosting accounts")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.42))
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
                                        .font(.system(size: 15, weight: .heavy))
                                    Text(provider.apiDescription)
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundStyle(.white.opacity(0.40))
                                        .lineLimit(1)
                                }
                                Spacer()
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 11, weight: .heavy))
                                    .foregroundStyle(.white.opacity(0.30))
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
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    VStack(spacing: 20) {
                        HStack {
                            Button {
                                store.error = nil
                                withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 15, weight: .heavy))
                                    .frame(width: 42, height: 42)
                                    .background(Color.white.opacity(0.07))
                                    .clipShape(Circle())
                            }
                            Spacer()
                        }
                        RegistrarMark(provider: provider, size: 82)
                        VStack(spacing: 6) {
                            Text("Connect \(provider.displayName)")
                                .font(.system(size: 25, weight: .heavy))
                            Text(provider.apiDescription)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.white.opacity(0.42))
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 18)

                    if let error = store.error {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.red)
                            .padding(.horizontal, 20)
                            .padding(.top, 18)
                    }

                    VStack(spacing: 14) {
                        securityCard(provider)

                        Button {
                            if let url = provider.credentialURL { UIApplication.shared.open(url) }
                        } label: {
                            Label("Open \(provider.displayName) API settings", systemImage: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .foregroundStyle(.white.opacity(0.82))
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())

                        fields(provider)

                        Button {
                            connect(provider)
                        } label: {
                            HStack(spacing: 9) {
                                if store.isConnecting { ProgressView().tint(.white) }
                                else {
                                    Text("Connect \(provider.displayName)").font(.system(size: 15, weight: .heavy))
                                    Image(systemName: "arrow.right").font(.system(size: 13, weight: .heavy))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(canConnect(provider) ? provider.accentColor.opacity(0.85) : Color.white.opacity(0.12))
                            .foregroundStyle(.white)
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
            .onChange(of: focusedField) { _, value in
                guard value != nil else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("registrar-connect", anchor: .bottom) }
                }
            }
        }
    }

    private func securityCard(_ provider: RegistrarProvider) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Device-only credentials", systemImage: "lock.shield.fill")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(provider.accentColor)
            Text(connectionNote(provider))
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white.opacity(0.48))
                .fixedSize(horizontal: false, vertical: true)
            Text("Credentials are stored in this iPhone’s Keychain and sent only to the registrar’s official API.")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.white.opacity(0.34))
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
        Group {
            if secure { SecureField(placeholder, text: text) }
            else { TextField(placeholder, text: text) }
        }
        .font(.system(size: 14, design: .monospaced))
        .textFieldStyle(.plain)
        .padding(15)
        .background(Color.white.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(focusedField == field ? 0.28 : 0.08), lineWidth: focusedField == field ? 1 : 0.5))
        .foregroundStyle(.white)
        .focused($focusedField, equals: field)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }

    private func connect(_ provider: RegistrarProvider) {
        Task {
            await store.connect(
                provider: provider,
                primaryCredential: apiKey,
                secondaryCredential: needsSecret(provider) ? apiSecret : nil,
                metadata: metadata(provider)
            )
            if store.error == nil { dismiss() }
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
        case .nameDotCom: "Use a CORE API username and token. Name.com currently does not support API access on accounts with two-step verification enabled."
        case .namecheap: "Enable API access and whitelist the same public IPv4 address you enter here. Namecheap signs every request with that address."
        case .goDaddy: "GoDaddy may require portfolio size or a paid Discount Domain Club plan before production Domains API access is enabled."
        case .gandi: "Create a scoped personal access token for the organization and domains you want available in the app."
        default: "Create a key with read access for domain lists and add write permissions only for operations you want to run."
        }
    }
}
