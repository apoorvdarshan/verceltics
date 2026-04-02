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

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.gray)

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(value)
                    .font(.system(size: 22, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white)

                if let changeText {
                    Text(changeText)
                        .font(.system(size: 11, weight: .bold).monospacedDigit())
                        .foregroundStyle(changeColor)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(changeColor.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
