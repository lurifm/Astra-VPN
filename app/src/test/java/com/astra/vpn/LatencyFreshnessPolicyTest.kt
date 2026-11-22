package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyFreshnessPolicyTest {
    @Test
    fun defaultFreshnessWindowMatchesFiveMinuteUiAndFailoverPolicy() {
        assertEquals(5L * 60L * 1_000L, LatencyFreshnessPolicy.DEFAULT_MAX_AGE_MS)
    }

    @Test
    fun keepsOnlyFreshPositiveMeasurementsForRequestedCandidates() {
        val now = 1_000_000L
        val result = LatencyFreshnessPolicy.freshLatencies(
            latencies = mapOf("fresh" to 42L, "stale" to 18L, "missing" to 25L, "invalid" to 0L),
            measuredAtElapsedMs = mapOf(
                "fresh" to now - 5_000L,
                "stale" to now - LatencyFreshnessPolicy.DEFAULT_MAX_AGE_MS - 1L,
                "invalid" to now - 1_000L
            ),
            nowElapsedMs = now,
            candidates = listOf("fresh", "stale", "missing", "invalid", "not-a-candidate")
        )
        assertEquals(mapOf("fresh" to 42L), result)
    }

    @Test
    fun includesMeasurementExactlyAtFreshnessBoundary() {
        val now = 1_000_000L
        val result = LatencyFreshnessPolicy.freshLatencies(
            latencies = mapOf("node" to 77L),
            measuredAtElapsedMs = mapOf("node" to now - LatencyFreshnessPolicy.DEFAULT_MAX_AGE_MS),
            nowElapsedMs = now,
            candidates = listOf("node")
        )
        assertEquals(mapOf("node" to 77L), result)
    }

    @Test
    fun rejectsFutureTimestampsAndInvalidClockOrAge() {
        assertTrue(
            LatencyFreshnessPolicy.freshLatencies(
                latencies = mapOf("future" to 20L),
                measuredAtElapsedMs = mapOf("future" to 101L),
                nowElapsedMs = 100L,
                candidates = listOf("future")
            ).isEmpty()
        )
        assertTrue(
            LatencyFreshnessPolicy.freshLatencies(
                latencies = mapOf("node" to 20L),
                measuredAtElapsedMs = mapOf("node" to 90L),
                nowElapsedMs = 100L,
                candidates = listOf("node"),
                maxAgeMs = 0L
            ).isEmpty()
        )
    }

    @Test
    fun rejectsMissingTimestampAndNonPositiveLatency() {
        val result = LatencyFreshnessPolicy.freshLatencies(
            latencies = mapOf("no-time" to 30L, "zero" to 0L, "negative" to -1L),
            measuredAtElapsedMs = mapOf("zero" to 90L, "negative" to 90L),
            nowElapsedMs = 100L,
            candidates = listOf("no-time", "zero", "negative")
        )
        assertTrue(result.isEmpty())
    }
}
