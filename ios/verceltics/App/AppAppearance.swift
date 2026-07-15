import Foundation
import Observation
import SwiftUI

enum AppAppearance: String, CaseIterable, Identifiable, Sendable {
    case system
    case light
    case dark

    var id: Self { self }

    init(storedValue: String?) {
        self = storedValue.flatMap(Self.init(rawValue:)) ?? .system
    }

    var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var systemImage: String {
        switch self {
        case .system: "circle.lefthalf.filled"
        case .light: "sun.max.fill"
        case .dark: "moon.stars.fill"
        }
    }

    var explanation: String {
        switch self {
        case .system: "Matches your device appearance automatically."
        case .light: "Keeps the workspace bright on this device."
        case .dark: "Keeps the workspace dark on this device."
        }
    }

    var preferredColorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

@Observable
@MainActor
final class AppAppearanceStore {
    static let storageKey = "app.appearance"

    private let defaults: UserDefaults
    private(set) var selection: AppAppearance

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        let storedValue = defaults.string(forKey: Self.storageKey)
        selection = AppAppearance(storedValue: storedValue)

        // Unknown values can be left behind by an older build. Removing them
        // restores the documented System default instead of persisting a
        // setting the current app cannot represent.
        if let storedValue, AppAppearance(rawValue: storedValue) == nil {
            defaults.removeObject(forKey: Self.storageKey)
        }
    }

    func select(_ appearance: AppAppearance) {
        guard selection != appearance else { return }
        selection = appearance
        defaults.set(appearance.rawValue, forKey: Self.storageKey)
    }
}
