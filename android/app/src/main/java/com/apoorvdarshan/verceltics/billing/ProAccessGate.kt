package com.apoorvdarshan.verceltics.billing

/**
 * Keeps the soft paywall's pending intent separate from purchase state.
 * RevenueCat entitlements remain the only authority for Pro access.
 */
class ProAccessGate<Route : Any> {
    var isPaywallPresented: Boolean = false
        private set
    var pendingRoute: Route? = null
        private set

    fun request(route: Route, hasProAccess: Boolean): Route? {
        if (hasProAccess) return route
        pendingRoute = route
        isPaywallPresented = true
        return null
    }

    fun resumeAfterDismiss(hasProAccess: Boolean): Route? {
        val route = pendingRoute
        pendingRoute = null
        isPaywallPresented = false
        return route.takeIf { hasProAccess }
    }
}
