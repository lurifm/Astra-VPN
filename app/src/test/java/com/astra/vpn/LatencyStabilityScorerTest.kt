package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyStabilityScorerTest {
    @Test
    fun needsAtLeastThreeRecentSamples() {
        val now = 1_000_000L
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 2_000L, 20L),
            LatencyStabilityScorer.Sample(now - 1_000L, 100L)
        )
        assertEquals(0L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun stableSamplesHaveSmallJitterPenalty() {
        val now = 1_000_000L
        val samples = listOf(40L, 42L, 44L).mapIndexed { index, latency ->
            LatencyStabilityScorer.Sample(now - index * 1_000L, latency)
        }
        assertEquals(2L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun unstableSamplesReceiveMedianAbsoluteDeviationPenalty() {
        val now = 1_000_000L
        val samples = listOf(20L, 40L, 80L).mapIndexed { index, latency ->
            LatencyStabilityScorer.Sample(now - index * 1_000L, latency)
        }
        assertEquals(20L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun ignoresStaleFutureAndInvalidSamples() {
        val now = 1_000_000L
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 1_000L, 50L),
            LatencyStabilityScorer.Sample(now - 2_000L, 50L),
            LatencyStabilityScorer.Sample(now - 3_000L, 50L),
            LatencyStabilityScorer.Sample(now - LatencyStabilityScorer.DEFAULT_WINDOW_MS - 1L, 900L),
            LatencyStabilityScorer.Sample(now + 1L, 1_000L),
            LatencyStabilityScorer.Sample(now - 4_000L, -1L)
        )
        assertEquals(0L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun capsExtremeJitterPenalty() {
        val now = 1_000_000L
        val samples = listOf(1L, 30_000L, 60_000L).mapIndexed { index, latency ->
            LatencyStabilityScorer.Sample(now - index * 1_000L, latency)
        }
        assertEquals(LatencyStabilityScorer.MAX_PENALTY_MS, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun penalizesSustainedRecentLatencyDegradationAgainstOlderBaseline() {
        val now = 10_000_000L
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 60L * 60_000L, 50L),
            LatencyStabilityScorer.Sample(now - 70L * 60_000L, 52L),
            LatencyStabilityScorer.Sample(now - 80L * 60_000L, 48L),
            LatencyStabilityScorer.Sample(now - 1L * 60_000L, 100L),
            LatencyStabilityScorer.Sample(now - 2L * 60_000L, 105L),
            LatencyStabilityScorer.Sample(now - 3L * 60_000L, 110L)
        )

        assertEquals(55L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun isolatedRecentSpikeDoesNotTriggerDegradationPenalty() {
        val now = 10_000_000L
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 60L * 60_000L, 50L),
            LatencyStabilityScorer.Sample(now - 70L * 60_000L, 52L),
            LatencyStabilityScorer.Sample(now - 80L * 60_000L, 48L),
            LatencyStabilityScorer.Sample(now - 1L * 60_000L, 500L)
        )

        assertEquals(2L, LatencyStabilityScorer.penaltyMs(samples, now))
    }

    @Test
    fun recentDegradationRespectsCustomScoringWindow() {
        val now = 10_000_000L
        val samples = listOf(
            LatencyStabilityScorer.Sample(now - 10_000L, 50L),
            LatencyStabilityScorer.Sample(now - 20_000L, 52L),
            LatencyStabilityScorer.Sample(now - 30_000L, 48L),
            LatencyStabilityScorer.Sample(now - 40_000L, 100L),
            LatencyStabilityScorer.Sample(now - 50_000L, 105L),
            LatencyStabilityScorer.Sample(now - 60_000L, 110L)
        )

        assertEquals(0L, LatencyStabilityScorer.penaltyMs(samples, now, 25_000L))
    }

    @Test
    fun rejectsInvalidClockAndWindow() {
        val sample = LatencyStabilityScorer.Sample(1L, 50L)
        assertEquals(0L, LatencyStabilityScorer.penaltyMs(listOf(sample), -1L))
        assertEquals(0L, LatencyStabilityScorer.penaltyMs(listOf(sample), 2L, 0L))
    }
}
