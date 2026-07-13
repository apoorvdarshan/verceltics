import SwiftUI

struct HostingAPIExplorerView: View {
    let account: VercelAccount
    let suggestedResource: HostingResource?

    @State private var method = "GET"
    @State private var path: String
    @State private var requestBody = ""
    @State private var response: HostingRawResponse?
    @State private var error: String?
    @State private var isSending = false
    @State private var showWriteConfirmation = false

    init(account: VercelAccount, suggestedResource: HostingResource? = nil) {
        self.account = account
        self.suggestedResource = suggestedResource
        _path = State(initialValue: Self.defaultPath(account: account, resource: suggestedResource))
        _requestBody = State(initialValue: account.provider == .railway ? "{\"query\":\"query { me { id name email } }\"}" : "")
    }

    private var provider: AccountProvider { account.provider }
    private var isWrite: Bool { method != "GET" }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    warning

                    Picker("Method", selection: $method) {
                        ForEach(["GET", "POST", "PUT", "PATCH", "DELETE"], id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    TextField("/provider-relative/path", text: $path)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13, design: .monospaced))
                        .padding(14)
                        .foregroundStyle(.white)
                        .providerPanel(accent: provider.accentColor)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    VStack(alignment: .leading, spacing: 8) {
                        Text(provider == .railway ? "GRAPHQL JSON BODY" : "JSON BODY")
                            .font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextEditor(text: $requestBody)
                            .font(.system(size: 11, design: .monospaced))
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 130)
                            .padding(10)
                            .background(Color.clear)
                    }
                    .padding(14)
                    .providerPanel(accent: provider.accentColor)

                    Button {
                        if isWrite { showWriteConfirmation = true } else { send() }
                    } label: {
                        HStack {
                            if isSending { ProgressView().tint(.white) }
                            else { Image(systemName: isWrite ? "exclamationmark.shield.fill" : "paperplane.fill") }
                            Text(isWrite ? "Review & Send Write Request" : "Send Request")
                                .font(.system(size: 14, weight: .heavy))
                        }
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(provider.accentColor.opacity(path.isEmpty ? 0.25 : 0.8))
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(path.isEmpty || isSending)

                    if let error {
                        Text(error)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(15)
                            .providerPanel(accent: .red)
                    }

                    if let response {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text("RESPONSE").font(.system(size: 9, weight: .heavy)).tracking(1.2)
                                Spacer()
                                Text("HTTP \(response.statusCode)").font(.system(size: 9, weight: .heavy)).foregroundStyle(.green)
                            }
                            Text(response.body.isEmpty ? "(empty response)" : response.body)
                                .font(.system(size: 10, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .foregroundStyle(.white.opacity(0.72))
                        .padding(15)
                        .providerPanel(accent: provider.accentColor)
                    }
                    Spacer().frame(height: 80)
                }
                .padding(16)
            }
        }
        .navigationTitle("\(provider.displayName) API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .confirmationDialog("Send \(method) request?", isPresented: $showWriteConfirmation, titleVisibility: .visible) {
            Button("Send \(method)", role: method == "DELETE" ? .destructive : nil) { send() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This is a real write request to \(provider.displayName). Confirm the path and JSON body first.")
        }
    }

    private var warning: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "terminal.fill").foregroundStyle(provider.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                Text("Full official API access").font(.system(size: 13, weight: .bold))
                Text("Paths stay locked to \(provider.displayName)’s API host. Every non-GET request requires confirmation.")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.42))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(15)
        .providerPanel(accent: provider.accentColor)
    }

    private func send() {
        isSending = true
        error = nil
        response = nil
        Task {
            do {
                response = try await HostingProviderAPI(account: account).rawRequest(
                    method: method,
                    path: path.trimmingCharacters(in: .whitespacesAndNewlines),
                    body: requestBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : requestBody
                )
            } catch { self.error = error.localizedDescription }
            isSending = false
        }
    }

    private static func defaultPath(account: VercelAccount, resource: HostingResource?) -> String {
        switch account.provider {
        case .netlify: resource.map { "/sites/\($0.id)" } ?? "/sites?per_page=100"
        case .railway: "/graphql/v2"
        case .render: resource.map { "/services/\($0.id)" } ?? "/services?limit=100"
        case .digitalOcean: resource.map { "/apps/\($0.id)" } ?? "/apps?per_page=200"
        case .heroku: resource.map { "/apps/\($0.id)" } ?? "/apps"
        case .fly:
            resource.map { "/apps/\($0.name)/machines" }
                ?? "/apps?org_slug=\(account.providerMetadata["organization"] ?? "personal")"
        case .firebase:
            resource.map { "/sites/\($0.id)/releases?pageSize=50" }
                ?? "/projects/\(account.providerMetadata["projectID"] ?? "PROJECT_ID")/sites"
        case .awsAmplify: resource.map { "/apps/\($0.id)" } ?? "/apps?maxResults=100"
        default: "/"
        }
    }
}
