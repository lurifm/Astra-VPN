package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputStabilityScorerTest {
    @Test
    fun returnsMedianInsteadOfBeingDominatedByOutlier() {
        val median = ThroughputStabilityScorer.medianMbps(
            listOf(
                ThroughputStabilityScorer.Sample(9_000L, 20.0),
                ThroughputStabilityScorer.Sample(9_100L, 22.0),
                ThroughputStabilityScorer.Sample(9_200L, 900.0),
                ThroughputStabilityScorer.Sample(9_300L, 24.0),
                ThroughputStabilityScorer.Sample(9_400L, 26.0)
            ),
            nowMs = 10_000L
        )
        assertEquals(24.0, median!!, 0.0001)
    }

    @Test
    fun ignoresStaleFutureAndInvalidSamples() {
        val median = ThroughputStabilityScorer.medianMbps(
            listOf(
                ThroughputStabilityScorer.Sample(1L, 500.0),
                ThroughputStabilityScorer.Sample(10_001L, 100.0),
                ThroughputStabilityScorer.Sample(9_900L, Double.NaN),
                ThroughputStabilityScorer.Sample(9_800L, Double.POSITIVE_INFINITY),
                ThroughputStabilityScorer.Sample(9_700L, -1.0),
                ThroughputStabilityScorer.Sample(9_600L, 32.0)
            ),
            nowMs = 10_000L,
            windowMs = 500L
        )
        assertEquals(32.0, median!!, 0.0001)
    }

    @Test
    fun returnsNullWhenNoFreshValidSampleExists() {
        assertNull(
            ThroughputStabilityScorer.medianMbps(
                listOf(ThroughputStabilityScorer.Sample(1L, 10.0)),
                nowMs = 10_000L,
                windowMs = 100L
            )
        )
    }

    @Test
    fun averagesTwoMiddleValuesForEvenSampleCount() {
        val median = ThroughputStabilityScorer.medianMbps(
            listOf(
                ThroughputStabilityScorer.Sample(9_000L, 10.0),
                ThroughputStabilityScorer.Sample(9_100L, 30.0)
            ),
            nowMs = 10_000L
        )
        assertEquals(20.0, median!!, 0.0001)
    }
}
