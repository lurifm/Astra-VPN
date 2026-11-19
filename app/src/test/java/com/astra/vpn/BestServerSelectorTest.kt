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
    fun prefersStableLatencyWhenCurrentPingsAreClose() {
        val selected = BestServerSelector.select(
            profiles = listOf("vless://jittery", "vless://stable"),
            latencyMs = mapOf("vless://jittery" to 45L, "vless://stable" to 65L),
            latencyStabilityPenaltyMs = mapOf("vless://jittery" to 30L)
        )
        assertEquals("vless://stable", selected)
    }

    @Test
    fun latencyJitterPenaltyIsBounded() {
        val selected = BestServerSelector.select(
            profiles = listOf("vless://jittery", "vless://fast"),
            latencyMs = mapOf("vless://jittery" to 300L, "vless://fast" to 40L),
            latencyStabilityPenaltyMs = mapOf("vless://jittery" to Long.MAX_VALUE)
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


    @Test
    fun extremeLatencyCannotWrapIntoBestScore() {
        val selected = BestServerSelector.select(
            listOf("hysteria2://extreme", "vless://normal"),
            mapOf("hysteria2://extreme" to Long.MAX_VALUE, "vless://normal" to 100L)
        )
        assertEquals("vless://normal", selected)
    }

    @Test
    fun ignoresStaleLatencyMeasurementsWhenFreshnessMetadataIsProvided() {
        val now = 10_000_000L
        val selected = BestServerSelector.select(
            profiles = listOf("tuic://stale", "vless://fresh"),
            latencyMs = mapOf("tuic://stale" to 5L, "vless://fresh" to 70L),
            latencyTimestamps = mapOf(
                "tuic://stale" to now - ServerSelectionFreshness.MAX_LATENCY_AGE_MS - 1L,
                "vless://fresh" to now
            ),
            nowMs = now
        )
        assertEquals("vless://fresh", selected)
    }

    @Test
    fun staleHighThroughputDoesNotDistortFreshCandidateScoring() {
        val now = 10_000_000L
        val profiles = listOf("vless://fresh-fast", "tuic://fresh-slower", "hysteria2://stale")
        val selected = BestServerSelector.select(
            profiles = profiles,
            latencyMs = mapOf(
                "vless://fresh-fast" to 50L,
                "tuic://fresh-slower" to 55L,
                "hysteria2://stale" to 200L
            ),
            throughputMbps = mapOf(
                "vless://fresh-fast" to 80.0,
                "tuic://fresh-slower" to 70.0,
                "hysteria2://stale" to 10_000.0
            ),
            latencyTimestamps = profiles.associateWith { now },
            throughputTimestamps = mapOf(
                "vless://fresh-fast" to now,
                "tuic://fresh-slower" to now,
                "hysteria2://stale" to now - ServerSelectionFreshness.MAX_THROUGHPUT_AGE_MS - 1L
            ),
            nowMs = now
        )
        assertEquals("tuic://fresh-slower", selected)
    }

    @Test
    fun ignoresStaleThroughputAndAppliesTimeOfDayPreferenceOnlyWhenConfigured() {
        val now = 10_000_000L
        val profiles = listOf("vless://preferred", "tuic://fast")
        val latencies = mapOf("vless://preferred" to 50L, "tuic://fast" to 56L)
        val baseline = BestServerSelector.select(profiles, latencies, nowMs = now)
        val preferred = BestServerSelector.select(
            profiles = profiles,
            latencyMs = latencies,
            nowMs = now,
            localHour = 19,
            preferredProtocols = mapOf(TimeOfDayServerPolicy.Window.EVENING to setOf("vless"))
        )
        assertEquals("tuic://fast", baseline)
        assertEquals("vless://preferred", preferred)

        val staleSpeed = BestServerSelector.select(
            profiles = profiles,
            latencyMs = latencies,
            throughputMbps = mapOf("vless://preferred" to 1.0, "tuic://fast" to 100.0),
            throughputTimestamps = mapOf(
                "vless://preferred" to now,
                "tuic://fast" to now - ServerSelectionFreshness.MAX_THROUGHPUT_AGE_MS - 1L
            ),
            nowMs = now
        )
        assertEquals("tuic://fast", staleSpeed)
    }
}
