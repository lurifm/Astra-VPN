package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TimeOfDayServerOptimizerTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_763_550_000_000L
    private val profile = "vless://server"

    @Test
    fun onePriorSampleDoesNotActivateHistoricalBlending() {
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 7L * DAY_MS, 100L),
            LatencyStabilityScorer.Sample(now, 1_000L)
        )

        val result = TimeOfDayServerOptimizer.adjustedLatencies(
            listOf(profile), mapOf(profile to 1_000L), { samples }, now, utc
        )

        assertEquals(1_000L, result[profile])
    }

    @Test
    fun requiresEnoughSamplesAndUsesMedianToLimitOutlierImpact() {
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 14L * DAY_MS, 100L),
            LatencyStabilityScorer.Sample(now - 7L * DAY_MS, 200L),
            LatencyStabilityScorer.Sample(now, 10_000L)
        )

        val result = TimeOfDayServerOptimizer.adjustedLatencies(
            listOf(profile), mapOf(profile to 1_000L), { samples }, now, utc
        )

        // Median of the matching samples is 200 ms; current value retains 70% weight.
        assertEquals(760L, result[profile])
    }

    @Test
    fun ignoresFutureAndOtherTimeBucketSamples() {
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 7L * DAY_MS, 100L),
            LatencyStabilityScorer.Sample(now + DAY_MS, 100L),
            LatencyStabilityScorer.Sample(now - 4L * 60L * 60L * 1_000L, 100L)
        )

        val result = TimeOfDayServerOptimizer.adjustedLatencies(
            listOf(profile), mapOf(profile to 250L), { samples }, now, utc
        )

        assertEquals(250L, result[profile])
    }

    @Test
    fun omitsProfilesWithoutCurrentMeasurement() {
        val result = TimeOfDayServerOptimizer.adjustedLatencies(
            listOf(profile), emptyMap(), { emptyList() }, now, utc
        )
        assertEquals(emptyMap<String, Long>(), result)
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
