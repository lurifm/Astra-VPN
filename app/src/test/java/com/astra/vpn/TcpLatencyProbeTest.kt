package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TcpLatencyProbeTest {
    @Test
    fun medianUsesMiddleSampleForOddCount() {
        assertEquals(42L, TcpLatencyProbe.median(listOf(90L, 42L, 12L, 55L, 30L)))
    }

    @Test
    fun medianAveragesTwoMiddleSamplesForEvenCount() {
        assertEquals(45L, TcpLatencyProbe.median(listOf(80L, 30L, 60L, 40L)))
    }

    @Test
    fun medianDoesNotDependOnInputOrder() {
        assertEquals(
            TcpLatencyProbe.median(listOf(11L, 90L, 40L, 22L, 70L)),
            TcpLatencyProbe.median(listOf(70L, 22L, 90L, 11L, 40L))
        )
    }

    @Test
    fun medianRejectsEmptySamples() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.median(emptyList())
        }
    }
}
