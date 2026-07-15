import SwiftUI
import UniformTypeIdentifiers

private let cloudflareMultipartUploadLimit = 25 * 1_024 * 1_024
private let cloudflareMultipartPartLimit = 100

private struct CloudflareMultipartPart: Identifiable, Equatable {
    let id: UUID
    var name: String
    var value: String
    var isFile: Bool
    var isRequired: Bool
    var fileName: String?
    var mimeType: String?
    var fileData: Data?

    init(
        id: UUID = UUID(),
        name: String,
        value: String = "",
        isFile: Bool = false,
        isRequired: Bool = false,
        fileName: String? = nil,
        mimeType: String? = nil,
        fileData: Data? = nil
    ) {
        self.id = id
        self.name = name
        self.value = value
        self.isFile = isFile
        self.isRequired = isRequired
        self.fileName = fileName
        self.mimeType = mimeType
        self.fileData = fileData
    }
}

struct CloudflareMultipartComposerView: View {
    let schemaFields: [CloudflareOpenAPIMultipartField]
    let onCompose: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var parts: [CloudflareMultipartPart]
    @State private var importingPartID: UUID?
    @State private var error: String?

    init(
        schemaFields: [CloudflareOpenAPIMultipartField],
        onCompose: @escaping (String, String) -> Void
    ) {
        self.schemaFields = schemaFields
        self.onCompose = onCompose
        _parts = State(
            initialValue: schemaFields.map {
                CloudflareMultipartPart(
                    name: $0.name,
                    value: $0.suggestedValue,
                    isFile: $0.isFile,
                    isRequired: $0.required
                )
            }
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    composerHeader
                    if let error {
                        CloudflareActionResultBanner(message: error, isError: true)
                    }
                    fieldsPanel
                    addFieldButton
                    composeButton
                }
                .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
                .padding(.vertical, 16)
                .appContentWidth(AppLayout.formMaxWidth, horizontalSizeClass: horizontalSizeClass)
            }
        }
        .navigationTitle("Multipart Body")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .fileImporter(
            isPresented: Binding(
                get: { importingPartID != nil },
                set: { if !$0 { importingPartID = nil } }
            ),
            allowedContentTypes: [.data]
        ) { result in
            importFile(result)
        }
        .tint(CloudflareStyle.orange)
    }

    private var composerHeader: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "shippingbox.and.arrow.backward.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.black.opacity(0.82))
                .frame(width: 43, height: 43)
                .background(CloudflareStyle.orange, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 4) {
                Text("Build the upload on-device")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Files are read locally, encoded into the request, and never stored by the app.")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            Spacer()
        }
        .padding(16)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var fieldsPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Form Fields", icon: "list.bullet.rectangle.fill", count: parts.count)
            Divider().overlay(AppTheme.divider)
            ForEach(Array(parts.enumerated()), id: \.element.id) { index, part in
                partEditor(part)
                if index < parts.count - 1 {
                    Divider().overlay(AppTheme.strokeSoft).padding(.leading, 16)
                }
            }
        }
        .cloudflarePanel()
    }

    private func partEditor(_ part: CloudflareMultipartPart) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                Image(systemName: part.isFile ? "doc.fill" : "text.cursor")
                    .foregroundStyle(CloudflareStyle.orange)
                TextField("Field name", text: stringBinding(part.id, \.name))
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if part.isRequired {
                    Text("REQUIRED")
                        .font(.system(size: 7, weight: .semibold))
                        .foregroundStyle(CloudflareStyle.red.opacity(0.8))
                }
                if !part.isRequired {
                    Button(role: .destructive) { parts.removeAll { $0.id == part.id } } label: {
                        Image(systemName: "trash")
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            if part.isFile {
                Button {
                    importingPartID = part.id
                } label: {
                    HStack {
                        Image(systemName: part.fileData == nil ? "doc.badge.plus" : "checkmark.circle.fill")
                        VStack(alignment: .leading, spacing: 2) {
                            Text(part.fileName ?? "Choose file")
                                .font(.system(size: 11, weight: .bold))
                            if let data = part.fileData {
                                Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                                    .font(.system(size: 9, weight: .medium))
                                    .foregroundStyle(AppTheme.textTertiary)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                    }
                    .foregroundStyle(part.fileData == nil ? CloudflareStyle.orange : CloudflareStyle.green)
                    .padding(12)
                    .frame(minHeight: 44)
                    .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            } else {
                TextField("Field value", text: stringBinding(part.id, \.value), axis: .vertical)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(1...6)
                    .padding(11)
                    .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(14)
    }

    private var addFieldButton: some View {
        Button {
            guard parts.count < cloudflareMultipartPartLimit else {
                error = "Multipart requests support up to \(cloudflareMultipartPartLimit) fields."
                return
            }
            parts.append(.init(name: "", isRequired: false))
        } label: {
            Label("Add custom field", systemImage: "plus.circle.fill")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)
                .frame(maxWidth: .infinity)
                .frame(minHeight: 48)
                .background(CloudflareStyle.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 11))
        }
        .buttonStyle(.plain)
        .disabled(parts.count >= cloudflareMultipartPartLimit)
        .opacity(parts.count >= cloudflareMultipartPartLimit ? 0.45 : 1)
    }

    private var composeButton: some View {
        Button {
            compose()
        } label: {
            Label("Use multipart body", systemImage: "checkmark.circle.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.black.opacity(0.84))
                .frame(maxWidth: .infinity)
                .frame(minHeight: 50)
                .background(CloudflareStyle.orange, in: RoundedRectangle(cornerRadius: 13))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func stringBinding(
        _ id: UUID,
        _ keyPath: WritableKeyPath<CloudflareMultipartPart, String>
    ) -> Binding<String> {
        Binding(
            get: {
                guard let part = parts.first(where: { $0.id == id }) else { return "" }
                return part[keyPath: keyPath]
            },
            set: { value in
                guard let index = parts.firstIndex(where: { $0.id == id }) else { return }
                parts[index][keyPath: keyPath] = value
            }
        )
    }

    private func importFile(_ result: Result<URL, Error>) {
        guard let id = importingPartID else { return }
        importingPartID = nil
        do {
            let url = try result.get()
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let existingPayloadBytes = parts.reduce(0) { partial, part in
                guard part.id != id else { return partial }
                return partial + (part.fileData?.count ?? part.value.lengthOfBytes(using: .utf8))
            }
            let remainingBytes = max(0, cloudflareMultipartUploadLimit - existingPayloadBytes)
            if let fileSize = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
               fileSize > remainingBytes {
                throw CloudflareMultipartError.payloadTooLarge
            }
            let data = try Data(contentsOf: url, options: [.mappedIfSafe])
            guard data.count <= remainingBytes else {
                throw CloudflareMultipartError.payloadTooLarge
            }
            guard let index = parts.firstIndex(where: { $0.id == id }) else { return }
            parts[index].fileData = data
            parts[index].fileName = url.lastPathComponent
            parts[index].mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            error = nil
        } catch {
            self.error = "Could not read that file: \(error.localizedDescription)"
        }
    }

    private func compose() {
        let usedParts = parts.filter {
            !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                ($0.isFile ? $0.fileData != nil : !$0.value.isEmpty)
        }
        let missing = parts.filter {
            $0.isRequired && ($0.name.isEmpty || ($0.isFile ? $0.fileData == nil : $0.value.isEmpty))
        }
        guard missing.isEmpty else {
            error = "Add values for required fields: \(missing.map(\.name).joined(separator: ", "))."
            return
        }
        guard !usedParts.isEmpty else {
            error = "Add at least one form field or file."
            return
        }
        guard usedParts.count <= cloudflareMultipartPartLimit else {
            error = "Multipart requests support up to \(cloudflareMultipartPartLimit) fields."
            return
        }

        var payloadBytes = 0
        for part in usedParts {
            let partBytes = part.fileData?.count ?? part.value.lengthOfBytes(using: .utf8)
            guard partBytes <= cloudflareMultipartUploadLimit - payloadBytes else {
                error = "The combined multipart body must be 25 MB or smaller."
                return
            }
            payloadBytes += partBytes
        }

        let boundary = "Verceltics-\(UUID().uuidString)"
        var data = Data()
        for part in usedParts {
            data.appendUTF8("--\(boundary)\r\n")
            if let fileData = part.fileData {
                let fileName = safeHeaderValue(part.fileName ?? "upload.bin")
                data.appendUTF8("Content-Disposition: form-data; name=\"\(safeHeaderValue(part.name))\"; filename=\"\(fileName)\"\r\n")
                data.appendUTF8("Content-Type: \(safeHeaderValue(part.mimeType ?? "application/octet-stream"))\r\n\r\n")
                data.append(fileData)
                data.appendUTF8("\r\n")
            } else {
                data.appendUTF8("Content-Disposition: form-data; name=\"\(safeHeaderValue(part.name))\"\r\n\r\n")
                data.appendUTF8(part.value)
                data.appendUTF8("\r\n")
            }
        }
        data.appendUTF8("--\(boundary)--\r\n")
        guard data.count <= cloudflareMultipartUploadLimit else {
            error = "The combined multipart body must be 25 MB or smaller."
            return
        }
        onCompose(data.base64EncodedString(), "multipart/form-data; boundary=\(boundary)")
        dismiss()
    }

    private func safeHeaderValue(_ value: String) -> String {
        value.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
    }
}

private enum CloudflareMultipartError: LocalizedError {
    case payloadTooLarge

    var errorDescription: String? {
        "The combined multipart body must be 25 MB or smaller."
    }
}

private extension Data {
    mutating func appendUTF8(_ value: String) {
        if let data = value.data(using: .utf8) { append(data) }
    }
}
