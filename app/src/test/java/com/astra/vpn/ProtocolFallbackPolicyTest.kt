package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolFallbackPolicyTest {
    @Test fun prefersDifferentProtocolAndOnlyReachableUntestedProfiles() {
        val active = "vless://active"
        val profiles = listOf(active, "vless://same", "tuic://fast", "hysteria2://backup")
        val result = ProtocolFallbackPolicy.candidates(
            active, profiles, setOf("vless://same", "tuic://fast", "hysteria2://backup"),
            setOf("hysteria2://backup")
        )
        assertEquals(listOf("tuic://fast", "vless://same"), result)
    }

    @Test fun doesNotReturnActiveOrUnreachableProfiles() {
        assertEquals(
            emptyList<String>(),
            ProtocolFallbackPolicy.candidates("vless://a", listOf("vless://a", "tuic://b"), emptySet())
        )
    }
}
