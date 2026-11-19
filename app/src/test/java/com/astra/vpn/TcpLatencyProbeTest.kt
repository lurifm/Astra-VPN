package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TcpLatencyProbeTest {
    @Test
    fun medianUsesMiddleSampleForOddSampleCount() {
        assertEquals(42L, TcpLatencyProbe.median(listOf(90L, 42L, 11L, 75L, 38L)))
    }

    @Test
    fun medianAveragesTwoMiddleSamplesForEvenSampleCount() {
        assertEquals(60L, TcpLatencyProbe.median(listOf(100L, 20L, 80L, 40L)))
    }

    @Test
    fun medianDoesNotDependOnInputOrder() {
        assertEquals(
            TcpLatencyProbe.median(listOf(9L, 1L, 5L)),
            TcpLatencyProbe.median(listOf(5L, 9L, 1L))
        )
    }

    @Test
    fun medianAveragesLargeValuesWithoutOverflow() {
        assertEquals(
            Long.MAX_VALUE - 1L,
            TcpLatencyProbe.median(listOf(Long.MAX_VALUE - 2L, Long.MAX_VALUE))
        )
    }

    @Test
    fun medianRejectsAnEmptyMeasurementSet() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.median(emptyList())
        }
    }
}
