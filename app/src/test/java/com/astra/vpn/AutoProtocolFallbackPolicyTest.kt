package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoProtocolFallbackPolicyTest {
    @Test fun prefersDifferentProtocolProfiles() {
        val current = "vless://id@one.example:443#one"
        val candidates = listOf(
            "vless://id@two.example:443#two",
            "hysteria2://pass@three.example:443#three",
            "tuic://id:pass@four.example:443#four"
        )
        assertEquals(candidates.drop(1), AutoProtocolFallbackPolicy.preferAlternativeProtocol(candidates, current))
    }

    @Test fun normalizesHy2Alias() {
        val current = "hy2://pass@one.example:443"
        val candidates = listOf("hysteria2://pass@two.example:443", "tuic://id:pass@three.example:443")
        assertEquals(listOf("tuic://id:pass@three.example:443"), AutoProtocolFallbackPolicy.preferAlternativeProtocol(candidates, current))
    }

    @Test fun preservesCandidatesWhenNoOtherProtocolExists() {
        val current = "vless://id@one.example:443"
        val candidates = listOf("vless://id@two.example:443", "vless://id@three.example:443")
        assertEquals(candidates, AutoProtocolFallbackPolicy.preferAlternativeProtocol(candidates, current))
    }

    @Test fun preservesCandidatesForUnknownCurrentProtocol() {
        val candidates = listOf("vless://id@two.example:443")
        assertEquals(candidates, AutoProtocolFallbackPolicy.preferAlternativeProtocol(candidates, "unknown://host"))
    }
}
