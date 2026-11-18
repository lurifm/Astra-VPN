package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TcpLatencyProbeTest {
    @Test
    fun medianUsesMiddleValueForOddSampleCount() {
        assertEquals(20L, TcpLatencyProbe.median(listOf(30L, 10L, 20L)))
    }

    @Test
    fun medianAveragesMiddleValuesForEvenSampleCount() {
        assertEquals(25L, TcpLatencyProbe.median(listOf(40L, 10L, 30L, 20L)))
    }

    @Test
    fun medianDoesNotDependOnInputOrder() {
        assertEquals(20L, TcpLatencyProbe.median(listOf(20L, 50L, 10L)))
        assertEquals(20L, TcpLatencyProbe.median(listOf(10L, 20L, 50L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun medianRejectsEmptySamples() {
        TcpLatencyProbe.median(emptyList())
    }
}
