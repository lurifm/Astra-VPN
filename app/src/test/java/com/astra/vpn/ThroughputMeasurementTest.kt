package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMeasurementTest {
    @Test
    fun convertsBytesAndDurationToMegabitsPerSecond() {
        assertEquals(8.0, ThroughputMeasurement.calculateMbps(1_000_000, 1_000)!!, 0.001)
    }

    @Test
    fun rejectsEmptyOrTooShortSamples() {
        assertNull(ThroughputMeasurement.calculateMbps(0, 1_000))
        assertNull(ThroughputMeasurement.calculateMbps(1_000, 249))
        assertNull(ThroughputMeasurement.calculateMbps(1_000, 0))
    }

    @Test
    fun fastThroughputAddsNoPenalty() {
        assertEquals(0L, ThroughputMeasurement.scorePenaltyMs(150.0))
        assertEquals(0L, ThroughputMeasurement.scorePenaltyMs(100.0))
    }

    @Test
    fun slowThroughputAddsBoundedPenalty() {
        assertEquals(180L, ThroughputMeasurement.scorePenaltyMs(10.0))
        assertEquals(ThroughputMeasurement.MAX_SCORE_PENALTY_MS,
            ThroughputMeasurement.scorePenaltyMs(0.001))
    }

    @Test
    fun invalidThroughputDoesNotAffectLatencyScoring() {
        assertEquals(0L, ThroughputMeasurement.scorePenaltyMs(null))
        assertEquals(0L, ThroughputMeasurement.scorePenaltyMs(Double.NaN))
        assertEquals(0L, ThroughputMeasurement.scorePenaltyMs(-1.0))
    }
}
