import SwiftUI

struct StatCard: View {
    let title: String
    let value: String
    var change: Double?
    var invertChange: Bool = false
    let icon: String
    var appearDelay: Double = 0

    private var isPositive: Bool {
        guard let change else { return true }
        return invertChange ? change <= 0 : change >= 0
    }

    private var changeColor: Color {
        guard change != nil else { return AppTheme.textTertiary }
        return isPositive ? AppTheme.success : AppTheme.danger
    }

    private var changeText: String? {
        guard let change else { return nil }
        let prefix = change >= 0 ? "+" : ""
        return "\(prefix)\(String(format: "%.0f", change))%"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
                Text(title.uppercased())
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AppTheme.textSecondary)
                    .tracking(0.8)
            }

            Text(value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
                .contentTransition(.numericText())
                .minimumScaleFactor(0.7)
                .lineLimit(1)

            if let changeText {
                HStack(spacing: 3) {
                    Image(systemName: isPositive ? "arrow.up.right" : "arrow.down.right")
                        .font(.caption2.weight(.semibold))
                    Text(changeText)
                        .font(.caption.weight(.semibold).monospacedDigit())
                }
                .foregroundStyle(changeColor)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(changeColor.opacity(0.14))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(changeColor.opacity(0.18), lineWidth: 0.5))
            } else {
                Text("—")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .appSurface()
    }
}
