import SwiftUI

struct RegistrarConnectionView: View {
    @Environment(RegistrarStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var selectedProvider: RegistrarProvider?
    private let onBack: (() -> Void)?
    private let onConnected: (() -> Void)?
    @State private var username = ""
    @State private var apiKey = ""
    @State private var apiSecret = ""
    @State private var clientIP = ""
    @State private var organization = ""
    @State private var detectedPublicIPv4: String?
    @State private var isDetectingPublicIPv4 = false
    @State private var publicIPv4Error: String?
    @State private var copiedPublicIPv4 = false
    @State private var publicIPv4RequestID: UUID?
    @State private var lastPrefilledNamecheapIPv4: String?
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
                            resetPublicIPv4LookupState()
                            if reduceMotion { selectedProvider = provider }
                            else { withAnimation(.spring(duration: 0.35)) { selectedProvider = provider } }
                        } label: {
                            HStack(spacing: 13) {
                                RegistrarMark(provider: provider, size: 42)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(provider.displayName)
                                        .font(.headline)
                                    Text(provider.apiDescription)
                                        .font(.footnote)
                                        .foregroundStyle(AppTheme.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                Spacer()
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(AppTheme.textTertiary)
                            }
                            .foregroundStyle(AppTheme.textPrimary)
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
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }.foregroundStyle(AppTheme.textPrimary)
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
                                resetPublicIPv4LookupState()
                                if let onBack {
                                    onBack()
                                } else if reduceMotion {
                                    selectedProvider = nil
                                } else {
                                    withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                                }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 15, weight: .semibold))
                                    .frame(width: 44, height: 44)
                                    .background(AppTheme.surfaceRaised)
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

                        if [.namecheap, .nameDotCom].contains(provider) {
                            publicIPv4Helper(provider)
                        }

                        fields(provider)

                        Button {
                            connect(provider)
                        } label: {
                            HStack(spacing: 9) {
                                if store.isConnecting { ProgressView().tint(.white) }
                                else {
                                    Text("Connect \(provider.displayName)").font(.headline)
                                    Image(systemName: "arrow.right").font(.callout.weight(.semibold))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 54)
                            .padding(.vertical, 4)
                            .background(canConnect(provider) ? AppTheme.signal : AppTheme.surfaceRaised)
                            .foregroundStyle(canConnect(provider) ? .white : AppTheme.textTertiary)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .disabled(!canConnect(provider) || store.isConnecting)
                        .accessibilityLabel(store.isConnecting ? "Connecting \(provider.displayName)" : "Connect \(provider.displayName)")
                        .id("registrar-connect")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 26)
                    Spacer().frame(height: 40)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .task(id: provider) {
                guard provider == .namecheap else { return }
                await detectPublicIPv4(for: provider, force: true)
            }
            .onDisappear {
                publicIPv4RequestID = nil
                isDetectingPublicIPv4 = false
            }
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

    private func publicIPv4Helper(_ provider: RegistrarProvider) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "network")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(provider.accentColor)
                    .frame(width: 32, height: 32)
                    .background(provider.accentColor.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("PUBLIC NETWORK ADDRESS")
                        .font(.caption2.weight(.bold))
                        .tracking(1.2)
                        .foregroundStyle(AppTheme.textTertiary)
                    Text(provider == .namecheap ? "Required by Namecheap" : "Optional Name.com allowlist")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                }
                Spacer()
                if isDetectingPublicIPv4 {
                    ProgressView()
                        .controlSize(.small)
                        .tint(provider.accentColor)
                }
            }

            if let address = detectedPublicIPv4 {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("This network")
                            .font(.caption)
                            .foregroundStyle(AppTheme.textTertiary)
                        Text(address)
                            .font(.title3.monospaced().weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                            .textSelection(.enabled)
                    }
                    Spacer()
                    Button {
                        copyPublicIPv4(address)
                    } label: {
                        Image(systemName: copiedPublicIPv4 ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(width: 38, height: 38)
                            .background(AppTheme.surfaceRaised)
                            .clipShape(Circle())
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .accessibilityLabel(copiedPublicIPv4 ? "Public IPv4 copied" : "Copy public IPv4")

                    Button {
                        Task { await detectPublicIPv4(for: provider, force: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(width: 38, height: 38)
                            .background(AppTheme.surfaceRaised)
                            .clipShape(Circle())
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(isDetectingPublicIPv4)
                    .accessibilityLabel("Detect public IPv4 again")
                }
            } else if isDetectingPublicIPv4 {
                Text("Detecting the public IPv4 used by this network…")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            } else {
                Button {
                    Task { await detectPublicIPv4(for: provider, force: true) }
                } label: {
                    Label(
                        publicIPv4Error == nil ? "Detect IP to whitelist" : "Try detection again",
                        systemImage: "scope"
                    )
                    .font(.footnote.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 42)
                    .foregroundStyle(provider.accentColor)
                    .background(provider.accentColor.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PressScaleButtonStyle())
            }

            if let publicIPv4Error {
                Label(publicIPv4Error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider().overlay(AppTheme.stroke)

            Text(publicIPv4Explanation(provider))
                .font(.caption)
                .foregroundStyle(AppTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Label("Wi-Fi, cellular, or VPN changes can change this address.", systemImage: "arrow.triangle.2.circlepath")
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
            if !clientIP.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               PublicIPv4Lookup.normalizedPublicIPv4(clientIP) == nil {
                Label("Enter a public IPv4 address that is also allowed in Namecheap.", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.warning)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
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
            .foregroundStyle(AppTheme.textPrimary)
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
        case .nameDotCom: ["username": username.trimmingCharacters(in: .whitespacesAndNewlines)]
        case .namecheap: [
            "username": username.trimmingCharacters(in: .whitespacesAndNewlines),
            "clientIP": PublicIPv4Lookup.normalizedPublicIPv4(clientIP) ?? clientIP.trimmingCharacters(in: .whitespacesAndNewlines)
        ]
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
        case .nameDotCom: return !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .namecheap:
            return !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && PublicIPv4Lookup.normalizedPublicIPv4(clientIP) != nil
        case .porkbun, .spaceship, .goDaddy: return !apiSecret.isEmpty
        default: return true
        }
    }

    private func connectionNote(_ provider: RegistrarProvider) -> String {
        switch provider {
        case .nameDotCom: "Use a CORE API username and production token. If two-step verification is enabled, also turn Name.com API Access ON under Security Settings; that toggle authorizes API calls without a 2FA code."
        case .namecheap: "Enable API access and whitelist the same public IPv4 address you enter here. Namecheap requires that address on every API call."
        case .goDaddy: "GoDaddy may require portfolio size or a paid Discount Domain Club plan before production Domains API access is enabled."
        case .gandi: "Create a scoped personal access token for the organization and domains you want available in the app."
        default: "Create a key with read access for domain lists and add write permissions only for operations you want to run."
        }
    }

    private func publicIPv4Explanation(_ provider: RegistrarProvider) -> String {
        switch provider {
        case .namecheap:
            "Add this exact address to Namecheap’s API whitelist. Verceltics prefills it below because Namecheap also requires ClientIp on each request."
        case .nameDotCom:
            "Only copy this into Name.com if you enable its optional IP allowlist. It is not saved or sent as a Name.com API credential."
        default:
            ""
        }
    }

    private func detectPublicIPv4(for provider: RegistrarProvider, force: Bool = false) async {
        guard [.namecheap, .nameDotCom].contains(provider) else { return }
        if !force, detectedPublicIPv4 != nil { return }

        let requestID = UUID()
        publicIPv4RequestID = requestID
        isDetectingPublicIPv4 = true
        publicIPv4Error = nil
        detectedPublicIPv4 = nil
        defer {
            if publicIPv4RequestID == requestID {
                isDetectingPublicIPv4 = false
            }
        }

        do {
            let address = try await PublicIPv4Lookup.resolve()
            guard !Task.isCancelled,
                  publicIPv4RequestID == requestID,
                  selectedProvider == provider else { return }
            detectedPublicIPv4 = address
            if provider == .namecheap {
                prefillNamecheapAddress(address)
            }
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled,
                  publicIPv4RequestID == requestID,
                  selectedProvider == provider else { return }
            publicIPv4Error = "Couldn’t detect this network. You can still enter the address manually."
        }
    }

    private func prefillNamecheapAddress(_ address: String) {
        let currentAddress = clientIP.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentAddress.isEmpty || currentAddress == lastPrefilledNamecheapIPv4 {
            clientIP = address
            lastPrefilledNamecheapIPv4 = address
        }
    }

    private func resetPublicIPv4LookupState() {
        publicIPv4RequestID = nil
        detectedPublicIPv4 = nil
        publicIPv4Error = nil
        copiedPublicIPv4 = false
        isDetectingPublicIPv4 = false
    }

    private func copyPublicIPv4(_ address: String) {
        UIPasteboard.general.string = address
        copiedPublicIPv4 = true
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            copiedPublicIPv4 = false
        }
    }
}
