package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestServerSelectorTest {
    @Test
    fun prefersUdpWhenLatencyIsEffectivelyEqual() {
        val selected = BestServerSelector.select(
            listOf("vless://fast", "hysteria2://udp"),
            mapOf("vless://fast" to 50L, "hysteria2://udp" to 58L)
        )
        assertEquals("hysteria2://udp", selected)
    }

    @Test
    fun muchFasterVlessStillWins() {
        val selected = BestServerSelector.select(
            listOf("hysteria2://udp", "vless://fast"),
            mapOf("hysteria2://udp" to 100L, "vless://fast" to 40L)
        )
        assertEquals("vless://fast", selected)
    }

    @Test
    fun tuicReceivesUdpPreference() {
        val selected = BestServerSelector.select(
            listOf("vless://node", "tuic://node"),
            mapOf("vless://node" to 70L, "tuic://node" to 75L)
        )
        assertEquals("tuic://node", selected)
    }

    @Test
    fun ignoresServersWithoutSuccessfulMeasurements() {
        assertEquals(
            "vless://online",
            BestServerSelector.select(
                listOf("hysteria2://offline", "vless://online"),
                mapOf("vless://online" to 80L)
            )
        )
    }

    @Test
    fun returnsNullWhenNoServerResponded() {
        assertNull(BestServerSelector.select(listOf("vless://offline"), emptyMap()))
    }
    @Test
    fun prefersMoreStableServerWhenLatencyIsClose() {
        val selected = BestServerSelector.select(
            listOf("vless://unstable", "vless://stable"),
            mapOf("vless://unstable" to 45L, "vless://stable" to 65L),
            recentFailures = mapOf("vless://unstable" to 3)
        )
        assertEquals("vless://stable", selected)
    }

    @Test
    fun stabilityPenaltyIsCappedAndCannotHideLargeLatencyDifference() {
        val selected = BestServerSelector.select(
            listOf("vless://unstable", "vless://fast"),
            mapOf("vless://unstable" to 300L, "vless://fast" to 40L),
            recentFailures = mapOf("vless://unstable" to 100)
        )
        assertEquals("vless://fast", selected)
    }

    @Test
    fun negativeFailureCountsAreIgnored() {
        val selected = BestServerSelector.select(
            listOf("vless://node"),
            mapOf("vless://node" to 50L),
            recentFailures = mapOf("vless://node" to -5)
        )
        assertEquals("vless://node", selected)
    }

}
