package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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
    fun boundedProbeStopsWaitingAtTheGlobalDeadline() {
        val timedOut = AtomicBoolean(false)
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            TcpLatencyProbe.runBounded(40L, onTimeout = { timedOut.set(true) }) {
                Thread.sleep(5_000L)
                "too late"
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Probe should return close to its 40 ms deadline, elapsed=${elapsedMs}ms", elapsedMs < 1_000L)
        assertTrue(timedOut.get())
    }

    @Test
    fun socketRegisteredAfterTimeoutIsClosedImmediately() {
        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(true)
        val socket = object : Socket() {
            var wasClosed = false
            override fun close() {
                wasClosed = true
            }
        }

        TcpLatencyProbe.registerActiveSocket(activeSocket, timedOut, socket)

        assertTrue(socket.wasClosed)
        assertEquals(null, activeSocket.get())
    }

    @Test
    fun boundedProbeReturnsCompletedOperation() {
        assertEquals("ok", TcpLatencyProbe.runBounded(1_000L) { "ok" })
    }

    @Test
    fun boundedProbePropagatesOperationErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.runBounded<Int>(1_000L) { throw IllegalArgumentException("bad endpoint") }
        }
    }

    @Test
    fun medianRejectsAnEmptyMeasurementSet() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.median(emptyList())
        }
    }
}
