package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BestServerThroughputSelectionTest {
    @Test
    fun throughputBreaksCloseLatencyTie() {
        val selected = BestServerSelector.select(
            profiles = listOf("vless://slow", "vless://fast"),
            latencyMs = mapOf("vless://slow" to 50L, "vless://fast" to 50L),
            throughputMbps = mapOf("vless://slow" to 10.0, "vless://fast" to 100.0)
        )
        assertEquals("vless://fast", selected)
    }

    @Test
    fun throughputCannotOverrideLargeLatencyDifference() {
        val selected = BestServerSelector.select(
            profiles = listOf("vless://lowping", "vless://bandwidth"),
            latencyMs = mapOf("vless://lowping" to 30L, "vless://bandwidth" to 120L),
            throughputMbps = mapOf("vless://lowping" to 10.0, "vless://bandwidth" to 10_000.0)
        )
        assertEquals("vless://lowping", selected)
    }

    @Test
    fun invalidThroughputDoesNotInfluenceSelection() {
        val selected = BestServerSelector.select(
            profiles = listOf("vless://first", "vless://second"),
            latencyMs = mapOf("vless://first" to 50L, "vless://second" to 50L),
            throughputMbps = mapOf("vless://first" to Double.NaN, "vless://second" to -1.0)
        )
        assertEquals("vless://first", selected)
    }
}
