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
    fun freshThroughputRefinesCloseLatencyCandidates() {
        val now = 1_800_000_000_000L
        val selected = BestServerSelector.select(
            profiles = listOf("vless://low-speed", "vless://high-speed"),
            latencyMs = mapOf("vless://low-speed" to 50L, "vless://high-speed" to 55L),
            throughputSamples = mapOf(
                "vless://low-speed" to ThroughputSample(2.0, 1.0, now),
                "vless://high-speed" to ThroughputSample(300.0, 150.0, now)
            ),
            nowEpochMs = now
        )
        assertEquals("vless://high-speed", selected)
    }

    @Test
    fun staleThroughputCannotChangeLatencySelectionOrHideLargeLatencyGap() {
        val now = 1_800_000_000_000L
        val selected = BestServerSelector.select(
            profiles = listOf("vless://slow-latency", "vless://fast-latency"),
            latencyMs = mapOf("vless://slow-latency" to 150L, "vless://fast-latency" to 40L),
            throughputSamples = mapOf(
                "vless://slow-latency" to ThroughputSample(1_000.0, 1_000.0, now),
                "vless://fast-latency" to ThroughputSample(1.0, 1.0, now - ThroughputFreshnessPolicy.MAX_AGE_MS - 1L)
            ),
            nowEpochMs = now
        )
        assertEquals("vless://fast-latency", selected)
    }
    @Test
    fun highThroughputCannotOverrideMateriallyWorseReliabilityScore() {
        val now = 1_800_000_000_000L
        val stable = "vless://stable"
        val fastButUnstable = "vless://fast-but-unstable"
        val selected = BestServerSelector.select(
            profiles = listOf(fastButUnstable, stable),
            latencyMs = mapOf(fastButUnstable to 50L, stable to 60L),
            recentFailures = mapOf(fastButUnstable to 3),
            throughputSamples = mapOf(
                fastButUnstable to ThroughputSample(1_000.0, 1_000.0, now),
                stable to ThroughputSample(2.0, 1.0, now)
            ),
            nowEpochMs = now
        )
        assertEquals(stable, selected)
    }

    @Test
    fun throughputStillRefinesCandidatesWithEqualReliabilityScore() {
        val now = 1_800_000_000_000L
        val low = "vless://low-speed"
        val high = "vless://high-speed"
        val selected = BestServerSelector.select(
            profiles = listOf(low, high),
            latencyMs = mapOf(low to 50L, high to 50L),
            throughputSamples = mapOf(
                low to ThroughputSample(2.0, 1.0, now),
                high to ThroughputSample(300.0, 150.0, now)
            ),
            nowEpochMs = now
        )
        assertEquals(high, selected)
    }

    @Test
    fun throughputBonusCannotEraseLargeBaseScoreGap() {
        val now = 1_800_000_000_000L
        val reliable = "vless://reliable"
        val unreliable = "vless://unreliable"
        val selected = BestServerSelector.select(
            profiles = listOf(unreliable, reliable),
            latencyMs = mapOf(unreliable to 50L, reliable to 60L),
            recentFailures = mapOf(unreliable to 3),
            throughputSamples = mapOf(
                unreliable to ThroughputSample(1_000.0, 1_000.0, now),
                reliable to ThroughputSample(1.0, 1.0, now)
            ),
            nowEpochMs = now
        )
        assertEquals(reliable, selected)
    }

    @Test
    fun throughputMayOvercomeOnlySmallBaseScoreGap() {
        val now = 1_800_000_000_000L
        val slightlySlower = "vless://slightly-slower"
        val faster = "vless://faster"
        val selected = BestServerSelector.select(
            profiles = listOf(slightlySlower, faster),
            latencyMs = mapOf(slightlySlower to 50L, faster to 60L),
            throughputSamples = mapOf(
                slightlySlower to ThroughputSample(1.0, 1.0, now),
                faster to ThroughputSample(1_000.0, 1_000.0, now)
            ),
            nowEpochMs = now
        )
        assertEquals(faster, selected)
    }

}
