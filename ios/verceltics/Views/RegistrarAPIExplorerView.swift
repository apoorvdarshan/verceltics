import SwiftUI
import UniformTypeIdentifiers

struct RegistrarAPIExplorerView: View {
    let account: RegistrarAccount
    let domain: RegistrarDomain?
    let preset: ProviderAPIRequestPreset?

    @State private var method: String
    @State private var path: String
    @State private var requestBody = ""
    @State private var customHeaders = ""
    @State private var contentType = "application/json"
    @State private var bodyIsBase64 = false
    @State private var response: RegistrarRawResponse?
    @State private var error: String?
    @State private var isSending = false
    @State private var showConfirmation = false
    @State private var showBodyFileImporter = false
    @State private var showOptionalBody = false
    @State private var twoPaneWidth: CGFloat = 0
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(account: RegistrarAccount, domain: RegistrarDomain? = nil, preset: ProviderAPIRequestPreset? = nil) {
        self.account = account
        self.domain = domain
        self.preset = preset
        let api = RegistrarAPI(account: account)
        _method = State(initialValue: preset?.method ?? (account.provider == .porkbun ? "POST" : "GET"))
        _path = State(initialValue: preset?.path ?? api.suggestedPath(for: domain))
        _requestBody = State(initialValue: preset?.body ?? (account.provider == .porkbun ? "{}" : ""))
        _contentType = State(initialValue: preset?.contentType ?? "application/json")
        _customHeaders = State(initialValue: Self.headerJSON(preset?.headers ?? [:]))
    }

    private var provider: RegistrarProvider { account.provider }
    private var api: RegistrarAPI { RegistrarAPI(account: account) }
    private var requiresConfirmation: Bool { api.isLikelyWrite(method: method, path: path) }
    private var bodyIsOptional: Bool { ["GET", "DELETE", "HEAD", "OPTIONS"].contains(method) }
    private var hasBody: Bool { !requestBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var showsBodyEditor: Bool { !bodyIsOptional || hasBody || showOptionalBody }
    private var canSend: Bool { !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    safetyCard
                    AppAdaptiveTwoPane {
                        requestComposer
                    } secondary: {
                        resultPane
                    }
                    .onGeometryChange(for: CGFloat.self) { geometry in
                        geometry.size.width
                    } action: { width in
                        twoPaneWidth = width
                    }
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.vertical, 16)
                .appContentWidth(AppLayout.catalogMaxWidth, horizontalSizeClass: horizontalSizeClass)
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
                    throw RegistrarAPIError.invalidConfiguration("Choose a file smaller than 25 MB.")
                }
                requestBody = data.base64EncodedString()
                bodyIsBase64 = true
                error = nil
            } catch { self.error = error.localizedDescription }
        }
        .confirmationDialog("Send this registrar request?", isPresented: $showConfirmation, titleVisibility: .visible) {
            Button("Send \(method)", role: method == "DELETE" || path.lowercased().contains("delete") ? .destructive : nil) { send() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This command can change a domain, create a purchase, or affect DNS. Confirm the path and request body first.")
        }
    }

    private var requestComposer: some View {
        VStack(spacing: 16) {
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
                if requiresConfirmation { showConfirmation = true } else { send() }
            } label: {
                HStack(spacing: 9) {
                    if isSending { ProgressView().tint(.white) }
                    else { Image(systemName: requiresConfirmation ? "exclamationmark.shield.fill" : "paperplane.fill") }
                    Text(requiresConfirmation ? "Review request" : "Send request")
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
        }
    }

    @ViewBuilder
    private var resultPane: some View {
        VStack(spacing: 16) {
            if let error {
                AppFeedbackBanner(
                    title: "Request failed",
                    message: error,
                    tint: AppTheme.danger
                )
            }
            if let response {
                AppAPIResponsePane(
                    statusCode: response.statusCode,
                    headers: response.headers,
                    body: Self.pretty(response.body)
                )
            } else if error == nil, twoPaneWidth >= 736 {
                AppEmptyState(
                    icon: "terminal",
                    title: "Response workspace",
                    message: "Send a request to inspect its status, headers, and body beside the request editor."
                )
                .frame(maxWidth: .infinity)
                .appSurface()
            }
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
            TextField("/registrar-relative/path", text: $path)
                .font(.callout.monospaced())
                .textFieldStyle(.plain)
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
                editorLabel(bodyIsBase64 ? "Base64-encoded binary body" : "Request body")
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

    private var safetyCard: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "lock.shield.fill").foregroundStyle(provider.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                Text("Full official registrar API")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Authentication is attached privately. Paths cannot leave \(provider.displayName)’s API host, and detected write or purchase commands require confirmation.")
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
                response = try await api.rawRequest(
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
            throw RegistrarAPIError.invalidConfiguration("Custom headers must be a JSON object whose values are strings.")
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
