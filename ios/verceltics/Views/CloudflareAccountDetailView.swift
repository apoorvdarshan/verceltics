import SwiftUI

struct CloudflareAccountDetailView: View {
    let api: CloudflareAPI
    let account: CloudflareAccountSummary
    let email: String
    let zoneCount: Int
    let pagesCount: Int
    let workerCount: Int

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    CloudflareEdgeHeader(
                        accountName: account.name,
                        email: email,
                        zones: zoneCount,
                        pages: pagesCount,
                        workers: workerCount
                    )

                    operationsLink
                    accountPanel
                    securityPanel
                    resourcePanel
                }
                .padding()
                .frame(maxWidth: horizontalSizeClass == .regular ? 760 : .infinity)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Account")
        .navigationBarTitleDisplayMode(.inline)
        .tint(CloudflareStyle.orange)
    }

    private var operationsLink: some View {
        NavigationLink {
            CloudflareAccountOperationsView(api: api, account: account, email: email)
        } label: {
            CloudflareResourceRow(
                icon: "switch.2",
                title: "Account operations",
                subtitle: "Members, roles, permissions, policies and audit logs",
                tint: CloudflareStyle.orange
            )
        }
        .buttonStyle(.plain)
        .cloudflarePanel(accentOpacity: 0.07)
    }

    private var accountPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Identity", icon: "building.2.fill")
            Divider().overlay(AppTheme.divider)
            CloudflareDetailRow(icon: "number", title: "Account ID", value: account.id)
            CloudflareDetailRow(icon: "building.2", title: "Name", value: account.name)
            CloudflareDetailRow(icon: "person.text.rectangle", title: "Type", value: account.type?.capitalized ?? "Standard")
            CloudflareDetailRow(icon: "envelope.fill", title: "Global API user", value: email)
            if let createdDate = account.createdDate {
                CloudflareDetailRow(
                    icon: "calendar",
                    title: "Created",
                    value: createdDate.formatted(date: .abbreviated, time: .shortened)
                )
            }
        }
        .cloudflarePanel()
    }

    private var securityPanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Security policy", icon: "lock.shield.fill")
            Divider().overlay(AppTheme.divider)
            CloudflareDetailRow(
                icon: "person.badge.shield.checkmark.fill",
                title: "Enforce two-factor authentication",
                value: twoFactorText,
                valueColor: account.settings?.enforceTwoFactor == true
                    ? CloudflareStyle.green
                    : AppTheme.textPrimary
            )
            CloudflareDetailRow(
                icon: "exclamationmark.bubble.fill",
                title: "Abuse contact",
                value: account.settings?.abuseContactEmail ?? "Not returned"
            )
        }
        .cloudflarePanel()
    }

    private var resourcePanel: some View {
        VStack(spacing: 0) {
            CloudflareSectionHeader(title: "Resource inventory", icon: "square.grid.2x2.fill")
            Divider().overlay(AppTheme.divider)
            CloudflareResourceRow(
                icon: "globe.americas.fill",
                title: "Zones",
                subtitle: "Domains, DNS, analytics and cache",
                tint: CloudflareStyle.orange
            ) {
                countPill(zoneCount)
            }
            Divider().overlay(AppTheme.divider).padding(.leading, 64)
            CloudflareResourceRow(
                icon: "doc.badge.gearshape.fill",
                title: "Pages projects",
                subtitle: "Sites and deployment history",
                tint: CloudflareStyle.amber
            ) {
                countPill(pagesCount)
            }
            Divider().overlay(AppTheme.divider).padding(.leading, 64)
            CloudflareResourceRow(
                icon: "shippingbox.fill",
                title: "Workers",
                subtitle: "Edge scripts and deployments",
                tint: CloudflareStyle.green
            ) {
                countPill(workerCount)
            }
        }
        .cloudflarePanel()
    }

    private var twoFactorText: String {
        guard let value = account.settings?.enforceTwoFactor else { return "Not returned" }
        return value ? "Required" : "Not required"
    }

    private func countPill(_ value: Int) -> some View {
        Text(value.formatted())
            .font(.system(size: 11, weight: .semibold).monospacedDigit())
            .foregroundStyle(AppTheme.textSecondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(AppTheme.divider)
            .clipShape(Capsule())
    }
}
