package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSelectionMatcherTest {
    @Test fun preservesExactLink() {
        val link = "vless://id@node.example:443#Alpha"
        assertEquals(link, ProfileSelectionMatcher.remap(link, listOf(link)))
    }

    @Test fun remapsRotatedCredentialsOnSameEndpointAndName() {
        val old = "vless://old-secret@node.example:443?security=reality#Alpha"
        val fresh = "vless://new-secret@node.example:443?security=reality&pbk=new-key#Alpha"
        assertEquals(fresh, ProfileSelectionMatcher.remap(old, listOf(fresh)))
    }

    @Test fun normalizesHostCaseAndProtocolAlias() {
        val old = "hysteria2://old@NODE.example:443#Fast"
        val fresh = "hy2://new@node.example:443#Fast"
        assertEquals(fresh, ProfileSelectionMatcher.remap(old, listOf(fresh)))
    }

    @Test fun refusesProtocolMismatch() {
        val old = "vless://id@node.example:443#Alpha"
        val fresh = "tuic://id:pw@node.example:443#Alpha"
        assertNull(ProfileSelectionMatcher.remap(old, listOf(fresh)))
    }

    @Test fun refusesAmbiguousEndpointMatches() {
        val old = "vless://old@node.example:443#Alpha"
        val candidates = listOf(
            "vless://one@node.example:443#Alpha",
            "vless://two@node.example:443#Beta"
        )
        assertNull(ProfileSelectionMatcher.remap(old, candidates))
    }

    @Test fun refusesNameFallbackEvenWhenDisplayNameIsUnique() {
        val old = "vless://old@old.example:443#Alpha"
        val candidate = "vless://new@new.example:443#Alpha"
        assertNull(ProfileSelectionMatcher.remap(old, listOf(candidate)))
    }

    @Test fun refusesAmbiguousNameFallback() {
        val old = "vless://old@old.example:443#Alpha"
        val candidates = listOf(
            "vless://one@one.example:443#Alpha",
            "vless://two@two.example:443#Alpha"
        )
        assertNull(ProfileSelectionMatcher.remap(old, candidates))
    }

    @Test fun noMatchReturnsNull() {
        val old = "vless://id@old.example:443#Alpha"
        val fresh = "vless://id@new.example:443#Beta"
        assertNull(ProfileSelectionMatcher.remap(old, listOf(fresh)))
    }
}
