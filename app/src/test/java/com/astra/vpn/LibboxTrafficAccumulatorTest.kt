package com.astra.vpn

import org.junit.Assert.*
import org.junit.Test

class LibboxTrafficAccumulatorTest {
    @Test fun accumulatesDeltasSeparatelyForEachOutbound() {
        val accumulator = LibboxTrafficAccumulator()
        accumulator.add("proxy-a", 100L, 250L)
        accumulator.add("proxy-a", 50L, 75L)
        accumulator.add("proxy-b", 7L, 11L)

        val snapshot = accumulator.snapshot()
        assertEquals(LibboxTrafficAccumulator.Totals(150L, 325L), snapshot["proxy-a"])
        assertEquals(LibboxTrafficAccumulator.Totals(7L, 11L), snapshot["proxy-b"])
    }

    @Test fun ignoresMissingTagsAndNegativeDeltasWithoutDecreasingTotals() {
        val accumulator = LibboxTrafficAccumulator()
        accumulator.add(null, 10L, 10L)
        accumulator.add(" ", 10L, 10L)
        accumulator.add("proxy", 20L, 30L)
        accumulator.add("proxy", -5L, -9L)
        accumulator.add("proxy", -5L, 4L)

        assertEquals(
            LibboxTrafficAccumulator.Totals(20L, 34L),
            accumulator.snapshot()["proxy"]
        )
        assertEquals(1, accumulator.snapshot().size)
    }

    @Test fun saturatesInsteadOfOverflowing() {
        val accumulator = LibboxTrafficAccumulator()
        accumulator.add("proxy", Long.MAX_VALUE, Long.MAX_VALUE)
        accumulator.add("proxy", 1L, 1L)

        assertEquals(
            LibboxTrafficAccumulator.Totals(Long.MAX_VALUE, Long.MAX_VALUE),
            accumulator.snapshot()["proxy"]
        )
    }

    @Test fun snapshotIsDetachedAndResetClearsAllOutbounds() {
        val accumulator = LibboxTrafficAccumulator()
        accumulator.add("proxy", 1L, 2L)
        val oldSnapshot = accumulator.snapshot()
        accumulator.add("proxy", 3L, 4L)
        assertEquals(LibboxTrafficAccumulator.Totals(1L, 2L), oldSnapshot["proxy"])

        accumulator.reset()
        assertTrue(accumulator.snapshot().isEmpty())
    }
}
