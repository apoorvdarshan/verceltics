package com.apoorvdarshan.verceltics.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProAccessGateTest {
    @Test
    fun proUserOpensRouteImmediately() {
        val gate = ProAccessGate<String>()

        val route = gate.request("project", hasProAccess = true)

        assertEquals("project", route)
        assertFalse(gate.isPaywallPresented)
        assertNull(gate.pendingRoute)
    }

    @Test
    fun freeUserSeesPaywallAndRouteIsRemembered() {
        val gate = ProAccessGate<String>()

        val route = gate.request("zone", hasProAccess = false)

        assertNull(route)
        assertTrue(gate.isPaywallPresented)
        assertEquals("zone", gate.pendingRoute)
    }

    @Test
    fun dismissingWithoutPurchaseDropsPendingRoute() {
        val gate = ProAccessGate<String>()
        gate.request("site", hasProAccess = false)

        val resumed = gate.resumeAfterDismiss(hasProAccess = false)

        assertNull(resumed)
        assertNull(gate.pendingRoute)
        assertFalse(gate.isPaywallPresented)
    }

    @Test
    fun purchaseResumesOriginalRouteOnce() {
        val gate = ProAccessGate<String>()
        gate.request("domain", hasProAccess = false)

        assertEquals("domain", gate.resumeAfterDismiss(hasProAccess = true))
        assertNull(gate.resumeAfterDismiss(hasProAccess = true))
    }
}
