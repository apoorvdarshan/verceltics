import SwiftUI

/// Keeps the soft paywall's pending intent separate from purchase state.
/// Existing RevenueCat entitlements remain the only authority for Pro access.
struct ProAccessGate<Route> {
    var isPaywallPresented = false
    private(set) var pendingRoute: Route?

    mutating func request(_ route: Route, hasProAccess: Bool) -> Route? {
        guard !hasProAccess else { return route }
        pendingRoute = route
        isPaywallPresented = true
        return nil
    }

    mutating func resumeAfterDismiss(hasProAccess: Bool) -> Route? {
        defer { pendingRoute = nil }
        guard hasProAccess else { return nil }
        return pendingRoute
    }
}

extension View {
    func proPaywall(
        isPresented: Binding<Bool>,
        onDismiss: @escaping () -> Void
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            PaywallView()
                .presentationSizing(.page)
                .presentationDragIndicator(.visible)
        }
    }
}
