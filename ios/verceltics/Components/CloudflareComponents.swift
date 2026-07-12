import SwiftUI

enum CloudflareStyle {
    static let orange = Color(red: 0.96, green: 0.42, blue: 0.12)
    static let amber = Color(red: 1.00, green: 0.65, blue: 0.20)
    static let green = Color(red: 0.30, green: 0.85, blue: 0.55)
    static let red = Color(red: 1.00, green: 0.35, blue: 0.35)
}

struct CloudflarePanelModifier: ViewModifier {
    var accentOpacity: Double = 0.035

    func body(content: Content) -> some View {
        content
            .background(
                ZStack {
                    LinearGradient(
                        colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    LinearGradient(
                        colors: [CloudflareStyle.orange.opacity(accentOpacity), .clear],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    LinearGradient(
                        colors: [Color.white.opacity(0.035), .clear],
                        startPoint: .top,
                        endPoint: .center
                    )
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.13), Color.white.opacity(0.04)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 0.5
                    )
            )
    }
}

extension View {
    func cloudflarePanel(accentOpacity: Double = 0.035) -> some View {
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
            Image(systemName: icon)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(CloudflareStyle.orange)
                .frame(width: 22, height: 22)
                .background(CloudflareStyle.orange.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.white)

            if let count {
                Text(count.formatted())
                    .font(.system(size: 10, weight: .heavy).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.4))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.white.opacity(0.06))
                    .clipShape(Capsule())
            }

            Spacer(minLength: 8)

            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(CloudflareStyle.orange)
                    .buttonStyle(.plain)
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
            Image(systemName: icon)
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(tint)
                .frame(width: 36, height: 36)
                .background(tint.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white.opacity(0.88))
                    .lineLimit(1)

                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.38))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

struct CloudflareChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 10, weight: .heavy))
            .foregroundStyle(.white.opacity(0.2))
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
        Text(text)
            .font(.system(size: 9, weight: .heavy))
            .foregroundStyle(color)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.12))
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(color.opacity(0.2), lineWidth: 0.5))
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
                    .font(.system(size: 9, weight: .heavy))
                Text(title.uppercased())
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
            }
            .foregroundStyle(.white.opacity(0.42))

            Text(value)
                .font(.system(size: 24, weight: .heavy, design: .rounded).monospacedDigit())
                .foregroundStyle(.white)
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
            Image(systemName: icon)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white.opacity(0.22))
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white.opacity(0.6))
            Text(message)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.32))
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
        ScrollView {
            VStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 20)
                    .fill(Color.white.opacity(0.05))
                    .frame(height: 148)
                HStack(spacing: 10) {
                    ForEach(0..<3, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 18)
                            .fill(Color.white.opacity(0.05))
                            .frame(height: 104)
                    }
                }
                ForEach(0..<4, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 18)
                        .fill(Color.white.opacity(0.045))
                        .frame(height: 88)
                }
            }
            .padding()
            .shimmering()
        }
    }
}

struct CloudflareErrorView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(CloudflareStyle.orange)

            Text("Cloudflare couldn’t load")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(.white)

            Text(message)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.42))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Button("Try Again", action: retry)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.black)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(CloudflareStyle.orange)
                .clipShape(Capsule())
                .buttonStyle(PressScaleButtonStyle())
        }
        .padding(28)
        .frame(maxWidth: 420)
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
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [CloudflareStyle.orange, CloudflareStyle.amber],
                                startPoint: .bottomLeading,
                                endPoint: .topTrailing
                            )
                        )
                    Image(systemName: "cloud.fill")
                        .font(.system(size: 21, weight: .black))
                        .foregroundStyle(.black.opacity(0.82))
                }
                .frame(width: 46, height: 46)
                .shadow(color: CloudflareStyle.orange.opacity(0.22), radius: 12, y: 5)

                VStack(alignment: .leading, spacing: 4) {
                    Text(accountName)
                        .font(.system(size: 20, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(email)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.36))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                CloudflareStatusPill(text: "CONNECTED", color: CloudflareStyle.green)
            }

            HStack(spacing: 0) {
                edgeNode(value: zones, title: "ZONES", icon: "globe")
                edgeLink
                edgeNode(value: pages, title: "PAGES", icon: "doc.badge.gearshape")
                edgeLink
                edgeNode(value: workers, title: "WORKERS", icon: "shippingbox.fill")
            }
        }
        .padding(18)
        .cloudflarePanel(accentOpacity: 0.09)
    }

    private func edgeNode(value: Int, title: String, icon: String) -> some View {
        VStack(spacing: 5) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 8, weight: .heavy))
                Text(value.formatted())
                    .font(.system(size: 16, weight: .heavy, design: .rounded).monospacedDigit())
            }
            .foregroundStyle(.white.opacity(0.88))
            Text(title)
                .font(.system(size: 8, weight: .heavy))
                .tracking(0.7)
                .foregroundStyle(.white.opacity(0.3))
        }
        .frame(minWidth: 62)
    }

    private var edgeLink: some View {
        HStack(spacing: 3) {
            Circle().fill(CloudflareStyle.orange.opacity(0.65)).frame(width: 3, height: 3)
            Rectangle().fill(CloudflareStyle.orange.opacity(0.22)).frame(height: 1)
            Circle().fill(CloudflareStyle.orange.opacity(0.65)).frame(width: 3, height: 3)
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
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white.opacity(0.78))
                Text("Changes use your Global API Key. Destructive actions always ask for confirmation.")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.34))
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
                        .font(.system(size: 10, weight: .heavy))
                }
                Text(title)
                    .font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
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
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white.opacity(0.72))
                .lineLimit(2)
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
    var valueColor: Color = .white.opacity(0.76)

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(.white.opacity(0.32))
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 2) {
                Text(title.uppercased())
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.7)
                    .foregroundStyle(.white.opacity(0.32))
                Text(value)
                    .font(.system(size: 13, weight: .semibold))
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
