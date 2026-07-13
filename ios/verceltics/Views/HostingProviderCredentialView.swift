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
    @State private var firebaseAuthMode = "refreshToken"
    @State private var firebaseClientID = ""
    @State private var firebaseClientSecret = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case credential, organization, projectID, accessKeyID, region, sessionToken, firebaseClientID, firebaseClientSecret }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    header

                    if let error = authManager.error {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.red)
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
            .onChange(of: focusedField) { _, value in
                guard value != nil else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    withAnimation(.easeOut(duration: 0.22)) {
                        proxy.scrollTo("provider-connect", anchor: .bottom)
                    }
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 20) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .heavy))
                        .frame(width: 42, height: 42)
                        .background(Color.white.opacity(0.07))
                        .clipShape(Circle())
                }
                .foregroundStyle(.white)
                Spacer()
            }

            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(provider.accentColor.opacity(0.14))
                ProviderMark(provider: provider, size: 42)
            }
            .frame(width: 92, height: 92)

            VStack(spacing: 6) {
                Text("Connect \(provider.displayName)")
                    .font(.system(size: 26, weight: .heavy))
                Text(provider.connectionSubtitle)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.42))
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
    }

    private var instructions: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connect securely")
                .font(.system(size: 13, weight: .heavy))
            StepRow(number: 1, text: instructionOne)
            StepRow(number: 2, text: instructionTwo)
            StepRow(number: 3, text: "Paste the credentials below and connect")

            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "lock.shield.fill")
                    .foregroundStyle(provider.accentColor)
                Text(credentialStorageMessage)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.48))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(provider.accentColor.opacity(0.25), lineWidth: 0.7)
        )
    }

    private var credentialLink: some View {
        Button {
            if let url = provider.credentialPageURL { UIApplication.shared.open(url) }
        } label: {
            Label("Open \(provider.displayName) credentials", systemImage: "arrow.up.right")
                .font(.system(size: 13, weight: .bold))
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .foregroundStyle(.white.opacity(0.82))
                .background(Color.white.opacity(0.06))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private var credentialStorageMessage: String {
        if provider == .firebase, firebaseAuthMode == "refreshToken" {
            return "Credentials stay in this iPhone’s Keychain. The refresh token goes only to Google’s official OAuth endpoint; its access token goes only to Firebase Hosting."
        }
        return "Credentials stay in this iPhone’s Keychain. Verceltics sends them only to \(provider.displayName)’s official API."
    }

    @ViewBuilder
    private var fields: some View {
        if provider == .awsAmplify {
            providerField("AWS Access Key ID", text: $accessKeyID, field: .accessKeyID)
            providerField("AWS Secret Access Key", text: $credential, field: .credential, secure: true)
            providerField("Region (for example us-east-1)", text: $region, field: .region)
            providerField("Session token (optional)", text: $sessionToken, field: .sessionToken, secure: true)
        } else {
            if provider == .firebase {
                Picker("Firebase authorization", selection: $firebaseAuthMode) {
                    Text("Refresh token").tag("refreshToken")
                    Text("Access token").tag("accessToken")
                }
                .pickerStyle(.segmented)
            }
            providerField(credentialPlaceholder, text: $credential, field: .credential, secure: true)
            if provider == .railway {
                Picker("Railway token type", selection: $railwayTokenType) {
                    Text("Account / Workspace").tag("account")
                    Text("Project").tag("project")
                }
                .pickerStyle(.segmented)
            } else if provider == .fly {
                providerField("Organization slug", text: $organization, field: .organization)
            } else if provider == .firebase {
                providerField("Firebase / Google Cloud project ID", text: $projectID, field: .projectID)
                if firebaseAuthMode == "refreshToken" {
                    providerField("Google OAuth client ID", text: $firebaseClientID, field: .firebaseClientID)
                    providerField("OAuth client secret (if required)", text: $firebaseClientSecret, field: .firebaseClientSecret, secure: true)
                }
            }
        }
    }

    private func providerField(
        _ placeholder: String,
        text: Binding<String>,
        field: Field,
        secure: Bool = false
    ) -> some View {
        Group {
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
            }
        }
        .textFieldStyle(.plain)
        .font(.system(size: 14, design: .monospaced))
        .padding(15)
        .background(Color.white.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(focusedField == field ? provider.accentColor.opacity(0.7) : Color.white.opacity(0.08), lineWidth: focusedField == field ? 1 : 0.5)
        )
        .foregroundStyle(.white)
        .focused($focusedField, equals: field)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
    }

    private var connectButton: some View {
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
                        .font(.system(size: 16, weight: .heavy))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 14, weight: .heavy))
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                LinearGradient(
                    colors: canConnect ? [provider.accentColor, provider.accentColor.opacity(0.72)] : [Color.white.opacity(0.16), Color.white.opacity(0.09)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(!canConnect || authManager.isLoading)
    }

    private var metadata: [String: String] {
        switch provider {
        case .railway: ["railwayTokenType": railwayTokenType]
        case .fly: ["organization": organization]
        case .firebase: [
            "projectID": projectID,
            "firebaseAuthMode": firebaseAuthMode,
            "firebaseClientID": firebaseClientID,
            "firebaseClientSecret": firebaseClientSecret,
        ]
        case .awsAmplify: ["accessKeyID": accessKeyID, "region": region, "sessionToken": sessionToken]
        default: [:]
        }
    }

    private var canConnect: Bool {
        guard !credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch provider {
        case .fly: return !organization.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .firebase:
            return !projectID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && (firebaseAuthMode == "accessToken" || !firebaseClientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        case .awsAmplify:
            return !accessKeyID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !region.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        default: return true
        }
    }

    private var credentialPlaceholder: String {
        switch provider {
        case .firebase: firebaseAuthMode == "refreshToken" ? "Google OAuth refresh token" : "Google OAuth access token"
        case .fly: "Fly.io access token"
        default: "\(provider.displayName) API token"
        }
    }

    private var instructionOne: String {
        switch provider {
        case .awsAmplify: "Create an IAM access key with Amplify permissions"
        case .firebase: firebaseAuthMode == "refreshToken" ? "Create OAuth credentials with Firebase Hosting access" : "Create a Google OAuth access token for Firebase Hosting"
        default: "Open \(provider.displayName)’s token or API key page"
        }
    }

    private var instructionTwo: String {
        switch provider {
        case .railway: railwayTokenType == "project" ? "Copy a project token from Project Settings, or choose Account / Workspace for a broader API token" : "Create an account or workspace token with the access you want Verceltics to use"
        case .fly: "Copy the token and your organization slug"
        case .firebase: firebaseAuthMode == "refreshToken" ? "Copy the refresh token, OAuth client ID, and Firebase project ID" : "Copy the short-lived access token and Firebase project ID"
        case .awsAmplify: "Copy the access key ID, secret and AWS region"
        default: "Create a token with the access you want Verceltics to use"
        }
    }
}
