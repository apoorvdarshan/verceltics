import SwiftUI

struct HostingProviderCredentialView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss

    let provider: AccountProvider
    let onBack: () -> Void

    @State private var credential = ""
    @State private var organization = "personal"
    @State private var projectID = ""
    @State private var accessKeyID = ""
    @State private var region = "us-east-1"
    @State private var sessionToken = ""
    @State private var railwayTokenType = "account"
    @State private var firebaseAuthorizing = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case credential, organization, projectID, accessKeyID, region, sessionToken }

    var body: some View {
        ScrollViewReader { _ in
            ScrollView {
                VStack(spacing: 0) {
                    header

                    if let error = authManager.error {
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
                        instructions
                        credentialLink
                        fields
                        connectButton
                            .id("provider-connect")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 26)

                    Spacer().frame(height: 42)
                }
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var header: some View {
        VStack(spacing: 20) {
            HStack {
                Button(action: onBack) {
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
                Text(provider.connectionSubtitle)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
    }

    private var instructions: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connect securely")
                .font(.subheadline.weight(.semibold))
            StepRow(number: 1, text: instructionOne)
            StepRow(number: 2, text: instructionTwo)
            StepRow(
                number: 3,
                text: provider == .firebase
                    ? "Enter the project ID, then continue with Google"
                    : "Paste the credentials below and connect"
            )

            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "lock.shield.fill")
                    .foregroundStyle(provider.accentColor)
                Text(credentialStorageMessage)
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
            if let url = provider.credentialPageURL { UIApplication.shared.open(url) }
        } label: {
            Label(
                provider == .firebase ? "Open Firebase console" : "Open \(provider.displayName) credentials",
                systemImage: "arrow.up.right"
            )
                .font(.footnote.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: 46)
                .foregroundStyle(AppTheme.textPrimary)
                .appSurface(raised: true)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private var credentialStorageMessage: String {
        if provider == .firebase {
            return "Google authorization stays in this device’s Keychain. Tokens go only to Google’s official OAuth and Firebase Hosting endpoints."
        }
        return "Credentials stay in this device’s Keychain. Verceltics sends them only to \(provider.displayName)’s official API."
    }

    @ViewBuilder
    private var fields: some View {
        if provider == .awsAmplify {
            providerField("AWS Access Key ID", text: $accessKeyID, field: .accessKeyID)
            providerField("AWS Secret Access Key", text: $credential, field: .credential, secure: true)
            providerField("Region (for example us-east-1)", text: $region, field: .region)
            providerField("Session token (optional)", text: $sessionToken, field: .sessionToken, secure: true)
        } else if provider == .firebase {
            providerField("Firebase / Google Cloud project ID", text: $projectID, field: .projectID)
        } else {
            providerField(credentialPlaceholder, text: $credential, field: .credential, secure: true)
            if provider == .railway {
                Picker("Railway token type", selection: $railwayTokenType) {
                    Text("Account / Workspace").tag("account")
                    Text("Project").tag("project")
                }
                .pickerStyle(.segmented)
            } else if provider == .fly {
                providerField("Organization slug", text: $organization, field: .organization)
            }
        }
    }

    private func providerField(
        _ placeholder: String,
        text: Binding<String>,
        field: Field,
        secure: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(placeholder)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
            Group {
                if secure {
                    SecureField("Enter value", text: text)
                } else {
                    TextField("Enter value", text: text)
                }
            }
            .textFieldStyle(.plain)
            .font(.body.monospaced())
            .padding(15)
            .background(AppTheme.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                    .strokeBorder(focusedField == field ? provider.accentColor.opacity(0.7) : AppTheme.stroke, lineWidth: focusedField == field ? 1 : 0.5)
            )
            .foregroundStyle(AppTheme.textPrimary)
            .focused($focusedField, equals: field)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
        }
    }

    @ViewBuilder
    private var connectButton: some View {
        if provider == .firebase {
            Button(action: connectFirebase) {
                HStack(spacing: 10) {
                    if firebaseAuthorizing || authManager.isLoading {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "person.crop.circle.badge.checkmark")
                        Text("Continue with Google")
                            .font(.system(size: 16, weight: .semibold))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(canConnect ? AppTheme.signal : AppTheme.surfaceRaised)
                .foregroundStyle(canConnect ? .white : AppTheme.textTertiary)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(!canConnect || firebaseAuthorizing || authManager.isLoading)
        } else {
            Button {
                Task {
                    await authManager.loginHostingProvider(provider, credential: credential, metadata: metadata)
                    if authManager.error == nil { dismiss() }
                }
            } label: {
                HStack(spacing: 10) {
                    if authManager.isLoading {
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
            .disabled(!canConnect || authManager.isLoading)
        }
    }

    private func connectFirebase() {
        let normalizedProjectID = projectID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedProjectID.isEmpty else { return }
        Task {
            firebaseAuthorizing = true
            authManager.error = nil
            defer { firebaseAuthorizing = false }
            do {
                let googleCredential = try await GoogleOAuthService.shared.authorizeFirebaseHosting()
                try Task.checkCancellation()
                var firebaseMetadata = [
                    "projectID": normalizedProjectID,
                    "firebaseAuthMode": "googleOAuth",
                ]
                if let subject = googleCredential.subject { firebaseMetadata["googleSubject"] = subject }
                if let email = googleCredential.email { firebaseMetadata["googleEmail"] = email }
                await authManager.loginHostingProvider(
                    .firebase,
                    credential: try googleCredential.keychainValue(),
                    metadata: firebaseMetadata
                )
                if authManager.error == nil { dismiss() }
            } catch is CancellationError {
                return
            } catch let oauthError as GoogleOAuthError where oauthError == .authorizationCancelled {
                return
            } catch {
                authManager.error = error.localizedDescription
            }
        }
    }

    private var metadata: [String: String] {
        switch provider {
        case .railway: ["railwayTokenType": railwayTokenType]
        case .fly: ["organization": organization]
        case .firebase: ["projectID": projectID, "firebaseAuthMode": "googleOAuth"]
        case .awsAmplify: ["accessKeyID": accessKeyID, "region": region, "sessionToken": sessionToken]
        default: [:]
        }
    }

    private var canConnect: Bool {
        if provider == .firebase {
            return !projectID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && GoogleOAuthService.shared.isConfigured
        }
        guard !credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch provider {
        case .fly: return !organization.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .awsAmplify:
            return !accessKeyID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !region.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        default: return true
        }
    }

    private var credentialPlaceholder: String {
        switch provider {
        case .fly: "Fly.io access token"
        default: "\(provider.displayName) API token"
        }
    }

    private var instructionOne: String {
        switch provider {
        case .awsAmplify: "Create an IAM access key with Amplify permissions"
        case .firebase: "Enable the Firebase Hosting API for your Google Cloud project"
        default: "Open \(provider.displayName)’s token or API key page"
        }
    }

    private var instructionTwo: String {
        switch provider {
        case .railway: railwayTokenType == "project" ? "Copy a project token from Project Settings, or choose Account / Workspace for a broader API token" : "Create an account or workspace token with the access you want Verceltics to use"
        case .fly: "Copy the token and your organization slug"
        case .firebase: "Use a Google account with access to that Firebase project"
        case .awsAmplify: "Copy the access key ID, secret and AWS region"
        default: "Create a token with the access you want Verceltics to use"
        }
    }
}
