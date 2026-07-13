import SwiftUI
import UniformTypeIdentifiers

struct HostingAPIExplorerView: View {
    let account: VercelAccount
    let suggestedResource: HostingResource?
    let preset: ProviderAPIRequestPreset?

    @State private var method = "GET"
    @State private var path: String
    @State private var requestBody = ""
    @State private var customHeaders = ""
    @State private var contentType = "application/json"
    @State private var bodyIsBase64 = false
    @State private var response: HostingRawResponse?
    @State private var error: String?
    @State private var isSending = false
    @State private var showWriteConfirmation = false
    @State private var showBodyFileImporter = false

    init(account: VercelAccount, suggestedResource: HostingResource? = nil, preset: ProviderAPIRequestPreset? = nil) {
        self.account = account
        self.suggestedResource = suggestedResource
        self.preset = preset
        _method = State(initialValue: preset?.method ?? "GET")
        _path = State(initialValue: preset?.path ?? Self.defaultPath(account: account, resource: suggestedResource))
        _requestBody = State(initialValue: preset?.body ?? (account.provider == .railway ? "{\"query\":\"query { me { id name email } }\"}" : ""))
        _contentType = State(initialValue: preset?.contentType ?? "application/json")
        _customHeaders = State(initialValue: Self.headerJSON(preset?.headers ?? [:]))
    }

    private var provider: AccountProvider { account.provider }
    private var isWrite: Bool { !["GET", "HEAD", "OPTIONS"].contains(method) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    warning

                    Picker("Method", selection: $method) {
                        ForEach(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"], id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.menu).tint(provider.accentColor)

                    TextField("/provider-relative/path", text: $path)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13, design: .monospaced))
                        .padding(14)
                        .foregroundStyle(.white)
                        .providerPanel(accent: provider.accentColor)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    VStack(alignment: .leading, spacing: 8) {
                        Text(provider == .railway ? "GRAPHQL JSON BODY" : (bodyIsBase64 ? "BASE64-ENCODED BINARY BODY" : "REQUEST BODY"))
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

                    if contentType.lowercased().contains("multipart/form-data") {
                        NavigationLink {
                            CloudflareMultipartComposerView(schemaFields: preset?.multipartFields ?? []) { body, composedContentType in
                                requestBody = body
                                contentType = composedContentType
                                bodyIsBase64 = true
                            }
                        } label: {
                            Label(
                                bodyIsBase64 ? "Edit encoded multipart upload" : "Build multipart upload",
                                systemImage: "doc.badge.plus"
                            )
                            .font(.system(size: 12, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(14)
                            .providerPanel(accent: provider.accentColor)
                        }
                        .buttonStyle(.plain)
                    }

                    if contentType.lowercased().contains("application/octet-stream") {
                        Button { showBodyFileImporter = true } label: {
                            Label(bodyIsBase64 ? "Replace binary file" : "Choose binary file", systemImage: "doc.fill.badge.plus")
                                .font(.system(size: 12, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .padding(14)
                                .providerPanel(accent: provider.accentColor)
                        }
                        .buttonStyle(.plain)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("CONTENT TYPE").font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextField("application/json", text: $contentType)
                            .font(.system(size: 11, design: .monospaced)).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Text("CUSTOM HEADERS · JSON OBJECT").font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextEditor(text: $customHeaders)
                            .font(.system(size: 10, design: .monospaced)).scrollContentBackground(.hidden).frame(minHeight: 72)
                    }
                    .padding(14).providerPanel(accent: provider.accentColor)

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
                                Text("HTTP \(response.statusCode)").font(.system(size: 9, weight: .heavy))
                                    .foregroundStyle((200...299).contains(response.statusCode) ? .green : .red)
                            }
                            if !response.headers.isEmpty {
                                Text(response.headers.sorted { $0.key.lowercased() < $1.key.lowercased() }.map { "\($0.key): \($0.value)" }.joined(separator: "\n"))
                                    .font(.system(size: 9, design: .monospaced)).foregroundStyle(.white.opacity(0.42)).textSelection(.enabled)
                            }
                            Text(Self.pretty(response.body))
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
        .navigationTitle(preset?.title ?? "\(provider.displayName) API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: contentType) { _, value in
            if !value.localizedCaseInsensitiveContains("multipart/form-data") &&
                !value.localizedCaseInsensitiveContains("application/octet-stream") {
                bodyIsBase64 = false
            }
        }
        .fileImporter(isPresented: $showBodyFileImporter, allowedContentTypes: [.data]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url, options: [.mappedIfSafe])
                guard data.count <= 25 * 1_024 * 1_024 else {
                    throw HostingProviderAPIError.invalidConfiguration("Choose a file smaller than 25 MB.")
                }
                requestBody = data.base64EncodedString()
                bodyIsBase64 = true
                error = nil
            } catch { self.error = error.localizedDescription }
        }
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
                let headers = try Self.parseHeaders(customHeaders)
                response = try await HostingProviderAPI(account: account).rawRequest(
                    method: method,
                    path: path.trimmingCharacters(in: .whitespacesAndNewlines),
                    body: requestBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : requestBody,
                    additionalHeaders: headers,
                    contentType: contentType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : contentType,
                    bodyIsBase64: bodyIsBase64,
                    returnHTTPErrorResponse: true
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

    private static func headerJSON(_ headers: [String: String]) -> String {
        guard !headers.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: headers, options: [.prettyPrinted, .sortedKeys]) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private static func parseHeaders(_ text: String) throws -> [String: String] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [:] }
        guard let data = trimmed.data(using: .utf8),
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              value.values.allSatisfy({ $0 is String }) else {
            throw HostingProviderAPIError.invalidConfiguration("Custom headers must be a JSON object whose values are strings.")
        }
        return value.compactMapValues { $0 as? String }
    }

    private static func pretty(_ body: String) -> String {
        guard !body.isEmpty else { return "(empty response)" }
        guard let data = body.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data),
              let formatted = try? JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]) else { return body }
        return String(data: formatted, encoding: .utf8) ?? body
    }
}
