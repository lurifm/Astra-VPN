package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyResultAccumulatorTest {
    @Test
    fun snapshotStartsWithEveryExpectedServerAsUnknown() {
        val accumulator = LatencyResultAccumulator(setOf("a", "b"))

        assertEquals(mapOf("a" to null, "b" to null), accumulator.snapshot())
        assertFalse(accumulator.allMeasured())
    }

    @Test
    fun mergeAcceptsOnlyPositiveMeasurementsForExpectedTags() {
        val accumulator = LatencyResultAccumulator(setOf("a", "b"))

        val result = accumulator.merge(
            mapOf("a" to 42L, "b" to 0L, "unexpected" to 12L)
        )

        assertEquals(42L, result["a"])
        assertNull(result["b"])
        assertFalse(result.containsKey("unexpected"))
        assertFalse(accumulator.allMeasured())
    }

    @Test
    fun placeholderDoesNotErasePreviouslyMeasuredLatency() {
        val accumulator = LatencyResultAccumulator(setOf("a"))

        accumulator.merge(mapOf("a" to 25L))
        val result = accumulator.merge(mapOf("a" to 0L))

        assertEquals(25L, result["a"])
        assertTrue(accumulator.allMeasured())
    }

    @Test
    fun allMeasuredRequiresEveryExpectedTagToHavePositiveLatency() {
        val accumulator = LatencyResultAccumulator(setOf("a", "b"))

        accumulator.merge(mapOf("a" to 10L))
        assertFalse(accumulator.allMeasured())

        accumulator.merge(mapOf("b" to 20L))
        assertTrue(accumulator.allMeasured())
    }

    @Test
    fun emptyExpectedSetIsNotConsideredMeasured() {
        assertFalse(LatencyResultAccumulator(emptySet()).allMeasured())
    }
}
