import SwiftUI

@Observable
@MainActor
final class CloudflareR2BucketViewModel {
    let api: CloudflareAPI
    let accountID: String
    let bucketName: String
    let jurisdiction: String?

    var bucket: CloudflareR2Bucket
    var isLoading = true
    var isDeleting = false
    var didDelete = false
    var actionMessage: String?
    var actionFailed = false

    init(api: CloudflareAPI, accountID: String, bucket: CloudflareR2Bucket) {
        self.api = api
        self.accountID = accountID
        bucketName = bucket.name
        jurisdiction = bucket.jurisdiction
        self.bucket = bucket
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            bucket = try await api.fetchR2Bucket(
                accountID: accountID,
                bucketName: bucketName,
                jurisdiction: jurisdiction
            )
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
    }

    func delete() async {
        isDeleting = true
        defer { isDeleting = false }
        do {
            try await api.deleteR2Bucket(
                accountID: accountID,
                bucketName: bucketName,
                jurisdiction: jurisdiction,
                confirmation: CloudflareMutationConfirmation(confirmingResourceID: bucketName)
            )
            didDelete = true
        } catch {
            actionMessage = error.localizedDescription
            actionFailed = true
        }
    }
}

struct CloudflareR2BucketView: View {
    let api: CloudflareAPI
    let accountID: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel: CloudflareR2BucketViewModel
    @State private var isConfirmingDelete = false

    init(api: CloudflareAPI, accountID: String, bucket: CloudflareR2Bucket) {
        self.api = api
        self.accountID = accountID
        _viewModel = State(wrappedValue: CloudflareR2BucketViewModel(api: api, accountID: accountID, bucket: bucket))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    header
                    metadata
                    CloudflareWriteNotice()

                    if let message = viewModel.actionMessage {
                        CloudflareActionResultBanner(message: message, isError: viewModel.actionFailed)
                    }

                    objectScopeNotice
                    dangerZone
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 850 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(viewModel.bucket.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .onChange(of: viewModel.didDelete) { _, deleted in if deleted { dismiss() } }
        .confirmationDialog("Delete this R2 bucket?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete Bucket", role: .destructive) { Task { await viewModel.delete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Cloudflare only deletes an empty bucket. The bucket and all of its configuration will be permanently removed.")
        }
        .tint(CloudflareStyle.orange)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 13) {
                Image(systemName: "shippingbox.fill")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(.black.opacity(0.82))
                    .frame(width: 46, height: 46)
                    .background(
                        LinearGradient(
                            colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                            startPoint: .bottomLeading,
                            endPoint: .topTrailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.bucket.name)
                        .font(.system(size: 21, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text("Cloudflare R2 bucket")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.38))
                }
                Spacer(minLength: 8)
                CloudflareStatusPill(text: "AVAILABLE", color: CloudflareStyle.green)
            }

            if let dashboardURL = URL(
                string: "https://dash.cloudflare.com/\(accountID)/r2/\(viewModel.bucket.jurisdiction ?? "default")/buckets/\(viewModel.bucket.name)"
            ) {
                Button {
                    UIApplication.shared.open(dashboardURL)
                } label: {
                    Label("Open Cloudflare Dashboard", systemImage: "arrow.up.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(CloudflareStyle.orange)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(CloudflareStyle.orange.opacity(0.10))
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.08)
    }

    private var metadata: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Bucket Metadata", icon: "info.circle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareDetailRow(icon: "shippingbox", title: "Name", value: viewModel.bucket.name)
            CloudflareDetailRow(
                icon: "globe",
                title: "Jurisdiction",
                value: viewModel.bucket.jurisdiction?.uppercased() ?? "DEFAULT"
            )
            CloudflareDetailRow(
                icon: "mappin.and.ellipse",
                title: "Location",
                value: viewModel.bucket.location?.uppercased() ?? "Automatic"
            )
            CloudflareDetailRow(
                icon: "archivebox.fill",
                title: "Default storage class",
                value: storageClassLabel(viewModel.bucket.storageClass)
            )
            if let date = viewModel.bucket.createdDate {
                CloudflareDetailRow(
                    icon: "calendar",
                    title: "Created",
                    value: date.formatted(date: .abbreviated, time: .shortened)
                )
            }
        }
        .cloudflarePanel()
    }

    private var objectScopeNotice: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Object Data", icon: "doc.on.doc.fill")
            Divider().overlay(Color.white.opacity(0.06))
            CloudflareEmptySection(
                icon: "externaldrive.badge.icloud",
                title: "Bucket management only",
                message: "This account-key screen manages the bucket resource and its returned metadata. Use Cloudflare’s object tooling for transfers and object data."
            )
        }
        .cloudflarePanel()
    }

    private var dangerZone: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Danger Zone", icon: "exclamationmark.triangle.fill")
            Divider().overlay(Color.white.opacity(0.06))
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Delete bucket")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white.opacity(0.76))
                    Text("The bucket must be empty before Cloudflare will delete it.")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.34))
                }
                Spacer(minLength: 8)
                CloudflareActionButton(
                    title: "Delete",
                    icon: "trash.fill",
                    role: .destructive,
                    isWorking: viewModel.isDeleting,
                    action: { isConfirmingDelete = true }
                )
            }
            .padding(16)
        }
        .cloudflarePanel(accentOpacity: 0.06)
    }

    private func storageClassLabel(_ value: String?) -> String {
        switch value {
        case "InfrequentAccess": "Infrequent Access"
        case let value?: value
        case nil: "Standard"
        }
    }
}
