import SwiftUI

struct StatCard: View {
    let title: String
    let value: String
    var change: Double?
    var invertChange: Bool = false
    let icon: String

    private var isPositive: Bool {
        guard let change else { return true }
        return invertChange ? change <= 0 : change >= 0
    }

    private var changeColor: Color {
        guard change != nil else { return .gray }
        return isPositive ? .green : .red
    }

    private var changeText: String? {
        guard let change else { return nil }
        let prefix = change >= 0 ? "+" : ""
        return "\(prefix)\(String(format: "%.0f", change))%"
    }

    private var changeIcon: String {
        guard let change else { return "minus" }
        return change >= 0 ? "arrow.up.right" : "arrow.down.right"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(title.uppercased())
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(0.8)
                        .foregroundStyle(.white.opacity(0.52))

                    Text(value)
                        .font(.system(size: 30, weight: .bold).monospacedDigit())
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }

                Spacer(minLength: 12)

                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.92))
                    .frame(width: 34, height: 34)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [
                                        .white.opacity(0.16),
                                        .white.opacity(0.05)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            }

            if let changeText {
                HStack(spacing: 6) {
                    Image(systemName: changeIcon)
                        .font(.system(size: 10, weight: .bold))
                    Text(changeText)
                        .font(.system(size: 11, weight: .bold).monospacedDigit())
                }
                .foregroundStyle(changeColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(changeColor.opacity(0.14))
                .clipShape(Capsule())
            } else {
                Text("No comparison")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.11),
                            Color.blue.opacity(0.08),
                            Color.white.opacity(0.04)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.26),
                            Color.blue.opacity(0.24),
                            Color.white.opacity(0.08)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: .black.opacity(0.42), radius: 22, x: 0, y: 14)
        .shadow(color: .blue.opacity(0.08), radius: 18, x: 0, y: 8)
    }
}
