import SwiftUI

struct SiteServiceConnectionView: View {
    @Environment(SiteStore.self) private var store

    let initialProvider: SiteIntegrationProvider
    let onBack: () -> Void
    let onConnected: () -> Void

    @State private var credential = ""
    @State private var siteURL = ""
    @State private var projectName = ""
    @State private var siteID = ""
    @State private var umamiBaseURL = "https://api.umami.is/v1"
    @State private var umamiAuthMode = "cloud"
    @State private var connectionTask: Task<Void, Never>?
    @State private var isVisible = false
    @FocusState private var focusedField: Field?

    private var googleOAuthIsConfigured: Bool {
        GoogleOAuthService.shared.isConfigured
    }

    private enum Field: Hashable {
        case credential
        case siteURL
        case projectName
        case siteID
        case baseURL
    }

    private var provider: SiteIntegrationProvider { initialProvider }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header

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
                    if provider.usesOAuth {
                        oauthPreparation
                    } else {
                        instructions
                        credentialLink
                        fields
                        connectButton
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 26)

                Spacer().frame(height: 42)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            isVisible = true
            store.clearTransientError()
        }
        .onDisappear { cancelConnectionTask() }
    }

    private var header: some View {
        VStack(spacing: 20) {
            HStack {
                Button(action: cancelConnectionAndGoBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .background(AppTheme.surfaceRaised)
                        .clipShape(Circle())
                }
                .foregroundStyle(AppTheme.textPrimary)
                Spacer()
            }

            SiteProviderMark(provider: provider, size: 36)
                .frame(width: 72, height: 72)
                .background(provider.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            VStack(spacing: 6) {
                Text("Connect \(provider.displayName)")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text(provider.connectionSubtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
    }

    private var oauthPreparation: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 12) {
                AppIconTile(icon: "key.horizontal.fill", tint: provider.accentColor, size: 40)
                VStack(alignment: .leading, spacing: 4) {
                    Text("OAuth access is prepared")
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                    Text(
                        googleOAuthIsConfigured
                            ? "Sign in with Google to grant read-only access. Tokens refresh securely and remain in this device’s Keychain."
                            : "PKCE sign-in, token refresh, identity matching, property discovery, and reporting are implemented. Connecting stays paused until your Google iOS OAuth client configuration is added to the app."
                    )
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("READ-ONLY ACCESS")
                    .font(.caption2.weight(.semibold))
                    .tracking(1.1)
                    .foregroundStyle(AppTheme.textSecondary)

                ForEach(oauthCapabilities, id: \.self) { capability in
                    Label(capability, systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textPrimary)
                        .labelStyle(SiteCapabilityLabelStyle(tint: provider.accentColor))
                }
            }

            Button {
                if let url = URL(string: "https://console.cloud.google.com/apis/credentials") {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label("Open Google Cloud credentials", systemImage: "arrow.up.right")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 46)
                    .foregroundStyle(AppTheme.textPrimary)
                    .appSurface(raised: true)
            }
            .buttonStyle(PressScaleButtonStyle())

            if googleOAuthIsConfigured {
                Button(action: beginGoogleConnection) {
                    HStack(spacing: 10) {
                        if store.isConnecting {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: "person.crop.circle.badge.checkmark")
                            Text("Continue with Google")
                                .font(.subheadline.weight(.semibold))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .foregroundStyle(.white)
                    .background(provider.accentColor)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                }
                .buttonStyle(PressScaleButtonStyle())
                .disabled(store.isConnecting)
            } else {
                Label("Waiting for Google OAuth configuration", systemImage: "pause.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .foregroundStyle(AppTheme.textSecondary)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            }
        }
        .padding(18)
        .providerSurface(accent: provider.accentColor)
    }

    private var oauthCapabilities: [String] {
        switch provider {
        case .googleSearchConsole:
            ["Verified properties", "Search performance", "Indexing and sitemaps"]
        case .googleAnalytics:
            ["Property discovery", "Traffic and engagement", "Realtime reporting"]
        default:
            []
        }
    }

    private var instructions: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connect securely")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
            StepRow(number: 1, text: instructionOne)
            StepRow(number: 2, text: instructionTwo)
            StepRow(number: 3, text: "Paste the requested details below and connect")

            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "lock.shield.fill")
                    .foregroundStyle(provider.accentColor)
                Text("Credentials stay in this device’s Keychain and are sent only to the service’s official API endpoint.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .providerSurface(accent: provider.accentColor)
    }

    private var credentialLink: some View {
        Button {
            if let credentialPageURL { UIApplication.shared.open(credentialPageURL) }
        } label: {
            Label("Open \(provider.displayName) credentials", systemImage: "arrow.up.right")
                .font(.footnote.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: 46)
                .foregroundStyle(AppTheme.textPrimary)
                .appSurface(raised: true)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    @ViewBuilder
    private var fields: some View {
        switch provider {
        case .pageSpeed:
            siteField("Site URL", placeholder: "https://example.com", text: $siteURL, field: .siteURL)
                .keyboardType(.URL)
            siteField("Google Cloud API key", placeholder: "Paste API key", text: $credential, field: .credential, secure: true)

        case .bingWebmaster:
            siteField("Bing Webmaster API key", placeholder: "Paste API key", text: $credential, field: .credential, secure: true)

        case .clarity:
            siteField("Project name", placeholder: "My website", text: $projectName, field: .projectName)
            siteField("Site URL (optional)", placeholder: "https://example.com", text: $siteURL, field: .siteURL)
                .keyboardType(.URL)
            siteField("Clarity export token", placeholder: "Paste bearer token", text: $credential, field: .credential, secure: true)

        case .plausible:
            siteField("Site ID", placeholder: "example.com", text: $siteID, field: .siteID)
                .keyboardType(.URL)
            siteField("Plausible Stats API key", placeholder: "Paste API key", text: $credential, field: .credential, secure: true)

        case .umami:
            Picker("Umami hosting", selection: $umamiAuthMode) {
                Text("Umami Cloud").tag("cloud")
                Text("Self-hosted").tag("selfHosted")
            }
            .pickerStyle(.segmented)

            if umamiAuthMode == "selfHosted" {
                siteField(
                    "Self-hosted base URL",
                    placeholder: "https://analytics.example.com",
                    text: $umamiBaseURL,
                    field: .baseURL
                )
                .keyboardType(.URL)
            }
            siteField(
                umamiAuthMode == "cloud" ? "Umami Cloud API key" : "Self-hosted bearer token",
                placeholder: "Paste credential",
                text: $credential,
                field: .credential,
                secure: true
            )

        case .uptimeRobot:
            siteField("UptimeRobot read-only API key", placeholder: "Paste API key", text: $credential, field: .credential, secure: true)

        case .betterStack:
            siteField("Better Stack API token", placeholder: "Paste bearer token", text: $credential, field: .credential, secure: true)

        case .googleSearchConsole, .googleAnalytics:
            EmptyView()
        }
    }

    private func siteField(
        _ title: String,
        placeholder: String,
        text: Binding<String>,
        field: Field,
        secure: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)

            Group {
                if secure {
                    SecureField(placeholder, text: text)
                } else {
                    TextField(placeholder, text: text)
                }
            }
            .textFieldStyle(.plain)
            .font(.body.monospaced())
            .padding(15)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                    .strokeBorder(
                        focusedField == field ? provider.accentColor.opacity(0.7) : AppTheme.stroke,
                        lineWidth: focusedField == field ? 1 : 0.5
                    )
            }
            .foregroundStyle(AppTheme.textPrimary)
            .focused($focusedField, equals: field)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
        }
    }

    private var connectButton: some View {
        Button(action: beginAPIConnection) {
            HStack(spacing: 10) {
                if store.isConnecting {
                    ProgressView().tint(.white)
                } else {
                    Text("Connect \(provider.displayName)")
                        .font(.system(size: 16, weight: .semibold))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 14, weight: .semibold))
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(canConnect ? AppTheme.signal : AppTheme.surfaceRaised)
            .foregroundStyle(canConnect ? .white : AppTheme.textTertiary)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(!canConnect || store.isConnecting)
        .onChange(of: umamiAuthMode) { _, mode in
            if mode == "selfHosted", umamiBaseURL.contains("api.umami.is") {
                umamiBaseURL = ""
            } else if mode == "cloud" {
                umamiBaseURL = "https://api.umami.is/v1"
            }
        }
    }

    private func beginGoogleConnection() {
        connectionTask?.cancel()
        connectionTask = Task { @MainActor in
            let connected = await store.connectGoogle(provider: provider)
            guard connected, !Task.isCancelled, isVisible else { return }
            onConnected()
        }
    }

    private func beginAPIConnection() {
        connectionTask?.cancel()
        connectionTask = Task { @MainActor in
            let connected = await store.connect(
                provider: provider,
                credential: credential,
                metadata: metadata
            )
            guard connected, !Task.isCancelled, isVisible else { return }
            onConnected()
        }
    }

    private func cancelConnectionAndGoBack() {
        cancelConnectionTask()
        onBack()
    }

    private func cancelConnectionTask() {
        isVisible = false
        connectionTask?.cancel()
        connectionTask = nil
    }

    private var metadata: [String: String] {
        switch provider {
        case .pageSpeed:
            ["siteURL": siteURL]
        case .clarity:
            ["projectName": projectName, "siteURL": siteURL]
        case .plausible:
            ["siteID": siteID]
        case .umami:
            ["baseURL": umamiBaseURL, "authMode": umamiAuthMode]
        default:
            [:]
        }
    }

    private var canConnect: Bool {
        guard !credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch provider {
        case .pageSpeed:
            return validHTTPSURL(siteURL)
        case .clarity:
            return !projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && (siteURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || validHTTPSURL(siteURL))
        case .plausible:
            return !siteID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .umami:
            return validHTTPSURL(umamiBaseURL)
        case .googleSearchConsole, .googleAnalytics:
            return false
        case .bingWebmaster, .uptimeRobot, .betterStack:
            return true
        }
    }

    private func validHTTPSURL(_ value: String) -> Bool {
        guard let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return url.scheme?.lowercased() == "https" && url.host != nil
    }

    private var instructionOne: String {
        switch provider {
        case .pageSpeed: "Create an API key with PageSpeed Insights and Chrome UX Report enabled"
        case .bingWebmaster: "Generate an API key in Bing Webmaster Tools → API Access"
        case .clarity: "Generate a project token in Settings → Data Export"
        case .plausible: "Create a Stats API key in your Plausible account settings"
        case .umami: "Create a Cloud API key or a token for your self-hosted instance"
        case .uptimeRobot: "Create a read-only key in Integrations & API"
        case .betterStack: "Create a Uptime API token in Better Stack"
        case .googleSearchConsole, .googleAnalytics: "Configure the Google OAuth client"
        }
    }

    private var instructionTwo: String {
        switch provider {
        case .pageSpeed: "Enter a public HTTPS URL to audit on mobile and desktop"
        case .bingWebmaster: "The key can read every verified site in that Bing account"
        case .clarity: "Clarity export tokens belong to one project and expose the previous three days"
        case .plausible: "Enter the exact Site ID used by the Plausible dashboard"
        case .umami: "Choose Cloud or provide the HTTPS base URL of your self-hosted instance"
        case .uptimeRobot: "A read-only key exposes monitor state, uptime, and response time"
        case .betterStack: "The token reads monitors, check cadence, and availability state"
        case .googleSearchConsole, .googleAnalytics: "Authorize read-only access"
        }
    }

    private var credentialPageURL: URL? {
        let value: String
        switch provider {
        case .pageSpeed: value = "https://console.cloud.google.com/apis/credentials"
        case .bingWebmaster: value = "https://www.bing.com/webmasters/home"
        case .clarity: value = "https://clarity.microsoft.com/projects"
        case .plausible: value = "https://plausible.io/settings/api-keys"
        case .umami: value = "https://cloud.umami.is/settings/api-keys"
        case .uptimeRobot: value = "https://dashboard.uptimerobot.com/integrations"
        case .betterStack: value = "https://betterstack.com/settings/api-tokens"
        case .googleSearchConsole, .googleAnalytics: value = "https://console.cloud.google.com/apis/credentials"
        }
        return URL(string: value)
    }
}

private struct SiteCapabilityLabelStyle: LabelStyle {
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 9) {
            configuration.icon
                .foregroundStyle(tint)
            configuration.title
        }
    }
}
