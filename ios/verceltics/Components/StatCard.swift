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
        return isPositive ? Color(red: 0.30, green: 0.85, blue: 0.55) : Color(red: 1.0, green: 0.42, blue: 0.42)
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
                    .font(.system(size: 9, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.35))
                Text(title.uppercased())
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.45))
                    .tracking(0.8)
            }

            Text(value)
                .font(.system(size: 28, weight: .heavy, design: .rounded).monospacedDigit())
                .foregroundStyle(.white)
                .contentTransition(.numericText())
                .minimumScaleFactor(0.7)
                .lineLimit(1)

            if let changeText {
                HStack(spacing: 3) {
                    Image(systemName: isPositive ? "arrow.up.right" : "arrow.down.right")
                        .font(.system(size: 8, weight: .heavy))
                    Text(changeText)
                        .font(.system(size: 11, weight: .heavy).monospacedDigit())
                }
                .foregroundStyle(changeColor)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(changeColor.opacity(0.14))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(changeColor.opacity(0.18), lineWidth: 0.5))
            } else {
                Text("—")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white.opacity(0.2))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            ZStack {
                LinearGradient(
                    colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [Color.white.opacity(0.04), .clear],
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
                        colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                        startPoint: .top, endPoint: .bottom
                    ),
                    lineWidth: 0.5
                )
        )
    }
}
