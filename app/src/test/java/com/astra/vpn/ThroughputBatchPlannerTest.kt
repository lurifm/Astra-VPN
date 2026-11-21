package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputBatchPlannerTest {
    @Test
    fun selectsAtMostFiveLowestLatencyProfiles() {
        val profiles = (1..8).map { "vless://node$it" }
        val latency = profiles.mapIndexed { index, profile -> profile to (80L - index * 5L) }.toMap()
        assertEquals(
            listOf("vless://node8", "vless://node7", "vless://node6", "vless://node5", "vless://node4"),
            ThroughputBatchPlanner.plan(profiles, latency, emptyMap())
        )
    }

    @Test
    fun reusesFreshSamplesAndOnlySchedulesMissingMeasurements() {
        val profiles = listOf("vless://a", "vless://b", "vless://c")
        val now = 1_800_000_000_000L
        val fresh = mapOf("vless://a" to ThroughputSample(100.0, 50.0, now))
        assertEquals(
            listOf("vless://b", "vless://c"),
            ThroughputBatchPlanner.plan(
                profiles,
                mapOf("vless://a" to 20L, "vless://b" to 30L, "vless://c" to 40L),
                fresh,
                limit = 5
            )
        )
    }

    @Test
    fun ignoresProfilesWithoutSuccessfulLatencyAndKeepsInputOrderForTies() {
        val profiles = listOf("vless://first", "vless://offline", "vless://second", "vless://third")
        assertEquals(
            listOf("vless://first", "vless://second", "vless://third"),
            ThroughputBatchPlanner.plan(
                profiles,
                mapOf("vless://first" to 50L, "vless://offline" to -1L,
                    "vless://second" to 50L, "vless://third" to 80L),
                emptyMap()
            )
        )
    }

    @Test
    fun zeroLimitReturnsNoCandidates() {
        assertEquals(
            emptyList<String>(),
            ThroughputBatchPlanner.plan(listOf("vless://a"), mapOf("vless://a" to 20L), emptyMap(), 0)
        )
    }
}
