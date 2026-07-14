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
    @State private var showOptionalBody = false

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
    private var bodyIsOptional: Bool { ["GET", "DELETE", "HEAD", "OPTIONS"].contains(method) }
    private var hasBody: Bool { !requestBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var showsBodyEditor: Bool { !bodyIsOptional || hasBody || showOptionalBody }
    private var canSend: Bool { !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    warning

                    requestTarget

                    if showsBodyEditor {
                        requestBodyEditor
                    } else {
                        optionalBodyButton
                    }

                    if showsBodyEditor && contentType.lowercased().contains("multipart/form-data") {
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
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                            .padding(.horizontal, 14)
                            .appSurface(raised: true)
                        }
                        .buttonStyle(.plain)
                    }

                    if showsBodyEditor && contentType.lowercased().contains("application/octet-stream") {
                        Button { showBodyFileImporter = true } label: {
                            Label(bodyIsBase64 ? "Replace binary file" : "Choose binary file", systemImage: "doc.fill.badge.plus")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .frame(minHeight: 44)
                                .padding(.horizontal, 14)
                                .appSurface(raised: true)
                        }
                        .buttonStyle(.plain)
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        editorLabel("Content type")
                        TextField("application/json", text: $contentType)
                            .font(.callout.monospaced())
                            .textFieldStyle(.plain)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityLabel("Content type")
                        editorLabel("Custom headers · JSON object")
                        TextEditor(text: $customHeaders)
                            .font(.footnote.monospaced())
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 88)
                            .padding(10)
                            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                            .accessibilityLabel("Custom headers JSON object")
                    }
                    .padding(14)
                    .appSurface()

                    Button {
                        if isWrite { showWriteConfirmation = true } else { send() }
                    } label: {
                        HStack {
                            if isSending { ProgressView().tint(.white) }
                            else { Image(systemName: isWrite ? "exclamationmark.shield.fill" : "paperplane.fill") }
                            Text(isWrite ? "Review write request" : "Send request")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 50)
                        .foregroundStyle(canSend ? Color.white : AppTheme.textTertiary)
                        .background(canSend ? AppTheme.signal : AppTheme.surfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(!canSend || isSending)

                    if let error {
                        AppFeedbackBanner(
                            title: "Request failed",
                            message: error,
                            tint: AppTheme.danger
                        )
                    }

                    if let response {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                editorLabel("Response")
                                Spacer()
                                AppStatusBadge(
                                    text: "HTTP \(response.statusCode)",
                                    tone: (200...299).contains(response.statusCode) ? .success : .danger
                                )
                            }
                            if !response.headers.isEmpty {
                                Text(response.headers.sorted { $0.key.lowercased() < $1.key.lowercased() }.map { "\($0.key): \($0.value)" }.joined(separator: "\n"))
                                    .font(.caption.monospaced())
                                    .foregroundStyle(AppTheme.textSecondary)
                                    .textSelection(.enabled)
                            }
                            Text(Self.pretty(response.body))
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .foregroundStyle(AppTheme.textPrimary)
                        .padding(15)
                        .appSurface()
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

    private var requestTarget: some View {
        VStack(alignment: .leading, spacing: 10) {
            editorLabel("Method")
            Picker("Method", selection: $method) {
                ForEach(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"], id: \.self) {
                    Text($0).tag($0)
                }
            }
            .pickerStyle(.menu)
            .tint(AppTheme.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 12)
            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))

            editorLabel("Request path")
            TextField("/provider-relative/path", text: $path)
                .textFieldStyle(.plain)
                .font(.callout.monospaced())
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .foregroundStyle(AppTheme.textPrimary)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityLabel("Request path")
        }
        .padding(14)
        .appSurface()
    }

    private var requestBodyEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                editorLabel(
                    provider == .railway
                        ? "GraphQL JSON body"
                        : (bodyIsBase64 ? "Base64-encoded binary body" : "Request body")
                )
                Spacer()
                if bodyIsOptional && !hasBody {
                    Button("Hide") { showOptionalBody = false }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(minHeight: 44)
                }
            }
            TextEditor(text: $requestBody)
                .font(.footnote.monospaced())
                .scrollContentBackground(.hidden)
                .frame(minHeight: 120)
                .padding(10)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                .accessibilityLabel("Request body")
        }
        .padding(14)
        .appSurface()
    }

    private var optionalBodyButton: some View {
        Button { showOptionalBody = true } label: {
            Label("Add optional request body", systemImage: "plus")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .appSurface()
    }

    private func editorLabel(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(AppTheme.textSecondary)
    }

    private var warning: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "terminal.fill").foregroundStyle(provider.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                Text("Full official API access")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Paths stay locked to \(provider.displayName)’s API host. Write requests require confirmation.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(15)
        .appSurface()
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
