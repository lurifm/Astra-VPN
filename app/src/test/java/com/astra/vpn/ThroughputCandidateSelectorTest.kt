package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputCandidateSelectorTest {
    @Test fun selectsOnlyFastestMeasuredProfiles() {
        val profiles = listOf("slow", "fast", "middle", "unmeasured", "fast")
        val latency = mapOf("slow" to 240L, "fast" to 35L, "middle" to 90L)

        assertEquals(
            listOf("fast", "middle"),
            ThroughputCandidateSelector.select(profiles, latency, maxCandidates = 2)
        )
    }

    @Test fun ignoresInvalidLatencyAndBlankProfiles() {
        val profiles = listOf("", "zero", "negative", "valid")
        val latency = mapOf("zero" to 0L, "negative" to -1L, "valid" to 50L)

        assertEquals(listOf("valid"), ThroughputCandidateSelector.select(profiles, latency))
    }

    @Test fun returnsEmptyForNonPositiveLimit() {
        assertTrue(
            ThroughputCandidateSelector.select(
                profiles = listOf("server"),
                latencyMs = mapOf("server" to 10L),
                maxCandidates = 0
            ).isEmpty()
        )
    }

    @Test fun tieBreakIsDeterministic() {
        val result = ThroughputCandidateSelector.select(
            profiles = listOf("z", "a", "m"),
            latencyMs = mapOf("z" to 20L, "a" to 20L, "m" to 20L),
            maxCandidates = 2
        )
        assertEquals(listOf("a", "m"), result)
    }
}
