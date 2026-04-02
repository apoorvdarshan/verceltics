import SwiftUI

struct StatCard: View {
    let title: String
    let value: String
    var change: Double?
    var invertChange: Bool = false
    let icon: String

    private var changeColor: Color {
        guard let change else { return .gray }
        let isPositive = invertChange ? change < 0 : change > 0
        return isPositive ? .green : .red
    }

    private var changeText: String? {
        guard let change else { return nil }
        let prefix = change >= 0 ? "+" : ""
        return "\(prefix)\(String(format: "%.1f", change))%"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption2)
                    .foregroundStyle(.gray)
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.gray)
            }

            Text(value)
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(.white)

            if let changeText {
                Text(changeText)
                    .font(.caption2.bold().monospacedDigit())
                    .foregroundStyle(changeColor)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(changeColor.opacity(0.15))
                    .clipShape(Capsule())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
