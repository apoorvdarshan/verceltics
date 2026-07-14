import SwiftUI

enum CloudflareStyle {
    static let orange = Color(red: 0.96, green: 0.42, blue: 0.12)
    static let amber = Color(red: 1.00, green: 0.65, blue: 0.20)
    static let green = AppTheme.success
    static let red = AppTheme.danger
}

struct CloudflarePanelModifier: ViewModifier {
    var accentOpacity: Double = 0

    @ViewBuilder
    func body(content: Content) -> some View {
        if accentOpacity > 0 {
            content
                .background {
                    LinearGradient(
                        colors: [CloudflareStyle.orange.opacity(accentOpacity), .clear],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }
                .appSurface()
        } else {
            content.appSurface()
        }
    }
}

extension View {
    func cloudflarePanel(accentOpacity: Double = 0) -> some View {
        modifier(CloudflarePanelModifier(accentOpacity: accentOpacity))
    }
}

struct CloudflareSectionHeader: View {
    let title: String
    let icon: String
    var count: Int?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack(spacing: 8) {
            AppIconTile(icon: icon, tint: CloudflareStyle.orange, size: 28)

            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
                .lineLimit(2)

            if let count {
                Text(count.formatted())
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.textSecondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(Capsule())
            }

            Spacer(minLength: 8)

            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .buttonStyle(.plain)
                    .frame(minHeight: 44)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

struct CloudflareResourceRow<Trailing: View>: View {
    let icon: String
    let title: String
    let subtitle: String?
    var tint: Color = CloudflareStyle.orange
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            AppIconTile(icon: icon, tint: tint)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)

                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }
            }

            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }
}

struct CloudflareChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.caption.weight(.semibold))
            .foregroundStyle(AppTheme.textTertiary)
    }
}

extension CloudflareResourceRow where Trailing == CloudflareChevron {
    init(icon: String, title: String, subtitle: String?, tint: Color = CloudflareStyle.orange) {
        self.init(icon: icon, title: title, subtitle: subtitle, tint: tint) {
            CloudflareChevron()
        }
    }
}

struct CloudflareStatusPill: View {
    let text: String
    var color: Color

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(color)
                .frame(width: 5, height: 5)
            Text(text)
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(color.opacity(0.09))
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(color.opacity(0.18), lineWidth: 0.5))
    }
}

struct CloudflareMetricCard: View {
    let title: String
    let value: String
    let icon: String
    var accent: Color = CloudflareStyle.orange

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption2.weight(.semibold))
                Text(title.uppercased())
                    .font(.caption2.weight(.semibold))
                    .tracking(0.8)
            }
            .foregroundStyle(AppTheme.textSecondary)

            Text(value)
                .font(.title3.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .contentTransition(.numericText())

            Capsule()
                .fill(accent.opacity(0.7))
                .frame(width: 24, height: 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cloudflarePanel(accentOpacity: 0.045)
    }
}

struct CloudflareEmptySection: View {
    let icon: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 9) {
            AppIconTile(icon: icon, tint: AppTheme.textTertiary, size: 40)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.textPrimary)
            Text(message)
                .font(.footnote)
                .foregroundStyle(AppTheme.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
    }
}

struct CloudflareLoadingView: View {
    var body: some View {
        AppDashboardLoadingView(accent: CloudflareStyle.orange)
    }
}

struct CloudflareErrorView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        AppEmptyState(
            icon: "exclamationmark.triangle.fill",
            title: "Cloudflare couldn’t load",
            message: message,
            actionTitle: "Try again",
            action: retry
        )
    }
}

struct CloudflareEdgeHeader: View {
    let accountName: String
    let email: String
    let zones: Int
    let pages: Int
    let workers: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 13) {
                ProviderMark(provider: .cloudflare, size: 25)
                    .frame(width: 46, height: 46)
                    .background(CloudflareStyle.orange.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous))
                .frame(width: 46, height: 46)

                VStack(alignment: .leading, spacing: 4) {
                    Text(accountName)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(2)
                    Text(email)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                AppStatusBadge(text: "Connected", tone: .success)
            }

            HStack(spacing: 0) {
                edgeNode(value: zones, title: "ZONES", icon: "globe")
                Divider().overlay(AppTheme.stroke).padding(.vertical, 4)
                edgeNode(value: pages, title: "PAGES", icon: "doc.badge.gearshape")
                Divider().overlay(AppTheme.stroke).padding(.vertical, 4)
                edgeNode(value: workers, title: "WORKERS", icon: "shippingbox.fill")
            }
        }
        .padding(18)
        .providerSurface(accent: CloudflareStyle.orange)
    }

    private func edgeNode(value: Int, title: String, icon: String) -> some View {
        VStack(spacing: 5) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.caption2.weight(.semibold))
                Text(value.formatted())
                    .font(.headline.monospacedDigit())
            }
            .foregroundStyle(AppTheme.textPrimary)
            Text(title)
                .font(.caption2.weight(.semibold))
                .tracking(0.7)
                .foregroundStyle(AppTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

}

struct CloudflareWriteNotice: View {
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)
            VStack(alignment: .leading, spacing: 3) {
                Text("Write access is guarded")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Changes use the connected Cloudflare credential. Destructive actions always ask for confirmation.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(CloudflareStyle.orange.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(CloudflareStyle.orange.opacity(0.12), lineWidth: 0.5)
        )
    }
}

struct CloudflareActionButton: View {
    let title: String
    let icon: String
    var role: ButtonRole?
    var isWorking = false
    let action: () -> Void

    private var tint: Color {
        role == .destructive ? CloudflareStyle.red : CloudflareStyle.orange
    }

    var body: some View {
        Button(role: role, action: action) {
            HStack(spacing: 7) {
                if isWorking {
                    ProgressView()
                        .controlSize(.small)
                        .tint(tint)
                } else {
                    Image(systemName: icon)
                        .font(.system(size: 10, weight: .semibold))
                }
                Text(title)
                    .font(.footnote.weight(.semibold))
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(tint.opacity(0.10))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(tint.opacity(0.16), lineWidth: 0.5))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(isWorking)
    }
}

struct CloudflareActionResultBanner: View {
    let message: String
    var isError = false

    private var tint: Color { isError ? CloudflareStyle.red : CloudflareStyle.green }

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(tint)
            Text(message)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AppTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(tint.opacity(0.075))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(tint.opacity(0.13), lineWidth: 0.5)
        )
    }
}

struct CloudflareDetailRow: View {
    let icon: String
    let title: String
    let value: String
    var valueColor: Color = AppTheme.textPrimary

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textTertiary)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 2) {
                Text(title.uppercased())
                    .font(.caption2.weight(.semibold))
                    .tracking(0.7)
                    .foregroundStyle(AppTheme.textSecondary)
                Text(value)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(valueColor)
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
            }

            Spacer(minLength: 8)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }
}

struct CloudflareSearchEmptyView: View {
    let searchText: String

    var body: some View {
        CloudflareEmptySection(
            icon: "magnifyingglass",
            title: "No matches",
            message: "Nothing in this Cloudflare account matches “\(searchText)”."
        )
        .cloudflarePanel()
    }
}
