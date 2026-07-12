import SwiftUI
import UniformTypeIdentifiers

@Observable
@MainActor
final class CloudflareAPIExplorerViewModel {
    let api: CloudflareAPI

    var method: CloudflareHTTPMethod = .get
    var path = "/accounts"
    var queryText = ""
    var headerText = ""
    var requestBody = ""
    var contentType = "application/json"
    var bodyEncoding: CloudflareRequestBodyEncoding = .utf8
    var response: CloudflareRawResponse?
    var elapsedMilliseconds: Int?
    var isExecuting = false
    var error: String?

    init(api: CloudflareAPI) {
        self.api = api
    }

    func execute(confirmed: Bool) async {
        isExecuting = true
        response = nil
        elapsedMilliseconds = nil
        error = nil
        let started = ContinuousClock.now

        do {
            let confirmation = method.isMutation && confirmed
                ? CloudflareMutationConfirmation(confirmingResourceID: path)
                : nil
            response = try await api.rawRequest(
                method: method,
                path: path,
                query: try parseQuery(),
                headers: try parseHeaders(),
                bodyText: method == .get ? nil : requestBody,
                contentType: method == .get ? nil : contentType,
                bodyEncoding: bodyEncoding,
                confirmation: confirmation
            )
            let duration = ContinuousClock.now - started
            elapsedMilliseconds = Int(duration.components.seconds * 1_000) +
                Int(duration.components.attoseconds / 1_000_000_000_000_000)
        } catch {
            self.error = error.localizedDescription
        }

        isExecuting = false
    }

    func clearResponse() {
        response = nil
        elapsedMilliseconds = nil
        error = nil
    }

    private func parseQuery() throws -> [String: String] {
        let normalized = queryText.replacingOccurrences(of: "&", with: "\n")
        let lines = normalized
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        var result: [String: String] = [:]
        for line in lines {
            let components = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard components.count == 2 else {
                throw CloudflareExplorerError.invalidQuery(line)
            }
            let key = String(components[0]).trimmingCharacters(in: .whitespacesAndNewlines)
            let value = String(components[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty else { throw CloudflareExplorerError.invalidQuery(line) }
            result[key.removingPercentEncoding ?? key] = value.removingPercentEncoding ?? value
        }
        return result
    }

    private func parseHeaders() throws -> [String: String] {
        let lines = headerText
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        var result: [String: String] = [:]
        for line in lines {
            let separator = line.firstIndex(of: ":") ?? line.firstIndex(of: "=")
            guard let separator else { throw CloudflareExplorerError.invalidHeader(line) }
            let name = String(line[..<separator]).trimmingCharacters(in: .whitespacesAndNewlines)
            let value = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { throw CloudflareExplorerError.invalidHeader(line) }
            result[name] = value
        }
        return result
    }
}

struct CloudflareAPIExplorerView: View {
    let api: CloudflareAPI
    let accountID: String?

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareAPIExplorerViewModel
    @State private var showingMutationConfirmation = false
    @State private var showingHeaders = false
    @State private var showingBodyFileImporter = false
    @State private var copied = false

    init(api: CloudflareAPI, accountID: String? = nil) {
        self.api = api
        self.accountID = accountID
        _viewModel = State(wrappedValue: CloudflareAPIExplorerViewModel(api: api))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    explorerNotice
                    requestPanel

                    if let error = viewModel.error {
                        CloudflareActionResultBanner(message: error, isError: true)
                    }

                    if let response = viewModel.response {
                        responsePanel(response)
                    } else if !viewModel.isExecuting && viewModel.error == nil {
                        CloudflareEmptySection(
                            icon: "terminal",
                            title: "Ready for a request",
                            message: "Responses stay only in this screen and are never saved."
                        )
                        .cloudflarePanel()
                    }
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 900 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("API Explorer")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear") { viewModel.clearResponse() }
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white.opacity(0.55))
                    .disabled(viewModel.response == nil && viewModel.error == nil)
            }
        }
        .confirmationDialog(
            confirmationTitle,
            isPresented: $showingMutationConfirmation,
            titleVisibility: .visible
        ) {
            Button(
                "Send \(viewModel.method.rawValue) Request",
                role: viewModel.method == .delete ? .destructive : nil
            ) {
                Task { await viewModel.execute(confirmed: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(viewModel.method.rawValue) \(normalizedDisplayPath)\nThis request can change live Cloudflare resources.")
        }
        .fileImporter(isPresented: $showingBodyFileImporter, allowedContentTypes: [.data]) { result in
            importBodyFile(result)
        }
        .tint(CloudflareStyle.orange)
    }

    private var explorerNotice: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "terminal.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)
            VStack(alignment: .leading, spacing: 4) {
                Text("Direct access to Cloudflare’s v4 API")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.82))
                Text("Use a relative path. Authentication headers are added securely. Request bodies can be UTF-8 or Base64, including prebuilt multipart payloads; credentials are never shown in the editor.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.36))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var requestPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Request", icon: "paperplane.fill")
            Divider().overlay(Color.white.opacity(0.06))

            methodPicker
                .padding(14)

            Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)

            VStack(alignment: .leading, spacing: 8) {
                Text("RELATIVE PATH")
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
                    .foregroundStyle(.white.opacity(0.34))
                HStack(spacing: 0) {
                    Text("/client/v4")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.28))
                    TextField("/accounts", text: $viewModel.path)
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .foregroundStyle(.white.opacity(0.8))
                }
                .padding(12)
                .background(Color.black.opacity(0.3))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
                )
            }
            .padding(16)

            quickPaths
                .padding(.horizontal, 16)
                .padding(.bottom, 14)

            Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)

            editor(label: "QUERY PARAMETERS", placeholder: "page=1\nper_page=50", text: $viewModel.queryText, minHeight: 74)

            Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)
            editor(
                label: "OPTIONAL REQUEST HEADERS",
                placeholder: "If-Match: etag\nAccept: application/json",
                text: $viewModel.headerText,
                minHeight: 74
            )

            if viewModel.method != .get {
                Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)
                bodyOptions
                Divider().overlay(Color.white.opacity(0.055)).padding(.horizontal, 16)
                editor(
                    label: "REQUEST BODY · \(viewModel.bodyEncoding.rawValue)",
                    placeholder: viewModel.bodyEncoding == .base64
                        ? "Paste a Base64-encoded raw request body"
                        : "{\n  \"key\": \"value\"\n}",
                    text: $viewModel.requestBody,
                    minHeight: 150
                )
            }

            Button {
                if viewModel.method.isMutation {
                    showingMutationConfirmation = true
                } else {
                    Task { await viewModel.execute(confirmed: false) }
                }
            } label: {
                HStack(spacing: 8) {
                    if viewModel.isExecuting {
                        ProgressView().controlSize(.small).tint(.black)
                    } else {
                        Image(systemName: "paperplane.fill")
                            .font(.system(size: 10, weight: .heavy))
                    }
                    Text(viewModel.isExecuting ? "Sending" : "Execute \(viewModel.method.rawValue)")
                        .font(.system(size: 13, weight: .heavy))
                }
                .foregroundStyle(.black.opacity(0.84))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(methodColor)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(viewModel.isExecuting || viewModel.path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .padding(16)
        }
        .cloudflarePanel(accentOpacity: 0.045)
    }

    private var bodyOptions: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 7) {
                Text("CONTENT TYPE")
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
                    .foregroundStyle(.white.opacity(0.34))
                TextField("application/json", text: $viewModel.contentType)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(11)
                    .background(Color.black.opacity(0.3))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
                    )
            }

            Picker("Body encoding", selection: $viewModel.bodyEncoding) {
                ForEach(CloudflareRequestBodyEncoding.allCases) { encoding in
                    Text(encoding.rawValue).tag(encoding)
                }
            }
            .pickerStyle(.segmented)

            Button {
                showingBodyFileImporter = true
            } label: {
                Label("Import raw body file as Base64", systemImage: "doc.badge.plus")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(CloudflareStyle.orange)
            }
            .buttonStyle(.plain)

            if viewModel.contentType.lowercased().contains("multipart/form-data") {
                Text("Include the matching boundary in Content-Type. Base64 mode accepts a complete binary multipart body.")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(CloudflareStyle.amber.opacity(0.72))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
    }

    private var methodPicker: some View {
        HStack(spacing: 6) {
            ForEach(CloudflareHTTPMethod.allCases) { method in
                Button {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        viewModel.method = method
                        viewModel.clearResponse()
                    }
                } label: {
                    Text(method.rawValue)
                        .font(.system(size: 9, weight: .heavy, design: .monospaced))
                        .foregroundStyle(viewModel.method == method ? .black.opacity(0.82) : .white.opacity(0.42))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(viewModel.method == method ? color(for: method) : Color.white.opacity(0.045))
                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var quickPaths: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                quickPath("Accounts", path: "/accounts")
                quickPath("Zones", path: accountID.map { "/zones?account.id=\($0)" } ?? "/zones")
                if let accountID {
                    quickPath("Pages", path: "/accounts/\(accountID)/pages/projects")
                    quickPath("Workers", path: "/accounts/\(accountID)/workers/scripts")
                    quickPath("D1", path: "/accounts/\(accountID)/d1/database")
                    quickPath("R2", path: "/accounts/\(accountID)/r2/buckets")
                    quickPath("KV", path: "/accounts/\(accountID)/storage/kv/namespaces")
                }
            }
        }
    }

    private func quickPath(_ title: String, path: String) -> some View {
        Button {
            let components = path.split(separator: "?", maxSplits: 1).map(String.init)
            if components.count == 2 {
                viewModel.path = components[0]
                viewModel.queryText = components[1].replacingOccurrences(of: "&", with: "\n")
            } else {
                viewModel.path = path
                viewModel.queryText = ""
            }
            viewModel.method = .get
            viewModel.clearResponse()
        } label: {
            Text(title)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white.opacity(0.52))
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color.white.opacity(0.055))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.06), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }

    private func editor(
        label: String,
        placeholder: String,
        text: Binding<String>,
        minHeight: CGFloat
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(label)
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
                    .foregroundStyle(.white.opacity(0.34))
                Spacer()
                if !text.wrappedValue.isEmpty {
                    Button("Clear") { text.wrappedValue = "" }
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white.opacity(0.35))
                }
            }

            ZStack(alignment: .topLeading) {
                if text.wrappedValue.isEmpty {
                    Text(placeholder)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.18))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .allowsHitTesting(false)
                }
                TextEditor(text: text)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.76))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: minHeight)
                    .padding(7)
            }
            .background(Color.black.opacity(0.3))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
            )
        }
        .padding(16)
    }

    private func responsePanel(_ response: CloudflareRawResponse) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 9) {
                Image(systemName: responseIsSuccess(response) ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .foregroundStyle(responseColor(response))
                Text("HTTP \(response.statusCode)")
                    .font(.system(size: 13, weight: .heavy, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.82))
                if let elapsedMilliseconds = viewModel.elapsedMilliseconds {
                    Text("\(elapsedMilliseconds) ms")
                        .font(.system(size: 10, weight: .semibold).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.3))
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = response.prettyPrintedBody
                    copied = true
                    Task {
                        try? await Task.sleep(for: .seconds(1.5))
                        copied = false
                    }
                } label: {
                    Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(copied ? CloudflareStyle.green : CloudflareStyle.orange)
                }
                .buttonStyle(.plain)
            }
            .padding(16)

            Divider().overlay(Color.white.opacity(0.06))

            ScrollView([.horizontal, .vertical], showsIndicators: true) {
                Text(response.prettyPrintedBody.isEmpty ? "<empty response body>" : response.prettyPrintedBody)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                    .textSelection(.enabled)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 120, maxHeight: 520)

            Divider().overlay(Color.white.opacity(0.06))

            Button {
                withAnimation(.easeInOut(duration: 0.18)) { showingHeaders.toggle() }
            } label: {
                HStack {
                    Text("RESPONSE HEADERS · \(response.headers.count)")
                        .font(.system(size: 9, weight: .heavy))
                        .tracking(0.7)
                        .foregroundStyle(.white.opacity(0.34))
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(.white.opacity(0.3))
                        .rotationEffect(.degrees(showingHeaders ? 180 : 0))
                }
                .padding(16)
            }
            .buttonStyle(.plain)

            if showingHeaders {
                VStack(alignment: .leading, spacing: 7) {
                    ForEach(response.headers.sorted(by: { $0.key < $1.key }), id: \.key) { key, value in
                        Text("\(key): \(value)")
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.42))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
        }
        .cloudflarePanel(accentOpacity: responseIsSuccess(response) ? 0.04 : 0)
    }

    private var confirmationTitle: String {
        viewModel.method == .delete ? "Send a destructive API request?" : "Send a write API request?"
    }

    private var normalizedDisplayPath: String {
        let path = viewModel.path.hasPrefix("/") ? viewModel.path : "/\(viewModel.path)"
        return "/client/v4\(path)"
    }

    private var methodColor: Color { color(for: viewModel.method) }

    private func color(for method: CloudflareHTTPMethod) -> Color {
        switch method {
        case .get: CloudflareStyle.green
        case .post: CloudflareStyle.orange
        case .put, .patch: CloudflareStyle.amber
        case .delete: CloudflareStyle.red
        }
    }

    private func responseIsSuccess(_ response: CloudflareRawResponse) -> Bool {
        (200...299).contains(response.statusCode)
    }

    private func responseColor(_ response: CloudflareRawResponse) -> Color {
        responseIsSuccess(response) ? CloudflareStyle.green : CloudflareStyle.red
    }

    private func importBodyFile(_ result: Result<URL, Error>) {
        do {
            let url = try result.get()
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url, options: [.mappedIfSafe])
            viewModel.requestBody = data.base64EncodedString(options: [.lineLength64Characters])
            viewModel.bodyEncoding = .base64
            if viewModel.contentType == "application/json" {
                viewModel.contentType = "application/octet-stream"
            }
            viewModel.error = nil
        } catch {
            viewModel.error = "Could not import the request body: \(error.localizedDescription)"
        }
    }
}

private enum CloudflareExplorerError: LocalizedError {
    case invalidQuery(String)
    case invalidHeader(String)

    var errorDescription: String? {
        switch self {
        case .invalidQuery(let line):
            "Query parameter “\(line)” must use key=value format."
        case .invalidHeader(let line):
            "Request header “\(line)” must use Name: value format."
        }
    }
}
