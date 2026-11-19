package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class AstraNetworkPreferencesTest {
    @Test fun mtuBoundsAreEnforced() {
        AstraNetworkPreferences(mtu = 1280)
        AstraNetworkPreferences(mtu = 1500)
        runCatching { AstraNetworkPreferences(mtu = 1279) }.onSuccess { error("MTU below supported range accepted") }
        runCatching { AstraNetworkPreferences(mtu = 1501) }.onSuccess { error("MTU above supported range accepted") }
    }

    @Test fun defaultsAreConservative() {
        val defaults = AstraNetworkPreferences()
        assertEquals(AstraNetworkPreferences.RoutingPreset.ALL_VPN, defaults.routingPreset)
        assertEquals(1400, defaults.mtu)
    }
}
