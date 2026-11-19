package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerLatencySortTest {
    @Test
    fun unavailableServersAreAlwaysLastInPingMode() {
        val result = ServerLatencySort.sort(
            profiles = listOf("offline", "slow", "untested", "fast"),
            mode = "ping",
            latencyMs = mapOf("slow" to 180L, "fast" to 42L),
            favorites = setOf("offline")
        )
        assertEquals(listOf("fast", "slow", "offline", "untested"), result)
    }

    @Test
    fun zeroAndNegativeLatencyValuesAreTreatedAsUnavailable() {
        val result = ServerLatencySort.sort(
            profiles = listOf("negative", "slow", "zero", "fast"),
            mode = "ping",
            latencyMs = mapOf("negative" to -1L, "slow" to 180L, "zero" to 0L, "fast" to 42L),
            favorites = setOf("negative", "zero")
        )
        assertEquals(listOf("fast", "slow", "negative", "zero"), result)
    }

    @Test
    fun favoritesOnlyBreakTiesAtSameMeasuredLatency() {
        val result = ServerLatencySort.sort(
            profiles = listOf("normal", "favorite", "slower"),
            mode = "ping",
            latencyMs = mapOf("normal" to 40L, "favorite" to 40L, "slower" to 90L),
            favorites = setOf("favorite")
        )
        assertEquals(listOf("favorite", "normal", "slower"), result)
    }

    @Test
    fun equalLatencyServersKeepTheirOriginalOrderAfterFavoriteTieBreak() {
        val result = ServerLatencySort.sort(
            profiles = listOf("z-server", "a-server", "favorite"),
            mode = "ping",
            latencyMs = mapOf("z-server" to 50L, "a-server" to 50L, "favorite" to 50L),
            favorites = setOf("favorite")
        )
        assertEquals(listOf("favorite", "z-server", "a-server"), result)
    }

    @Test
    fun alphabeticalModeIsCaseInsensitive() {
        val result = ServerLatencySort.sort(
            profiles = listOf("zeta", "Alpha", "beta"),
            mode = "alphabet",
            latencyMs = emptyMap(),
            favorites = emptySet()
        )
        assertEquals(listOf("Alpha", "beta", "zeta"), result)
    }
}
