import SwiftUI

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

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    safetyCard
                    Picker("Method", selection: $method) {
                        ForEach(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"], id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.menu).tint(provider.accentColor)

                    TextField("/registrar-relative/path", text: $path)
                        .font(.system(size: 12, design: .monospaced))
                        .textFieldStyle(.plain)
                        .padding(14)
                        .foregroundStyle(.white)
                        .providerPanel(accent: provider.accentColor)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    VStack(alignment: .leading, spacing: 8) {
                        Text("JSON BODY")
                            .font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextEditor(text: $requestBody)
                            .font(.system(size: 11, design: .monospaced))
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 130)
                    }
                    .padding(14)
                    .providerPanel(accent: provider.accentColor)

                    if contentType.lowercased().contains("multipart/form-data") {
                        NavigationLink {
                            CloudflareMultipartComposerView(schemaFields: []) { body, composedContentType in
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

                    VStack(alignment: .leading, spacing: 8) {
                        Text("CONTENT TYPE").font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextField("application/json", text: $contentType)
                            .font(.system(size: 11, design: .monospaced)).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Text("CUSTOM HEADERS · JSON OBJECT")
                            .font(.system(size: 9, weight: .heavy)).tracking(1.2).foregroundStyle(.white.opacity(0.35))
                        TextEditor(text: $customHeaders)
                            .font(.system(size: 10, design: .monospaced)).scrollContentBackground(.hidden).frame(minHeight: 72)
                    }
                    .padding(14).providerPanel(accent: provider.accentColor)

                    Button {
                        if requiresConfirmation { showConfirmation = true } else { send() }
                    } label: {
                        HStack(spacing: 9) {
                            if isSending { ProgressView().tint(.white) }
                            else { Image(systemName: requiresConfirmation ? "exclamationmark.shield.fill" : "paperplane.fill") }
                            Text(requiresConfirmation ? "Review & Send Request" : "Send Request")
                                .font(.system(size: 14, weight: .heavy))
                        }
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(provider.accentColor.opacity(path.isEmpty ? 0.22 : 0.82))
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(path.isEmpty || isSending)

                    if let error {
                        Text(error).font(.system(size: 11, weight: .semibold)).foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(15).providerPanel(accent: .red)
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
                                .font(.system(size: 10, design: .monospaced)).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .foregroundStyle(.white.opacity(0.72)).padding(15).providerPanel(accent: provider.accentColor)
                    }
                    Spacer().frame(height: 80)
                }
                .padding(16)
            }
        }
        .navigationTitle(preset?.title ?? "\(provider.displayName) API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .confirmationDialog("Send this registrar request?", isPresented: $showConfirmation, titleVisibility: .visible) {
            Button("Send \(method)", role: method == "DELETE" || path.lowercased().contains("delete") ? .destructive : nil) { send() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This command can change a domain, create a purchase, or affect DNS. Confirm the path and request body first.")
        }
    }

    private var safetyCard: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "lock.shield.fill").foregroundStyle(provider.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                Text("Full official registrar API").font(.system(size: 13, weight: .bold))
                Text("Authentication is attached privately. Paths cannot leave \(provider.displayName)’s API host, and detected write or purchase commands require confirmation.")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.42))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(15).providerPanel(accent: provider.accentColor)
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
